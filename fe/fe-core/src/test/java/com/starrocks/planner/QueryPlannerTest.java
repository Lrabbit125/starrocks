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

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/planner/QueryPlanTest.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.planner;

import com.starrocks.common.AnalysisException;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.ha.FrontendNodeType;
import com.starrocks.meta.BlackListSql;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.QueryState;
import com.starrocks.qe.StmtExecutor;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.CreateDbStmt;
import com.starrocks.sql.ast.DropDbStmt;
import com.starrocks.sql.ast.ShowCreateDbStmt;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.sql.parser.SqlParser;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Mock;
import mockit.MockUp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

public class QueryPlannerTest {
    private static ConnectContext connectContext;
    private static StarRocksAssert starRocksAssert;
    private static String DB_NAME = "test";

    @BeforeAll
    public static void beforeClass() throws Exception {
        UtFrameUtils.createMinStarRocksCluster();

        // create connect context
        connectContext = UtFrameUtils.createDefaultCtx();
        connectContext.setQueryId(UUIDUtil.genUUID());
        starRocksAssert = new StarRocksAssert(connectContext);
        starRocksAssert.withDatabase(DB_NAME).useDatabase(DB_NAME);

        starRocksAssert.withTable("CREATE TABLE test.`baseall` (\n" +
                "  `k1` tinyint(4) NULL COMMENT \"\",\n" +
                "  `k2` smallint(6) NULL COMMENT \"\",\n" +
                "  `k3` int(11) NULL COMMENT \"\",\n" +
                "  `k4` bigint(20) NULL COMMENT \"\",\n" +
                "  `k5` decimal(9, 3) NULL COMMENT \"\",\n" +
                "  `k6` char(5) NULL COMMENT \"\",\n" +
                "  `k10` date NULL COMMENT \"\",\n" +
                "  `k11` datetime NULL COMMENT \"\",\n" +
                "  `k7` varchar(20) NULL COMMENT \"\",\n" +
                "  `k8` double MAX NULL COMMENT \"\",\n" +
                "  `k9` float SUM NULL COMMENT \"\"\n" +
                ") ENGINE=OLAP\n" +
                "AGGREGATE KEY(`k1`, `k2`, `k3`, `k4`, `k5`, `k6`, `k10`, `k11`, `k7`)\n" +
                "COMMENT \"OLAP\"\n" +
                "DISTRIBUTED BY HASH(`k1`) BUCKETS 5\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ");");
    }

    @Test
    public void testMultiStmts() {
        String sql = "SHOW VARIABLES LIKE 'lower_case_%'; SHOW VARIABLES LIKE 'sql_mode'";
        List<StatementBase> stmts = com.starrocks.sql.parser.SqlParser.parse(sql, connectContext.getSessionVariable());
        Assertions.assertEquals(2, stmts.size());

        sql = "SHOW VARIABLES LIKE 'lower_case_%';;;";
        stmts = com.starrocks.sql.parser.SqlParser.parse(sql, connectContext.getSessionVariable());
        Assertions.assertEquals(3, stmts.size());

        sql = "SHOW VARIABLES LIKE 'lower_case_%';;;SHOW VARIABLES LIKE 'lower_case_%';";
        stmts = com.starrocks.sql.parser.SqlParser.parse(sql, connectContext.getSessionVariable());
        Assertions.assertEquals(4, stmts.size());

        sql = "SHOW VARIABLES LIKE 'lower_case_%'";
        stmts = com.starrocks.sql.parser.SqlParser.parse(sql, connectContext.getSessionVariable());
        Assertions.assertEquals(1, stmts.size());
    }

    @Test
    public void testCreateDbQueryPlanWithSchemaSyntax() throws Exception {
        String createSchemaSql = "create schema if not exists test";
        String createDbSql = "create database if not exists test";
        CreateDbStmt createSchemaStmt =
                (CreateDbStmt) UtFrameUtils.parseStmtWithNewParser(createSchemaSql, connectContext);
        CreateDbStmt createDbStmt = (CreateDbStmt) UtFrameUtils.parseStmtWithNewParser(createDbSql, connectContext);

        Assertions.assertEquals("test", createSchemaStmt.getFullDbName());
    }

    @Test
    public void testDropDbQueryPlanWithSchemaSyntax() throws Exception {
        String dropSchemaSql = "drop schema if exists test";
        String dropDbSql = "drop database if exists test";
        DropDbStmt dropSchemaStmt = (DropDbStmt) UtFrameUtils.parseStmtWithNewParser(dropSchemaSql, connectContext);
        DropDbStmt dropDbStmt = (DropDbStmt) UtFrameUtils.parseStmtWithNewParser(dropDbSql, connectContext);
        Assertions.assertEquals("test", dropSchemaStmt.getDbName());
    }

    @Test
    public void testShowCreateDbQueryPlanWithSchemaSyntax() throws Exception {
        String showCreateSchemaSql = "show create schema test";
        String showCreateDbSql = "show create database test";
        ShowCreateDbStmt showCreateSchemaStmt =
                (ShowCreateDbStmt) UtFrameUtils.parseStmtWithNewParser(showCreateSchemaSql, connectContext);
        ShowCreateDbStmt showCreateDbStmt =
                (ShowCreateDbStmt) UtFrameUtils.parseStmtWithNewParser(showCreateDbSql, connectContext);
        Assertions.assertEquals("test", showCreateSchemaStmt.getDb());
    }

    @Test
    public void testSqlBlackList() throws Exception {
        String setEnableSqlBlacklist = "admin set frontend config (\"enable_sql_blacklist\" = \"true\")";
        StatementBase statement = SqlParser.parseSingleStatement(setEnableSqlBlacklist,
                connectContext.getSessionVariable().getSqlMode());

        StmtExecutor stmtExecutor0 = new StmtExecutor(connectContext, statement);
        stmtExecutor0.execute();

        String addBlackListSql = "add sqlblacklist \"select k1 from .+\"";
        statement = SqlParser.parseSingleStatement(addBlackListSql,
                connectContext.getSessionVariable().getSqlMode());
        StmtExecutor stmtExecutor1 = new StmtExecutor(connectContext, statement);
        stmtExecutor1.execute();

        Assertions.assertEquals(GlobalStateMgr.getCurrentState().getSqlBlackList().getBlackLists().size(), 1);
        long id = -1;
        for (BlackListSql entry : GlobalStateMgr.getCurrentState().getSqlBlackList().getBlackLists()) {
            id = entry.id;
            Assertions.assertEquals("select k1 from .+", entry.pattern.pattern());
        }

        String sql = "select k1 from test.baseall";
        statement = SqlParser.parseSingleStatement(sql,
                connectContext.getSessionVariable().getSqlMode());
        StmtExecutor stmtExecutor2 = new StmtExecutor(connectContext, statement);
        stmtExecutor2.execute();
        Assertions.assertEquals("Access denied; This sql is in blacklist, please contact your admin",
                connectContext.getState().getErrorMessage());
        connectContext.getState().setError("");

        String sqlWithLineSeparators = "select k1 \n" +
                " from \n" +
                " test.baseall";
        StatementBase statement1 =
                SqlParser.parse(sqlWithLineSeparators, connectContext.getSessionVariable().getSqlMode()).get(0);
        StmtExecutor stmtExecutor4 = new StmtExecutor(connectContext, statement1);
        stmtExecutor4.execute();
        Assertions.assertEquals("Access denied; This sql is in blacklist, please contact your admin",
                connectContext.getState().getErrorMessage());
        connectContext.getState().setError("");

        // for ctas
        String sqlWithCtas = "create table ctas_test properties(\"replication_num\"=\"1\") as select k1 from test.baseall";
        statement = SqlParser.parseSingleStatement(sqlWithCtas,
                connectContext.getSessionVariable().getSqlMode());
        StmtExecutor stmtExecutor5 = new StmtExecutor(connectContext, statement);
        stmtExecutor5.execute();
        Assertions.assertEquals("Access denied; This sql is in blacklist, please contact your admin",
                connectContext.getState().getErrorMessage());
        connectContext.getState().setError("");

        String deleteBlackListSql = "delete sqlblacklist " + String.valueOf(id);
        statement = SqlParser.parseSingleStatement(deleteBlackListSql,
                connectContext.getSessionVariable().getSqlMode());
        StmtExecutor stmtExecutor3 = new StmtExecutor(connectContext, statement);
        stmtExecutor3.execute();
        Assertions.assertEquals(0, GlobalStateMgr.getCurrentState().getSqlBlackList().getBlackLists().size());
    }

    @Test
    public void testSqlBlackListWithLineSeparators() throws Exception {
        String setEnableSqlBlacklist = "admin set frontend config (\"enable_sql_blacklist\" = \"true\")";
        StatementBase statement = SqlParser.parseSingleStatement(setEnableSqlBlacklist,
                connectContext.getSessionVariable().getSqlMode());
        StmtExecutor stmtExecutor0 = new StmtExecutor(connectContext, statement);
        stmtExecutor0.execute();

        String addBlackListSql = "add sqlblacklist \"select \n k1 from .+\"";
        statement = SqlParser.parseSingleStatement(addBlackListSql,
                connectContext.getSessionVariable().getSqlMode());
        StmtExecutor stmtExecutor1 = new StmtExecutor(connectContext, statement);
        stmtExecutor1.execute();

        Assertions.assertEquals(1, GlobalStateMgr.getCurrentState().getSqlBlackList().getBlackLists().size());
        long id = -1;
        for (BlackListSql entry : GlobalStateMgr.getCurrentState().getSqlBlackList().getBlackLists()) {
            id = entry.id;
            Assertions.assertEquals("select k1 from .+", entry.pattern.pattern());
        }

        String sql = "select k1 from test.baseall";
        statement = SqlParser.parse(sql, connectContext.getSessionVariable().getSqlMode()).get(0);
        StmtExecutor stmtExecutor2 = new StmtExecutor(connectContext, statement);
        stmtExecutor2.execute();
        Assertions.assertEquals("Access denied; This sql is in blacklist, please contact your admin",
                connectContext.getState().getErrorMessage());
        connectContext.getState().setError("");

        String sqlWithLineSeparators = "select k1 \n" +
                " from \n" +
                " test.baseall";
        StatementBase statement1 =
                SqlParser.parse(sqlWithLineSeparators, connectContext.getSessionVariable().getSqlMode()).get(0);
        StmtExecutor stmtExecutor4 = new StmtExecutor(connectContext, statement1);
        stmtExecutor4.execute();
        Assertions.assertEquals("Access denied; This sql is in blacklist, please contact your admin",
                connectContext.getState().getErrorMessage());
        connectContext.getState().setError("");

        String deleteBlackListSql = "delete sqlblacklist " + String.valueOf(id);
        statement = SqlParser.parseSingleStatement(deleteBlackListSql,
                connectContext.getSessionVariable().getSqlMode());
        StmtExecutor stmtExecutor3 = new StmtExecutor(connectContext, statement);
        stmtExecutor3.execute();
        Assertions.assertEquals(0, GlobalStateMgr.getCurrentState().getSqlBlackList().getBlackLists().size());
    }

    @Test
    public void testSqlBlackListUseWhere() throws Exception {
        String setEnableSqlBlacklist = "admin set frontend config (\"enable_sql_blacklist\" = \"true\")";
        StatementBase statement = SqlParser.parseSingleStatement(setEnableSqlBlacklist,
                connectContext.getSessionVariable().getSqlMode());
        StmtExecutor stmtExecutor0 = new StmtExecutor(connectContext, statement);
        stmtExecutor0.execute();

        String addBlackListSql = "add sqlblacklist \"( where )\"";
        statement = SqlParser.parseSingleStatement(addBlackListSql,
                connectContext.getSessionVariable().getSqlMode());
        StmtExecutor stmtExecutor1 = new StmtExecutor(connectContext, statement);
        stmtExecutor1.execute();

        Assertions.assertEquals(GlobalStateMgr.getCurrentState().getSqlBlackList().getBlackLists().size(), 1);
        long id = -1;
        for (BlackListSql entry : GlobalStateMgr.getCurrentState().getSqlBlackList().getBlackLists()) {
            id = entry.id;
            Assertions.assertEquals("( where )", entry.pattern.pattern());
        }

        String sql4 = "select k1 as awhere from test.baseall";
        StatementBase statementBase4 = SqlParser.parse(sql4, connectContext.getSessionVariable().getSqlMode()).get(0);
        StmtExecutor stmtExecutor4 = new StmtExecutor(connectContext, statementBase4);
        stmtExecutor4.execute();
        Assertions.assertEquals("", connectContext.getState().getErrorMessage());

        String sql = "select k1 from test.baseall where k1 > 0";
        StatementBase statementBase = SqlParser.parse(sql, connectContext.getSessionVariable().getSqlMode()).get(0);
        StmtExecutor stmtExecutor2 = new StmtExecutor(connectContext, statementBase);
        stmtExecutor2.execute();
        Assertions.assertEquals("Access denied; This sql is in blacklist, please contact your admin",
                connectContext.getState().getErrorMessage());

        String deleteBlackListSql = "delete sqlblacklist " + String.valueOf(id);
        statement = SqlParser.parseSingleStatement(deleteBlackListSql,
                connectContext.getSessionVariable().getSqlMode());
        StmtExecutor stmtExecutor3 = new StmtExecutor(connectContext, statement);
        stmtExecutor3.execute();
        Assertions.assertEquals(0, GlobalStateMgr.getCurrentState().getSqlBlackList().getBlackLists().size());
    }
    @Test
    public void testSqlBlackListWithInsert() throws Exception {
        String setEnableSqlBlacklist = "admin set frontend config (\"enable_sql_blacklist\" = \"true\")";
        StatementBase statement = SqlParser.parseSingleStatement(setEnableSqlBlacklist,
                connectContext.getSessionVariable().getSqlMode());
        StmtExecutor stmtExecutor0 = new StmtExecutor(connectContext, statement);
        stmtExecutor0.execute();

        String addBlackListSql = "add sqlblacklist \"insert into .+ values.+\"";
        statement = SqlParser.parseSingleStatement(addBlackListSql,
                connectContext.getSessionVariable().getSqlMode());
        StmtExecutor stmtExecutor1 = new StmtExecutor(connectContext, statement);
        stmtExecutor1.execute();

        Assertions.assertEquals(GlobalStateMgr.getCurrentState().getSqlBlackList().getBlackLists().size(), 1);
        long id = -1;
        for (BlackListSql entry : GlobalStateMgr.getCurrentState().getSqlBlackList().getBlackLists()) {
            id = entry.id;
            Assertions.assertEquals("insert into .+ values.+", entry.pattern.pattern());
        }

        String sql =
                "insert into test.baseall values (1, 1, 1, 1, 1, 'a', '2020-02-05', '2020-02-05 13:22:35', 'starrocks', 33.3, 22.55)";
        statement = SqlParser.parse(sql, connectContext.getSessionVariable().getSqlMode()).get(0);
        StmtExecutor stmtExecutor2 = new StmtExecutor(connectContext, statement);
        try {
            stmtExecutor2.execute();
        } catch (AnalysisException e) {

        }
        Assertions.assertEquals("Access denied; This sql is in blacklist, please contact your admin",
                connectContext.getState().getErrorMessage());

        String deleteBlackListSql = "delete sqlblacklist " + String.valueOf(id);
        statement = SqlParser.parseSingleStatement(deleteBlackListSql,
                connectContext.getSessionVariable().getSqlMode());
        StmtExecutor stmtExecutor3 = new StmtExecutor(connectContext, statement);
        stmtExecutor3.execute();
        Assertions.assertEquals(0, GlobalStateMgr.getCurrentState().getSqlBlackList().getBlackLists().size());
    }

    @Test
    public void testFollowerProxyQuery() throws Exception {
        new MockUp<GlobalStateMgr>() {
            @Mock
            public FrontendNodeType getFeType() {
                return FrontendNodeType.FOLLOWER;
            }

            @Mock
            public boolean isLeader() {
                return false;
            }
        };

        String sql = "select k1 from test.baseall";
        StatementBase statement = SqlParser.parse(sql, connectContext.getSessionVariable().getSqlMode()).get(0);

        {
            StmtExecutor executor = new StmtExecutor(connectContext, statement);
            Assertions.assertTrue(executor.isForwardToLeader() == true);
        }
        {
            connectContext.getSessionVariable().setFollowerQueryForwardMode("default");
            StmtExecutor executor = new StmtExecutor(connectContext, statement);
            Assertions.assertTrue(executor.isForwardToLeader() == true);
        }
        {
            connectContext.getSessionVariable().setFollowerQueryForwardMode("follower");
            StmtExecutor executor = new StmtExecutor(connectContext, statement);
            Assertions.assertTrue(executor.isForwardToLeader() == false);
            connectContext.getSessionVariable().setFollowerQueryForwardMode("leader");
            Assertions.assertTrue(executor.isForwardToLeader() == false);
        }
        {
            connectContext.getSessionVariable().setFollowerQueryForwardMode("leader");
            StmtExecutor executor = new StmtExecutor(connectContext, statement);
            Assertions.assertTrue(executor.isForwardToLeader() == true);
            connectContext.getSessionVariable().setFollowerQueryForwardMode("follower");
            Assertions.assertTrue(executor.isForwardToLeader() == true);
        }
    }

    @Test
    public void testErrTypeInQueryState() throws Exception {
        ConnectContext ctx = UtFrameUtils.initCtxForNewPrivilege(UserIdentity.ROOT);
        ctx.setQueryId(UUIDUtil.genUUID());

        StatementBase statement = SqlParser.parseSingleStatement("select * from test.baseall",
                ctx.getSessionVariable().getSqlMode());
        StmtExecutor executor = new StmtExecutor(ctx, statement);
        executor.execute();
        Assertions.assertEquals(QueryState.ErrType.UNKNOWN, ctx.getState().getErrType());

        statement = SqlParser.parseSingleStatement("select * from test.baseallxxx",
                ctx.getSessionVariable().getSqlMode());
        executor = new StmtExecutor(ctx, statement);
        executor.execute();
        Assertions.assertEquals(QueryState.ErrType.ANALYSIS_ERR, ctx.getState().getErrType());
    }
}
