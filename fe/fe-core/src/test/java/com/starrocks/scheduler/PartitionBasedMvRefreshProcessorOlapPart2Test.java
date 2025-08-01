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

package com.starrocks.scheduler;

import com.starrocks.catalog.Database;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.catalog.MvUpdateInfo;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.common.Config;
import com.starrocks.common.FeConstants;
import com.starrocks.common.util.PropertyAnalyzer;
import com.starrocks.common.util.RuntimeProfile;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SessionVariable;
import com.starrocks.scheduler.persist.TaskRunStatus;
import com.starrocks.schema.MTable;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.optimizer.QueryMaterializationContext;
import com.starrocks.sql.optimizer.rule.transformation.materialization.MVTestBase;
import com.starrocks.sql.plan.ExecPlan;
import com.starrocks.sql.plan.PlanTestBase;
import com.starrocks.thrift.TGetTasksParams;
import com.starrocks.utframe.UtFrameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.MethodName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.starrocks.common.util.PropertyAnalyzer.PROPERTIES_WAREHOUSE;
import static com.starrocks.scheduler.TaskRun.MV_ID;

@TestMethodOrder(MethodName.class)
public class PartitionBasedMvRefreshProcessorOlapPart2Test extends MVTestBase {
    private static final Logger LOG = LogManager.getLogger(PartitionBasedMvRefreshProcessorOlapPart2Test.class);

    private static String R1;
    private static String R2;
    @BeforeAll
    public static void beforeClass() throws Exception {
        MVTestBase.beforeClass();
        // range partition table
        R1 = "CREATE TABLE r1 \n" +
                "(\n" +
                "    dt date,\n" +
                "    k2 int,\n" +
                "    v1 int \n" +
                ")\n" +
                "PARTITION BY RANGE(dt)\n" +
                "(\n" +
                "    PARTITION p0 values [('2021-12-01'),('2022-01-01')),\n" +
                "    PARTITION p1 values [('2022-01-01'),('2022-02-01')),\n" +
                "    PARTITION p2 values [('2022-02-01'),('2022-03-01')),\n" +
                "    PARTITION p3 values [('2022-03-01'),('2022-04-01'))\n" +
                ")\n" +
                "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                "PROPERTIES('replication_num' = '1');";
        R2 = "CREATE TABLE r2 \n" +
                "(\n" +
                "    dt date,\n" +
                "    k2 int,\n" +
                "    v1 int \n" +
                ")\n" +
                "PARTITION BY date_trunc('day', dt)\n" +
                "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                "PROPERTIES('replication_num' = '1');";
    }

    @Test
    public void testMVRefreshWithTheSameTables1() {
        starRocksAssert.withMTables(List.of(
                        new MTable("tt1", "k1",
                                List.of(
                                        "k1 int",
                                        "k2 int",
                                        "k3 string",
                                        "dt date"
                                ),
                                "dt",
                                List.of(
                                        "PARTITION p0 values [('2021-12-01'),('2021-12-02'))",
                                        "PARTITION p1 values [('2021-12-02'),('2021-12-03'))",
                                        "PARTITION p2 values [('2021-12-03'),('2021-12-04'))"
                                )
                        )
                ),
                () -> {
                    String[] mvSqls = {
                            "create materialized view test_mv1 \n" +
                                    "partition by dt \n" +
                                    "distributed by RANDOM\n" +
                                    "refresh deferred manual\n" +
                                    "as select a.dt, b.k2 from tt1 a " +
                                    "   join tt1 b on a.dt = substr(date_sub(b.dt, interval dayofyear(a.dt) day), 1, 10)" +
                                    "   join tt1 c on a.dt = substr(date_add(c.dt, interval dayofyear(a.dt) day), 1, 10)" +
                                    " where a.k1 > 1 and b.k1 > 2 and c.k1 > 3;",
                            "create materialized view test_mv1 \n" +
                                    "partition by dt \n" +
                                    "distributed by RANDOM\n" +
                                    "refresh deferred manual\n" +
                                    "as select a.dt, b.k2 from tt1 a " +
                                    "   join tt1 b on a.dt = date_sub(b.dt, interval dayofyear(a.dt) day)" +
                                    "   join tt1 c on a.dt = date_add(c.dt, interval dayofyear(a.dt) day)" +
                                    " where a.k1 > 1 and b.k1 > 2 and c.k1 > 3;",
                    };
                    for (String mvSql : mvSqls) {
                        starRocksAssert.withMaterializedView(mvSql, (obj) -> {
                            String mvName = (String) obj;
                            assertPlanWithoutPushdownBelowScan(mvName);
                        });
                    }
                });
    }

    private void assertPlanWithoutPushdownBelowScan(String mvName) throws Exception {
        Database testDb = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
        MaterializedView materializedView = ((MaterializedView) GlobalStateMgr.getCurrentState().getLocalMetastore()
                .getTable(testDb.getFullName(), mvName));
        Assertions.assertEquals(1, materializedView.getPartitionExprMaps().size());
        Task task = TaskBuilder.buildMvTask(materializedView, testDb.getFullName());
        Map<String, String> testProperties = task.getProperties();
        testProperties.put(TaskRun.IS_TEST, "true");

        String insertSql = "insert into tt1 partition(p0) values(1, 1, 1, '2021-12-01');";
        executeInsertSql(connectContext, insertSql);

        TaskRun taskRun = TaskRunBuilder.newBuilder(task).build();
        taskRun.initStatus(UUIDUtil.genUUID().toString(), System.currentTimeMillis());
        taskRun.executeTaskRun();
        PartitionBasedMvRefreshProcessor processor =
                (PartitionBasedMvRefreshProcessor) taskRun.getProcessor();
        ExecPlan execPlan = processor.getMvContext().getExecPlan();
        Assertions.assertTrue(execPlan != null);
        String plan = execPlan.getExplainString(StatementBase.ExplainLevel.NORMAL);
        PlanTestBase.assertContains(plan, "     TABLE: tt1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 1: k1 > 1\n" +
                "     partitions=1/3");
        PlanTestBase.assertContains(plan, "     TABLE: tt1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 5: k1 > 2\n" +
                "     partitions=3/3");
        PlanTestBase.assertContains(plan, "     TABLE: tt1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 9: k1 > 3\n" +
                "     partitions=3/3");
    }

    @Test
    public void testMVRefreshWithTheSameTables22() {
        starRocksAssert.withMTables(List.of(
                        new MTable("tt1", "k1",
                                List.of(
                                        "k1 int",
                                        "k2 int",
                                        "k3 string",
                                        "dt date"
                                ),
                                "dt",
                                List.of(
                                        "PARTITION p0 values [('2021-12-01'),('2021-12-02'))",
                                        "PARTITION p1 values [('2021-12-02'),('2021-12-03'))",
                                        "PARTITION p2 values [('2021-12-03'),('2021-12-04'))"
                                )
                        )
                ),
                () -> {
                    String[] mvSqls = {
                            "create materialized view test_mv1 \n" +
                                    "partition by dt \n" +
                                    "distributed by RANDOM\n" +
                                    "refresh deferred manual\n" +
                                    "as select a.dt, b.k2 from tt1 a " +
                                    "   join tt1 b on a.dt = b.dt" +
                                    "   join tt1 c on a.dt = c.dt" +
                                    " where a.k1 > 1 and b.k1 > 2 and c.k1 > 3;",
                            "create materialized view test_mv1 \n" +
                                    "partition by dt \n" +
                                    "distributed by RANDOM\n" +
                                    "refresh deferred manual\n" +
                                    "as select a.dt, b.k2 from tt1 a " +
                                    "   join tt1 b on a.dt = date_trunc('day', b.dt)" +
                                    "   join tt1 c on a.dt = date_trunc('day', c.dt)" +
                                    " where a.k1 > 1 and b.k1 > 2 and c.k1 > 3;",
                    };
                    for (String mvSql : mvSqls) {
                        starRocksAssert.withMaterializedView(mvSql, (obj) -> {
                            String mvName = (String) obj;
                            assertPlanWithPushdownBelowScan(mvName);
                        });
                    }
                    ;
                });
    }

    private void assertPlanWithPushdownBelowScan(String mvName) throws Exception {
        Database testDb = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
        MaterializedView materializedView = ((MaterializedView) GlobalStateMgr.getCurrentState().getLocalMetastore()
                .getTable(testDb.getFullName(), mvName));
        Assertions.assertEquals(1, materializedView.getPartitionExprMaps().size());
        Task task = TaskBuilder.buildMvTask(materializedView, testDb.getFullName());
        Map<String, String> testProperties = task.getProperties();
        testProperties.put(TaskRun.IS_TEST, "true");

        String insertSql = "insert into tt1 partition(p0) values(1, 1, 1, '2021-12-01');";
        executeInsertSql(connectContext, insertSql);

        TaskRun taskRun = TaskRunBuilder.newBuilder(task).build();
        taskRun.initStatus(UUIDUtil.genUUID().toString(), System.currentTimeMillis());
        taskRun.executeTaskRun();
        PartitionBasedMvRefreshProcessor processor =
                (PartitionBasedMvRefreshProcessor) taskRun.getProcessor();
        ExecPlan execPlan = processor.getMvContext().getExecPlan();
        Assertions.assertTrue(execPlan != null);
        String plan = execPlan.getExplainString(StatementBase.ExplainLevel.NORMAL);
        PlanTestBase.assertContains(plan, "     TABLE: tt1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 1: k1 > 1, 4: dt IS NOT NULL\n" +
                "     partitions=1/3");
        PlanTestBase.assertContains(plan, "     TABLE: tt1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 5: k1 > 2, 8: dt IS NOT NULL\n" +
                "     partitions=1/3");
        PlanTestBase.assertContains(plan, "     TABLE: tt1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 9: k1 > 3, 12: dt IS NOT NULL\n" +
                "     partitions=1/3");
    }

    @Test
    public void testRefreshWithCachePartitionTraits() throws Exception {
        starRocksAssert.withTable("CREATE TABLE tbl1 \n" +
                "(\n" +
                "    k1 date,\n" +
                "    k2 int,\n" +
                "    v1 int sum\n" +
                ")\n" +
                "PARTITION BY RANGE(k1)\n" +
                "(\n" +
                "    PARTITION p1 values [('2022-02-01'),('2022-02-16')),\n" +
                "    PARTITION p2 values [('2022-02-16'),('2022-03-01'))\n" +
                ")\n" +
                "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                "PROPERTIES('replication_num' = '1');");
        starRocksAssert.withMaterializedView("create materialized view test_mv1 \n" +
                        "partition by (k1) \n" +
                        "distributed by random \n" +
                        "refresh deferred manual\n" +
                        "as select k1, k2, sum(v1) as total from tbl1 group by k1, k2;",
                () -> {
                    UtFrameUtils.mockEnableQueryContextCache();
                    executeInsertSql(connectContext, "insert into tbl1 values(\"2022-02-20\", 1, 10)");
                    OlapTable table = (OlapTable) getTable("test", "tbl1");
                    MaterializedView mv = getMv("test", "test_mv1");
                    PartitionBasedMvRefreshProcessor processor = refreshMV("test", mv);
                    QueryMaterializationContext queryMVContext = connectContext.getQueryMVContext();
                    Assertions.assertTrue(queryMVContext == null);

                    {
                        RuntimeProfile runtimeProfile = processor.getRuntimeProfile();
                        QueryMaterializationContext.QueryCacheStats queryCacheStats = getQueryCacheStats(runtimeProfile);
                        String key = String.format("cache_getUpdatedPartitionNames_%s_%s", mv.getId(), table.getId());
                        Assertions.assertTrue(queryCacheStats != null);
                        Assertions.assertTrue(queryCacheStats.getCounter().containsKey(key));
                        Assertions.assertTrue(queryCacheStats.getCounter().get(key) == 1);
                    }

                    Set<String> partitionsToRefresh1 = getPartitionNamesToRefreshForMv(mv);
                    Assertions.assertTrue(partitionsToRefresh1.isEmpty());

                    executeInsertSql(connectContext, "insert into tbl1 values(\"2022-02-20\", 2, 10)");
                    Partition p2 = table.getPartition("p2");
                    while (p2.getDefaultPhysicalPartition().getVisibleVersion() != 3) {
                        Thread.sleep(1000);
                    }
                    MvUpdateInfo mvUpdateInfo = getMvUpdateInfo(mv);
                    Assertions.assertTrue(mvUpdateInfo.getMvToRefreshType() == MvUpdateInfo.MvToRefreshType.PARTIAL);
                    Assertions.assertTrue(mvUpdateInfo.isValidRewrite());
                    partitionsToRefresh1 = getPartitionNamesToRefreshForMv(mv);
                    Assertions.assertFalse(partitionsToRefresh1.isEmpty());

                    {
                        processor = refreshMV("test", mv);
                        RuntimeProfile runtimeProfile = processor.getRuntimeProfile();
                        QueryMaterializationContext.QueryCacheStats queryCacheStats = getQueryCacheStats(runtimeProfile);
                        String key = String.format("cache_getUpdatedPartitionNames_%s_%s", mv.getId(), table.getId());
                        Assertions.assertTrue(queryCacheStats != null);
                        Assertions.assertTrue(queryCacheStats.getCounter().containsKey(key));
                        Assertions.assertTrue(queryCacheStats.getCounter().get(key) >= 1);
                    }
                });
        starRocksAssert.dropTable("tbl1");
    }

    @Test
    public void testTaskRun() {
        starRocksAssert.withTable(new MTable("tbl6", "k2",
                        List.of(
                                "k1 date",
                                "k2 int",
                                "v1 int"
                        ),
                        "k1",
                        List.of(
                                "PARTITION p0 values [('2021-12-01'),('2022-01-01'))",
                                "PARTITION p1 values [('2022-01-01'),('2022-02-01'))",
                                "PARTITION p2 values [('2022-02-01'),('2022-03-01'))",
                                "PARTITION p3 values [('2022-03-01'),('2022-04-01'))",
                                "PARTITION p4 values [('2022-04-01'),('2022-05-01'))"
                        )
                ),
                () -> {
                    starRocksAssert
                            .withMaterializedView("create materialized view test_task_run \n" +
                                    "partition by date_trunc('month',k1) \n" +
                                    "distributed by hash(k2) buckets 10\n" +
                                    "refresh deferred manual\n" +
                                    "properties('replication_num' = '1', 'partition_refresh_number'='1')\n" +
                                    "as select k1, k2 from tbl6;");
                    String mvName = "test_task_run";
                    Database testDb = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(DB_NAME);
                    MaterializedView mv = ((MaterializedView) testDb.getTable(mvName));
                    TaskManager tm = GlobalStateMgr.getCurrentState().getTaskManager();

                    executeInsertSql(connectContext, "insert into tbl6 partition(p1) values('2022-01-02',2,10);");
                    executeInsertSql(connectContext, "insert into tbl6 partition(p2) values('2022-02-02',2,10);");

                    long taskId = tm.getTask(TaskBuilder.getMvTaskName(mv.getId())).getId();
                    TaskRunScheduler taskRunScheduler = tm.getTaskRunScheduler();

                    // refresh materialized view
                    HashMap<String, String> taskRunProperties = new HashMap<>();
                    taskRunProperties.put(TaskRun.FORCE, Boolean.toString(true));
                    Task task = TaskBuilder.buildMvTask(mv, testDb.getFullName());
                    TaskRun taskRun = TaskRunBuilder.newBuilder(task).build();
                    taskRun.setTaskId(taskId);
                    taskRun.initStatus(UUIDUtil.genUUID().toString(), System.currentTimeMillis());
                    taskRunScheduler.addPendingTaskRun(taskRun);
                    Config.enable_task_history_archive = false;

                    int i = 0;
                    while (i++ < 300 && (taskRunScheduler.getRunnableTaskRun(taskId) != null
                            || tm.listMVRefreshedTaskRunStatus(null, null).isEmpty())) {
                        Thread.sleep(100);
                    }
                    if (tm.listMVRefreshedTaskRunStatus(null, null).isEmpty()) {
                        return;
                    }
                    // without db name
                    Assertions.assertFalse(tm.getMatchedTaskRunStatus(null).isEmpty());
                    Assertions.assertFalse(tm.filterTasks(null).isEmpty());
                    Assertions.assertFalse(tm.listMVRefreshedTaskRunStatus(null, null).isEmpty());

                    // specific db
                    TGetTasksParams getTasksParams = new TGetTasksParams();
                    getTasksParams.setDb(DB_NAME);
                    Assertions.assertFalse(tm.getMatchedTaskRunStatus(getTasksParams).isEmpty());
                    Assertions.assertFalse(tm.filterTasks(getTasksParams).isEmpty());
                    Assertions.assertFalse(tm.listMVRefreshedTaskRunStatus(DB_NAME, null).isEmpty());

                    while (taskRunScheduler.getRunningTaskCount() > 0) {
                        Thread.sleep(100);
                    }
                    starRocksAssert.dropMaterializedView("test_task_run");
                    Config.enable_task_history_archive = true;
                }
        );
    }

    @Test
    public void testRefreshPriority() {
        starRocksAssert.withTable(new MTable("tbl6", "k2",
                        List.of(
                                "k1 date",
                                "k2 int",
                                "v1 int"
                        ),
                        "k1",
                        List.of(
                                "PARTITION p0 values [('2021-12-01'),('2022-01-01'))",
                                "PARTITION p1 values [('2022-01-01'),('2022-02-01'))",
                                "PARTITION p2 values [('2022-02-01'),('2022-03-01'))",
                                "PARTITION p3 values [('2022-03-01'),('2022-04-01'))",
                                "PARTITION p4 values [('2022-04-01'),('2022-05-01'))"
                        )
                ),
                () -> {
                    String mvName = "mv_refresh_priority";
                    starRocksAssert.withMaterializedView("create materialized view test.mv_refresh_priority\n" +
                            "partition by date_trunc('month',k1) \n" +
                            "distributed by hash(k2) buckets 10\n" +
                            "refresh deferred manual\n" +
                            "properties('replication_num' = '1', 'partition_refresh_number'='1')\n" +
                            "as select k1, k2 from tbl6;");
                    Database testDb = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
                    MaterializedView mv = ((MaterializedView) testDb.getTable(mvName));
                    TaskManager tm = GlobalStateMgr.getCurrentState().getTaskManager();
                    long taskId = tm.getTask(TaskBuilder.getMvTaskName(mv.getId())).getId();
                    TaskRunScheduler taskRunScheduler = tm.getTaskRunScheduler();

                    executeInsertSql(connectContext, "insert into tbl6 partition(p1) values('2022-01-02',2,10);");
                    executeInsertSql(connectContext, "insert into tbl6 partition(p2) values('2022-02-02',2,10);");

                    HashMap<String, String> taskRunProperties = new HashMap<>();
                    taskRunProperties.put(TaskRun.FORCE, Boolean.toString(true));
                    Task task = TaskBuilder.buildMvTask(mv, testDb.getFullName());
                    TaskRun taskRun = TaskRunBuilder.newBuilder(task).build();
                    initAndExecuteTaskRun(taskRun);
                    TGetTasksParams params = new TGetTasksParams();
                    params.setTask_name(task.getName());
                    List<TaskRunStatus> statuses = tm.getMatchedTaskRunStatus(params);
                    while (statuses.size() != 1) {
                        statuses = tm.getMatchedTaskRunStatus(params);
                        Thread.sleep(100);
                    }
                    Assertions.assertEquals(1, statuses.size());
                    TaskRunStatus status = statuses.get(0);
                    // default priority for next refresh batch is Constants.TaskRunPriority.HIGHER.value()
                    Assertions.assertEquals(Constants.TaskRunPriority.HIGHER.value(), status.getPriority());
                    starRocksAssert.dropMaterializedView("mv_refresh_priority");
                }
        );
    }

    @Test
    public void testMVRefreshProperties() {
        starRocksAssert.withTable(new MTable("tbl6", "k2",
                        List.of(
                                "k1 date",
                                "k2 int",
                                "v1 int"
                        ),
                        "k1",
                        List.of(
                                "PARTITION p0 values [('2021-12-01'),('2022-01-01'))",
                                "PARTITION p1 values [('2022-01-01'),('2022-02-01'))",
                                "PARTITION p2 values [('2022-02-01'),('2022-03-01'))",
                                "PARTITION p3 values [('2022-03-01'),('2022-04-01'))",
                                "PARTITION p4 values [('2022-04-01'),('2022-05-01'))"
                        )
                ),
                () -> {
                    String mvName = "test_mv1";
                    starRocksAssert.withMaterializedView("create materialized view test_mv1 \n" +
                            "partition by date_trunc('month',k1) \n" +
                            "distributed by hash(k2) buckets 10\n" +
                            "refresh deferred manual\n" +
                            "properties(" +
                            "   'replication_num' = '1', " +
                            "   'session.enable_materialized_view_rewrite' = 'true', \n" +
                            "   'session.enable_materialized_view_for_insert' = 'true',  \n" +
                            "   'partition_refresh_number'='1'" +
                            ")\n" +
                            "as select k1, k2 from tbl6;",
                            () -> {
                                Database testDb = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
                                MaterializedView mv = ((MaterializedView) testDb.getTable(mvName));
                                executeInsertSql(connectContext,
                                        "insert into tbl6 partition(p1) values('2022-01-02',2,10);");
                                executeInsertSql(connectContext, "insert into tbl6 partition(p2) values('2022-02-02',2,10);");

                                HashMap<String, String> taskRunProperties = new HashMap<>();
                                taskRunProperties.put(TaskRun.FORCE, Boolean.toString(true));
                                Task task = TaskBuilder.buildMvTask(mv, testDb.getFullName());
                                ExecuteOption executeOption = new ExecuteOption(70, false, new HashMap<>());
                                TaskRun taskRun = TaskRunBuilder.newBuilder(task).setExecuteOption(executeOption).build();
                                initAndExecuteTaskRun(taskRun);
                                TGetTasksParams params = new TGetTasksParams();
                                params.setTask_name(task.getName());
                                TaskManager tm = GlobalStateMgr.getCurrentState().getTaskManager();
                                List<TaskRunStatus> statuses = tm.getMatchedTaskRunStatus(params);
                                while (statuses.size() != 1) {
                                    statuses = tm.getMatchedTaskRunStatus(params);
                                    Thread.sleep(100);
                                }
                                Assertions.assertEquals(1, statuses.size());
                                TaskRunStatus status = statuses.get(0);
                                // the priority for next refresh batch is 70 which is specified in executeOption
                                Assertions.assertEquals(70, status.getPriority());

                                PartitionBasedMvRefreshProcessor processor =
                                        (PartitionBasedMvRefreshProcessor) taskRun.getProcessor();
                                MvTaskRunContext mvTaskRunContext = processor.getMvContext();
                                Assertions.assertEquals(70, mvTaskRunContext.getExecuteOption().getPriority());
                                Map<String, String> properties = mvTaskRunContext.getProperties();
                                Assertions.assertEquals(2, properties.size());
                                Assertions.assertTrue(properties.containsKey(MV_ID));
                                Assertions.assertTrue(properties.containsKey(PROPERTIES_WAREHOUSE));
                                // Ensure that table properties are not passed to the task run
                                Assertions.assertFalse(properties.containsKey(PropertyAnalyzer.PROPERTIES_REPLICATION_NUM));

                                ConnectContext context = mvTaskRunContext.getCtx();
                                SessionVariable sessionVariable = context.getSessionVariable();
                                // Ensure that session properties are set
                                Assertions.assertTrue(sessionVariable.isEnableMaterializedViewRewrite());
                                Assertions.assertTrue(sessionVariable.isEnableMaterializedViewRewriteForInsert());
                                starRocksAssert.dropMaterializedView(mvName);
                            });
                }
        );
    }

    private void withTablePartitions(String tableName) {
        if (tableName.equalsIgnoreCase("r1")) {
            return;
        }

        addRangePartition(tableName, "p1", "2024-01-01", "2024-01-02");
        addRangePartition(tableName, "p2", "2024-01-02", "2024-01-03");
        addRangePartition(tableName, "p3", "2024-01-03", "2024-01-04");
        addRangePartition(tableName, "p4", "2024-01-04", "2024-01-05");
    }

    private void testMVRefreshWithTTLCondition(String tableName) {
        withTablePartitions(tableName);
        String mvCreateDdl = String.format("create materialized view test_mv1\n" +
                "partition by (dt) \n" +
                "distributed by random \n" +
                "REFRESH DEFERRED MANUAL \n" +
                "PROPERTIES ('partition_retention_condition' = 'dt >= current_date() - interval 1 month')\n " +
                "as select * from %s;", tableName);
        starRocksAssert.withMaterializedView(mvCreateDdl,
                () -> {
                    String mvName = "test_mv1";
                    MaterializedView mv = starRocksAssert.getMv("test", mvName);
                    String query = String.format("select * from %s ", tableName);
                    {
                        // all partitions are expired, no need to create partitions for mv
                        PartitionBasedMvRefreshProcessor processor = refreshMV("test", mv);
                        Assertions.assertEquals(0, mv.getVisiblePartitions().size());
                        Assertions.assertTrue(processor.getNextTaskRun() == null);
                        ExecPlan execPlan = processor.getMvContext().getExecPlan();
                        Assertions.assertTrue(execPlan == null);
                        String plan = getFragmentPlan(query);
                        PlanTestBase.assertContains(plan, String.format("TABLE: %s\n" +
                                "     PREAGGREGATION: ON\n" +
                                "     partitions=4/4", tableName));
                    }

                    {
                        // add new partitions
                        LocalDateTime now = LocalDateTime.now();
                        addRangePartition(tableName, "p5",
                                now.minusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                                now.minusMonths(1).plusDays(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                                true);
                        addRangePartition(tableName, "p6",
                                now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                                now.plusDays(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                                true);
                        String plan = getFragmentPlan(query);
                        PlanTestBase.assertContains(plan, String.format("TABLE: %s\n" +
                                "     PREAGGREGATION: ON\n" +
                                "     partitions=6/6", tableName));
                    }

                    {
                        String alterMVSql = String.format("alter materialized view %s set (" +
                                "'query_rewrite_consistency' = 'loose')", mvName);
                        starRocksAssert.alterMvProperties(alterMVSql);
                        String plan = getFragmentPlan(query);
                        PlanTestBase.assertContains(plan, String.format("TABLE: %s\n" +
                                "     PREAGGREGATION: ON\n" +
                                "     partitions=6/6", tableName));
                    }

                    {
                        String alterMVSql = String.format("alter materialized view %s set (" +
                                "'query_rewrite_consistency' = 'force_mv')", mvName);
                        starRocksAssert.alterMvProperties(alterMVSql);
                        String plan = getFragmentPlan(query);
                        PlanTestBase.assertContains(plan, ":UNION");
                        PlanTestBase.assertMatches(plan, String.format("     TABLE: %s\n" +
                                "     PREAGGREGATION: ON\n" +
                                "     PREDICATES: .*\n" +
                                "     partitions=4/6", tableName));
                        PlanTestBase.assertContains(plan, "TABLE: test_mv1\n" +
                                "     PREAGGREGATION: ON\n" +
                                "     partitions=0/0");

                    }

                    refreshMV("test", mv);
                    {
                        String plan = getFragmentPlan(query);
                        PlanTestBase.assertContains(plan, ":UNION");
                        PlanTestBase.assertMatches(plan, String.format("     TABLE: %s\n" +
                                "     PREAGGREGATION: ON\n" +
                                "     PREDICATES: .*\n" +
                                "     partitions=4/6", tableName));
                        PlanTestBase.assertContains(plan, "TABLE: test_mv1\n" +
                                "     PREAGGREGATION: ON\n" +
                                "     partitions=2/2");
                    }
                    {
                        String query2 = String.format("select * from %s where dt >= current_date() - interval 1 month ",
                                tableName);
                        FeConstants.enablePruneEmptyOutputScan = true;
                        String plan = getFragmentPlan(query2);
                        PlanTestBase.assertNotContains(plan, ":UNION");
                        PlanTestBase.assertContains(plan, "     TABLE: test_mv1\n" +
                                "     PREAGGREGATION: ON\n" +
                                "     partitions=2/2\n" +
                                "     rollup: test_mv1");
                        FeConstants.enablePruneEmptyOutputScan = false;
                    }
                });
    }

    @Test
    public void testMVRefreshWithTTLConditionTT1() {
        connectContext.getSessionVariable().setMaterializedViewRewriteMode("force");
        starRocksAssert.withTable(R1,
                (obj) -> {
                    String tableName = (String) obj;
                    testMVRefreshWithTTLCondition(tableName);
                });
        connectContext.getSessionVariable().setMaterializedViewRewriteMode("default");
    }

    @Test
    public void testMVRefreshWithTTLConditionTT2() {
        connectContext.getSessionVariable().setMaterializedViewRewriteMode("force");
        starRocksAssert.withTable(R2,
                (obj) -> {
                    String tableName = (String) obj;
                    testMVRefreshWithTTLCondition(tableName);
                });
        connectContext.getSessionVariable().setMaterializedViewRewriteMode("default");
    }

    @Test
    public void testMVRefreshWithTTLConditionTT3() {
        starRocksAssert.withTable(R1,
                (obj) -> {
                    String tableName = (String) obj;
                    testMVRefreshWithTTLCondition(tableName);
                });
    }

    @Test
    public void testMVRefreshWithTTLConditionTT4() {
        starRocksAssert.withTable(R2,
                (obj) -> {
                    String tableName = (String) obj;
                    testMVRefreshWithTTLCondition(tableName);
                });
    }

    @Test
    public void testMVRefreshWithMultiTables() throws Exception {
        starRocksAssert.withTable("CREATE TABLE t1 (dt1 date, int1 int)\n" +
                "PARTITION BY RANGE(dt1)\n" +
                "(\n" +
                "PARTITION p202006 VALUES LESS THAN (\"2020-07-01\"),\n" +
                "PARTITION p202007 VALUES LESS THAN (\"2020-08-01\"),\n" +
                "PARTITION p202008 VALUES LESS THAN (\"2020-09-01\")\n" +
                ");");
        starRocksAssert.withTable("CREATE TABLE t2 (dt2 date, int2 int);");
        executeInsertSql("INSERT INTO t2 VALUES (\"2020-06-23\",1),(\"2020-07-23\",1),(\"2020-07-23\",1)" +
                ",(\"2020-08-23\",1),(null,null);\n");
        executeInsertSql("INSERT INTO t1 VALUES (\"2020-06-23\",1);\n");
        starRocksAssert.withMaterializedView("CREATE MATERIALIZED VIEW mv2 PARTITION BY dt1 " +
                "REFRESH DEFERRED MANUAL PROPERTIES (\"partition_refresh_number\"=\"1\")\n" +
                "AS SELECT dt1,sum(int1) from t1 group by dt1 union all\n" +
                "SELECT dt2,sum(int2) from t2 group by dt2;");
        String mvName = "mv2";
        MaterializedView mv = starRocksAssert.getMv("test", mvName);
        ExecuteOption executeOption = new ExecuteOption(70, false, new HashMap<>());
        Database testDb = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
        Task task = TaskBuilder.buildMvTask(mv, testDb.getFullName());
        TaskRun taskRun = TaskRunBuilder.newBuilder(task).setExecuteOption(executeOption).build();
        initAndExecuteTaskRun(taskRun);
        PartitionBasedMvRefreshProcessor processor =
                (PartitionBasedMvRefreshProcessor) taskRun.getProcessor();
        MvTaskRunContext mvTaskRunContext = processor.getMvContext();
        Assertions.assertTrue(mvTaskRunContext.getExecPlan() != null);
        ExecPlan execPlan = mvTaskRunContext.getExecPlan();
        assertPlanContains(execPlan, "     TABLE: t2\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: (4: dt2 < '2020-07-01') OR (4: dt2 IS NULL)");
        assertPlanContains(execPlan, "     TABLE: t1\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: (1: dt1 < '2020-07-01') OR (1: dt1 IS NULL)\n" +
                "     partitions=1/3");
    }

    @Test
    public void testMVRefreshWithMultiTables2() throws Exception {
        String partitionTable = "CREATE TABLE partition_table (dt1 date, int1 int)\n" +
                "PARTITION BY RANGE(dt1)\n" +
                "(\n" +
                "PARTITION p202006 VALUES LESS THAN (\"2020-07-01\"),\n" +
                "PARTITION p202007 VALUES LESS THAN (\"2020-08-01\"),\n" +
                "PARTITION p202008 VALUES LESS THAN (\"2020-09-01\")\n" +
                ");";
        String partitionTableValue = "INSERT INTO partition_table VALUES (\"2020-06-23\",1);\n";
        String mvQuery = "CREATE MATERIALIZED VIEW mv2 " +
                "PARTITION BY date_trunc('day', dt1) " +
                "REFRESH DEFERRED MANUAL PROPERTIES (\"partition_refresh_number\"=\"1\")\n" +
                "AS SELECT dt1,sum(int1) from partition_table group by dt1 union all\n" +
                "SELECT dt2,sum(int2) from non_partition_table group by dt2;";
        testMVRefreshWithOnePartitionAndOneUnPartitionTable(partitionTable, partitionTableValue, mvQuery,
                "     TABLE: partition_table\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: (1: dt1 < '2020-07-01') OR (1: dt1 IS NULL)\n" +
                        "     partitions=1/3",
                "     TABLE: non_partition_table\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: (4: dt2 < '2020-07-01') OR (4: dt2 IS NULL)\n" +
                        "     partitions=1/1");
    }

    @Test
    public void testMVRefreshWithMultiTables1() throws Exception {
        String partitionTable = "CREATE TABLE partition_table (dt1 date, int1 int)\n" +
                "PARTITION BY date_trunc('day', dt1)";
        starRocksAssert.withTable(partitionTable);
        addRangePartition("partition_table", "p1", "2024-01-04", "2024-01-05");

        String partitionTableValue = "INSERT INTO partition_table VALUES (\"2024-01-04\",1);\n";
        String mvQuery = "CREATE MATERIALIZED VIEW mv2 " +
                "PARTITION BY date_trunc('day', dt1) " +
                "REFRESH DEFERRED MANUAL PROPERTIES (\"partition_refresh_number\"=\"1\")\n" +
                "AS SELECT dt1,sum(int1) from partition_table group by dt1 union all\n" +
                "SELECT dt2,sum(int2) from non_partition_table group by dt2;";

        testMVRefreshWithOnePartitionAndOneUnPartitionTable("", partitionTableValue, mvQuery,
                "     TABLE: non_partition_table\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     PREDICATES: 4: dt2 >= '2024-01-04', 4: dt2 < '2024-01-05'\n" +
                        "     partitions=1/1",
                "     TABLE: partition_table\n" +
                        "     PREAGGREGATION: ON\n" +
                        "     partitions=1/1");
    }

    @Test
    public void testMVWithEmptyRefresh1() throws Exception {
        starRocksAssert.withTable("CREATE TABLE t1 (dt1 date, int1 int)\n" +
                "PARTITION BY RANGE(dt1)\n" +
                "(\n" +
                "PARTITION p202006 VALUES LESS THAN (\"2020-07-01\"),\n" +
                "PARTITION p202007 VALUES LESS THAN (\"2020-08-01\"),\n" +
                "PARTITION p202008 VALUES LESS THAN (\"2020-09-01\")\n" +
                ");");
        starRocksAssert.withTable("CREATE TABLE t2 (dt2 date, int2 int);");
        starRocksAssert.withMaterializedView("CREATE MATERIALIZED VIEW mv2 PARTITION BY dt1 " +
                "REFRESH DEFERRED MANUAL PROPERTIES (\"partition_refresh_number\"=\"1\")\n" +
                "AS SELECT dt1,sum(int1) from t1 group by dt1 union all\n" +
                "SELECT dt2,sum(int2) from t2 group by dt2;");
        String mvName = "mv2";
        MaterializedView mv = starRocksAssert.getMv("test", mvName);
        ExecuteOption executeOption = new ExecuteOption(70, false, new HashMap<>());
        Database testDb = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
        Task task = TaskBuilder.buildMvTask(mv, testDb.getFullName());
        TaskRun taskRun = TaskRunBuilder.newBuilder(task).setExecuteOption(executeOption).build();
        taskRun.initStatus(UUIDUtil.genUUID().toString(), System.currentTimeMillis());
        Constants.TaskRunState state = taskRun.executeTaskRun();
        Assertions.assertEquals(Constants.TaskRunState.SKIPPED, state);
    }

    @Test
    public void testMVWithEmptyRefresh2() throws Exception {
        starRocksAssert.withTable("CREATE TABLE t1 (dt1 date, int1 int)\n" +
                "PARTITION BY RANGE(dt1)\n" +
                "(\n" +
                "PARTITION p202006 VALUES LESS THAN (\"2020-07-01\"),\n" +
                "PARTITION p202007 VALUES LESS THAN (\"2020-08-01\"),\n" +
                "PARTITION p202008 VALUES LESS THAN (\"2020-09-01\")\n" +
                ");");
        starRocksAssert.withMaterializedView("CREATE MATERIALIZED VIEW mv2 PARTITION BY dt1 " +
                "REFRESH DEFERRED MANUAL PROPERTIES (\"partition_refresh_number\"=\"1\")\n" +
                "AS SELECT dt1,sum(int1) from t1 group by dt1;");
        String mvName = "mv2";
        MaterializedView mv = starRocksAssert.getMv("test", mvName);
        ExecuteOption executeOption = new ExecuteOption(70, false, new HashMap<>());
        Database testDb = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
        Task task = TaskBuilder.buildMvTask(mv, testDb.getFullName());
        TaskRun taskRun = TaskRunBuilder.newBuilder(task).setExecuteOption(executeOption).build();
        taskRun.initStatus(UUIDUtil.genUUID().toString(), System.currentTimeMillis());
        Constants.TaskRunState state = taskRun.executeTaskRun();
        Assertions.assertEquals(Constants.TaskRunState.SKIPPED, state);
    }
}