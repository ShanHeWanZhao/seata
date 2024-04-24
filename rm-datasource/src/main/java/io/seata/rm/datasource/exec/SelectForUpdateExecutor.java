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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import io.seata.common.util.StringUtils;
import io.seata.core.context.RootContext;
import io.seata.rm.datasource.StatementProxy;
import io.seata.rm.datasource.sql.struct.TableRecords;
import io.seata.sqlparser.SQLRecognizer;
import io.seata.sqlparser.SQLSelectRecognizer;
import io.seata.sqlparser.util.JdbcConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The type Select for update executor.
 *
 * @param <S> the type parameter
 * @author sharajava
 */
public class SelectForUpdateExecutor<T, S extends Statement> extends BaseTransactionalExecutor<T, S> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SelectForUpdateExecutor.class);

    /**
     * Instantiates a new Select for update executor.
     *
     * @param statementProxy    the statement proxy
     * @param statementCallback the statement callback
     * @param sqlRecognizer     the sql recognizer
     */
    public SelectForUpdateExecutor(StatementProxy<S> statementProxy, StatementCallback<T, S> statementCallback,
                                   SQLRecognizer sqlRecognizer) {
        super(statementProxy, statementCallback, sqlRecognizer);
    }

    @Override
    public T doExecute(Object... args) throws Throwable {
        Connection conn = statementProxy.getConnection();
        DatabaseMetaData dbmd = conn.getMetaData();
        T rs;
        Savepoint sp = null;
        boolean originalAutoCommit = conn.getAutoCommit();
        try {
            // 在这里单独开启事务。如果没有原事务则直接开启，如果有则新建一个savepoint来开启事务
            if (originalAutoCommit) { // 没有原事务
                /*
                 * In order to hold the local db lock during global lock checking
                 * set auto commit value to false first if original auto commit was true
                 */
                conn.setAutoCommit(false);
            } else if (dbmd.supportsSavepoints()) { // 有原事务，新建一个savepoint来开启内嵌事务
                /*
                 * In order to release the local db lock when global lock conflict
                 * create a save point if original auto commit was false, then use the save point here to release db
                 * lock during global lock checking if necessary
                 */
                sp = conn.setSavepoint();
            } else {
                throw new SQLException("not support savepoint. please check your db version");
            }

            LockRetryController lockRetryController = new LockRetryController();
            ArrayList<List<Object>> paramAppenderList = new ArrayList<>();
            String selectPKSQL = buildSelectSQL(paramAppenderList);
            while (true) {
                try {
                    // #870
                    // execute return
                    // executeQuery return ResultSet
                    rs = statementCallback.execute(statementProxy.getTargetStatement(), args);

                    // Try to get global lock of those rows selected
                    TableRecords selectPKRows = buildTableRecords(getTableMeta(), selectPKSQL, paramAppenderList);
                    // 构建lock key
                    String lockKeys = buildLockKey(selectPKRows);
                    if (StringUtils.isNullOrEmpty(lockKeys)) { // lock key不存在，代表没有锁冲突，直接就break了
                        break;
                    }

                    if (RootContext.inGlobalTransaction() || RootContext.requireGlobalLock()) { // 全局锁的获取和判断必须在@GlobalTransactional or @GlobalLock注解下
                        // Do the same thing under either @GlobalTransactional or @GlobalLock, 
                        // that only check the global lock  here.
                        statementProxy.getConnectionProxy().checkLock(lockKeys);
                    } else {
                        throw new RuntimeException("Unknown situation!");
                    }
                    // 走到这就代表全局锁没有冲突（要查询的数据没有被多个事务同时处理），可直接返回了
                    break;
                } catch (LockConflictException lce) {
                    // 全局锁冲突，本地事务先rollback，释放select .. for update获取到的锁
                    if (sp != null) {
                        conn.rollback(sp);
                    } else {
                        conn.rollback();
                    }
                    // sleep后会再进行重试
                    // trigger retry
                    lockRetryController.sleep(lce);
                }
            }
        } finally {
            // 复原现场
            if (sp != null) {
                try {
                    if (!JdbcConstants.ORACLE.equalsIgnoreCase(getDbType())) {
                        conn.releaseSavepoint(sp);
                    }
                } catch (SQLException e) {
                    LOGGER.error("{} release save point error.", getDbType(), e);
                }
            }
            if (originalAutoCommit) {
                conn.setAutoCommit(true);
            }
        }
        return rs;
    }

    private String buildSelectSQL(ArrayList<List<Object>> paramAppenderList) {
        SQLSelectRecognizer recognizer = (SQLSelectRecognizer)sqlRecognizer;
        StringBuilder selectSQLAppender = new StringBuilder("SELECT ");
        selectSQLAppender.append(getColumnNamesInSQL(getTableMeta().getEscapePkNameList(getDbType())));
        selectSQLAppender.append(" FROM ").append(getFromTableInSQL());
        String whereCondition = buildWhereCondition(recognizer, paramAppenderList);
        if (StringUtils.isNotBlank(whereCondition)) {
            selectSQLAppender.append(" WHERE ").append(whereCondition);
        }
        selectSQLAppender.append(" FOR UPDATE");
        return selectSQLAppender.toString();
    }
}
