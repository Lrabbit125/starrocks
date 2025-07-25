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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/backup/BackupJobTest.java

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

package com.starrocks.backup;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.analysis.TableName;
import com.starrocks.analysis.TableRef;
import com.starrocks.backup.BackupJob.BackupJobState;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.FsBroker;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.View;
import com.starrocks.common.Config;
import com.starrocks.common.FeConstants;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.common.util.UnitTestUtil;
import com.starrocks.common.util.concurrent.lock.LockManager;
import com.starrocks.metric.MetricRepo;
import com.starrocks.persist.EditLog;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.task.AgentBatchTask;
import com.starrocks.task.AgentTask;
import com.starrocks.task.AgentTaskExecutor;
import com.starrocks.task.AgentTaskQueue;
import com.starrocks.task.SnapshotTask;
import com.starrocks.task.UploadTask;
import com.starrocks.thrift.TBackend;
import com.starrocks.thrift.TFinishTaskRequest;
import com.starrocks.thrift.TStatus;
import com.starrocks.thrift.TStatusCode;
import com.starrocks.thrift.TTaskType;
import com.starrocks.transaction.GtidGenerator;
import mockit.Delegate;
import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class BackupJobTest {

    private BackupJob job;
    private BackupJob jobView;
    private Database db;

    private long dbId = 1;
    private long tblId = 2;
    private long partId = 3;
    private long idxId = 4;
    private long tabletId = 5;
    private long backendId = 10000;
    private long version = 6;
    private long viewId = 10;

    private long repoId = 20000;
    private AtomicLong id = new AtomicLong(50000);

    @Mocked
    private GlobalStateMgr globalStateMgr;

    private MockBackupHandler backupHandler;

    private MockRepositoryMgr repoMgr;

    // Thread is not mockable in Jmockit, use subclass instead
    private final class MockBackupHandler extends BackupHandler {
        public MockBackupHandler(GlobalStateMgr globalStateMgr) {
            super(globalStateMgr);
        }

        @Override
        public RepositoryMgr getRepoMgr() {
            return repoMgr;
        }
    }

    // Thread is not mockable in Jmockit, use subclass instead
    private final class MockRepositoryMgr extends RepositoryMgr {
        public MockRepositoryMgr() {
            super();
        }

        @Override
        public Repository getRepo(long repoId) {
            return repo;
        }
    }

    @Mocked
    private EditLog editLog;

    private Repository repo = new Repository(repoId, "repo", false, "my_repo",
                new BlobStorage("broker", Maps.newHashMap()));

    @BeforeAll
    public static void start() {
        Config.tmp_dir = "./";
        File backupDir = new File(BackupHandler.BACKUP_ROOT_DIR.toString());
        backupDir.mkdirs();

        MetricRepo.init();
    }

    @AfterAll
    public static void end() throws IOException {
        Config.tmp_dir = "./";
        File backupDir = new File(BackupHandler.BACKUP_ROOT_DIR.toString());
        if (backupDir.exists()) {
            Files.walk(BackupHandler.BACKUP_ROOT_DIR,
                                    FileVisitOption.FOLLOW_LINKS).sorted(Comparator.reverseOrder()).map(Path::toFile)
                        .forEach(File::delete);
        }
    }

    @BeforeEach
    public void setUp() {
        globalStateMgr = GlobalStateMgr.getCurrentState();
        repoMgr = new MockRepositoryMgr();
        backupHandler = new MockBackupHandler(globalStateMgr);

        // Thread is unmockable after Jmockit version 1.48, so use reflection to set field instead.
        Deencapsulation.setField(globalStateMgr, "backupHandler", backupHandler);

        db = UnitTestUtil.createDb(dbId, tblId, partId, idxId, tabletId, backendId, version, KeysType.AGG_KEYS);
        View view = UnitTestUtil.createTestView(viewId);
        db.registerTableUnlocked(view);

        LockManager lockManager = new LockManager();

        new Expectations(globalStateMgr) {
            {
                globalStateMgr.getLockManager();
                minTimes = 0;
                result = lockManager;

                globalStateMgr.getGtidGenerator();
                minTimes = 0;
                result = new GtidGenerator();

                globalStateMgr.getLocalMetastore().getDb(anyLong);
                minTimes = 0;
                result = db;



                globalStateMgr.getNextId();
                minTimes = 0;
                result = id.getAndIncrement();

                globalStateMgr.getEditLog();
                minTimes = 0;
                result = editLog;

                globalStateMgr.getLocalMetastore().getTable("testDb", "testTable");
                minTimes = 0;
                result = db.getTable(tblId);

                globalStateMgr.getLocalMetastore().getTable("testDb", UnitTestUtil.VIEW_NAME);
                minTimes = 0;
                result = db.getTable(viewId);

                globalStateMgr.getLocalMetastore().getTable("testDb", "unknown_tbl");
                minTimes = 0;
                result = null;
            }
        };

        new Expectations() {
            {
                editLog.logBackupJob((BackupJob) any);
                minTimes = 0;
                result = new Delegate() {
                    public void logBackupJob(BackupJob job) {
                        System.out.println("log backup job: " + job);
                    }
                };
            }
        };

        new MockUp<AgentTaskExecutor>() {
            @Mock
            public void submit(AgentBatchTask task) {

            }
        };

        new MockUp<Repository>() {
            @Mock
            Status upload(String localFilePath, String remoteFilePath) {
                return Status.OK;
            }

            @Mock
            Status getBrokerAddress(Long beId, GlobalStateMgr globalStateMgr, List<FsBroker> brokerAddrs) {
                brokerAddrs.add(new FsBroker());
                return Status.OK;
            }
        };

        List<TableRef> tableRefs = Lists.newArrayList();
        tableRefs.add(new TableRef(new TableName(UnitTestUtil.DB_NAME, UnitTestUtil.TABLE_NAME), null));
        job = new BackupJob("label", dbId, UnitTestUtil.DB_NAME, tableRefs, 13600 * 1000, globalStateMgr, repo.getId());

        List<TableRef> viewRefs = Lists.newArrayList();
        viewRefs.add(new TableRef(new TableName(UnitTestUtil.DB_NAME, UnitTestUtil.VIEW_NAME), null));
        jobView = new BackupJob("label-view", dbId, UnitTestUtil.DB_NAME, viewRefs, 13600 * 1000, globalStateMgr, repo.getId());
    }

    @Test
    public void testRunNormal() {
        // 1.pending
        Assertions.assertEquals(BackupJobState.PENDING, job.getState());
        job.run();
        Assertions.assertEquals(Status.OK, job.getStatus());
        Assertions.assertEquals(BackupJobState.SNAPSHOTING, job.getState());

        BackupMeta backupMeta = job.getBackupMeta();
        Assertions.assertEquals(1, backupMeta.getTables().size());
        OlapTable backupTbl = (OlapTable) backupMeta.getTable(UnitTestUtil.TABLE_NAME);
        List<String> partNames = Lists.newArrayList(backupTbl.getPartitionNames());
        Assertions.assertNotNull(backupTbl);
        Assertions.assertEquals(backupTbl.getSignature(BackupHandler.SIGNATURE_VERSION, partNames, true),
                ((OlapTable) db.getTable(tblId)).getSignature(BackupHandler.SIGNATURE_VERSION, partNames, true));
        Assertions.assertEquals(1, AgentTaskQueue.getTaskNum());
        AgentTask task = AgentTaskQueue.getTask(backendId, TTaskType.MAKE_SNAPSHOT, tabletId);
        Assertions.assertTrue(task instanceof SnapshotTask);
        SnapshotTask snapshotTask = (SnapshotTask) task;

        // 2. snapshoting
        job.run();
        Assertions.assertEquals(Status.OK, job.getStatus());
        Assertions.assertEquals(BackupJobState.SNAPSHOTING, job.getState());

        // 3. snapshot finished
        String snapshotPath = "/path/to/snapshot";
        List<String> snapshotFiles = Lists.newArrayList();
        snapshotFiles.add("1.dat");
        snapshotFiles.add("1.idx");
        snapshotFiles.add("1.hdr");
        TStatus taskStatus = new TStatus(TStatusCode.OK);
        TBackend tBackend = new TBackend("", 0, 1);
        TFinishTaskRequest request = new TFinishTaskRequest(tBackend, TTaskType.MAKE_SNAPSHOT,
                    snapshotTask.getSignature(), taskStatus);
        request.setSnapshot_files(snapshotFiles);
        request.setSnapshot_path(snapshotPath);
        Assertions.assertTrue(job.finishTabletSnapshotTask(snapshotTask, request));
        job.run();
        Assertions.assertEquals(Status.OK, job.getStatus());
        Assertions.assertEquals(BackupJobState.UPLOAD_SNAPSHOT, job.getState());

        // 4. upload snapshots
        AgentTaskQueue.clearAllTasks();
        job.run();
        Assertions.assertEquals(Status.OK, job.getStatus());
        Assertions.assertEquals(BackupJobState.UPLOADING, job.getState());
        Assertions.assertEquals(1, AgentTaskQueue.getTaskNum());
        task = AgentTaskQueue.getTask(backendId, TTaskType.UPLOAD, id.get() - 1);
        Assertions.assertTrue(task instanceof UploadTask);
        UploadTask upTask = (UploadTask) task;

        Assertions.assertEquals(job.getJobId(), upTask.getJobId());
        Map<String, String> srcToDest = upTask.getSrcToDestPath();
        Assertions.assertEquals(1, srcToDest.size());
        System.out.println(srcToDest);
        String dest = srcToDest.get(snapshotPath + "/" + tabletId + "/" + 0);
        Assertions.assertNotNull(dest);

        // 5. uploading
        job.run();
        Assertions.assertEquals(Status.OK, job.getStatus());
        Assertions.assertEquals(BackupJobState.UPLOADING, job.getState());
        Map<Long, List<String>> tabletFileMap = Maps.newHashMap();
        request = new TFinishTaskRequest(tBackend, TTaskType.UPLOAD,
                    upTask.getSignature(), taskStatus);
        request.setTablet_files(tabletFileMap);

        Assertions.assertFalse(job.finishSnapshotUploadTask(upTask, request));
        List<String> tabletFiles = Lists.newArrayList();
        tabletFileMap.put(tabletId, tabletFiles);
        Assertions.assertFalse(job.finishSnapshotUploadTask(upTask, request));
        tabletFiles.add("1.dat.4f158689243a3d6030352fec3cfd3798");
        tabletFiles.add("wrong_files.idx.4f158689243a3d6030352fec3cfd3798");
        tabletFiles.add("wrong_files.hdr.4f158689243a3d6030352fec3cfd3798");
        Assertions.assertFalse(job.finishSnapshotUploadTask(upTask, request));
        tabletFiles.clear();
        tabletFiles.add("1.dat.4f158689243a3d6030352fec3cfd3798");
        tabletFiles.add("1.idx.4f158689243a3d6030352fec3cfd3798");
        tabletFiles.add("1.hdr.4f158689243a3d6030352fec3cfd3798");
        Assertions.assertTrue(job.finishSnapshotUploadTask(upTask, request));
        job.run();
        Assertions.assertEquals(Status.OK, job.getStatus());
        Assertions.assertEquals(BackupJobState.SAVE_META, job.getState());

        // 6. save meta
        job.run();
        Assertions.assertEquals(Status.OK, job.getStatus());
        Assertions.assertEquals(BackupJobState.UPLOAD_INFO, job.getState());
        File metaInfo = new File(job.getLocalMetaInfoFilePath());
        Assertions.assertTrue(metaInfo.exists());
        File jobInfo = new File(job.getLocalJobInfoFilePath());
        Assertions.assertTrue(jobInfo.exists());

        BackupMeta restoreMetaInfo = null;
        BackupJobInfo restoreJobInfo = null;
        try {
            restoreMetaInfo = BackupMeta.fromFile(job.getLocalMetaInfoFilePath(), FeConstants.STARROCKS_META_VERSION);
            Assertions.assertEquals(1, restoreMetaInfo.getTables().size());
            OlapTable olapTable = (OlapTable) restoreMetaInfo.getTable(tblId);
            Assertions.assertNotNull(olapTable);
            Assertions.assertNotNull(restoreMetaInfo.getTable(UnitTestUtil.TABLE_NAME));
            List<String> names = Lists.newArrayList(olapTable.getPartitionNames());
            Assertions.assertEquals(((OlapTable) db.getTable(tblId)).getSignature(BackupHandler.SIGNATURE_VERSION, names, true),
                    olapTable.getSignature(BackupHandler.SIGNATURE_VERSION, names, true));

            restoreJobInfo = BackupJobInfo.fromFile(job.getLocalJobInfoFilePath());
            Assertions.assertEquals(UnitTestUtil.DB_NAME, restoreJobInfo.dbName);
            Assertions.assertEquals(job.getLabel(), restoreJobInfo.name);
            Assertions.assertEquals(1, restoreJobInfo.tables.size());
        } catch (IOException e) {
            e.printStackTrace();
            Assertions.fail();
        }

        Assertions.assertNull(job.getBackupMeta());
        Assertions.assertNull(job.getJobInfo());

        // 7. upload_info
        job.run();
        Assertions.assertEquals(Status.OK, job.getStatus());
        Assertions.assertEquals(BackupJobState.FINISHED, job.getState());

        try {
            // test get backup info
            job.getInfo();
        } catch (Exception ignore) {
        }
    }

    @Test
    public void testRunAbnormal() {
        // 1.pending
        AgentTaskQueue.clearAllTasks();

        List<TableRef> tableRefs = Lists.newArrayList();
        tableRefs.add(new TableRef(new TableName(UnitTestUtil.DB_NAME, "unknown_tbl"), null));
        job = new BackupJob("label", dbId, UnitTestUtil.DB_NAME, tableRefs, 13600 * 1000, globalStateMgr, repo.getId());
        job.run();
        Assertions.assertEquals(Status.ErrCode.NOT_FOUND, job.getStatus().getErrCode());
        Assertions.assertEquals(BackupJobState.CANCELLED, job.getState());
    }

    @Test
    public void testRunViewNormal() {
        // 1.pending
        Assertions.assertEquals(BackupJobState.PENDING, jobView.getState());
        jobView.run();
        Assertions.assertEquals(Status.OK, jobView.getStatus());
        Assertions.assertEquals(BackupJobState.SNAPSHOTING, jobView.getState());

        BackupMeta backupMeta = jobView.getBackupMeta();
        Assertions.assertEquals(1, backupMeta.getTables().size());
        View backupView = (View) backupMeta.getTable(UnitTestUtil.VIEW_NAME);
        Assertions.assertTrue(backupView != null);
        Assertions.assertTrue(backupView.getPartitions().isEmpty());

        // 2. snapshoting finished, not snapshot needed
        jobView.run();
        Assertions.assertEquals(Status.OK, jobView.getStatus());
        Assertions.assertEquals(BackupJobState.UPLOAD_SNAPSHOT, jobView.getState());

        // 3. upload snapshots
        jobView.run();
        Assertions.assertEquals(Status.OK, jobView.getStatus());
        Assertions.assertEquals(BackupJobState.UPLOADING, jobView.getState());

        // 4. uploading
        jobView.run();
        Assertions.assertEquals(Status.OK, jobView.getStatus());
        Assertions.assertEquals(BackupJobState.SAVE_META, jobView.getState());

        // 5. save meta
        jobView.run();
        Assertions.assertEquals(Status.OK, jobView.getStatus());
        Assertions.assertEquals(BackupJobState.UPLOAD_INFO, jobView.getState());
        File metaInfo = new File(jobView.getLocalMetaInfoFilePath());
        Assertions.assertTrue(metaInfo.exists());
        File jobInfo = new File(jobView.getLocalJobInfoFilePath());
        Assertions.assertTrue(jobInfo.exists());

        BackupMeta restoreMetaInfo = null;
        BackupJobInfo restoreJobInfo = null;
        try {
            restoreMetaInfo = BackupMeta.fromFile(jobView.getLocalMetaInfoFilePath(), FeConstants.STARROCKS_META_VERSION);
            Assertions.assertEquals(1, restoreMetaInfo.getTables().size());
            View view = (View) restoreMetaInfo.getTable(viewId);
            Assertions.assertNotNull(view);
            Assertions.assertNotNull(restoreMetaInfo.getTable(UnitTestUtil.VIEW_NAME));
        } catch (IOException e) {
            e.printStackTrace();
            Assertions.fail();
        }

        Assertions.assertNull(jobView.getBackupMeta());
        Assertions.assertNull(jobView.getJobInfo());

        // 6. upload_info
        jobView.run();
        Assertions.assertEquals(Status.OK, jobView.getStatus());
        Assertions.assertEquals(BackupJobState.FINISHED, jobView.getState());

        try {
            // test get backup info
            jobView.getInfo();
        } catch (Exception ignore) {
        }
    }
}
