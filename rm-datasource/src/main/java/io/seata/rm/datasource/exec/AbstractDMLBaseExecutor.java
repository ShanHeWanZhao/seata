/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.rm.datasource.exec;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.seata.common.exception.NotSupportYetException;
import io.seata.common.util.CollectionUtils;
import io.seata.rm.datasource.AbstractConnectionProxy;
import io.seata.rm.datasource.ConnectionContext;
import io.seata.rm.datasource.ConnectionProxy;
import io.seata.rm.datasource.StatementProxy;
import io.seata.rm.datasource.sql.struct.TableRecords;
import io.seata.sqlparser.SQLRecognizer;
import io.seata.sqlparser.util.JdbcConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The type Abstract dml base executor.
 *
 * @param <T> the type parameter
 * @param <S> the type parameter
 * @author sharajava
 */
public abstract class AbstractDMLBaseExecutor<T, S extends Statement> extends BaseTransactionalExecutor<T, S> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractDMLBaseExecutor.class);

    protected static final String WHERE = " WHERE ";


    /**
     * Instantiates a new Abstract dml base executor.
     *
     * @param statementProxy    the statement proxy
     * @param statementCallback the statement callback
     * @param sqlRecognizer     the sql recognizer
     */
    public AbstractDMLBaseExecutor(StatementProxy<S> statementProxy, StatementCallback<T, S> statementCallback,
                                   SQLRecognizer sqlRecognizer) {
        super(statementProxy, statementCallback, sqlRecognizer);
    }

    /**
     * Instantiates a new Base transactional executor.
     *
     * @param statementProxy    the statement proxy
     * @param statementCallback the statement callback
     * @param sqlRecognizers    the multi sql recognizer
     */
    public AbstractDMLBaseExecutor(StatementProxy<S> statementProxy, StatementCallback<T, S> statementCallback,
                                   List<SQLRecognizer> sqlRecognizers) {
        super(statementProxy, statementCallback, sqlRecognizers);
    }

    @Override
    public T doExecute(Object... args) throws Throwable {
        AbstractConnectionProxy connectionProxy = statementProxy.getConnectionProxy();
        if (connectionProxy.getAutoCommit()) { // 当前事务为自动提交（代表没有主动开启事务）
            // 一般就是只用了分布式事务但没有使用本地事务的情况。这时每条dml sql都会被当作分支事务来执行，即执行完后都会操作seata的commit来注册分支事务（增加了rpc通信压力）
            return executeAutoCommitTrue(args);
        } else {
            return executeAutoCommitFalse(args);
        }
    }

    /**
     * Execute auto commit false t.
     *
     * @param args the args
     * @return the t
     * @throws Exception the exception
     */
    protected T executeAutoCommitFalse(Object[] args) throws Exception {
        // 只有mysql支持多主键
        if (!JdbcConstants.MYSQL.equalsIgnoreCase(getDbType()) && isMultiPk()) {
            throw new NotSupportYetException("multi pk only support mysql!");
        }
        // 记录sql执行前镜像
        TableRecords beforeImage = beforeImage();
        // 执行业务sql
        T result = statementCallback.execute(statementProxy.getTargetStatement(), args);
        // 记录sql执行后的镜像
        TableRecords afterImage = afterImage(beforeImage);
        // 准备undo_log（只是准备，还未写表的）
        prepareUndoLog(beforeImage, afterImage);
        return result;
    }

    private boolean isMultiPk() {
        if (null != sqlRecognizer) {
            return getTableMeta().getPrimaryKeyOnlyName().size() > 1;
        }
        if (CollectionUtils.isNotEmpty(sqlRecognizers)) {
            List<SQLRecognizer> distinctSQLRecognizer = sqlRecognizers.stream().filter(
                distinctByKey(t -> t.getTableName())).collect(Collectors.toList());
            for (SQLRecognizer sqlRecognizer : distinctSQLRecognizer) {
                if (getTableMeta(sqlRecognizer.getTableName()).getPrimaryKeyOnlyName().size() > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Map<Object, Boolean> map = new HashMap<>();
        return t -> map.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }


    /**
     * Execute auto commit true t. <p/>
     * 为autoCommit为true准备的sql执行 <br/>
     * 先设置autoCommit为false，最后手动commit（保证就算没有开启本地事务也会走seata的commit）
     *
     * @param args the args
     * @return the t
     * @throws Throwable the throwable
     */
    protected T executeAutoCommitTrue(Object[] args) throws Throwable {
        ConnectionProxy connectionProxy = statementProxy.getConnectionProxy();
        try {
            // 改为非自动提交事务
            connectionProxy.changeAutoCommit();
            // 锁重试机制来commit
            return new LockRetryPolicy(connectionProxy).execute(() -> {
                T result = executeAutoCommitFalse(args); // 走非自动提交事务的commit逻辑（准备undo日志）
                connectionProxy.commit();
                return result;
            });
        } catch (Exception e) {
            // when exception occur in finally,this exception will lost, so just print it here
            LOGGER.error("execute executeAutoCommitTrue error:{}", e.getMessage(), e);
            if (!LockRetryPolicy.isLockRetryPolicyBranchRollbackOnConflict()) {
                connectionProxy.getTargetConnection().rollback();
            }
            throw e;
        } finally {
            connectionProxy.getContext().reset();
            connectionProxy.setAutoCommit(true);
        }
    }

    /**
     * Before image table records.
     *
     * @return the table records
     * @throws SQLException the sql exception
     */
    protected abstract TableRecords beforeImage() throws SQLException;

    /**
     * After image table records.
     *
     * @param beforeImage the before image
     * @return the table records
     * @throws SQLException the sql exception
     */
    protected abstract TableRecords afterImage(TableRecords beforeImage) throws SQLException;

    private static class LockRetryPolicy extends ConnectionProxy.LockRetryPolicy {

        LockRetryPolicy(final ConnectionProxy connection) {
            super(connection);
        }

        @Override
        public <T> T execute(Callable<T> callable) throws Exception {
            if (LOCK_RETRY_POLICY_BRANCH_ROLLBACK_ON_CONFLICT) {
                return doRetryOnLockConflict(callable);
            } else {
                return callable.call();
            }
        }

        @Override
        protected void onException(Exception e) throws Exception {
            ConnectionContext context = connection.getContext();
            //UndoItems can't use the Set collection class to prevent ABA
            context.removeSavepoint(null);
            connection.getTargetConnection().rollback();
        }

        public static boolean isLockRetryPolicyBranchRollbackOnConflict() {
            return LOCK_RETRY_POLICY_BRANCH_ROLLBACK_ON_CONFLICT;
        }
    }
}
