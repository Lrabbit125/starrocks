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

package com.starrocks.sql.analyzer;

import com.starrocks.utframe.UtFrameUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.starrocks.sql.analyzer.AnalyzeTestUtil.analyzeFail;
import static com.starrocks.sql.analyzer.AnalyzeTestUtil.analyzeSuccess;

public class AnalyzeSPMTest {

    @BeforeAll
    public static void beforeClass() throws Exception {
        UtFrameUtils.createMinStarRocksCluster();
        AnalyzeTestUtil.init();
    }

    @Test
    public void testControlBaselinePlan() {
        analyzeSuccess("enable baseline 1,2,3,4;");
        analyzeSuccess("disable baseline 1,2,3,4;");
    }

    @Test
    public void testSPMFunctionSQL() {
        analyzeSuccess("select * from t0 where t0.v2 = _spm_const_var(1, 3)");
        analyzeSuccess("select * from t0 where t0.v2 in (_spm_const_list(1, 2, 4, 5, 3))");
    }

    @Test
    public void testShowSPMFunctionSQL2() {
        analyzeSuccess("show baseline");
        analyzeSuccess("show baseline where id = 2");
        analyzeSuccess("show baseline where id = 2 and bindSQLDigest = 'asdf'");
        analyzeSuccess("show baseline where bindSQLDigest = 'asdf' or updateTime <= now()");
        analyzeSuccess("show baseline where bindSQLDigest like 'asdf' or updateTime <= now()");
        analyzeSuccess("show baseline on select * from tb1");

        // error field
        analyzeFail("show baseline where xxx = 'asdf'");

        // unsupported function
        analyzeFail("show baseline where bindSQLDigest = lpad('asdf')");
    }
}
