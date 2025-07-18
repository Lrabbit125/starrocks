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

package com.starrocks.benchmark;

import com.starrocks.common.Config;
import com.starrocks.qe.SessionVariable;
import com.starrocks.sql.optimizer.CachingMvPlanContextBuilder;
import com.starrocks.sql.optimizer.rule.transformation.materialization.MVTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 20)
@Fork(1)
public class MvRewritePerfTest extends MVTestBase {

    private static final int MV_NUM = 40;

    @BeforeAll
    public static void beforeClass() throws Exception {
        MVTestBase.beforeClass();

        // Env
        Config.mv_plan_cache_max_size = 1024;
        CachingMvPlanContextBuilder.getInstance().rebuildCache();
        starRocksAssert.getCtx().setDumpInfo(null);

        // Base tables
        starRocksAssert.withTable(cluster, "t0");
        starRocksAssert.withTable(cluster, "t1");
        cluster.runSql("test", "insert into t0 values(1, 1, 1), (2,2,2)");
        cluster.runSql("test", "insert into t1 values(1, 1, 1), (2,2,2)");

        // MV_NUM msv with same schema
        for (int i = 0; i < MV_NUM; i++) {
            // join MV
            String joinMV = "mv_candidate_join_" + i;
            starRocksAssert.withRefreshedMaterializedView("create materialized view " + joinMV +
                    " refresh async as " +
                    " select t0.v1, t0.v2, t0.v3, t1.k1 from t0 left join t1 on t0.v1 = t1.v1");

            // agg MV
            String aggMV = "mv_candidate_agg_" + i;
            starRocksAssert.withRefreshedMaterializedView("create materialized view " + aggMV +
                    " refresh async as " +
                    " select t0.v1, sum(t1.v1), count(t1.v2) from t0 left join t1 on t0.v1 = t1.v1" +
                    " group by t0.v1");
        }

        LOG.info("prepared {} materialized views", MV_NUM);
    }

    @BeforeEach
    public void before() {
        super.before();
        starRocksAssert.getCtx().getSessionVariable().setEnableQueryDump(false);
        starRocksAssert.getCtx().getSessionVariable().setCboMaterializedViewRewriteRuleOutputLimit(
                SessionVariable.DEFAULT_SESSION_VARIABLE.getCboMaterializedViewRewriteRuleOutputLimit());
        starRocksAssert.getCtx().getSessionVariable().setCboMaterializedViewRewriteCandidateLimit(
                SessionVariable.DEFAULT_SESSION_VARIABLE.getCboMaterializedViewRewriteCandidateLimit());
    }

    @AfterEach
    public void after() throws Exception {
        super.after();
    }

    // round: 0.01 [+- 0.00], round.block: 0.00 [+- 0.00], round.gc: 0.00 [+- 0.00], GC.calls: 0, GC.time: 0.00,
    // time.total: 0.57, time.warmup: 0.34, time.bench: 0.23
    @Test
    @Benchmark
    public void testManyCandidateMv_Join_WithRewriteLimit() throws Exception {
        final String sql = " select t0.v1, t0.v2, t0.v3, t1.k1 from t0 left join t1 on t0.v1 = t1.v1";
        starRocksAssert.getCtx().getSessionVariable().setCboMaterializedViewRewriteRuleOutputLimit(3);
        starRocksAssert.query(sql).explainContains("mv_candidate_join");
    }

    // round: 0.01 [+- 0.00], round.block: 0.00 [+- 0.00], round.gc: 0.00 [+- 0.00], GC.calls: 0, GC.time: 0.00,
    // time.total: 0.23, time.warmup: 0.03, time.bench: 0.20
    @Test
    @Benchmark
    public void testManyCandidateMv_Join_WithoutRewriteLimit() throws Exception {
        final String sql = " select t0.v1, t0.v2, t0.v3, t1.k1 from t0 left join t1 on t0.v1 = t1.v1";
        starRocksAssert.getCtx().getSessionVariable().setCboMaterializedViewRewriteRuleOutputLimit(1000);
        starRocksAssert.query(sql).explainContains("mv_candidate_join");
    }

    //  round: 0.01 [+- 0.00], round.block: 0.00 [+- 0.00], round.gc: 0.00 [+- 0.00], GC.calls: 0, GC.time: 0.00,
    //  time.total: 0.13, time.warmup: 0.02, time.bench: 0.12
    @Test
    @Benchmark
    public void testManyCandidateMV_WithCandidateLimit() throws Exception {
        final String sql = " select t0.v1, t0.v2, t0.v3, t1.k1 from t0 left join t1 on t0.v1 = t1.v1";
        starRocksAssert.getCtx().getSessionVariable().setCboMaterializedViewRewriteCandidateLimit(3);
        starRocksAssert.getCtx().getSessionVariable().setCboMaterializedViewRewriteRuleOutputLimit(0);
        starRocksAssert.query(sql).explainContains("mv_candidate_join");
    }

    //  round: 0.02 [+- 0.00], round.block: 0.00 [+- 0.00], round.gc: 0.00 [+- 0.00], GC.calls: 0, GC.time: 0.00,
    //  time.total: 0.40, time.warmup: 0.06, time.bench: 0.34
    @Test
    @Benchmark
    public void testManyCandidateMV_WithoutCandidateLimit() throws Exception {
        final String sql = " select t0.v1, t0.v2, t0.v3, t1.k1 from t0 left join t1 on t0.v1 = t1.v1";
        starRocksAssert.getCtx().getSessionVariable().setCboMaterializedViewRewriteCandidateLimit(0);
        starRocksAssert.getCtx().getSessionVariable().setCboMaterializedViewRewriteRuleOutputLimit(0);
        starRocksAssert.query(sql).explainContains("mv_candidate_join");
    }

    // round: 0.02 [+- 0.00], round.block: 0.00 [+- 0.00], round.gc: 0.00 [+- 0.00], GC.calls: 0, GC.time: 0.00,
    // time.total: 0.45, time.warmup: 0.07, time.bench: 0.38
    @Test
    @Benchmark
    public void testManyCandidateMv_Agg_WithRewriteLimit() throws Exception {
        final String sql =
                " select t0.v1, sum(t1.v1), count(t1.v2) from t0 left join t1 on t0.v1 = t1.v1 group by t0.v1";
        starRocksAssert.getCtx().getSessionVariable().setCboMaterializedViewRewriteRuleOutputLimit(3);
        starRocksAssert.query(sql).explainContains("mv_candidate_agg");
    }

    //  round: 0.02 [+- 0.00], round.block: 0.00 [+- 0.00], round.gc: 0.00 [+- 0.00], GC.calls: 0, GC.time: 0.00,
    //  time.total: 0.45, time.warmup: 0.06, time.bench: 0.38
    @Test
    @Benchmark
    public void testManyCandidateMv_Agg_WithoutRewriteLimit() throws Exception {
        final String sql =
                " select t0.v1, sum(t1.v1), count(t1.v2) from t0 left join t1 on t0.v1 = t1.v1 group by t0.v1";
        starRocksAssert.getCtx().getSessionVariable().setCboMaterializedViewRewriteRuleOutputLimit(1000);
        starRocksAssert.query(sql).explainContains("mv_candidate_agg");
    }
}
