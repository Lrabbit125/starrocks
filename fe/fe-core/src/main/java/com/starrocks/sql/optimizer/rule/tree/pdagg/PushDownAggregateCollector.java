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

package com.starrocks.sql.optimizer.rule.tree.pdagg;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.common.Pair;
import com.starrocks.qe.SessionVariable;
import com.starrocks.sql.optimizer.ExpressionContext;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptExpressionVisitor;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.base.ColumnRefFactory;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.logical.LogicalAggregationOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalFilterOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalJoinOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalProjectOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalScanOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalUnionOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.CaseWhenOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rewrite.ReplaceColumnRefRewriter;
import com.starrocks.sql.optimizer.statistics.ColumnStatistic;
import com.starrocks.sql.optimizer.statistics.ExpressionStatisticCalculator;
import com.starrocks.sql.optimizer.statistics.MultiColumnCombinedStats;
import com.starrocks.sql.optimizer.statistics.Statistics;
import com.starrocks.sql.optimizer.statistics.StatisticsCalculator;
import com.starrocks.sql.optimizer.statistics.StatisticsEstimateCoefficient;
import com.starrocks.sql.optimizer.task.TaskContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.starrocks.sql.optimizer.statistics.StatisticsEstimateCoefficient.SMALL_BROADCAST_JOIN_MAX_COMBINED_NDV_LIMIT;
import static com.starrocks.sql.optimizer.statistics.StatisticsEstimateCoefficient.SMALL_BROADCAST_JOIN_MAX_NDV_LIMIT;

/*
 * Collect all can be push down aggregate context, to get which aggregation can be
 * pushed down and the push down path.
 *
 * Can't rewrite directly, because we don't know which aggregation needs to be
 * push down before arrive at scan node.
 *
 * And in this phase, the key of AggregateContext's map is origin aggregate column, for
 * mark push down which aggregation, the value will multi-rewrite by path, for check
 * which aggregation needs push down
 */
public class PushDownAggregateCollector extends OptExpressionVisitor<Void, AggregatePushDownContext> {
    private static final Logger LOG = LogManager.getLogger(PushDownAggregateCollector.class);

    private static final int DISABLE_PUSH_DOWN_AGG = -1;
    private static final int PUSH_DOWN_AGG_AUTO = 0;
    private static final int PUSH_DOWN_ALL_AGG = 1;
    private static final int PUSH_DOWN_MEDIUM_CARDINALITY_AGG = 2;
    private static final int PUSH_DOWN_HIGH_CARDINALITY_AGG = 3;

    private static final List<String> WHITE_FNS = ImmutableList.of(FunctionSet.MAX, FunctionSet.MIN,
            FunctionSet.SUM, FunctionSet.HLL_UNION, FunctionSet.BITMAP_UNION, FunctionSet.PERCENTILE_UNION);

    private final TaskContext taskContext;
    private final OptimizerContext optimizerContext;
    private final ColumnRefFactory factory;
    private final SessionVariable sessionVariable;

    // Used to assign a unique index to each path from root to leaf node.
    // For example, in the following tree structure, the index of A#1, Join#3, Join#5 is 0,
    // the index of B#2 is 1, and the index of C#4 is 2.
    //      Join#5
    //    Join#3   C#4
    // A#1    B#2
    private final MutableInt nextRootToLeafPathIndex;
    // OrigAgg -> Map[PathIndex -> List[AggregatePushDownContext]].
    private final Map<LogicalAggregationOperator, Map<Integer, List<AggregatePushDownContext>>> rewriteContextCandidates =
            Maps.newHashMap();
    private final Map<LogicalAggregationOperator, List<AggregatePushDownContext>> allRewriteContext = Maps.newHashMap();

    public PushDownAggregateCollector(TaskContext taskContext) {
        this(taskContext, new MutableInt());
    }

    private PushDownAggregateCollector(TaskContext taskContext, MutableInt nextRootToLeafPathIndex) {
        this.taskContext = taskContext;
        this.optimizerContext = taskContext.getOptimizerContext();
        this.factory = taskContext.getOptimizerContext().getColumnRefFactory();
        this.sessionVariable = taskContext.getOptimizerContext().getSessionVariable();
        this.nextRootToLeafPathIndex = nextRootToLeafPathIndex;
    }

    Map<LogicalAggregationOperator, List<AggregatePushDownContext>> getAllRewriteContext() {
        return allRewriteContext;
    }

    public void collect(OptExpression root) {
        collect(root, new AggregatePushDownContext(nextRootToLeafPathIndex.getAndIncrement()));
    }

    private void collect(OptExpression root, AggregatePushDownContext context) {
        process(root, context);
        selectPushDownTarget();
    }

    @Override
    public Void visit(OptExpression optExpression, AggregatePushDownContext context) {
        // forbidden push down
        for (OptExpression input : optExpression.getInputs()) {
            process(input, AggregatePushDownContext.EMPTY);
        }
        return null;
    }

    private Void processChild(OptExpression optExpression, AggregatePushDownContext context) {
        for (OptExpression input : optExpression.getInputs()) {
            process(input, context);
        }
        return null;
    }

    private void process(OptExpression opt, AggregatePushDownContext context) {
        opt.getOp().accept(this, opt, context);
    }

    private boolean isInvalid(OptExpression optExpression, AggregatePushDownContext context) {
        return context.isEmpty() || optExpression.getOp().hasLimit();
    }

    @Override
    public Void visitLogicalFilter(OptExpression optExpression, AggregatePushDownContext context) {
        if (isInvalid(optExpression, context)) {
            return visit(optExpression, context);
        }

        // add filter columns in groupBys
        LogicalFilterOperator filter = (LogicalFilterOperator) optExpression.getOp();
        filter.getRequiredChildInputColumns().getStream().map(factory::getColumnRef)
                .forEach(v -> context.groupBys.put(v, v));
        return processChild(optExpression, context);
    }

    @Override
    public Void visitLogicalProject(OptExpression optExpression, AggregatePushDownContext context) {
        if (isInvalid(optExpression, context)) {
            return visit(optExpression, context);
        }

        LogicalProjectOperator project = (LogicalProjectOperator) optExpression.getOp();

        if (project.getColumnRefMap().entrySet().stream().allMatch(e -> e.getValue().equals(e.getKey()))) {
            return processChild(optExpression, context);
        }

        // rewrite
        ReplaceColumnRefRewriter rewriter = new ReplaceColumnRefRewriter(project.getColumnRefMap());
        context.aggregations.replaceAll((k, v) -> (CallOperator) rewriter.rewrite(v));
        context.groupBys.replaceAll((k, v) -> rewriter.rewrite(v));

        if (project.getColumnRefMap().values().stream().allMatch(ScalarOperator::isColumnRef)) {
            return processChild(optExpression, context);
        }

        // handle specials functions case-when/if
        // split to groupBys and mock new aggregations by values, don't need to save
        // origin predicate, we just do check in collect phase
        for (Map.Entry<ColumnRefOperator, CallOperator> entry : context.aggregations.entrySet()) {
            CallOperator aggFn = entry.getValue();
            ScalarOperator aggInput = aggFn.getChild(0);

            if (!(aggInput instanceof CallOperator)) {
                continue;
            }

            CallOperator callInput = (CallOperator) aggInput;
            if (aggInput instanceof CaseWhenOperator) {
                CaseWhenOperator caseWhen = (CaseWhenOperator) aggInput;
                for (ScalarOperator condition : caseWhen.getAllConditionClause()) {
                    condition.getUsedColumns().getStream().map(factory::getColumnRef)
                            .forEach(v -> context.groupBys.put(v, v));
                }

                List<ScalarOperator> newWhenThen = Lists.newArrayList();
                for (int i = 0; i < caseWhen.getWhenClauseSize(); i++) {
                    if (caseWhen.getThenClause(i).isConstant() && !caseWhen.getThenClause(i).isConstantNull()) {
                        // forbidden push down
                        return visit(optExpression, context);
                    }
                    newWhenThen.add(ConstantOperator.createBoolean(false));
                    newWhenThen.add(caseWhen.getThenClause(i));
                }

                // mock just value case when
                CaseWhenOperator newCaseWhen = new CaseWhenOperator(caseWhen.getType(), null,
                        caseWhen.hasElse() ? caseWhen.getElseClause() : null, newWhenThen);

                // replace origin
                aggFn.setChild(0, newCaseWhen);
            } else if (callInput.getFunction() != null &&
                    FunctionSet.IF.equals(callInput.getFunction().getFunctionName().getFunction())) {
                if (aggInput.getChildren().stream().skip(1).anyMatch(c -> c.isConstant() && !c.isConstantNull())) {
                    // forbidden push down
                    return visit(optExpression, context);
                }

                aggInput.getChild(0).getUsedColumns().getStream().map(factory::getColumnRef)
                        .forEach(v -> context.groupBys.put(v, v));
                aggInput.setChild(0, ConstantOperator.createBoolean(false));
            }
        }

        // check has constant aggregate, forbidden
        if (!context.aggregations.isEmpty() &&
                context.aggregations.values().stream().allMatch(ScalarOperator::isConstant)) {
            return visit(optExpression, context);
        }

        return processChild(optExpression, context);
    }

    @Override
    public Void visitLogicalAggregate(OptExpression optExpression, AggregatePushDownContext context) {
        LogicalAggregationOperator aggregate = (LogicalAggregationOperator) optExpression.getOp();
        // distinct/count* aggregate can't push down
        if (aggregate.getAggregations().values().stream().anyMatch(c -> c.isDistinct() || c.isCountStar())) {
            return visit(optExpression, context);
        }

        // all constant can't push down
        if (!aggregate.getAggregations().isEmpty() &&
                aggregate.getAggregations().values().stream().allMatch(ScalarOperator::isConstant)) {
            return visit(optExpression, context);
        }

        // none group by don't push down
        if (aggregate.getGroupingKeys().isEmpty()) {
            return visit(optExpression, context);
        }

        context = new AggregatePushDownContext(context.rootToLeafPathIndex);
        context.setAggregator(aggregate);
        return processChild(optExpression, context);
    }

    @Override
    public Void visitLogicalJoin(OptExpression optExpression, AggregatePushDownContext context) {
        if (isInvalid(optExpression, context)) {
            return visit(optExpression, context);
        }
        // constant aggregate can't push down
        if (!context.aggregations.isEmpty() &&
                context.aggregations.values().stream().allMatch(ScalarOperator::isConstant)) {
            return visit(optExpression, context);
        }

        boolean isSmallBroadcastJoin = isSmallBroadcastJoin(optExpression);
        if (isSmallBroadcastJoin && !context.pushPaths.isEmpty() && isAllChildrenSP(optExpression)) {
            context.targetPosition = optExpression;
            addCandidateContext(context.origAggregator, context);
        }

        // split aggregate to left/right child
        AggregatePushDownContext leftContext = splitJoinAggregate(optExpression, context, 0, isSmallBroadcastJoin);
        AggregatePushDownContext rightContext = splitJoinAggregate(optExpression, context, 1, false);
        process(optExpression.inputAt(0), leftContext);
        process(optExpression.inputAt(1), rightContext);
        return null;
    }

    /*
     * When aggregation is pushed down to join, it means that the columns on aggregation are from
     * multi-tables, maybe aggregate columns from left table and group by columns from right table.
     * We only push down aggregation to child which one support aggregate columns, because aggregate
     * columns will ignore 1-N join, so there will check:
     *
     * 1. all aggregation related columns must come from one child, and
     *    it can be push down to both sides if columns is empty, like only group by
     * 2. re-compute group by columns
     *  2.1. split the original group by columns
     *  2.2. add join on-predicate and predicate used columns to group by
     *
     * e.g. 1-N case
     * select t0.v1, t1.v1, sum(t0.v2), sum(t1.v2) from t0 join t1 on t0.v1 = t1.v1;
     *
     * t0.v1    t0.v2           t1.v1   t1.v2
     *   1        1      Join     1        1
     *   1        1               1        1
     */
    private AggregatePushDownContext splitJoinAggregate(OptExpression optExpression, AggregatePushDownContext context,
                                                        int child, boolean immediateChildOfSmallBroadcastJoin) {
        LogicalJoinOperator join = (LogicalJoinOperator) optExpression.getOp();
        ColumnRefSet childOutput = optExpression.getChildOutputColumns(child);

        // check aggregations
        ColumnRefSet aggregationsRefs = new ColumnRefSet();
        context.aggregations.values().stream().map(CallOperator::getUsedColumns).forEach(aggregationsRefs::union);
        if (!childOutput.containsAll(aggregationsRefs)) {
            return AggregatePushDownContext.EMPTY;
        }

        int rootToLeafPathIndex = child == 0 ? context.rootToLeafPathIndex : nextRootToLeafPathIndex.getAndIncrement();
        AggregatePushDownContext childContext = new AggregatePushDownContext(rootToLeafPathIndex);
        childContext.aggregations.putAll(context.aggregations);

        // check group by
        for (Map.Entry<ColumnRefOperator, ScalarOperator> entry : context.groupBys.entrySet()) {
            ColumnRefSet groupByUseColumns = entry.getValue().getUsedColumns();
            if (childOutput.containsAll(groupByUseColumns)) {
                childContext.groupBys.put(entry.getKey(), entry.getValue());
            } else if (childOutput.isIntersect(groupByUseColumns)) {
                // e.g. group by abs(a + b), we can derive group by a
                Map<ColumnRefOperator, ScalarOperator> rewriteMap = groupByUseColumns.getStream()
                        .filter(c -> !childOutput.contains(c)).map(factory::getColumnRef)
                        .collect(Collectors.toMap(k -> k, k -> ConstantOperator.createNull(k.getType())));
                ReplaceColumnRefRewriter rewriter = new ReplaceColumnRefRewriter(rewriteMap);
                childContext.groupBys.put(entry.getKey(), rewriter.rewrite(entry.getValue()));
            }
        }

        if (join.getOnPredicate() != null) {
            join.getOnPredicate().getUsedColumns().getStream().map(factory::getColumnRef)
                    .filter(childOutput::contains)
                    .forEach(c -> childContext.groupBys.put(c, c));
        }

        if (join.getPredicate() != null) {
            join.getPredicate().getUsedColumns().getStream().map(factory::getColumnRef)
                    .filter(childOutput::contains)
                    .forEach(v -> childContext.groupBys.put(v, v));
        }

        childContext.immediateChildOfSmallBroadcastJoin = immediateChildOfSmallBroadcastJoin;
        childContext.origAggregator = context.origAggregator;
        childContext.pushPaths.addAll(context.pushPaths);
        childContext.pushPaths.add(child);
        return childContext;
    }

    @Override
    public Void visitLogicalUnion(OptExpression optExpression, AggregatePushDownContext context) {
        if (isInvalid(optExpression, context)) {
            return visit(optExpression, context);
        }

        List<PushDownAggregateCollector> collectors = Lists.newArrayList();
        LogicalUnionOperator union = (LogicalUnionOperator) optExpression.getOp();
        for (int i = 0; i < optExpression.getInputs().size(); i++) {
            List<ColumnRefOperator> childOutput = union.getChildOutputColumns().get(i);
            Map<ColumnRefOperator, ScalarOperator> rewriteMap = Maps.newHashMap();
            Preconditions.checkState(childOutput.size() == union.getOutputColumnRefOp().size());
            for (int k = 0; k < union.getOutputColumnRefOp().size(); k++) {
                rewriteMap.put(union.getOutputColumnRefOp().get(k), childOutput.get(k));
            }

            ReplaceColumnRefRewriter rewriter = new ReplaceColumnRefRewriter(rewriteMap);
            AggregatePushDownContext childContext = new AggregatePushDownContext(nextRootToLeafPathIndex.getAndIncrement());
            childContext.origAggregator = context.origAggregator;
            childContext.aggregations.putAll(context.aggregations);
            childContext.aggregations.replaceAll((k, v) -> (CallOperator) rewriter.rewrite(v));

            childContext.groupBys.putAll(context.groupBys);
            childContext.groupBys.replaceAll((k, v) -> rewriter.rewrite(v));
            childContext.pushPaths.addAll(context.pushPaths);
            childContext.pushPaths.add(i);

            PushDownAggregateCollector collector = new PushDownAggregateCollector(this.taskContext, nextRootToLeafPathIndex);
            collectors.add(collector);
            collector.collect(optExpression.inputAt(i), childContext);
        }

        // collect push down aggregate context
        List<List<AggregatePushDownContext>> allChildRewriteContext = Lists.newArrayList();
        for (PushDownAggregateCollector childCollector : collectors) {
            if (childCollector.allRewriteContext.containsKey(context.origAggregator)) {
                allChildRewriteContext.add(childCollector.allRewriteContext.get(context.origAggregator));
                childCollector.allRewriteContext.remove(context.origAggregator);
            }
        }

        // merge other rewrite context
        for (PushDownAggregateCollector collector : collectors) {
            Preconditions.checkState(
                    !CollectionUtils.containsAny(allRewriteContext.keySet(), collector.allRewriteContext.keySet()));
            allRewriteContext.putAll(collector.allRewriteContext);
        }

        // none aggregate can push down to union children
        if (allChildRewriteContext.isEmpty() || allChildRewriteContext.size() != collectors.size()) {
            return null;
        }

        Set<ColumnRefOperator> checkGroupBys = new HashSet<>(context.groupBys.keySet());
        Set<ColumnRefOperator> checkAggregations = new HashSet<>(context.aggregations.keySet());

        for (List<AggregatePushDownContext> childContexts : allChildRewriteContext) {
            Set<ColumnRefOperator> cg = new HashSet<>();
            Set<ColumnRefOperator> ca = new HashSet<>();

            childContexts.forEach(c -> {
                cg.addAll(c.groupBys.keySet());
                ca.addAll(c.aggregations.keySet());
            });

            // Must all same, like Agg1, Agg2 split by Union, and Scan1 support Agg1/Agg2, and
            // Scan2 only support Agg1, we must promise to either push down or not push down
            // like:
            //         UNION
            //        /      \
            //     Scan1    Join
            //             /    \
            //         Scan2    Scan3
            if (!cg.containsAll(checkGroupBys) || !ca.containsAll(checkAggregations)) {
                return null;
            }
        }

        List<AggregatePushDownContext> list = allRewriteContext.getOrDefault(context.origAggregator, Lists.newArrayList());
        allChildRewriteContext.forEach(list::addAll);
        allRewriteContext.put(context.origAggregator, list);
        return null;
    }

    @Override
    public Void visitLogicalCTEAnchor(OptExpression optExpression, AggregatePushDownContext context) {
        process(optExpression.inputAt(1), context);
        process(optExpression.inputAt(0), AggregatePushDownContext.EMPTY);
        return null;
    }

    @Override
    public Void visitLogicalTableScan(OptExpression optExpression, AggregatePushDownContext context) {
        if (!isInvalid(optExpression, context)) {
            context.targetPosition = optExpression;
            addCandidateContext(context.origAggregator, context);
        }
        return null;
    }

    private boolean checkStatistics(AggregatePushDownContext context, ColumnRefSet groupBys, Statistics statistics) {
        final int pushDownMode = sessionVariable.getCboPushDownAggregateMode();

        // check force push down flag
        // flag 0: auto. 1: force push down. -1: don't push down. 2: push down medium. 3: push down high
        if (pushDownMode == PUSH_DOWN_ALL_AGG) {
            return true;
        }

        if (pushDownMode == DISABLE_PUSH_DOWN_AGG) {
            return false;
        }

        List<ColumnStatistic> lower = Lists.newArrayList();
        List<ColumnStatistic> medium = Lists.newArrayList();
        List<ColumnStatistic> high = Lists.newArrayList();

        List<ColumnStatistic>[] cards = new List[] {lower, medium, high};

        Set<ColumnRefOperator> columnRefOperators = groupBys.getStream()
                .map(factory::getColumnRef)
                .collect(Collectors.toSet());

        double maxSingleColumnDistinct = 0.0;
        double maxMultiColumnDistinct = 0.0;
        Set<ColumnStatistic> columnStatistics = new HashSet<>();

        Pair<Set<ColumnRefOperator>, MultiColumnCombinedStats> mcStats = statistics.getLargestSubsetMCStats(columnRefOperators);

        if (sessionVariable.isCboPushDownAggWithMultiColumnStats() && mcStats != null && !mcStats.first.isEmpty()) {
            double ndv = Math.max(1, mcStats.second.getNdv());
            ColumnStatistic multiColumnStat = ColumnStatistic.builder().setDistinctValuesCount(ndv).build();
            columnStatistics.add(multiColumnStat);
            maxMultiColumnDistinct = ndv;

            Set<ColumnRefOperator> remainedColumns = new HashSet<>(columnRefOperators);
            remainedColumns.removeAll(mcStats.first);

            for (ColumnRefOperator col : remainedColumns) {
                ColumnStatistic stat = ExpressionStatisticCalculator.calculate(col, statistics);
                columnStatistics.add(stat);
                maxSingleColumnDistinct = Math.max(maxSingleColumnDistinct, stat.getDistinctValuesCount());
            }
        } else {
            for (ColumnRefOperator col : columnRefOperators) {
                ColumnStatistic stat = ExpressionStatisticCalculator.calculate(col, statistics);
                columnStatistics.add(stat);
                maxSingleColumnDistinct = Math.max(maxSingleColumnDistinct, stat.getDistinctValuesCount());
            }
        }

        double outputRowCount = statistics.getOutputRowCount();
        for (ColumnStatistic stat : columnStatistics) {
            cards[groupByCardinality(stat, outputRowCount)].add(stat);
        }

        if (pushDownMode == PUSH_DOWN_AGG_AUTO && context.immediateChildOfSmallBroadcastJoin) {
            if (maxSingleColumnDistinct > SMALL_BROADCAST_JOIN_MAX_NDV_LIMIT) {
                return false;
            }
            if (maxMultiColumnDistinct > SMALL_BROADCAST_JOIN_MAX_COMBINED_NDV_LIMIT) {
                return false;
            }
        }

        double lowerCartesian = lower.stream().map(ColumnStatistic::getDistinctValuesCount).reduce((a, b) -> a * b)
                .orElse(Double.MAX_VALUE);

        // pow(row_count/20, a half of lower column size)
        double lowerUpper = Math.max(statistics.getOutputRowCount() / 20, 1);
        lowerUpper = Math.pow(lowerUpper, Math.max(lower.size() / 2, 1));

        String aggStr = context.aggregations.values().stream().map(CallOperator::toString)
                .collect(Collectors.joining(", "));
        String groupStr = groupBys.getStream().map(String::valueOf).collect(Collectors.joining(", "));

        LOG.debug("Push down aggregation[" + aggStr + "]" +
                " group by[" + groupStr + "]," +
                " check statistics rows[" + statistics.getOutputRowCount() +
                "] high[" + high.size() +
                "] mid[" + medium.size() +
                "] low[" + lower.size() +
                "] cartesian[" + lowerCartesian +
                "] upper-cartesian[" + lowerUpper + "], mode[" + pushDownMode + "]");

        // 1. white push down rules
        // 1.1 only one lower/medium cardinality columns
        if (high.isEmpty() && (lower.size() + medium.size()) == 1) {
            return true;
        }

        // 1.2 the cartesian of all lower/count <= 1
        // 1.3 the lower cardinality <= 3 and lowerCartesian < lowerUpper
        // 1.4 follow medium cardinality flag
        if (high.isEmpty() && medium.isEmpty()) {
            if (lowerCartesian <= statistics.getOutputRowCount() || lower.size() <= 2) {
                return true;
            } else if (lower.size() <= 3 && lowerCartesian < lowerUpper) {
                return true;
            } else {
                return pushDownMode >= PUSH_DOWN_MEDIUM_CARDINALITY_AGG;
            }
        }


        // 2.1 high cardinality >= 2
        // 2.2 medium cardinality > 2
        // 2.3 high cardinality = 1 and medium cardinality > 0
        if (high.size() >= 2 || medium.size() > 2 || (high.size() == 1 && !medium.isEmpty())) {
            return false;
        }

        // 3. Extremely low cardinality for lower with at most one medium or high.
        double lowerCartesianLowerBound =
                statistics.getOutputRowCount() / StatisticsEstimateCoefficient.LOWER_AGGREGATE_EFFECT_COEFFICIENT;
        if (high.size() + medium.size() == 1 && lower.size() <= 2 && lowerCartesian <= lowerCartesianLowerBound) {
            return true;
        }

        // 4. high cardinality < 2 and lower cardinality < 2
        if (high.size() == 1 && lower.size() <= 2) {
            return pushDownMode >= PUSH_DOWN_HIGH_CARDINALITY_AGG;
        }

        // 5. medium cardinality <= 2
        if (lower.size() <= 2) {
            if (pushDownMode >= PUSH_DOWN_MEDIUM_CARDINALITY_AGG) {
                return true;
            }
            return statistics.getOutputRowCount() >=
                    StatisticsEstimateCoefficient.SMALL_SCALE_ROWS_LIMIT;
        }

        return false;
    }

    // high(2): row_count / cardinality < MEDIUM_AGGREGATE_EFFECT_COEFFICIENT
    // medium(1): row_count / cardinality >= MEDIUM_AGGREGATE_EFFECT_COEFFICIENT and < LOW_AGGREGATE_EFFECT_COEFFICIENT
    // lower(0): row_count / cardinality >= LOW_AGGREGATE_EFFECT_COEFFICIENT
    public static int groupByCardinality(ColumnStatistic statistic, double rowCount) {
        if (statistic.isUnknown()) {
            return 2;
        }

        double distinct = statistic.getDistinctValuesCount();

        if (rowCount == 0 || distinct * StatisticsEstimateCoefficient.MEDIUM_AGGREGATE_EFFECT_COEFFICIENT > rowCount) {
            return 2;
        } else if (distinct * StatisticsEstimateCoefficient.MEDIUM_AGGREGATE_EFFECT_COEFFICIENT <= rowCount &&
                distinct * StatisticsEstimateCoefficient.LOW_AGGREGATE_EFFECT_COEFFICIENT > rowCount) {
            return 1;
        } else if (distinct * StatisticsEstimateCoefficient.LOW_AGGREGATE_EFFECT_COEFFICIENT <= rowCount) {
            return 0;
        }

        return 2;
    }

    private boolean isSmallBroadcastJoin(OptExpression optExpression) {
        if (!sessionVariable.isCboPushDownAggregateOnBroadcastJoin()) {
            return false;
        }

        Statistics rightStatistics = optExpression.inputAt(1).getStatistics();
        if (rightStatistics == null) {
            return false;
        }
        double rightRows = rightStatistics.getOutputRowCount();
        return rightRows <= sessionVariable.getBroadcastRowCountLimit() &&
                rightRows <= sessionVariable.getCboPushDownAggregateOnBroadcastJoinRowCountLimit();
    }

    /**
     * Whether all the children are scan/project/filter.
     * @return true, if all the children are scan/project/filter.
     */
    private static boolean isAllChildrenSP(OptExpression root) {
        return root.getInputs().stream().allMatch(PushDownAggregateCollector::isAllSP);
    }

    private static boolean isAllSP(OptExpression root) {
        if (root.getOp().getOpType() != OperatorType.LOGICAL_PROJECT &&
                root.getOp().getOpType() != OperatorType.LOGICAL_FILTER &&
                !(root.getOp() instanceof LogicalScanOperator)) {
            return false;
        }
        return root.getInputs().stream().allMatch(PushDownAggregateCollector::isAllSP);
    }

    private void addFinalContext(LogicalAggregationOperator origAggregator, AggregatePushDownContext context) {
        allRewriteContext
                .computeIfAbsent(origAggregator, k -> Lists.newArrayList())
                .add(context);
    }

    private void addCandidateContext(LogicalAggregationOperator origAggregator, AggregatePushDownContext context) {
        rewriteContextCandidates
                .computeIfAbsent(origAggregator, k -> Maps.newHashMap())
                .computeIfAbsent(context.rootToLeafPathIndex, k -> Lists.newArrayList())
                .add(context);
    }

    private void selectPushDownTarget() {
        rewriteContextCandidates.forEach((origAggregator, pathToContexts) -> {
            // Select a proper context for each root-to-leaf path.
            pathToContexts.values().forEach(contexts -> {
                // The order of rewriteContextCandidates.contexts is from top to bottom,
                // and here we choose the first position that can be pushed down from bottom to top.
                for (int i = contexts.size() - 1; i >= 0; i--) {
                    AggregatePushDownContext context = contexts.get(i);
                    if (canPushDown(context.targetPosition, context)) {
                        addFinalContext(origAggregator, context);
                        break;
                    }
                }
            });
        });
    }

    private boolean canPushDown(OptExpression optExpression, AggregatePushDownContext context) {
        // least cross join/union/cte
        if (context.isEmpty() || context.pushPaths.isEmpty()) {
            return false;
        }

        if (context.aggregations.isEmpty() && context.groupBys.isEmpty()) {
            return false;
        }

        // distinct function, not support function can't push down
        if (context.aggregations.values().stream()
                .anyMatch(v -> v.isDistinct() || !WHITE_FNS.contains(v.getFnName()))) {
            return false;
        }

        ColumnRefSet outputColumns = optExpression.getOutputColumns();

        ColumnRefSet allGroupByColumns = new ColumnRefSet();
        context.groupBys.values().forEach(c -> allGroupByColumns.union(c.getUsedColumns()));

        ColumnRefSet allAggregateColumns = new ColumnRefSet();
        context.aggregations.values().forEach(c -> allAggregateColumns.union(c.getUsedColumns()));

        Preconditions.checkState(outputColumns.containsAll(allGroupByColumns));
        Preconditions.checkState(outputColumns.containsAll(allAggregateColumns));

        ExpressionContext expressionContext = new ExpressionContext(optExpression);
        StatisticsCalculator statisticsCalculator = new StatisticsCalculator(expressionContext, factory, optimizerContext);
        statisticsCalculator.estimatorStats();

        if (!checkStatistics(context, allGroupByColumns, expressionContext.getStatistics())) {
            return false;
        }

        return true;
    }

}
