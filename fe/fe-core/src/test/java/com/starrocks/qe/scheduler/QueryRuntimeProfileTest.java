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

package com.starrocks.qe.scheduler;

import com.starrocks.common.util.Counter;
import com.starrocks.common.util.RuntimeProfile;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.scheduler.dag.JobSpec;
import com.starrocks.thrift.FrontendServiceVersion;
import com.starrocks.thrift.TCounterAggregateType;
import com.starrocks.thrift.TReportExecStatusParams;
import com.starrocks.thrift.TRuntimeProfileTree;
import com.starrocks.thrift.TUnit;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

public class QueryRuntimeProfileTest {

    private ConnectContext connectContext;

    @Mocked
    private JobSpec jobSpec;

    @BeforeEach
    public void setup() {
        connectContext = UtFrameUtils.createDefaultCtx();
        connectContext.setQueryId(UUIDUtil.genUUID());
        connectContext.setExecutionId(UUIDUtil.toTUniqueId(connectContext.getQueryId()));

        new Expectations() {
            {
                jobSpec.getQueryId();
                result = connectContext.getExecutionId();
                minTimes = 0;

                jobSpec.isEnablePipeline();
                result = true;
                minTimes = 0;
            }
        };
    }

    @Test
    public void testMergeLoadChannelProfile() {
        new Expectations() {
            {
                jobSpec.hasOlapTableSink();
                result = true;
                minTimes = 0;
            }
        };

        QueryRuntimeProfile profile = new QueryRuntimeProfile(connectContext, jobSpec, false);
        profile.initFragmentProfiles(1);
        TReportExecStatusParams reportExecStatusParams = buildReportStatus(1L);
        profile.updateLoadChannelProfile(reportExecStatusParams);
        Optional<RuntimeProfile> optional = profile.mergeLoadChannelProfile();
        Assertions.assertTrue(optional.isPresent());
        verifyMergedLoadChannelProfile(optional.get());
    }

    @Test
    public void testBuildNonPipelineQueryProfile() {
        new Expectations() {
            {
                jobSpec.hasOlapTableSink();
                result = true;
                minTimes = 0;
                jobSpec.isEnablePipeline();
                result = false;
                minTimes = 0;
            }
        };

        QueryRuntimeProfile profile = new QueryRuntimeProfile(connectContext, jobSpec, false);
        profile.initFragmentProfiles(1);
        TReportExecStatusParams reportExecStatusParams = buildReportStatus(1L);
        profile.updateLoadChannelProfile(reportExecStatusParams);
        RuntimeProfile runtimeProfile = profile.buildQueryProfile(true);
        Assertions.assertNotNull(runtimeProfile);
        Assertions.assertEquals(2, runtimeProfile.getChildMap().size());
        Assertions.assertSame(profile.getFragmentProfiles().get(0), runtimeProfile.getChild("Fragment 0"));
        RuntimeProfile loadChannelProfile = runtimeProfile.getChild("LoadChannel");
        Assertions.assertNotNull(loadChannelProfile);
        verifyMergedLoadChannelProfile(loadChannelProfile);
    }

    @Test
    public void testMultipleUpdateLoadChannelProfile() {
        new Expectations() {
            {
                jobSpec.hasOlapTableSink();
                result = true;
                minTimes = 0;
            }
        };

        QueryRuntimeProfile profile = new QueryRuntimeProfile(connectContext, jobSpec, false);
        profile.initFragmentProfiles(1);

        // Generate and update with multiple reportStatus
        for (long i = 1; i <= 3; i++) {
            TReportExecStatusParams reportExecStatusParams = buildReportStatus(i);
            profile.updateLoadChannelProfile(reportExecStatusParams);

            Optional<RuntimeProfile> optional = profile.mergeLoadChannelProfile();
            Assertions.assertTrue(optional.isPresent());
            RuntimeProfile mergedProfile = optional.get();

            // Verify the correctness of the final profile
            Assertions.assertEquals("LoadChannel", mergedProfile.getName());
            Assertions.assertEquals("288fb1df-f955-472f-a377-cb1e10e4d993", mergedProfile.getInfoString("LoadId"));
            Assertions.assertEquals("40", mergedProfile.getInfoString("TxnId"));
            Assertions.assertEquals("127.0.0.1,127.0.0.2", mergedProfile.getInfoString("BackendAddresses"));
            Assertions.assertEquals(2, mergedProfile.getCounter("ChannelNum").getValue());
            Assertions.assertEquals(537395200 * i, mergedProfile.getCounter("PeakMemoryUsage").getValue());
            Assertions.assertEquals(1073741824 * i, mergedProfile.getCounter("__MAX_OF_PeakMemoryUsage").getValue());
            Assertions.assertEquals(1048576 * i, mergedProfile.getCounter("__MIN_OF_PeakMemoryUsage").getValue());
            Assertions.assertEquals(2, mergedProfile.getChildMap().size());

            RuntimeProfile indexProfile1 = mergedProfile.getChild("Index (id=10176)");
            Assertions.assertEquals(162 * i, indexProfile1.getCounter("AddChunkCount").getValue());
            Assertions.assertEquals(82 * i, indexProfile1.getCounter("__MAX_OF_AddChunkCount").getValue());
            Assertions.assertEquals(80 * i, indexProfile1.getCounter("__MIN_OF_AddChunkCount").getValue());
            Assertions.assertEquals(15000000000L * i, indexProfile1.getCounter("AddChunkTime").getValue());
            Assertions.assertEquals(20000000000L * i, indexProfile1.getCounter("__MAX_OF_AddChunkTime").getValue());
            Assertions.assertEquals(10000000000L * i, indexProfile1.getCounter("__MIN_OF_AddChunkTime").getValue());

            RuntimeProfile indexProfile2 = mergedProfile.getChild("Index (id=10298)");
            Assertions.assertEquals(162 * i, indexProfile2.getCounter("AddChunkCount").getValue());
            Assertions.assertEquals(82 * i, indexProfile2.getCounter("__MAX_OF_AddChunkCount").getValue());
            Assertions.assertEquals(80 * i, indexProfile2.getCounter("__MIN_OF_AddChunkCount").getValue());
            Assertions.assertEquals(1500000000L * i, indexProfile2.getCounter("AddChunkTime").getValue());
            Assertions.assertEquals(2000000000L * i, indexProfile2.getCounter("__MAX_OF_AddChunkTime").getValue());
            Assertions.assertEquals(1000000000L * i, indexProfile2.getCounter("__MIN_OF_AddChunkTime").getValue());
        }

    }

    private void verifyMergedLoadChannelProfile(RuntimeProfile mergedProfile) {
        Assertions.assertEquals("LoadChannel", mergedProfile.getName());
        Assertions.assertEquals("288fb1df-f955-472f-a377-cb1e10e4d993", mergedProfile.getInfoString("LoadId"));
        Assertions.assertEquals("40", mergedProfile.getInfoString("TxnId"));
        Assertions.assertEquals("127.0.0.1,127.0.0.2", mergedProfile.getInfoString("BackendAddresses"));
        Assertions.assertEquals(2, mergedProfile.getCounter("ChannelNum").getValue());
        Assertions.assertEquals(537395200, mergedProfile.getCounter("PeakMemoryUsage").getValue());
        Assertions.assertEquals(1073741824, mergedProfile.getCounter("__MAX_OF_PeakMemoryUsage").getValue());
        Assertions.assertEquals(1048576, mergedProfile.getCounter("__MIN_OF_PeakMemoryUsage").getValue());
        Assertions.assertEquals(2, mergedProfile.getChildMap().size());

        RuntimeProfile indexProfile1 = mergedProfile.getChild("Index (id=10176)");
        Assertions.assertEquals(162, indexProfile1.getCounter("AddChunkCount").getValue());
        Assertions.assertEquals(82, indexProfile1.getCounter("__MAX_OF_AddChunkCount").getValue());
        Assertions.assertEquals(80, indexProfile1.getCounter("__MIN_OF_AddChunkCount").getValue());
        Assertions.assertEquals(15000000000L, indexProfile1.getCounter("AddChunkTime").getValue());
        Assertions.assertEquals(20000000000L, indexProfile1.getCounter("__MAX_OF_AddChunkTime").getValue());
        Assertions.assertEquals(10000000000L, indexProfile1.getCounter("__MIN_OF_AddChunkTime").getValue());

        RuntimeProfile indexProfile2 = mergedProfile.getChild("Index (id=10298)");
        Assertions.assertEquals(162, indexProfile2.getCounter("AddChunkCount").getValue());
        Assertions.assertEquals(82, indexProfile2.getCounter("__MAX_OF_AddChunkCount").getValue());
        Assertions.assertEquals(80, indexProfile2.getCounter("__MIN_OF_AddChunkCount").getValue());
        Assertions.assertEquals(1500000000L, indexProfile2.getCounter("AddChunkTime").getValue());
        Assertions.assertEquals(2000000000L, indexProfile2.getCounter("__MAX_OF_AddChunkTime").getValue());
        Assertions.assertEquals(1000000000L, indexProfile2.getCounter("__MIN_OF_AddChunkTime").getValue());
    }

    private TReportExecStatusParams buildReportStatus(long valueBase) {
        RuntimeProfile profile = new RuntimeProfile("LoadChannel");
        profile.addInfoString("LoadId", "288fb1df-f955-472f-a377-cb1e10e4d993");
        profile.addInfoString("TxnId", "40");

        RuntimeProfile channelProfile1 = new RuntimeProfile("Channel (host=127.0.0.1)");
        profile.addChild(channelProfile1);
        Counter peakMemoryCounter1 = channelProfile1.addCounter("PeakMemoryUsage", TUnit.BYTES,
                Counter.createStrategy(TCounterAggregateType.AVG));
        peakMemoryCounter1.setValue(1024 * 1024 * 1024 * valueBase);

        RuntimeProfile indexProfile1 = new RuntimeProfile("Index (id=10176)");
        channelProfile1.addChild(indexProfile1);
        Counter addChunkCounter1 = indexProfile1.addCounter("AddChunkCount", TUnit.UNIT,
                Counter.createStrategy(TUnit.UNIT));
        addChunkCounter1.setValue(82 * valueBase);
        Counter addChunkTime1 = indexProfile1.addCounter("AddChunkTime", TUnit.TIME_NS,
                Counter.createStrategy(TCounterAggregateType.AVG));
        addChunkTime1.setValue(20000000000L * valueBase);

        RuntimeProfile indexProfile2 = new RuntimeProfile("Index (id=10298)");
        channelProfile1.addChild(indexProfile2);
        Counter addChunkCounter2 = indexProfile2.addCounter("AddChunkCount", TUnit.UNIT,
                Counter.createStrategy(TUnit.UNIT));
        addChunkCounter2.setValue(82 * valueBase);
        Counter addChunkTime2 = indexProfile2.addCounter("AddChunkTime", TUnit.TIME_NS,
                Counter.createStrategy(TCounterAggregateType.AVG));
        addChunkTime2.setValue(1000000000L * valueBase);

        RuntimeProfile channelProfile2 = new RuntimeProfile("Channel (host=127.0.0.2)");
        profile.addChild(channelProfile2);
        Counter peakMemoryCounter2 = channelProfile2.addCounter("PeakMemoryUsage", TUnit.BYTES,
                Counter.createStrategy(TCounterAggregateType.AVG));
        peakMemoryCounter2.setValue(1024 * 1024 * valueBase);

        RuntimeProfile indexProfile3 = new RuntimeProfile("Index (id=10176)");
        channelProfile2.addChild(indexProfile3);
        Counter addChunkCounter3 = indexProfile3.addCounter("AddChunkCount", TUnit.UNIT,
                Counter.createStrategy(TUnit.UNIT));
        addChunkCounter3.setValue(80 * valueBase);
        Counter addChunkTime3 = indexProfile3.addCounter("AddChunkTime", TUnit.TIME_NS,
                Counter.createStrategy(TCounterAggregateType.AVG));
        addChunkTime3.setValue(10000000000L * valueBase);

        RuntimeProfile indexProfile4 = new RuntimeProfile("Index (id=10298)");
        channelProfile2.addChild(indexProfile4);
        Counter addChunkCounter4 = indexProfile4.addCounter("AddChunkCount", TUnit.UNIT,
                Counter.createStrategy(TUnit.UNIT));
        addChunkCounter4.setValue(80 * valueBase);
        Counter addChunkTime4 = indexProfile4.addCounter("AddChunkTime", TUnit.TIME_NS,
                Counter.createStrategy(TCounterAggregateType.AVG));
        addChunkTime4.setValue(2000000000L * valueBase);

        TReportExecStatusParams params = new TReportExecStatusParams(FrontendServiceVersion.V1);
        TRuntimeProfileTree profileTree = profile.toThrift();
        params.setLoad_channel_profile(profileTree);

        return params;
    }
}
