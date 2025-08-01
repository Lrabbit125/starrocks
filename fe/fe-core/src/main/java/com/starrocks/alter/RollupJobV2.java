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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/alter/RollupJobV2.java

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

package com.starrocks.alter;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.annotations.SerializedName;
import com.starrocks.analysis.CastExpr;
import com.starrocks.analysis.DescriptorTable;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.ExprSubstitutionMap;
import com.starrocks.analysis.SlotDescriptor;
import com.starrocks.analysis.SlotRef;
import com.starrocks.analysis.TableName;
import com.starrocks.analysis.TupleDescriptor;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.ColumnId;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.LocalTablet;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.MaterializedIndex.IndexState;
import com.starrocks.catalog.MaterializedIndexMeta;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.OlapTable.OlapTableState;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PhysicalPartition;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.Replica;
import com.starrocks.catalog.Replica.ReplicaState;
import com.starrocks.catalog.ScalarType;
import com.starrocks.catalog.SchemaInfo;
import com.starrocks.catalog.Tablet;
import com.starrocks.catalog.TabletInvertedIndex;
import com.starrocks.catalog.TabletMeta;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Config;
import com.starrocks.common.FeConstants;
import com.starrocks.common.Status;
import com.starrocks.common.io.Text;
import com.starrocks.common.util.TimeUtils;
import com.starrocks.common.util.concurrent.MarkedCountDownLatch;
import com.starrocks.common.util.concurrent.lock.AutoCloseableLock;
import com.starrocks.common.util.concurrent.lock.LockType;
import com.starrocks.common.util.concurrent.lock.Locker;
import com.starrocks.persist.gson.GsonPostProcessable;
import com.starrocks.persist.gson.GsonUtils;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.OriginStatement;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.SelectAnalyzer;
import com.starrocks.sql.ast.CreateMaterializedViewStmt;
import com.starrocks.sql.common.MetaUtils;
import com.starrocks.sql.optimizer.rule.mv.MVUtils;
import com.starrocks.task.AgentBatchTask;
import com.starrocks.task.AgentTask;
import com.starrocks.task.AgentTaskExecutor;
import com.starrocks.task.AgentTaskQueue;
import com.starrocks.task.AlterReplicaTask;
import com.starrocks.task.CreateReplicaTask;
import com.starrocks.thrift.TColumn;
import com.starrocks.thrift.TDescriptorTable;
import com.starrocks.thrift.TStatusCode;
import com.starrocks.thrift.TStorageMedium;
import com.starrocks.thrift.TStorageType;
import com.starrocks.thrift.TTabletSchema;
import com.starrocks.thrift.TTabletType;
import com.starrocks.thrift.TTaskType;
import io.opentelemetry.api.trace.StatusCode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Version 2 of RollupJob.
 * This is for replacing the old RollupJob
 * https://github.com/apache/incubator-doris/issues/1429
 */
public class RollupJobV2 extends AlterJobV2 implements GsonPostProcessable {
    private static final Logger LOG = LogManager.getLogger(RollupJobV2.class);

    // physical partition id -> (rollup tablet id -> base tablet id)
    @SerializedName(value = "partitionIdToBaseRollupTabletIdMap")
    private Map<Long, Map<Long, Long>> physicalPartitionIdToBaseRollupTabletIdMap = Maps.newHashMap();
    @SerializedName(value = "partitionIdToRollupIndex")
    private Map<Long, MaterializedIndex> physicalPartitionIdToRollupIndex = Maps.newHashMap();

    // rollup and base schema info
    @SerializedName(value = "baseIndexId")
    private long baseIndexId;
    @SerializedName(value = "rollupIndexId")
    private long rollupIndexId;
    @SerializedName(value = "baseIndexName")
    private String baseIndexName;
    @SerializedName(value = "rollupIndexName")
    private String rollupIndexName;

    @SerializedName(value = "rollupSchema")
    private List<Column> rollupSchema = Lists.newArrayList();
    @SerializedName(value = "rollupSchemaVersion")
    private int rollupSchemaVersion;
    @SerializedName(value = "baseSchemaHash")
    private int baseSchemaHash;
    @SerializedName(value = "rollupSchemaHash")
    private int rollupSchemaHash;

    @SerializedName(value = "rollupKeysType")
    private KeysType rollupKeysType;
    @SerializedName(value = "rollupShortKeyColumnCount")
    private short rollupShortKeyColumnCount;
    @SerializedName(value = "origStmt")
    private OriginStatement origStmt;

    // The rollup job will wait all transactions before this txn id finished, then send the rollup tasks.
    @SerializedName(value = "watershedTxnId")
    protected long watershedTxnId = -1;
    @SerializedName(value = "viewDefineSql")
    private String viewDefineSql;
    @SerializedName(value = "isColocateMVIndex")
    protected boolean isColocateMVIndex = false;

    private Expr whereClause;

    // save all create rollup tasks
    private AgentBatchTask rollupBatchTask = new AgentBatchTask();

    // runtime variable for synchronization between cancel and runPendingJob
    private MarkedCountDownLatch<Long, Long> createReplicaLatch = null;
    private AtomicBoolean waitingCreatingReplica = new AtomicBoolean(false);
    private AtomicBoolean isCancelling = new AtomicBoolean(false);

    // for deserialization
    public RollupJobV2() {
        super(JobType.ROLLUP);
    }

    public RollupJobV2(long jobId, long dbId, long tableId, String tableName, long timeoutMs,
                       long baseIndexId, long rollupIndexId, String baseIndexName, String rollupIndexName,
                       int rollupSchemaVersion, List<Column> rollupSchema, Expr whereClause, int baseSchemaHash,
                       int rollupSchemaHash, KeysType rollupKeysType, short rollupShortKeyColumnCount,
                       OriginStatement origStmt, String viewDefineSql, boolean isColocateMVIndex) {
        super(jobId, JobType.ROLLUP, dbId, tableId, tableName, timeoutMs);

        this.baseIndexId = baseIndexId;
        this.rollupIndexId = rollupIndexId;
        this.baseIndexName = baseIndexName;
        this.rollupIndexName = rollupIndexName;
        this.rollupSchemaVersion = rollupSchemaVersion;

        this.rollupSchema = rollupSchema;
        this.baseSchemaHash = baseSchemaHash;
        this.rollupSchemaHash = rollupSchemaHash;
        this.rollupKeysType = rollupKeysType;
        this.rollupShortKeyColumnCount = rollupShortKeyColumnCount;

        this.origStmt = origStmt;
        this.viewDefineSql = viewDefineSql;
        this.isColocateMVIndex = isColocateMVIndex;
        this.whereClause = whereClause;
    }

    @Override
    public void addTabletIdMap(long partitionId, long rollupTabletId, long baseTabletId) {
        Map<Long, Long> tabletIdMap =
                    physicalPartitionIdToBaseRollupTabletIdMap.computeIfAbsent(partitionId, k -> Maps.newHashMap());
        tabletIdMap.put(rollupTabletId, baseTabletId);
    }

    @Override
    public void addMVIndex(long partitionId, MaterializedIndex mvIndex) {
        this.physicalPartitionIdToRollupIndex.put(partitionId, mvIndex);
    }

    public String getRollupIndexName() {
        return rollupIndexName;
    }

    @VisibleForTesting
    public void setIsCancelling(boolean isCancelling) {
        this.isCancelling.set(isCancelling);
    }

    @VisibleForTesting
    public boolean isCancelling() {
        return this.isCancelling.get();
    }

    @VisibleForTesting
    public void setWaitingCreatingReplica(boolean waitingCreatingReplica) {
        this.waitingCreatingReplica.set(waitingCreatingReplica);
    }

    @VisibleForTesting
    public boolean waitingCreatingReplica() {
        return this.waitingCreatingReplica.get();
    }

    /**
     * runPendingJob():
     * 1. Create all rollup replicas and wait them finished.
     * 2. After creating done, add this shadow rollup index to globalStateMgr, user can not see this
     * rollup, but internal load process will generate data for this rollup index.
     * 3. Get a new transaction id, then set job's state to WAITING_TXN
     */
    @Override
    protected void runPendingJob() throws AlterCancelException {
        Preconditions.checkState(jobState == JobState.PENDING, jobState);

        LOG.info("begin to send create rollup replica tasks. job: {}", jobId);
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbId);
        if (db == null) {
            throw new AlterCancelException("Database " + dbId + " does not exist");
        }

        if (!checkTableStable(db)) {
            return;
        }

        // 1. create rollup replicas
        AgentBatchTask batchTask = new AgentBatchTask();
        // count total replica num
        int totalReplicaNum = 0;
        for (MaterializedIndex rollupIdx : physicalPartitionIdToRollupIndex.values()) {
            for (Tablet tablet : rollupIdx.getTablets()) {
                totalReplicaNum += ((LocalTablet) tablet).getImmutableReplicas().size();
            }
        }
        MarkedCountDownLatch<Long, Long> countDownLatch = new MarkedCountDownLatch<Long, Long>(totalReplicaNum);
        createReplicaLatch = countDownLatch;
        OlapTable tbl = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getId(), tableId);
        if (tbl == null) {
            throw new AlterCancelException("Table " + tableId + " does not exist");
        }

        try (AutoCloseableLock ignore =
                    new AutoCloseableLock(new Locker(), db.getId(), Lists.newArrayList(tbl.getId()), LockType.READ)) {
            MaterializedIndexMeta index = tbl.getIndexMetaByIndexId(tbl.getBaseIndexId());
            Preconditions.checkState(tbl.getState() == OlapTableState.ROLLUP);
            for (Map.Entry<Long, MaterializedIndex> entry : this.physicalPartitionIdToRollupIndex.entrySet()) {
                long partitionId = entry.getKey();
                PhysicalPartition partition = tbl.getPhysicalPartition(partitionId);
                if (partition == null) {
                    continue;
                }
                TStorageMedium storageMedium = tbl.getPartitionInfo()
                            .getDataProperty(partition.getParentId()).getStorageMedium();
                TTabletType tabletType = tbl.getPartitionInfo().getTabletType(partition.getParentId());
                MaterializedIndex rollupIndex = entry.getValue();

                TTabletSchema tabletSchema = SchemaInfo.newBuilder()
                            .setId(rollupIndexId) // For newly created materialized, schema id equals to index id
                            .setVersion(rollupSchemaVersion)
                            .setKeysType(rollupKeysType)
                            .setShortKeyColumnCount(rollupShortKeyColumnCount)
                            .setSchemaHash(rollupSchemaHash)
                            .setStorageType(TStorageType.COLUMN)
                            .setBloomFilterColumnNames(tbl.getBfColumnIds())
                            .setBloomFilterFpp(tbl.getBfFpp())
                            .setIndexes(OlapTable.getIndexesBySchema(tbl.getCopiedIndexes(), rollupSchema))
                            .setSortKeyIndexes(null) // Rollup tablets does not have sort key
                            .setSortKeyUniqueIds(null)
                            .addColumns(rollupSchema)
                            .build().toTabletSchema();

                Map<Long, Long> tabletIdMap = this.physicalPartitionIdToBaseRollupTabletIdMap.get(partitionId);
                for (Tablet rollupTablet : rollupIndex.getTablets()) {
                    long rollupTabletId = rollupTablet.getId();
                    List<Replica> rollupReplicas = ((LocalTablet) rollupTablet).getImmutableReplicas();
                    for (Replica rollupReplica : rollupReplicas) {
                        long backendId = rollupReplica.getBackendId();
                        Preconditions.checkNotNull(tabletIdMap.get(rollupTabletId)); // baseTabletId
                        countDownLatch.addMark(backendId, rollupTabletId);
                        CreateReplicaTask task = CreateReplicaTask.newBuilder()
                                    .setNodeId(backendId)
                                    .setDbId(dbId)
                                    .setTableId(tableId)
                                    .setPartitionId(partitionId)
                                    .setIndexId(rollupIndexId)
                                    .setTabletId(rollupTabletId)
                                    .setVersion(Partition.PARTITION_INIT_VERSION)
                                    .setStorageMedium(storageMedium)
                                    .setLatch(countDownLatch)
                                    .setEnablePersistentIndex(tbl.enablePersistentIndex())
                                    .setPrimaryIndexCacheExpireSec(tbl.primaryIndexCacheExpireSec())
                                    .setTabletType(tabletType)
                                    .setCompressionType(tbl.getCompressionType())
                                    .setCompressionLevel(tbl.getCompressionLevel())
                                    .setCreateSchemaFile(true)
                                    .setBaseTabletId(tabletIdMap.get(rollupTabletId))
                                    .setTabletSchema(tabletSchema)
                                    .build();
                        batchTask.addTask(task);
                    } // end for rollupReplicas
                } // end for rollupTablets
            }
        }

        if (!FeConstants.runningUnitTest) {
            // send all tasks and wait them finished
            AgentTaskQueue.addBatchTask(batchTask);
            AgentTaskExecutor.submit(batchTask);
            long timeout = Math.min(Config.tablet_create_timeout_second * 1000L * totalReplicaNum,
                        Config.max_create_table_timeout_second * 1000L);
            boolean ok = false;
            try {
                waitingCreatingReplica.set(true);
                if (isCancelling.get()) {
                    AgentTaskQueue.removeBatchTask(batchTask, TTaskType.CREATE);
                    return;
                }
                ok = countDownLatch.await(timeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                LOG.warn("InterruptedException: ", e);
                ok = false;
            } finally {
                waitingCreatingReplica.set(false);
            }

            if (!ok || !countDownLatch.getStatus().ok()) {
                // create rollup replicas failed. just cancel the job
                // clear tasks and show the failed replicas to user
                AgentTaskQueue.removeBatchTask(batchTask, TTaskType.CREATE);
                String errMsg = null;
                if (!countDownLatch.getStatus().ok()) {
                    errMsg = countDownLatch.getStatus().getErrorMsg();
                } else {
                    List<Entry<Long, Long>> unfinishedMarks = countDownLatch.getLeftMarks();
                    // only show at most 3 results
                    List<Entry<Long, Long>> subList = unfinishedMarks.subList(0, Math.min(unfinishedMarks.size(), 3));
                    errMsg = "Error replicas:" + Joiner.on(", ").join(subList);
                }
                LOG.warn("failed to create rollup replicas for job: {}, {}", jobId, errMsg);
                throw new AlterCancelException("Create rollup replicas failed. Error: " + errMsg);
            }
        }

        // create all rollup replicas success.
        // add rollup index to globalStateMgr
        tbl = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getId(), tableId);
        if (tbl == null) {
            throw new AlterCancelException("Table " + tableId + " does not exist");
        }
        Preconditions.checkState(tbl.getState() == OlapTableState.ROLLUP);
        try (AutoCloseableLock ignore =
                    new AutoCloseableLock(new Locker(), db.getId(), Lists.newArrayList(tbl.getId()), LockType.WRITE)) {
            addRollupIndexToCatalog(tbl);
        }

        this.watershedTxnId =
                    GlobalStateMgr.getCurrentState().getGlobalTransactionMgr().getTransactionIDGenerator().getNextTransactionId();
        this.jobState = JobState.WAITING_TXN;
        span.setAttribute("watershedTxnId", this.watershedTxnId);
        span.addEvent("setWaitingTxn");

        // write edit log
        GlobalStateMgr.getCurrentState().getEditLog().logAlterJob(this);
        LOG.info("transfer rollup job {} state to {}, watershed txn_id: {}", jobId, this.jobState, watershedTxnId);
    }

    private void addRollupIndexToCatalog(OlapTable tbl) {
        for (Partition partition : tbl.getPartitions()) {
            for (PhysicalPartition physicalPartition : partition.getSubPartitions()) {
                long partitionId = physicalPartition.getId();
                MaterializedIndex rollupIndex = this.physicalPartitionIdToRollupIndex.get(partitionId);
                Preconditions.checkNotNull(rollupIndex);
                Preconditions.checkState(rollupIndex.getState() == IndexState.SHADOW, rollupIndex.getState());
                physicalPartition.createRollupIndex(rollupIndex);
            }
        }

        tbl.setIndexMeta(rollupIndexId, rollupIndexName, rollupSchema, rollupSchemaVersion /* initial schema version */,
                    rollupSchemaHash, rollupShortKeyColumnCount, TStorageType.COLUMN, rollupKeysType, origStmt);
        MaterializedIndexMeta indexMeta = tbl.getIndexMetaByIndexId(rollupIndexId);
        Preconditions.checkNotNull(indexMeta);
        indexMeta.setDbId(dbId);
        indexMeta.setViewDefineSql(viewDefineSql);
        indexMeta.setColocateMVIndex(isColocateMVIndex);
        indexMeta.setWhereClause(whereClause);
        tbl.rebuildFullSchema();
    }

    public static Expr analyzeExpr(SelectAnalyzer.RewriteAliasVisitor visitor,
                             Type type, String name, Expr defineExpr,
                             Map<String, SlotDescriptor> slotDescByName) throws AlterCancelException {
        List<SlotRef> slots = new ArrayList<>();
        defineExpr.collect(SlotRef.class, slots);
        for (SlotRef slot : slots) {
            SlotDescriptor slotDesc = slotDescByName.get(slot.getColumnName());
            if (slotDesc == null) {
                slotDesc = slotDescByName.get(name);
            }
            if (slotDesc == null) {
                throw new AlterCancelException("slotDesc is null, slot = " + slot.getColumnName()
                            + ", column = " + name);
            }
            slot.setDesc(slotDesc);
        }

        ExprSubstitutionMap smap = new ExprSubstitutionMap();
        for (SlotRef slot : slots) {
            SlotDescriptor slotDesc = slotDescByName.get(slot.getColumnName());
            Preconditions.checkNotNull(slotDesc);
            smap.getLhs().add(slot);
            SlotRef slotRef = new SlotRef(slotDesc);
            slotRef.setColumnName(slot.getColumnName());
            smap.getRhs().add(slotRef);
        }
        Expr newExpr = defineExpr.clone(smap);
        newExpr = newExpr.accept(visitor, null);
        newExpr = Expr.analyzeAndCastFold(newExpr);
        Type newType = newExpr.getType();
        if (!type.isFullyCompatible(newType)) {
            newExpr = new CastExpr(type, newExpr);
        }
        return newExpr;
    }

    // DB lock should be hold already
    public static AlterReplicaTask.RollupJobV2Params analyzeAndCreateRollupJobV2Params(OlapTable tbl,
                                                                                       List<Column> rollupSchema,
                                                                                       Expr whereClause,
                                                                                       String dbFullName)
            throws AlterCancelException {
        DescriptorTable descTable = new DescriptorTable();
        TupleDescriptor tupleDesc = descTable.createTupleDescriptor();
        Map<String, SlotDescriptor> slotDescByName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<Column> rollupColumns = new ArrayList<Column>();
        Set<String> columnNames = new HashSet<String>();
        Set<String> baseTableColumnNames = Sets.newHashSet();
        for (Column column : tbl.getBaseSchema()) {
            rollupColumns.add(column);
            columnNames.add(column.getName());
            baseTableColumnNames.add(column.getName());
        }
        Set<String> usedBaseTableColNames = Sets.newLinkedHashSet();
        for (Column column : rollupSchema) {
            if (!columnNames.contains(column.getName())) {
                rollupColumns.add(column);
            } else {
                usedBaseTableColNames.add(column.getName());
            }
        }

        /*
         * The expression substitution is needed here, because all slotRefs in
         * definedExpr are still is unAnalyzed. slotRefs get isAnalyzed == true
         * if it is init by SlotDescriptor. The slot information will be used by be to identify
         * the column location in a chunk.
         */
        for (Column column : tbl.getBaseSchema()) {
            SlotDescriptor destSlotDesc = descTable.addSlotDescriptor(tupleDesc);
            destSlotDesc.setIsMaterialized(true);
            destSlotDesc.setColumn(column);
            destSlotDesc.setIsNullable(column.isAllowNull());

            slotDescByName.put(column.getName(), destSlotDesc);
        }

        List<Expr> outputExprs = Lists.newArrayList();
        for (Column col : tbl.getBaseSchema()) {
            SlotDescriptor slotDesc = slotDescByName.get(col.getName());
            if (slotDesc == null) {
                throw new AlterCancelException("Expression for materialized view column can not find " +
                        "the ref column");
            }
            SlotRef slotRef = new SlotRef(slotDesc);
            slotRef.setColumnName(col.getName());
            outputExprs.add(slotRef);
        }

        TableName tableName = new TableName(dbFullName, tbl.getName());
        Map<String, Expr> defineExprs = Maps.newHashMap();

        // sourceScope must be set null tableName for its Field in RelationFields
        // because we hope slotRef can not be resolved in sourceScope but can be
        // resolved in outputScope to force to replace the node using outputExprs.
        SelectAnalyzer.RewriteAliasVisitor visitor = MVUtils.buildRewriteAliasVisitor(ConnectContext.buildInner(),
                tbl, tableName, outputExprs);
        for (Column column : rollupColumns) {
            if (column.getDefineExpr() == null) {
                continue;
            }

            Expr definedExpr = analyzeExpr(visitor, column.getType(), column.getName(),
                    column.getDefineExpr(), slotDescByName);

            defineExprs.put(column.getName(), definedExpr);

            List<SlotRef> slots = Lists.newArrayList();
            definedExpr.collect(SlotRef.class, slots);
            slots.stream().map(slot -> slot.getColumnName()).forEach(usedBaseTableColNames::add);
        }

        Expr whereExpr = null;
        if (whereClause != null) {
            Type type = ScalarType.createType(PrimitiveType.BOOLEAN);
            whereExpr = analyzeExpr(visitor, type, CreateMaterializedViewStmt.WHERE_PREDICATE_COLUMN_NAME,
                    whereClause, slotDescByName);
            List<SlotRef> slots = Lists.newArrayList();
            whereExpr.collect(SlotRef.class, slots);
            slots.stream().map(slot -> slot.getColumnName()).forEach(usedBaseTableColNames::add);
        }

        for (String refColName : usedBaseTableColNames) {
            if (!baseTableColumnNames.contains(refColName)) {
                throw new AlterCancelException("Materialized view's ref column " + refColName + " is not " +
                        "found in the base table.");
            }
        }

        // make sure the key columns are in the front of the list which is the limitation of the be `ChunkAggregator`
        List<ColumnId> usedColIds = Lists.newArrayList();
        List<ColumnId> nonKeyColIds = Lists.newArrayList();
        for (String name : usedBaseTableColNames) {
            Column col = tbl.getColumn(name);
            if (col.isKey()) {
                usedColIds.add(col.getColumnId());
            } else {
                nonKeyColIds.add(col.getColumnId());
            }
        }
        usedColIds.addAll(nonKeyColIds);
        return new AlterReplicaTask.RollupJobV2Params(defineExprs, whereExpr, descTable.toThrift(), usedColIds);
    }

    /**
     * runWaitingTxnJob():
     * 1. Wait the transactions before the watershedTxnId to be finished.
     * 2. If all previous transactions finished, send create rollup tasks to BE.
     * 3. Change job state to RUNNING.
     */
    @Override
    protected void runWaitingTxnJob() throws AlterCancelException {
        Preconditions.checkState(jobState == JobState.WAITING_TXN, jobState);

        try {
            if (!isPreviousLoadFinished()) {
                LOG.info("wait transactions before {} to be finished, rollup job: {}", watershedTxnId, jobId);
                return;
            }
        } catch (AnalysisException e) {
            throw new AlterCancelException(e.getMessage());
        }

        LOG.info("previous transactions are all finished, begin to send rollup tasks. job: {}", jobId);
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbId);
        if (db == null) {
            throw new AlterCancelException("Databasee " + dbId + " does not exist");
        }
        OlapTable tbl = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getId(), tableId);
        if (tbl == null) {
            throw new AlterCancelException("Table " + tableId + " does not exist");
        }

        Map<Long, List<TColumn>> indexToThriftColumns = new HashMap<>();
        Optional<TDescriptorTable> tDescTable = Optional.empty();
        try (AutoCloseableLock ignore =
                    new AutoCloseableLock(new Locker(), db.getId(), Lists.newArrayList(tbl.getId()), LockType.READ)) {
            Preconditions.checkState(tbl.getState() == OlapTableState.ROLLUP);
            // only needs to analyze once
            AlterReplicaTask.RollupJobV2Params rollupJobV2Params =
                    analyzeAndCreateRollupJobV2Params(tbl, rollupSchema, whereClause, db.getFullName());
            for (Map.Entry<Long, MaterializedIndex> entry : this.physicalPartitionIdToRollupIndex.entrySet()) {
                long partitionId = entry.getKey();
                PhysicalPartition partition = tbl.getPhysicalPartition(partitionId);
                Preconditions.checkNotNull(partition, partitionId);

                // the rollup task will transform the data before visible version(included).
                long visibleVersion = partition.getVisibleVersion();

                MaterializedIndex rollupIndex = entry.getValue();
                Map<Long, Long> tabletIdMap = this.physicalPartitionIdToBaseRollupTabletIdMap.get(partitionId);
                for (Tablet rollupTablet : rollupIndex.getTablets()) {
                    long rollupTabletId = rollupTablet.getId();
                    long baseTabletId = tabletIdMap.get(rollupTabletId);

                    List<Replica> rollupReplicas = ((LocalTablet) rollupTablet).getImmutableReplicas();
                    TabletInvertedIndex invertedIndex = GlobalStateMgr.getCurrentState().getTabletInvertedIndex();
                    long baseIndexId = invertedIndex.getTabletMeta(baseTabletId).getIndexId();
                    List<TColumn> baseTColumn = indexToThriftColumns.get(baseIndexId);
                    if (baseTColumn == null) {
                        baseTColumn = tbl.getIndexMetaByIndexId(baseIndexId).getSchema()
                                .stream()
                                .map(Column::toThrift)
                                .collect(Collectors.toList());
                        indexToThriftColumns.put(baseIndexId, baseTColumn);
                    }

                    for (Replica rollupReplica : rollupReplicas) {
                        AlterReplicaTask rollupTask = AlterReplicaTask.rollupLocalTablet(
                                    rollupReplica.getBackendId(), dbId, tableId, partitionId, rollupIndexId, rollupTabletId,
                                    baseTabletId, rollupReplica.getId(), rollupSchemaHash, baseSchemaHash, visibleVersion, jobId,
                                    rollupJobV2Params, baseTColumn);
                        rollupBatchTask.addTask(rollupTask);
                    }
                }
            }
        }

        AgentTaskQueue.addBatchTask(rollupBatchTask);
        AgentTaskExecutor.submit(rollupBatchTask);
        this.jobState = JobState.RUNNING;
        span.addEvent("setRunning");

        // DO NOT write edit log here, tasks will be send again if FE restart or master changed.
        LOG.info("transfer rollup job {} state to {}", jobId, this.jobState);
    }

    /**
     * runRunningJob()
     * 1. Wait all create rollup tasks to be finished.
     * 2. Check the integrity of the newly created rollup index.
     * 3. Set rollup index's state to NORMAL to let it visible to query.
     * 4. Set job'state as FINISHED.
     */
    @Override
    protected void runRunningJob() throws AlterCancelException {
        Preconditions.checkState(jobState == JobState.RUNNING, jobState);

        // must check if db or table still exist first.
        // or if table is dropped, the tasks will never be finished,
        // and the job will be in RUNNING state forever.
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbId);
        if (db == null) {
            throw new AlterCancelException("Databasee " + dbId + " does not exist");
        }

        OlapTable tbl = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getId(), tableId);
        if (tbl == null) {
            throw new AlterCancelException("Table " + tableId + " does not exist");
        }

        if (!rollupBatchTask.isFinished()) {
            LOG.info("rollup tasks not finished. job: {}", jobId);
            List<AgentTask> tasks = rollupBatchTask.getUnfinishedTasks(2000);
            for (AgentTask task : tasks) {
                if (task.isFailed() || task.getFailedTimes() >= 3) {
                    throw new AlterCancelException("rollup task failed after try three times: " + task.getErrorMsg());
                }
            }
            return;
        }

        /*
         * all tasks are finished. check the integrity.
         * we just check whether all rollup replicas are healthy.
         */
        try (AutoCloseableLock ignore =
                    new AutoCloseableLock(new Locker(), db.getId(), Lists.newArrayList(tbl.getId()), LockType.WRITE)) {
            Preconditions.checkState(tbl.getState() == OlapTableState.ROLLUP);
            for (Map.Entry<Long, MaterializedIndex> entry : this.physicalPartitionIdToRollupIndex.entrySet()) {
                long partitionId = entry.getKey();
                PhysicalPartition partition = tbl.getPhysicalPartition(partitionId);
                if (partition == null) {
                    continue;
                }

                long visiableVersion = partition.getVisibleVersion();
                short expectReplicationNum = tbl.getPartitionInfo().getReplicationNum(partition.getParentId());

                MaterializedIndex rollupIndex = entry.getValue();
                for (Tablet rollupTablet : rollupIndex.getTablets()) {
                    List<Replica> replicas = ((LocalTablet) rollupTablet).getImmutableReplicas();
                    int healthyReplicaNum = 0;
                    for (Replica replica : replicas) {
                        if (replica.getLastFailedVersion() < 0
                                    && replica.checkVersionCatchUp(visiableVersion, false)) {
                            healthyReplicaNum++;
                        }
                    }

                    if (healthyReplicaNum < expectReplicationNum / 2 + 1) {
                        LOG.warn("rollup tablet {} has few healthy replicas: {}, rollup job: {}",
                                    rollupTablet.getId(), replicas, jobId);
                        throw new AlterCancelException(
                                    "rollup tablet " + rollupTablet.getId() + " has few healthy replicas");
                    }
                } // end for tablets
            } // end for partitions

            onFinished(tbl);
        }

        this.jobState = JobState.FINISHED;
        this.finishedTimeMs = System.currentTimeMillis();

        GlobalStateMgr.getCurrentState().getEditLog().logAlterJob(this);
        LOG.info("rollup job finished: {}", jobId);
        this.span.end();
    }

    @Override
    protected void runFinishedRewritingJob() throws AlterCancelException {
        // nothing to do
    }

    private void onFinished(OlapTable tbl) {
        for (Partition partition : tbl.getPartitions()) {
            for (PhysicalPartition physicalPartition : partition.getSubPartitions()) {
                MaterializedIndex rollupIndex = physicalPartition.getIndex(rollupIndexId);
                Preconditions.checkNotNull(rollupIndex, rollupIndexId);
                for (Tablet tablet : rollupIndex.getTablets()) {
                    for (Replica replica : ((LocalTablet) tablet).getImmutableReplicas()) {
                        replica.setState(ReplicaState.NORMAL);
                    }
                }
                physicalPartition.visualiseShadowIndex(rollupIndexId, false);
            }
        }
        tbl.rebuildFullSchema();
        tbl.lastSchemaUpdateTime.set(System.nanoTime());
    }

    @Override
    public final boolean cancel(String errMsg) {
        isCancelling.set(true);
        try {
            // If waitingCreatingReplica == false, we will assume that
            // cancel thread will get the object lock very quickly.
            if (waitingCreatingReplica.get()) {
                Preconditions.checkState(createReplicaLatch != null);
                createReplicaLatch.countDownToZero(new Status(TStatusCode.OK, ""));
            }
            synchronized (this) {
                return cancelInternal(errMsg);
            }
        } finally {
            isCancelling.set(false);
        }
    }

    /**
     * cancelImpl() can be called any time any place.
     * We need to clean any possible residual of this job.
     */
    @Override
    protected boolean cancelImpl(String errMsg) {
        if (jobState.isFinalState()) {
            return false;
        }
        cancelInternal();

        jobState = JobState.CANCELLED;
        this.errMsg = errMsg;
        this.finishedTimeMs = System.currentTimeMillis();
        LOG.info("cancel {} job {}, err: {}", this.type, jobId, errMsg);
        GlobalStateMgr.getCurrentState().getEditLog().logAlterJob(this);
        span.setStatus(StatusCode.ERROR, errMsg);
        span.end();
        return true;
    }

    private void cancelInternal() {
        // clear tasks if has
        AgentTaskQueue.removeBatchTask(rollupBatchTask, TTaskType.ALTER);
        // remove all rollup indexes, and set state to NORMAL
        TabletInvertedIndex invertedIndex = GlobalStateMgr.getCurrentState().getTabletInvertedIndex();
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbId);
        if (db != null) {
            OlapTable tbl = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getId(), tableId);
            if (tbl != null) {
                try (AutoCloseableLock ignore =
                            new AutoCloseableLock(new Locker(), db.getId(), Lists.newArrayList(tbl.getId()), LockType.WRITE)) {
                    for (Long partitionId : physicalPartitionIdToRollupIndex.keySet()) {
                        MaterializedIndex rollupIndex = physicalPartitionIdToRollupIndex.get(partitionId);
                        for (Tablet rollupTablet : rollupIndex.getTablets()) {
                            invertedIndex.deleteTablet(rollupTablet.getId());
                        }
                        PhysicalPartition partition = tbl.getPhysicalPartition(partitionId);
                        partition.deleteRollupIndex(rollupIndexId);
                    }
                    tbl.deleteIndexInfo(rollupIndexName);
                }
            }
        }
    }

    // Check whether transactions of the given database which txnId is less than 'watershedTxnId' are finished.
    protected boolean isPreviousLoadFinished() throws AnalysisException {
        return GlobalStateMgr.getCurrentState().getGlobalTransactionMgr()
                    .isPreviousTransactionsFinished(watershedTxnId, dbId, Lists.newArrayList(tableId));
    }

    /**
     * Replay job in PENDING state.
     * Should replay all changes before this job's state transfer to PENDING.
     * These changes should be same as changes in RollupHander.processAddRollup()
     */
    private void replayPending(RollupJobV2 replayedJob) {
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbId);
        if (db == null) {
            // database may be dropped before replaying this log. just return
            return;
        }

        OlapTable tbl = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getId(), tableId);
        if (tbl == null) {
            // table may be dropped before replaying this log. just return
            return;
        }

        try (AutoCloseableLock ignore = new AutoCloseableLock(new Locker(), db.getId(), Lists.newArrayList(tbl.getId()),
                    LockType.WRITE)) {
            addTabletToInvertedIndex(tbl);
        }

        // to make sure that this job will run runPendingJob() again to create the rollup replicas
        this.jobState = JobState.PENDING;
        this.watershedTxnId = replayedJob.watershedTxnId;

        LOG.info("replay pending rollup job: {}", jobId);
    }

    private void addTabletToInvertedIndex(OlapTable tbl) {
        TabletInvertedIndex invertedIndex = GlobalStateMgr.getCurrentState().getTabletInvertedIndex();
        // add all rollup replicas to tablet inverted index
        for (Long partitionId : physicalPartitionIdToRollupIndex.keySet()) {
            MaterializedIndex rollupIndex = physicalPartitionIdToRollupIndex.get(partitionId);
            PhysicalPartition physicalPartition = tbl.getPhysicalPartition(partitionId);
            TStorageMedium medium = tbl.getPartitionInfo().getDataProperty(physicalPartition.getParentId()).getStorageMedium();
            TabletMeta rollupTabletMeta = new TabletMeta(dbId, tableId, partitionId, rollupIndexId,
                    medium);

            for (Tablet rollupTablet : rollupIndex.getTablets()) {
                invertedIndex.addTablet(rollupTablet.getId(), rollupTabletMeta);
                for (Replica rollupReplica : ((LocalTablet) rollupTablet).getImmutableReplicas()) {
                    invertedIndex.addReplica(rollupTablet.getId(), rollupReplica);
                }
            }
        }
    }

    /**
     * Replay job in WAITING_TXN state.
     * Should replay all changes in runPendingJob()
     */
    private void replayWaitingTxn(RollupJobV2 replayedJob) {
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbId);
        if (db == null) {
            // database may be dropped before replaying this log. just return
            return;
        }
        OlapTable tbl = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getId(), tableId);
        if (tbl == null) {
            // table may be dropped before replaying this log. just return
            return;
        }

        try (AutoCloseableLock ignore =
                    new AutoCloseableLock(new Locker(), db.getId(), Lists.newArrayList(tbl.getId()), LockType.WRITE)) {
            addRollupIndexToCatalog(tbl);
        }

        // should still be in WAITING_TXN state, so that the alter tasks will be resend again
        this.jobState = JobState.WAITING_TXN;
        this.watershedTxnId = replayedJob.watershedTxnId;

        LOG.info("replay waiting txn rollup job: {}", jobId);
    }

    /**
     * Replay job in FINISHED state.
     * Should replay all changes in runRuningJob()
     */
    private void replayFinished(RollupJobV2 replayedJob) {
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbId);
        if (db != null) {
            OlapTable tbl = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getId(), tableId);
            if (tbl != null) {
                Preconditions.checkState(tbl.getState() == OlapTableState.ROLLUP);
                try (AutoCloseableLock ignore
                            = new AutoCloseableLock(new Locker(), db.getId(), Lists.newArrayList(tbl.getId()), LockType.WRITE)) {
                    onFinished(tbl);
                }
            }
        }

        this.jobState = JobState.FINISHED;
        this.finishedTimeMs = replayedJob.finishedTimeMs;

        LOG.info("replay finished rollup job: {}", jobId);
    }

    /**
     * Replay job in CANCELLED state.
     */
    private void replayCancelled(RollupJobV2 replayedJob) {
        cancelInternal();
        this.jobState = JobState.CANCELLED;
        this.finishedTimeMs = replayedJob.finishedTimeMs;
        this.errMsg = replayedJob.errMsg;
        LOG.info("replay cancelled rollup job: {}", jobId);
    }

    @Override
    public void replay(AlterJobV2 replayedJob) {
        RollupJobV2 replayedRollupJob = (RollupJobV2) replayedJob;
        switch (replayedJob.jobState) {
            case PENDING:
                replayPending(replayedRollupJob);
                break;
            case WAITING_TXN:
                replayWaitingTxn(replayedRollupJob);
                break;
            case FINISHED:
                replayFinished(replayedRollupJob);
                break;
            case CANCELLED:
                replayCancelled(replayedRollupJob);
                break;
            default:
                break;
        }
    }

    @Override
    protected void getInfo(List<List<Comparable>> infos) {
        List<Comparable> info = Lists.newArrayList();
        info.add(jobId);
        info.add(tableName);
        info.add(TimeUtils.longToTimeString(createTimeMs));
        info.add(TimeUtils.longToTimeString(finishedTimeMs));
        info.add(baseIndexName);
        info.add(rollupIndexName);
        info.add(rollupIndexId);
        info.add(watershedTxnId);
        info.add(jobState.name());
        info.add(errMsg);
        // progress
        if (jobState == JobState.RUNNING && rollupBatchTask.getTaskNum() > 0) {
            info.add(rollupBatchTask.getFinishedTaskNum() + "/" + rollupBatchTask.getTaskNum());
        } else {
            info.add(FeConstants.NULL_STRING);
        }
        info.add(timeoutMs / 1000);
        infos.add(info);
    }

    public List<List<String>> getUnfinishedTasks(int limit) {
        List<List<String>> taskInfos = Lists.newArrayList();
        if (jobState == JobState.RUNNING) {
            List<AgentTask> tasks = rollupBatchTask.getUnfinishedTasks(limit);
            for (AgentTask agentTask : tasks) {
                AlterReplicaTask rollupTask = (AlterReplicaTask) agentTask;
                List<String> info = Lists.newArrayList();
                info.add(String.valueOf(rollupTask.getBackendId()));
                info.add(String.valueOf(rollupTask.getBaseTabletId()));
                info.add(String.valueOf(rollupTask.getSignature()));
                taskInfos.add(info);
            }
        }
        return taskInfos;
    }


    @Override
    public Map<Long, MaterializedIndex> getPartitionIdToRollupIndex() {
        return physicalPartitionIdToRollupIndex;
    }

    private void setColumnsDefineExpr(Map<String, Expr> columnNameToDefineExpr) {
        for (Map.Entry<String, Expr> entry : columnNameToDefineExpr.entrySet()) {
            for (Column column : rollupSchema) {
                if (column.getName().equalsIgnoreCase(entry.getKey())) {
                    column.setDefineExpr(entry.getValue());
                    break;
                }
            }
        }
    }

    @Override
    public void write(DataOutput out) throws IOException {
        String json = GsonUtils.GSON.toJson(this, AlterJobV2.class);
        Text.writeString(out, json);
    }

    @Override
    public void gsonPostProcess() throws IOException {
        if (this.rollupBatchTask == null) {
            this.rollupBatchTask = new AgentBatchTask();
        }

        // analyze define stmt
        if (origStmt == null) {
            return;
        }

        if (jobState != JobState.PENDING) {
            return;
        }

        Map<String, Expr> columnNameToDefineExpr = MetaUtils.parseColumnNameToDefineExpr(origStmt);
        if (columnNameToDefineExpr.containsKey(CreateMaterializedViewStmt.WHERE_PREDICATE_COLUMN_NAME)) {
            whereClause = columnNameToDefineExpr.get(CreateMaterializedViewStmt.WHERE_PREDICATE_COLUMN_NAME);
        }
        setColumnsDefineExpr(columnNameToDefineExpr);
    }

    @Override
    public Optional<Long> getTransactionId() {
        return watershedTxnId < 0 ? Optional.empty() : Optional.of(watershedTxnId);
    }
}
