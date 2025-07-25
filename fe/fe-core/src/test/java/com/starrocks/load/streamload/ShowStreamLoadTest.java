// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.load.streamload;

import com.starrocks.common.Config;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.http.rest.TransactionResult;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.ShowExecutor;
import com.starrocks.qe.ShowResultSet;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.WarehouseManager;
import com.starrocks.sql.ast.CreateDbStmt;
import com.starrocks.sql.ast.CreateTableStmt;
import com.starrocks.sql.ast.ShowStreamLoadStmt;
import com.starrocks.system.Backend;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ShowStreamLoadTest {
    private static final Logger LOG = LogManager.getLogger(ShowStreamLoadTest.class);
    private static ConnectContext connectContext;

    @BeforeAll
    public static void beforeClass() throws Exception {
        UtFrameUtils.createMinStarRocksCluster();
        Backend be = UtFrameUtils.addMockBackend(10002);
        be.setDecommissioned(true);
        UtFrameUtils.addMockBackend(10003);
        UtFrameUtils.addMockBackend(10004);
        Config.enable_strict_storage_medium_check = true;
        Config.enable_auto_tablet_distribution = true;

        // create connect context
        connectContext = UtFrameUtils.createDefaultCtx();
        connectContext.setDatabase("test_db");
        connectContext.setQueryId(UUIDUtil.genUUID());

        // create database
        String createDbStmtStr = "create database test_db;";
        CreateDbStmt createDbStmt = (CreateDbStmt) UtFrameUtils.parseStmtWithNewParser(createDbStmtStr, connectContext);
        GlobalStateMgr.getCurrentState().getLocalMetastore().createDb(createDbStmt.getFullDbName());
        // create table
        String createTableStmtStr = "CREATE TABLE test_db.test_tbl (c0 int, c1 string, c2 int, c3 bigint) " +
                "DUPLICATE KEY (c0) DISTRIBUTED BY HASH (c0) BUCKETS 3 properties(\"replication_num\"=\"1\") ;;";
        CreateTableStmt createTableStmt = (CreateTableStmt) UtFrameUtils.
                parseStmtWithNewParser(createTableStmtStr, connectContext);
        StarRocksAssert.utCreateTableWithRetry(createTableStmt);
    }

    @Test
    public void testShowStreamLoad() throws Exception {
        StreamLoadMgr streamLoadManager = GlobalStateMgr.getCurrentState().getStreamLoadMgr();

        String dbName = "test_db";
        String tableName = "test_tbl";
        long timeoutMillis = 100000;

        String labelName = "label_stream_load";
        TransactionResult resp = new TransactionResult();
        streamLoadManager.beginLoadTaskFromBackend(dbName, tableName, labelName, null, "", "", timeoutMillis, resp, false,
                WarehouseManager.DEFAULT_RESOURCE);
        labelName = "label_routine_load";
        streamLoadManager.beginLoadTaskFromBackend(dbName, tableName, labelName, null, "", "", timeoutMillis, resp, true,
                WarehouseManager.DEFAULT_RESOURCE);

        String sql = "show all stream load";
        ShowStreamLoadStmt showStreamLoadStmt = (ShowStreamLoadStmt) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
        ShowResultSet resultSet = ShowExecutor.execute(showStreamLoadStmt, connectContext);
        Assertions.assertEquals(resultSet.getResultRows().size(), 2);

        String sqlWithWhere = "show all stream load where Type = \"ROUTINE_LOAD\"";
        ShowStreamLoadStmt showStreamLoadStmtWithWhere = (ShowStreamLoadStmt) UtFrameUtils.
                parseStmtWithNewParser(sqlWithWhere, connectContext);
        ShowResultSet resultSetWithWhere = ShowExecutor.execute(showStreamLoadStmtWithWhere, connectContext);
        Assertions.assertEquals(resultSetWithWhere.getResultRows().size(), 1);
    }
}
