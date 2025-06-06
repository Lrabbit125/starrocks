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

#include "aggregator.h"

#include <algorithm>
#include <memory>
#include <type_traits>
#include <variant>

#include "column/chunk.h"
#include "column/column_helper.h"
#include "column/vectorized_fwd.h"
#include "common/config.h"
#include "common/status.h"
#include "exec/agg_runtime_filter_builder.h"
#include "exec/aggregate/agg_profile.h"
#include "exec/exec_node.h"
#include "exec/limited_pipeline_chunk_buffer.h"
#include "exec/pipeline/operator.h"
#include "exec/spill/spiller.hpp"
#include "exprs/agg/agg_state_if.h"
#include "exprs/agg/agg_state_merge.h"
#include "exprs/agg/agg_state_union.h"
#include "exprs/agg/aggregate_state_allocator.h"
#include "gen_cpp/PlanNodes_types.h"
#include "runtime/current_thread.h"
#include "runtime/descriptors.h"
#include "runtime/memory/roaring_hook.h"
#include "types/logical_type.h"
#include "udf/java/utils.h"
#include "util/runtime_profile.h"

namespace starrocks {

static const std::unordered_set<std::string> ALWAYS_NULLABLE_RESULT_AGG_FUNCS = {
        "variance_samp", "var_samp", "stddev_samp", "covar_samp", "corr", "max_by_v2", "min_by_v2"};

static const std::string AGG_STATE_UNION_SUFFIX = "_union";
static const std::string AGG_STATE_MERGE_SUFFIX = "_merge";
static const std::string AGG_STATE_IF_SUFFIX = "_if";
static const std::string FUNCTION_COUNT = "count";

template <bool UseIntermediateAsOutput>
bool AggFunctionTypes::is_result_nullable() const {
    if constexpr (UseIntermediateAsOutput) {
        // If using intermediate results as output, no output will be generated and only the input will be serialized.
        // Therefore, only judge whether the input is nullable to decide whether to serialize null data.
        return has_nullable_child;
    } else {
        // `is_nullable` means whether the output MAY be nullable. It will be false only when the output is always non-nullable.
        // Therefore, we need to decide whether the output is really nullable case by case:
        // 1. Same as input: `has_nullable_child` = `has_nullable_child && is_nullable(true)`.
        // 2. Always non-nullable: `false` = `has_nullable_child && is_nullable(false)`, eg. count, count distinct, and bitmap_union_int.
        // 3. Always nullable: `is_always_nullable_result`.
        return (has_nullable_child && is_nullable) || is_always_nullable_result;
    }
}

bool AggFunctionTypes::use_nullable_fn(bool use_intermediate_as_output) const {
    // The non-nullable version functions assume that both the input and output are non-nullable, while the nullable version
    // functions support nullable input or nullable output, which will judge whether the input and output are nullable.
    //
    // NOTE that for the case of `is_always_nullable_result=true`, the function created with `use_intermediate_as_output=true`
    // also needs to use `is_result_nullable<true>` when getting the finalize result.
    // Because for the case of `is_always_nullable_result=true and has_nullable_child=false`, the function is the non-nullable version,
    // which causes only non-nullable output can be created.
    if (use_intermediate_as_output) {
        return has_nullable_child || is_result_nullable<true>();
    } else {
        return has_nullable_child || is_result_nullable<false>();
    }
}

std::string AggrAutoContext::get_auto_state_string(const AggrAutoState& state) {
    switch (state) {
    case INIT_PREAGG:
        return "INIT_PREAGG";
    case ADJUST:
        return "ADJUST";
    case PASS_THROUGH:
        return "PASS_THROUGH";
    case FORCE_PREAGG:
        return "FORCE_PREAGG";
    case PREAGG:
        return "PREAGG";
    case SELECTIVE_PREAGG:
        return "SELECTIVE_PREAGG";
    }
    return "UNKNOWN";
}

void AggrAutoContext::update_continuous_limit() {
    continuous_limit = continuous_limit * 2 > ContinuousUpperLimit ? ContinuousUpperLimit : continuous_limit * 2;
}

size_t AggrAutoContext::get_continuous_limit() {
    return continuous_limit;
}

bool AggrAutoContext::is_high_reduction(const size_t agg_count, const size_t chunk_size) {
    return agg_count >= HighReduction * chunk_size;
}

bool AggrAutoContext::is_low_reduction(const size_t agg_count, const size_t chunk_size) {
    return agg_count <= LowReduction * chunk_size;
}

Status init_udaf_context(int64_t fid, const std::string& url, const std::string& checksum, const std::string& symbol,
                         FunctionContext* context);

AggregatorParamsPtr convert_to_aggregator_params(const TPlanNode& tnode) {
    auto params = std::make_shared<AggregatorParams>();
    params->conjuncts = tnode.conjuncts;
    params->limit = tnode.limit;

    // TODO: STREAM_AGGREGATION_NODE will be added later.
    DCHECK_EQ(tnode.node_type, TPlanNodeType::AGGREGATION_NODE);
    switch (tnode.node_type) {
    case TPlanNodeType::AGGREGATION_NODE: {
        params->needs_finalize = tnode.agg_node.need_finalize;
        params->streaming_preaggregation_mode = tnode.agg_node.streaming_preaggregation_mode;
        params->intermediate_tuple_id = tnode.agg_node.intermediate_tuple_id;
        params->output_tuple_id = tnode.agg_node.output_tuple_id;
        params->sql_grouping_keys = tnode.agg_node.__isset.sql_grouping_keys ? tnode.agg_node.sql_grouping_keys : "";
        params->sql_aggregate_functions =
                tnode.agg_node.__isset.sql_aggregate_functions ? tnode.agg_node.sql_aggregate_functions : "";
        params->has_outer_join_child =
                tnode.agg_node.__isset.has_outer_join_child && tnode.agg_node.has_outer_join_child;
        params->grouping_exprs = tnode.agg_node.grouping_exprs;
        params->aggregate_functions = tnode.agg_node.aggregate_functions;
        params->intermediate_aggr_exprs = tnode.agg_node.intermediate_aggr_exprs;
        params->enable_pipeline_share_limit =
                tnode.agg_node.__isset.enable_pipeline_share_limit ? tnode.agg_node.enable_pipeline_share_limit : false;
        break;
    }
    default:
        __builtin_unreachable();
    }
    params->init();
    return params;
}

void AggregatorParams::init() {
    size_t agg_size = aggregate_functions.size();
    agg_fn_types.resize(agg_size);
    // init aggregate function types
    for (size_t i = 0; i < agg_size; ++i) {
        const TExpr& desc = aggregate_functions[i];
        const TFunction& fn = desc.nodes[0].fn;
        VLOG_ROW << fn.name.function_name << ", arg nullable " << desc.nodes[0].has_nullable_child
                 << ", result nullable " << desc.nodes[0].is_nullable;

        if (fn.name.function_name == FUNCTION_COUNT ||
            fn.name.function_name == (FUNCTION_COUNT + AGG_STATE_IF_SUFFIX)) {
            // count function is always not nullable
            agg_fn_types[i] = {TypeDescriptor(TYPE_BIGINT), TypeDescriptor(TYPE_BIGINT), {}, false, false};
        } else {
            // whether agg function has nullable child
            const bool has_nullable_child = has_outer_join_child || desc.nodes[0].has_nullable_child;
            // whether agg function is nullable
            bool is_nullable = desc.nodes[0].is_nullable;
            // collect arg_typedescs for aggregate function.
            std::vector<FunctionContext::TypeDesc> arg_typedescs;
            for (auto& type : fn.arg_types) {
                arg_typedescs.push_back(TypeDescriptor::from_thrift(type));
            }
            TypeDescriptor return_type = TypeDescriptor::from_thrift(fn.ret_type);
            TypeDescriptor serde_type = TypeDescriptor::from_thrift(fn.aggregate_fn.intermediate_type);
            agg_fn_types[i] = {return_type, serde_type, arg_typedescs, has_nullable_child, is_nullable};
            agg_fn_types[i].is_always_nullable_result =
                    ALWAYS_NULLABLE_RESULT_AGG_FUNCS.contains(fn.name.function_name);
            if (fn.__isset.agg_state_desc && fn.name.function_name.ends_with(AGG_STATE_IF_SUFFIX)) {
                agg_fn_types[i].is_always_nullable_result = true;
            }
            if (fn.name.function_name == "array_agg" || fn.name.function_name == "group_concat") {
                // set order by info
                if (fn.aggregate_fn.__isset.is_asc_order && fn.aggregate_fn.__isset.nulls_first &&
                    !fn.aggregate_fn.is_asc_order.empty()) {
                    agg_fn_types[i].is_asc_order = fn.aggregate_fn.is_asc_order;
                    agg_fn_types[i].nulls_first = fn.aggregate_fn.nulls_first;
                }
                if (fn.aggregate_fn.__isset.is_distinct) {
                    agg_fn_types[i].is_distinct = fn.aggregate_fn.is_distinct;
                }
            }
        }
    }

    // init group by types
    size_t group_by_size = grouping_exprs.size();
    group_by_types.resize(group_by_size);
    for (size_t i = 0; i < group_by_size; ++i) {
        TExprNode expr = grouping_exprs[i].nodes[0];
        group_by_types[i].result_type = TypeDescriptor::from_thrift(expr.type);
        group_by_types[i].is_nullable = expr.is_nullable || has_outer_join_child;
        has_nullable_key = has_nullable_key || group_by_types[i].is_nullable;
        VLOG_ROW << "group by column " << i << " result_type " << group_by_types[i].result_type << " is_nullable "
                 << expr.is_nullable;
    }

    VLOG_ROW << "has_nullable_key " << has_nullable_key;
}

#define ALIGN_TO(size, align) ((size + align - 1) / align * align)
#define PAD(size, align) (align - (size % align)) % align;

Aggregator::Aggregator(AggregatorParamsPtr params) : _params(std::move(params)) {
    _allocator = std::make_unique<CountingAllocatorWithHook>();
}

Status Aggregator::open(RuntimeState* state) {
    if (_is_opened) {
        return Status::OK();
    }
    _is_opened = true;
    RETURN_IF_ERROR(Expr::open(_group_by_expr_ctxs, state));
    for (int i = 0; i < _agg_fn_ctxs.size(); ++i) {
        RETURN_IF_ERROR(Expr::open(_agg_expr_ctxs[i], state));
        RETURN_IF_ERROR(_evaluate_const_columns(i));
    }
    for (auto& _intermediate_agg_expr_ctx : _intermediate_agg_expr_ctxs) {
        RETURN_IF_ERROR(Expr::open(_intermediate_agg_expr_ctx, state));
    }
    RETURN_IF_ERROR(Expr::open(_conjunct_ctxs, state));

    // init function context
    _has_udaf = std::any_of(_fns.begin(), _fns.end(),
                            [](const auto& ctx) { return ctx.binary_type == TFunctionBinaryType::SRJAR; });
    if (_has_udaf) {
        auto promise_st = call_function_in_pthread(state, [this]() {
            for (int i = 0; i < _agg_fn_ctxs.size(); ++i) {
                if (_fns[i].binary_type == TFunctionBinaryType::SRJAR) {
                    const auto& fn = _fns[i];
                    auto st = init_udaf_context(fn.fid, fn.hdfs_location, fn.checksum, fn.aggregate_fn.symbol,
                                                _agg_fn_ctxs[i]);
                    RETURN_IF_ERROR(st);
                }
            }
            return Status::OK();
        });
        RETURN_IF_ERROR(promise_st->get_future().get());
    }

    // For SQL: select distinct id from table or select id from from table group by id;
    // we don't need to allocate memory for agg states.
    if (_is_only_group_by_columns) {
        TRY_CATCH_BAD_ALLOC(_init_agg_hash_variant(_hash_set_variant));
    } else {
        TRY_CATCH_BAD_ALLOC(_init_agg_hash_variant(_hash_map_variant));
    }

    {
        _agg_states_total_size = 16;
        _max_agg_state_align_size = 8;
        if (!_is_only_group_by_columns) {
            _hash_map_variant.visit([&](auto& variant) {
                auto& hash_map_with_key = *variant;
                using HashMapWithKey = std::remove_reference_t<decltype(hash_map_with_key)>;
                _agg_states_total_size = sizeof(typename HashMapWithKey::KeyType);
                _max_agg_state_align_size = alignof(typename HashMapWithKey::KeyType);
            });

            DCHECK_GT(_agg_fn_ctxs.size(), 0);
            _max_agg_state_align_size = std::max(_max_agg_state_align_size, _agg_functions[0]->alignof_size());
            _agg_states_total_size += PAD(_agg_states_total_size, _agg_functions[0]->alignof_size());

            // compute agg state total size and offsets
            for (int i = 0; i < _agg_fn_ctxs.size(); ++i) {
                _agg_states_offsets[i] = _agg_states_total_size;
                _agg_states_total_size += _agg_functions[i]->size();
                _max_agg_state_align_size = std::max(_max_agg_state_align_size, _agg_functions[i]->alignof_size());

                // If not the last aggregate_state, we need pad it so that next aggregate_state will be aligned.
                if (i + 1 < _agg_fn_ctxs.size()) {
                    size_t next_state_align_size = _agg_functions[i + 1]->alignof_size();
                    // Extend total_size to next alignment requirement
                    // Add padding by rounding up '_agg_states_total_size' to be a multiplier of next_state_align_size.
                    _agg_states_total_size = ALIGN_TO(_agg_states_total_size, next_state_align_size);
                }
            }
            _agg_states_total_size = ALIGN_TO(_agg_states_total_size, _max_agg_state_align_size);
            _state_allocator.aggregate_key_size = _agg_states_total_size;
            _state_allocator.pool = _mem_pool.get();
        }
    }

    // AggregateFunction::create needs to call create in JNI,
    // but prepare is executed in bthread, which will cause the JNI code to crash

    if (_group_by_expr_ctxs.empty()) {
        _single_agg_state = _mem_pool->allocate_aligned(_agg_states_total_size, _max_agg_state_align_size);
        RETURN_IF_UNLIKELY_NULL(_single_agg_state, Status::MemoryAllocFailed("alloc single agg state failed"));
        auto call_agg_create = [this]() {
            size_t created = 0;
            try {
                for (int i = 0; i < _agg_functions.size(); i++) {
                    _agg_functions[i]->create(_agg_fn_ctxs[i], _single_agg_state + _agg_states_offsets[i]);
                    created++;
                }
            } catch (std::bad_alloc& e) {
                tls_thread_status.set_is_catched(false);
                for (int i = 0; i < created; i++) {
                    _agg_functions[i]->destroy(_agg_fn_ctxs[i], _single_agg_state + _agg_states_offsets[i]);
                }
                _single_agg_state = nullptr;
                return Status::MemoryLimitExceeded("aggregate::create allocate failed");
            }

            return Status::OK();
        };
        if (_has_udaf) {
            auto promise_st = call_function_in_pthread(state, call_agg_create);
            RETURN_IF_ERROR(promise_st->get_future().get());
        } else {
            RETURN_IF_ERROR(call_agg_create());
        }

        if (_agg_expr_ctxs.empty()) {
            return Status::InternalError("Invalid agg query plan");
        }
    }

    RETURN_IF_ERROR(check_has_error());

    _limited_buffer = std::make_unique<LimitedPipelineChunkBuffer<AggStatistics>>(
            _agg_stat, 1, config::local_exchange_buffer_mem_limit_per_driver,
            state->chunk_size() * config::streaming_agg_chunk_buffer_size);

    return Status::OK();
}

Status Aggregator::prepare(RuntimeState* state, ObjectPool* pool, RuntimeProfile* runtime_profile) {
    if (_is_prepared) {
        return Status::OK();
    }
    _is_prepared = true;
    _state = state;
    _pool = pool;
    _runtime_profile = runtime_profile;

    _limit = _params->limit;
    _needs_finalize = _params->needs_finalize;
    _streaming_preaggregation_mode = _params->streaming_preaggregation_mode;
    _intermediate_tuple_id = _params->intermediate_tuple_id;
    _output_tuple_id = _params->output_tuple_id;

    RETURN_IF_ERROR(Expr::create_expr_trees(_pool, _params->conjuncts, &_conjunct_ctxs, state, true));
    RETURN_IF_ERROR(Expr::create_expr_trees(_pool, _params->grouping_exprs, &_group_by_expr_ctxs, state, true));

    // add profile attributes
    if (!_params->sql_grouping_keys.empty()) {
        _runtime_profile->add_info_string("GroupingKeys", _params->sql_grouping_keys);
    }
    if (!_params->sql_aggregate_functions.empty()) {
        _runtime_profile->add_info_string("AggregateFunctions", _params->sql_aggregate_functions);
    }

    bool has_outer_join_child = _params->has_outer_join_child;

    size_t group_by_size = _group_by_expr_ctxs.size();
    _group_by_columns.resize(group_by_size);
    _group_by_types = _params->group_by_types;
    _has_nullable_key = _params->has_nullable_key;

    _tmp_agg_states.resize(_state->chunk_size());

    auto& aggregate_functions = _params->aggregate_functions;
    size_t agg_size = aggregate_functions.size();
    _agg_fn_ctxs.resize(agg_size);
    _agg_functions.resize(agg_size);
    _agg_expr_ctxs.resize(agg_size);
    _agg_input_columns.resize(agg_size);
    _agg_input_raw_columns.resize(agg_size);
    _agg_fn_types.resize(agg_size);
    _agg_states_offsets.resize(agg_size);
    _is_merge_funcs.resize(agg_size);
    _agg_fn_types = _params->agg_fn_types;

    for (int i = 0; i < agg_size; ++i) {
        const TExpr& desc = aggregate_functions[i];
        const TFunction& fn = desc.nodes[0].fn;
        const auto& agg_fn_type = _agg_fn_types[i];
        _is_merge_funcs[i] = aggregate_functions[i].nodes[0].agg_expr.is_merge_agg;

        // get function
        bool is_result_nullable = _is_agg_result_nullable(desc, agg_fn_type);
        RETURN_IF_ERROR(_create_aggregate_function(state, fn, is_result_nullable, &_agg_functions[i]));
        VLOG_ROW << "has_outer_join_child " << has_outer_join_child << ", is_result_nullable " << is_result_nullable;

        int node_idx = 0;
        for (int j = 0; j < desc.nodes[0].num_children; ++j) {
            ++node_idx;
            Expr* expr = nullptr;
            ExprContext* ctx = nullptr;
            RETURN_IF_ERROR(
                    Expr::create_tree_from_thrift_with_jit(_pool, desc.nodes, nullptr, &node_idx, &expr, &ctx, state));
            _agg_expr_ctxs[i].emplace_back(ctx);
        }

        // It is very critical, because for a count(*) or count(1) aggregation function, when it first be applied to
        // input data, the agg function needs no input columns; but when it is parted into two parts when query cache
        // enabled, the latter part after cache operator must always handle intermediate types, so the agg function
        // need at least one input column to store intermediate result.
        auto num_args = std::max<size_t>(1UL, desc.nodes[0].num_children);
        _agg_input_columns[i].resize(num_args);
        _agg_input_raw_columns[i].resize(num_args);
    }

    if (!_params->intermediate_aggr_exprs.empty()) {
        auto& aggr_exprs = _params->intermediate_aggr_exprs;
        _intermediate_agg_expr_ctxs.resize(agg_size);
        for (int i = 0; i < agg_size; ++i) {
            int node_idx = 0;
            Expr* expr = nullptr;
            ExprContext* ctx = nullptr;
            RETURN_IF_ERROR(Expr::create_tree_from_thrift_with_jit(_pool, aggr_exprs[i].nodes, nullptr, &node_idx,
                                                                   &expr, &ctx, state));
            _intermediate_agg_expr_ctxs[i].emplace_back(ctx);
        }
    }

    _mem_pool = std::make_unique<MemPool>();
    _is_only_group_by_columns = _agg_expr_ctxs.empty() && !_group_by_expr_ctxs.empty();

    _agg_stat = _pool->add(new AggStatistics(_runtime_profile));
    SCOPED_TIMER(_runtime_profile->total_time_counter());

    _intermediate_tuple_desc = state->desc_tbl().get_tuple_descriptor(_intermediate_tuple_id);
    _output_tuple_desc = state->desc_tbl().get_tuple_descriptor(_output_tuple_id);
    DCHECK_EQ(_intermediate_tuple_desc->slots().size(), _output_tuple_desc->slots().size());

    RETURN_IF_ERROR(Expr::prepare(_group_by_expr_ctxs, state));

    for (const auto& ctx : _agg_expr_ctxs) {
        RETURN_IF_ERROR(Expr::prepare(ctx, state));
    }

    for (const auto& ctx : _intermediate_agg_expr_ctxs) {
        RETURN_IF_ERROR(Expr::prepare(ctx, state));
    }

    RETURN_IF_ERROR(Expr::prepare(_conjunct_ctxs, state));

    // Initial for FunctionContext of every aggregate functions
    for (int i = 0; i < _agg_fn_ctxs.size(); ++i) {
        auto& agg_fn_type = _agg_fn_types[i];
        auto& agg_func = _agg_functions[i];
        TypeDescriptor return_type = agg_fn_type.result_type;
        std::vector<TypeDescriptor> arg_types = agg_fn_type.arg_typedescs;

        const AggStateDesc* agg_state_desc = nullptr;
        if (dynamic_cast<const AggStateUnion*>(agg_func)) {
            auto* agg_state_union = down_cast<const AggStateUnion*>(agg_func);
            agg_state_desc = agg_state_union->get_agg_state_desc();
        } else if (dynamic_cast<const AggStateMerge*>(agg_func)) {
            auto* agg_state_merge = down_cast<const AggStateMerge*>(agg_func);
            agg_state_desc = agg_state_merge->get_agg_state_desc();
        } else if (dynamic_cast<const AggStateIf*>(agg_func)) {
            auto* agg_state_if = down_cast<const AggStateIf*>(agg_func);
            agg_state_desc = agg_state_if->get_agg_state_desc();
        }

        if (agg_state_desc != nullptr) {
            return_type = agg_state_desc->get_return_type();
            arg_types = agg_state_desc->get_arg_types();
        }

        _agg_fn_ctxs[i] =
                FunctionContext::create_context(state, _mem_pool.get(), return_type, arg_types, agg_fn_type.is_distinct,
                                                agg_fn_type.is_asc_order, agg_fn_type.nulls_first);
        if (state->query_options().__isset.group_concat_max_len) {
            _agg_fn_ctxs[i]->set_group_concat_max_len(state->query_options().group_concat_max_len);
        }
        state->obj_pool()->add(_agg_fn_ctxs[i]);
        _agg_fn_ctxs[i]->set_mem_usage_counter(&_agg_state_mem_usage);
    }

    // save TFunction object
    _fns.reserve(_agg_fn_ctxs.size());
    for (int i = 0; i < _agg_fn_ctxs.size(); ++i) {
        _fns.emplace_back(aggregate_functions[i].nodes[0].fn);
    }

    // prepare for spiller
    if (spiller()) {
        RETURN_IF_ERROR(spiller()->prepare(state));
    }

    return Status::OK();
}

bool Aggregator::_is_agg_result_nullable(const TExpr& desc, const AggFunctionTypes& agg_func_type) {
    const TFunction& fn = desc.nodes[0].fn;
    // NOTE: For count, we cannot use agg_func_type since it's only mocked valeus.
    if (fn.name.function_name == FUNCTION_COUNT) {
        if (fn.arg_types.empty()) {
            return false;
        }
        return _params->has_outer_join_child || desc.nodes[0].has_nullable_child;
    } else {
        return agg_func_type.use_nullable_fn(_use_intermediate_as_output());
    }
}

Status Aggregator::_create_aggregate_function(starrocks::RuntimeState* state, const TFunction& fn,
                                              bool is_result_nullable, const AggregateFunction** ret) {
    std::vector<TypeDescriptor> arg_types;
    for (auto& type : fn.arg_types) {
        arg_types.push_back(TypeDescriptor::from_thrift(type));
    }

    // check whether it's _merge/_union combinator if it contains agg state type
    auto& func_name = fn.name.function_name;
    if (fn.__isset.agg_state_desc) {
        auto agg_state_desc = AggStateDesc::from_thrift(fn.agg_state_desc);
        auto nested_func_name = agg_state_desc.get_func_name();
        bool isMergeOrUnion = nested_func_name + AGG_STATE_MERGE_SUFFIX == func_name ||
                              nested_func_name + AGG_STATE_UNION_SUFFIX == func_name;
        if (arg_types.size() != 1 && isMergeOrUnion) {
            return Status::InternalError(strings::Substitute("Invalid agg function plan: $0 with (arg type $1)",
                                                             func_name, arg_types.size()));
        }

        if (nested_func_name + AGG_STATE_MERGE_SUFFIX == func_name) {
            // aggregate _merge combinator
            auto* nested_func = AggStateDesc::get_agg_state_func(&agg_state_desc);
            if (nested_func == nullptr) {
                return Status::InternalError(
                        strings::Substitute("Merge combinator function $0 fails to get the nested agg func: $1 ",
                                            func_name, nested_func_name));
            }
            auto merge_agg_func = std::make_shared<AggStateMerge>(std::move(agg_state_desc), nested_func);
            *ret = merge_agg_func.get();
            _combinator_function.emplace_back(std::move(merge_agg_func));
        } else if (nested_func_name + AGG_STATE_UNION_SUFFIX == func_name) {
            // aggregate _union combinator
            auto* nested_func = AggStateDesc::get_agg_state_func(&agg_state_desc);
            if (nested_func == nullptr) {
                return Status::InternalError(
                        strings::Substitute("Union combinator function $0 fails to get the nested agg func: $1 ",
                                            func_name, nested_func_name));
            }
            auto union_agg_func = std::make_shared<AggStateUnion>(std::move(agg_state_desc), nested_func);
            *ret = union_agg_func.get();
            _combinator_function.emplace_back(std::move(union_agg_func));
        } else if (nested_func_name + AGG_STATE_IF_SUFFIX == func_name) {
            // aggregate _if combinator
            auto* nested_func = AggStateDesc::get_agg_state_func(&agg_state_desc);
            if (nested_func == nullptr) {
                return Status::InternalError(
                        strings::Substitute("if combinator function $0 fails to get the nested agg func: $1 ",
                                            func_name, nested_func_name));
            }
            auto if_agg_func = std::make_shared<AggStateIf>(std::move(agg_state_desc), nested_func);
            *ret = if_agg_func.get();
            _combinator_function.emplace_back(std::move(if_agg_func));
        } else {
            return Status::InternalError(
                    strings::Substitute("Agg function combinator is not implemented: $0 ", func_name));
        }
    } else {
        // get function
        if (func_name == FUNCTION_COUNT) {
            auto* func = get_aggregate_function(FUNCTION_COUNT, TYPE_BIGINT, TYPE_BIGINT, is_result_nullable);
            if (func == nullptr) {
                return Status::InternalError(strings::Substitute("Invalid agg function plan: $0 ", func_name));
            }
            *ret = func;
        } else {
            TypeDescriptor return_type = TypeDescriptor::from_thrift(fn.ret_type);
            TypeDescriptor serde_type = TypeDescriptor::from_thrift(fn.aggregate_fn.intermediate_type);
            DCHECK_LE(1, fn.arg_types.size());
            TypeDescriptor arg_type = arg_types[0];
            auto* func = get_aggregate_function(func_name, return_type, arg_types, is_result_nullable, fn.binary_type,
                                                state->func_version());
            if (func == nullptr) {
                return Status::InternalError(strings::Substitute(
                        "Invalid agg function plan: $0 with (arg type $1, serde type $2, result type $3, nullable $4)",
                        func_name, type_to_string(arg_type.type), type_to_string(serde_type.type),
                        type_to_string(return_type.type), is_result_nullable ? "true" : "false"));
            }
            *ret = func;
            VLOG_ROW << "get agg function " << func->get_name() << " serde_type " << serde_type << " return_type "
                     << return_type;
        }
    }
    return Status::OK();
}

Status Aggregator::reset_state(starrocks::RuntimeState* state, const std::vector<ChunkPtr>& refill_chunks,
                               pipeline::Operator* refill_op, bool reset_sink_complete) {
    RETURN_IF_ERROR(_reset_state(state, reset_sink_complete));
    // begin_pending_reset_state just tells the Aggregator, the chunks are intermediate type, it should call
    // merge method of agg functions to process these chunks.
    begin_pending_reset_state();
    for (const auto& chunk : refill_chunks) {
        if (chunk == nullptr || chunk->is_empty()) {
            continue;
        }
        RETURN_IF_ERROR(refill_op->push_chunk(state, chunk));
    }
    end_pending_reset_state();
    return Status::OK();
}

Status Aggregator::_reset_state(RuntimeState* state, bool reset_sink_complete) {
    SCOPED_THREAD_LOCAL_STATE_ALLOCATOR_SETTER(_allocator.get());
    _is_ht_eos = false;
    _num_input_rows = 0;
    _is_prepared = false;
    _is_opened = false;
    if (reset_sink_complete) {
        _is_sink_complete = false;
    }
    _it_hash.reset();
    _num_rows_processed = 0;
    _num_pass_through_rows = 0;
    _num_rows_returned = 0;

    _limited_buffer->clear();

    _tmp_agg_states.assign(_tmp_agg_states.size(), nullptr);
    _streaming_selection.assign(_streaming_selection.size(), 0);

    DCHECK(_mem_pool != nullptr);
    // Note: we must free agg_states object before _mem_pool free_all;
    if (_group_by_expr_ctxs.empty()) {
        for (int i = 0; i < _agg_functions.size(); i++) {
            _agg_functions[i]->destroy(_agg_fn_ctxs[i], _single_agg_state + _agg_states_offsets[i]);
        }
    } else if (!_is_only_group_by_columns) {
        _release_agg_memory();
    }

    for (int i = 0; i < _agg_functions.size(); i++) {
        if (_agg_fn_ctxs[i]) {
            _agg_fn_ctxs[i]->release_mems();
        }
    }

    _mem_pool->free_all();
    _agg_state_mem_usage = 0;

    if (_group_by_expr_ctxs.empty()) {
        _single_agg_state = _mem_pool->allocate_aligned(_agg_states_total_size, _max_agg_state_align_size);
        for (int i = 0; i < _agg_functions.size(); i++) {
            _agg_functions[i]->create(_agg_fn_ctxs[i], _single_agg_state + _agg_states_offsets[i]);
        }
    } else if (_is_only_group_by_columns) {
        TRY_CATCH_BAD_ALLOC(_init_agg_hash_variant(_hash_set_variant));
    } else {
        TRY_CATCH_BAD_ALLOC(_init_agg_hash_variant(_hash_map_variant));
    }

    // _state_allocator holds the entries of the hash_map/hash_set, when iterating a hash_map/set, the _state_allocator
    // is used to access these entries, so we must reset the _state_allocator along with the hash_map/hash_set.
    _state_allocator.reset();
    return Status::OK();
}

Status Aggregator::spill_aggregate_data(RuntimeState* state, std::function<StatusOr<ChunkPtr>()> chunk_provider) {
    auto spiller = this->spiller();
    auto spill_channel = this->spill_channel();

    while (!spiller->is_full()) {
        auto chunk_with_st = chunk_provider();
        if (chunk_with_st.ok()) {
            if (!chunk_with_st.value()->is_empty()) {
                RETURN_IF_ERROR(
                        spiller->spill(state, chunk_with_st.value(), TRACKER_WITH_SPILLER_GUARD(state, spiller)));
            }
        } else if (chunk_with_st.status().is_end_of_file()) {
            // chunk_provider return eos means provider has output all data from hash_map/hash_set.
            // then we just return OK
            return Status::OK();
        } else {
            return chunk_with_st.status();
        }
    }

    spill_channel->add_spill_task(std::move(chunk_provider));

    return Status::OK();
}

void Aggregator::close(RuntimeState* state) {
    if (_is_closed) {
        return;
    }

    _is_closed = true;
    // Clear the buffer
    if (_limited_buffer != nullptr) {
        _limited_buffer->clear();
    }

    auto agg_close = [this, state]() {
        // _mem_pool is nullptr means prepare phase failed
        if (_mem_pool != nullptr) {
            // Note: we must free agg_states object before _mem_pool free_all;
            if (_single_agg_state != nullptr) {
                SCOPED_THREAD_LOCAL_STATE_ALLOCATOR_SETTER(_allocator.get());
                for (int i = 0; i < _agg_functions.size(); i++) {
                    _agg_functions[i]->destroy(_agg_fn_ctxs[i], _single_agg_state + _agg_states_offsets[i]);
                }
            } else if (!_is_only_group_by_columns) {
                _release_agg_memory();
            }

            _mem_pool->free_all();
        }

        for (int i = 0; i < _agg_functions.size(); i++) {
            if (_agg_fn_ctxs[i]) {
                _agg_fn_ctxs[i]->release_mems();
            }
        }

        if (_is_only_group_by_columns) {
            _hash_set_variant.reset();
        } else {
            _hash_map_variant.reset();
        }

        Expr::close(_group_by_expr_ctxs, state);
        for (const auto& i : _agg_expr_ctxs) {
            Expr::close(i, state);
        }
        Expr::close(_conjunct_ctxs, state);
        return Status::OK();
    };
    if (_has_udaf) {
        auto promise_st = call_function_in_pthread(state, agg_close);
        (void)promise_st->get_future().get();
    } else {
        (void)agg_close();
    }
}

bool Aggregator::is_chunk_buffer_empty() {
    return _limited_buffer->is_empty();
}

ChunkPtr Aggregator::poll_chunk_buffer() {
    auto notify = defer_notify_sink();
    return _limited_buffer->pull();
}

void Aggregator::offer_chunk_to_buffer(const ChunkPtr& chunk) {
    auto notify = defer_notify_source();
    _limited_buffer->push(chunk);
}

bool Aggregator::is_chunk_buffer_full() {
    return _limited_buffer->is_full();
}

bool Aggregator::should_expand_preagg_hash_tables(size_t prev_row_returned, size_t input_chunk_size, int64_t ht_mem,
                                                  int64_t ht_rows) const {
    // Need some rows in tables to have valid statistics.
    if (ht_rows == 0) {
        return true;
    }

    // Find the appropriate reduction factor in our table for the current hash table sizes.
    int cache_level = 0;
    while (cache_level + 1 < STREAMING_HT_MIN_REDUCTION_SIZE &&
           ht_mem >= STREAMING_HT_MIN_REDUCTION[cache_level + 1].min_ht_mem) {
        cache_level++;
    }

    // Compare the number of rows in the hash table with the number of input rows that
    // were aggregated into it. Exclude passed through rows from this calculation since
    // they were not in hash tables.
    const int64_t input_rows = prev_row_returned - input_chunk_size;
    const int64_t aggregated_input_rows = input_rows - _num_rows_returned;
    double current_reduction = static_cast<double>(aggregated_input_rows) / ht_rows;

    // inaccurate, which could lead to a divide by zero below.
    if (aggregated_input_rows <= 0) {
        return true;
    }
    // Extrapolate the current reduction factor (r) using the formula
    // R = 1 + (N / n) * (r - 1), where R is the reduction factor over the full input data
    // set, N is the number of input rows, excluding passed-through rows, and n is the
    // number of rows inserted or merged into the hash tables. This is a very rough
    // approximation but is good enough to be useful.
    double min_reduction = STREAMING_HT_MIN_REDUCTION[cache_level].streaming_ht_min_reduction;
    return current_reduction > min_reduction;
}

Status Aggregator::evaluate_agg_input_column(Chunk* chunk, std::vector<ExprContext*>& agg_expr_ctxs, int i) {
    SCOPED_TIMER(_agg_stat->expr_compute_timer);
    for (size_t j = 0; j < agg_expr_ctxs.size(); j++) {
        // _agg_input_raw_columns[i][j] != nullptr means this column has been evaluated
        if (_agg_input_raw_columns[i][j] != nullptr) {
            continue;
        }
        // For simplicity and don't change the overall processing flow,
        // We handle const column as normal data column
        // TODO(kks): improve const column aggregate later
        ASSIGN_OR_RETURN(auto&& col, agg_expr_ctxs[j]->evaluate(chunk));
        // if first column is const, we have to unpack it. Most agg function only has one arg, and treat it as non-const column
        if (j == 0) {
            _agg_input_columns[i][j] = ColumnHelper::unpack_and_duplicate_const_column(chunk->num_rows(), col);
        } else {
            // if function has at least two argument, unpack const column selectively
            // for function like corr, FE forbid second args to be const, we will always unpack const column for it
            // for function like percentile_disc, the second args is const, do not unpack it
            if (agg_expr_ctxs[j]->root()->is_constant()) {
                _agg_input_columns[i][j] = std::move(col);
            } else {
                _agg_input_columns[i][j] = ColumnHelper::unpack_and_duplicate_const_column(chunk->num_rows(), col);
            }
        }
        _agg_input_raw_columns[i][j] = _agg_input_columns[i][j].get();
    }
    return Status::OK();
}

Status Aggregator::compute_single_agg_state(Chunk* chunk, size_t chunk_size) {
    SCOPED_TIMER(_agg_stat->agg_function_compute_timer);
    bool use_intermediate = _use_intermediate_as_input();
    auto& agg_expr_ctxs = use_intermediate ? _intermediate_agg_expr_ctxs : _agg_expr_ctxs;

    for (size_t i = 0; i < _agg_fn_ctxs.size(); i++) {
        // evaluate arguments at i-th agg function
        RETURN_IF_ERROR(evaluate_agg_input_column(chunk, agg_expr_ctxs[i], i));
        SCOPED_THREAD_LOCAL_STATE_ALLOCATOR_SETTER(_allocator.get());
        // batch call update or merge for singe stage
        if (!_is_merge_funcs[i] && !use_intermediate) {
            _agg_functions[i]->update_batch_single_state_exception_safe(_agg_fn_ctxs[i], chunk_size,
                                                                        _agg_input_raw_columns[i].data(),
                                                                        _single_agg_state + _agg_states_offsets[i]);
        } else {
            DCHECK_GE(_agg_input_columns[i].size(), 1);
            _agg_functions[i]->merge_batch_single_state_exception_safe(_agg_fn_ctxs[i],
                                                                       _single_agg_state + _agg_states_offsets[i],
                                                                       _agg_input_columns[i][0].get(), 0, chunk_size);
        }
    }
    RETURN_IF_ERROR(check_has_error());
    return Status::OK();
}

Status Aggregator::compute_batch_agg_states(Chunk* chunk, size_t chunk_size) {
    SCOPED_TIMER(_agg_stat->agg_function_compute_timer);
    bool use_intermediate = _use_intermediate_as_input();
    auto& agg_expr_ctxs = use_intermediate ? _intermediate_agg_expr_ctxs : _agg_expr_ctxs;

    for (size_t i = 0; i < _agg_fn_ctxs.size(); i++) {
        // evaluate arguments at i-th agg function
        RETURN_IF_ERROR(evaluate_agg_input_column(chunk, agg_expr_ctxs[i], i));
        SCOPED_THREAD_LOCAL_STATE_ALLOCATOR_SETTER(_allocator.get());
        // batch call update or merge
        if (!_is_merge_funcs[i] && !use_intermediate) {
            _agg_functions[i]->update_batch_exception_safe(_agg_fn_ctxs[i], chunk_size, _agg_states_offsets[i],
                                                           _agg_input_raw_columns[i].data(), _tmp_agg_states.data());
        } else {
            DCHECK_GE(_agg_input_columns[i].size(), 1);
            _agg_functions[i]->merge_batch_exception_safe(_agg_fn_ctxs[i], _agg_input_columns[i][0]->size(),
                                                          _agg_states_offsets[i], _agg_input_columns[i][0].get(),
                                                          _tmp_agg_states.data());
        }
    }
    RETURN_IF_ERROR(check_has_error());
    return Status::OK();
}

Status Aggregator::compute_batch_agg_states_with_selection(Chunk* chunk, size_t chunk_size) {
    SCOPED_TIMER(_agg_stat->agg_function_compute_timer);
    bool use_intermediate = _use_intermediate_as_input();
    auto& agg_expr_ctxs = use_intermediate ? _intermediate_agg_expr_ctxs : _agg_expr_ctxs;

    for (size_t i = 0; i < _agg_fn_ctxs.size(); i++) {
        RETURN_IF_ERROR(evaluate_agg_input_column(chunk, agg_expr_ctxs[i], i));
        SCOPED_THREAD_LOCAL_STATE_ALLOCATOR_SETTER(_allocator.get());
        if (!_is_merge_funcs[i] && !use_intermediate) {
            _agg_functions[i]->update_batch_selectively_exception_safe(
                    _agg_fn_ctxs[i], chunk_size, _agg_states_offsets[i], _agg_input_raw_columns[i].data(),
                    _tmp_agg_states.data(), _streaming_selection);
        } else {
            DCHECK_GE(_agg_input_columns[i].size(), 1);
            _agg_functions[i]->merge_batch_selectively_exception_safe(
                    _agg_fn_ctxs[i], _agg_input_columns[i][0]->size(), _agg_states_offsets[i],
                    _agg_input_columns[i][0].get(), _tmp_agg_states.data(), _streaming_selection);
        }
    }
    RETURN_IF_ERROR(check_has_error());
    return Status::OK();
}

RuntimeFilter* Aggregator::build_in_filters(RuntimeState* state, RuntimeFilterBuildDescriptor* desc) {
    int expr_order = desc->build_expr_order();
    const auto& group_type_type = _group_by_types[expr_order].result_type.type;
    AggInRuntimeFilterBuilder in_builder(desc, group_type_type);
    return in_builder.build(this, state->obj_pool());
}

Status Aggregator::_evaluate_const_columns(int i) {
    // used for const columns.
    Columns const_columns;
    const_columns.reserve(_agg_expr_ctxs[i].size());
    for (auto& j : _agg_expr_ctxs[i]) {
        ASSIGN_OR_RETURN(auto col, j->root()->evaluate_const(j));
        const_columns.emplace_back(std::move(col));
    }
    _agg_fn_ctxs[i]->set_constant_columns(const_columns);
    return Status::OK();
}

Status Aggregator::convert_to_chunk_no_groupby(ChunkPtr* chunk) {
    SCOPED_TIMER(_agg_stat->get_results_timer);
    // TODO(kks): we should approve memory allocate here
    auto use_intermediate = _use_intermediate_as_output();
    Columns agg_result_column = _create_agg_result_columns(1, use_intermediate);
    SCOPED_THREAD_LOCAL_STATE_ALLOCATOR_SETTER(_allocator.get());
    if (!use_intermediate) {
        TRY_CATCH_BAD_ALLOC(_finalize_to_chunk(_single_agg_state, agg_result_column));
    } else {
        TRY_CATCH_BAD_ALLOC(_serialize_to_chunk(_single_agg_state, agg_result_column));
    }
    RETURN_IF_ERROR(check_has_error());
    // For agg function column is non-nullable and table is empty
    // sum(zero_row) should be null, not 0.
    if (UNLIKELY(_num_input_rows == 0 && _group_by_expr_ctxs.empty() && !use_intermediate)) {
        for (size_t i = 0; i < _agg_fn_types.size(); i++) {
            if (_agg_fn_types[i].is_nullable) {
                agg_result_column[i] = ColumnHelper::create_column(_agg_fn_types[i].result_type, true);
                agg_result_column[i]->append_default();
            }
        }
    }

    TupleDescriptor* tuple_desc = use_intermediate ? _intermediate_tuple_desc : _output_tuple_desc;

    ChunkPtr result_chunk = std::make_shared<Chunk>();
    for (size_t i = 0; i < agg_result_column.size(); i++) {
        result_chunk->append_column(std::move(agg_result_column[i]), tuple_desc->slots()[i]->id());
    }
    ++_num_rows_returned;
    ++_num_rows_processed;
    *chunk = std::move(result_chunk);
    _is_ht_eos = true;

    return Status::OK();
}

void Aggregator::process_limit(ChunkPtr* chunk) {
    if (_reached_limit()) {
        int64_t num_rows_over = _num_rows_returned - _limit;
        (*chunk)->set_num_rows((*chunk)->num_rows() - num_rows_over);
        COUNTER_SET(_agg_stat->rows_returned_counter, _limit);
        _is_ht_eos = true;
        LOG(INFO) << "Aggregate Node ReachedLimit " << _limit;
    }
}

Status Aggregator::evaluate_groupby_exprs(Chunk* chunk) {
    _set_passthrough(chunk->owner_info().is_passthrough());
    _reset_exprs();
    return _evaluate_group_by_exprs(chunk);
}

Status Aggregator::output_chunk_by_streaming(Chunk* input_chunk, ChunkPtr* chunk,
                                             bool force_use_intermediate_as_output) {
    return output_chunk_by_streaming(input_chunk, chunk, input_chunk->num_rows(), false,
                                     force_use_intermediate_as_output);
}

Status Aggregator::output_chunk_by_streaming(Chunk* input_chunk, ChunkPtr* chunk, size_t num_input_rows,
                                             bool use_selection, bool force_use_intermediate_as_output) {
    // The input chunk is already intermediate-typed, so there is no need to convert it again.
    // Only when the input chunk is input-typed, we should convert it into intermediate-typed chunk.
    // is_passthrough is on indicate that the chunk is input-typed.
    auto use_intermediate_as_input = _use_intermediate_as_input();
    const auto& slots = _intermediate_tuple_desc->slots();

    DCHECK(!use_selection || !_group_by_columns.empty());
    // If using selection, then `_group_by_columns` has been filtered by `_streaming_selection`, and input_chunk has
    // not been filtered yet. `input_chunk` is filtered by `_streaming_selection` after `evaluate_agg_fn_exprs`.
    const size_t num_rows = use_selection ? _group_by_columns[0]->size() : num_input_rows;

    // build group by columns
    ChunkPtr result_chunk = std::make_shared<Chunk>();
    for (size_t i = 0; i < _group_by_columns.size(); i++) {
        DCHECK_EQ(num_rows, _group_by_columns[i]->size());
        // materialize group by const columns
        if (_group_by_columns[i]->is_constant()) {
            auto res =
                    ColumnHelper::unfold_const_column(_group_by_types[i].result_type, num_rows, _group_by_columns[i]);
            result_chunk->append_column(std::move(res), slots[i]->id());
        } else {
            result_chunk->append_column(_group_by_columns[i], slots[i]->id());
        }
    }

    // build aggregate function values
    if (!_agg_fn_ctxs.empty()) {
        DCHECK(!_group_by_columns.empty());
        RETURN_IF_ERROR(evaluate_agg_fn_exprs(input_chunk));
        if (use_selection) {
            for (size_t i = 0; i < _agg_fn_ctxs.size(); i++) {
                for (auto& agg_input_column : _agg_input_columns[i]) {
                    // AggColumn and GroupColumn may be the same SharedPtr,
                    // If ColumnSize and ChunkSize are not equal,
                    // indicating that the Filter has been executed in GroupByColumn
                    // e.g.: select c1, count(distinct c1) from t1 group by c1;

                    // At present, the type of problem cannot be completely solved,
                    // and a new solution needs to be designed to solve it completely
                    if (agg_input_column != nullptr && agg_input_column->size() == num_input_rows) {
                        agg_input_column->filter(_streaming_selection);
                    }
                }
            }
        }

        Columns agg_result_column = _create_agg_result_columns(num_rows, true);
        for (size_t i = 0; i < _agg_fn_ctxs.size(); i++) {
            size_t id = _group_by_columns.size() + i;
            auto slot_id = slots[id]->id();
            if (_is_merge_funcs[i] || use_intermediate_as_input) {
                DCHECK(i < _agg_input_columns.size() && _agg_input_columns[i].size() >= 1);
                if (force_use_intermediate_as_output) {
                    if (agg_result_column[i]->is_nullable()) {
                        _agg_input_columns[i][0] = ColumnHelper::cast_to_nullable_column(_agg_input_columns[i][0]);
                    }
                }
                result_chunk->append_column(std::move(_agg_input_columns[i][0]), slot_id);
            } else {
                {
                    SCOPED_THREAD_LOCAL_STATE_ALLOCATOR_SETTER(_allocator.get());
                    _agg_functions[i]->convert_to_serialize_format(_agg_fn_ctxs[i], _agg_input_columns[i],
                                                                   result_chunk->num_rows(), &agg_result_column[i]);
                }
                result_chunk->append_column(std::move(agg_result_column[i]), slot_id);
            }
        }
        RETURN_IF_ERROR(check_has_error());
    }

    _num_pass_through_rows += result_chunk->num_rows();
    _num_rows_returned += result_chunk->num_rows();
    _num_rows_processed += result_chunk->num_rows();
    COUNTER_UPDATE(_agg_stat->pass_through_row_count, result_chunk->num_rows());
    *chunk = std::move(result_chunk);
    return Status::OK();
}

Status Aggregator::convert_to_spill_format(Chunk* input_chunk, ChunkPtr* chunk) {
    auto use_intermediate_as_input = _use_intermediate_as_input();
    size_t num_rows = input_chunk->num_rows();
    ChunkPtr result_chunk = std::make_shared<Chunk>();
    const auto& slots = _intermediate_tuple_desc->slots();
    // build group by column
    for (size_t i = 0; i < _group_by_columns.size(); i++) {
        DCHECK_EQ(num_rows, _group_by_columns[i]->size());
        // materialize group by const columns
        if (_group_by_columns[i]->is_constant()) {
            auto res =
                    ColumnHelper::unfold_const_column(_group_by_types[i].result_type, num_rows, _group_by_columns[i]);
            result_chunk->append_column(std::move(res), slots[i]->id());
        } else {
            result_chunk->append_column(_group_by_columns[i], slots[i]->id());
        }
    }

    if (!_agg_fn_ctxs.empty()) {
        DCHECK(!_group_by_columns.empty());

        RETURN_IF_ERROR(evaluate_agg_fn_exprs(input_chunk));

        const auto num_rows = _group_by_columns[0]->size();
        Columns agg_result_column = _create_agg_result_columns(num_rows, true);
        for (size_t i = 0; i < _agg_fn_ctxs.size(); i++) {
            size_t id = _group_by_columns.size() + i;
            auto slot_id = slots[id]->id();
            // If it is AGG stage 3/4, the input of AGG is the intermediate result type (merge/serilaze stage and merge/finalize stage),
            // and it can be directly converted to intermediate result type at this time
            if (_is_merge_funcs[i] || use_intermediate_as_input) {
                DCHECK(i < _agg_input_columns.size() && _agg_input_columns[i].size() >= 1);
                result_chunk->append_column(std::move(_agg_input_columns[i][0]), slot_id);
            } else {
                _agg_functions[i]->convert_to_serialize_format(_agg_fn_ctxs[i], _agg_input_columns[i],
                                                               result_chunk->num_rows(), &agg_result_column[i]);
                result_chunk->append_column(std::move(agg_result_column[i]), slot_id);
            }
        }
        RETURN_IF_ERROR(check_has_error());
    }
    _num_rows_processed += result_chunk->num_rows();
    *chunk = std::move(result_chunk);

    return Status::OK();
}

Status Aggregator::output_chunk_by_streaming_with_selection(Chunk* input_chunk, ChunkPtr* chunk,
                                                            bool force_use_intermediate_as_output) {
    // Streaming aggregate at least has one group by column
    const size_t num_input_rows = _group_by_columns[0]->size();
    for (auto& _group_by_column : _group_by_columns) {
        // Multi GroupColumn may be have the same SharedPtr
        // If ColumnSize and ChunkSize are not equal,
        // indicating that the Filter has been executed in previous GroupByColumn
        // e.g.: select c1, cast(c1 as int) from t1 group by c1, cast(c1 as int);

        // At present, the type of problem cannot be completely solved,
        // and a new solution needs to be designed to solve it completely
        if (_group_by_column->size() == num_input_rows) {
            _group_by_column->filter(_streaming_selection);
        }
    }

    RETURN_IF_ERROR(
            output_chunk_by_streaming(input_chunk, chunk, num_input_rows, true, force_use_intermediate_as_output));
    return Status::OK();
}

void Aggregator::try_convert_to_two_level_map() {
    auto current_size = _hash_map_variant.reserved_memory_usage(mem_pool());
    if (current_size > get_two_level_threahold()) {
        _hash_map_variant.convert_to_two_level(_state);
    }
}

void Aggregator::try_convert_to_two_level_set() {
    auto current_size = _hash_set_variant.reserved_memory_usage(mem_pool());
    if (current_size > get_two_level_threahold()) {
        _hash_set_variant.convert_to_two_level(_state);
    }
}

Status Aggregator::check_has_error() {
    for (const auto* ctx : _agg_fn_ctxs) {
        if (ctx->has_error()) {
            return Status::RuntimeError(ctx->error_msg());
        }
    }
    return Status::OK();
}

// When need finalize, create column by result type
// otherwise, create column by serde type
Columns Aggregator::_create_agg_result_columns(size_t num_rows, bool use_intermediate) {
    Columns agg_result_columns(_agg_fn_types.size());

    if (!use_intermediate) {
        for (size_t i = 0; i < _agg_fn_types.size(); ++i) {
            // For count, count distinct, bitmap_union_int such as never return null function,
            // we need to create a not-nullable column.
            agg_result_columns[i] = ColumnHelper::create_column(_agg_fn_types[i].result_type,
                                                                _agg_fn_types[i].is_result_nullable<false>());
            agg_result_columns[i]->reserve(num_rows);
        }
    } else {
        for (size_t i = 0; i < _agg_fn_types.size(); ++i) {
            agg_result_columns[i] = ColumnHelper::create_column(_agg_fn_types[i].serde_type,
                                                                _agg_fn_types[i].is_result_nullable<true>());
            agg_result_columns[i]->reserve(num_rows);
        }
    }
    return agg_result_columns;
}

Columns Aggregator::_create_group_by_columns(size_t num_rows) const {
    Columns group_by_columns(_group_by_types.size());
    for (size_t i = 0; i < _group_by_types.size(); ++i) {
        group_by_columns[i] =
                ColumnHelper::create_column(_group_by_types[i].result_type, _group_by_types[i].is_nullable);
        group_by_columns[i]->reserve(num_rows);
    }
    return group_by_columns;
}

void Aggregator::_serialize_to_chunk(ConstAggDataPtr __restrict state, Columns& agg_result_columns) {
    for (size_t i = 0; i < _agg_fn_ctxs.size(); i++) {
        _agg_functions[i]->serialize_to_column(_agg_fn_ctxs[i], state + _agg_states_offsets[i],
                                               agg_result_columns[i].get());
    }
}

void Aggregator::_finalize_to_chunk(ConstAggDataPtr __restrict state, Columns& agg_result_columns) {
    for (size_t i = 0; i < _agg_fn_ctxs.size(); i++) {
        _agg_functions[i]->finalize_to_column(_agg_fn_ctxs[i], state + _agg_states_offsets[i],
                                              agg_result_columns[i].get());
    }
}

void Aggregator::_destroy_state(AggDataPtr __restrict state) {
    SCOPED_THREAD_LOCAL_STATE_ALLOCATOR_SETTER(_allocator.get());
    for (size_t i = 0; i < _agg_fn_ctxs.size(); i++) {
        _agg_functions[i]->destroy(_agg_fn_ctxs[i], state + _agg_states_offsets[i]);
    }
}

ChunkPtr Aggregator::_build_output_chunk(const Columns& group_by_columns, const Columns& agg_result_columns,
                                         bool use_intermediate_as_output) {
    ChunkPtr result_chunk = std::make_shared<Chunk>();
    // For different agg phase, we should use different TupleDescriptor
    if (!use_intermediate_as_output) {
        for (size_t i = 0; i < group_by_columns.size(); i++) {
            result_chunk->append_column(group_by_columns[i], _output_tuple_desc->slots()[i]->id());
        }
        for (size_t i = 0; i < agg_result_columns.size(); i++) {
            size_t id = group_by_columns.size() + i;
            result_chunk->append_column(agg_result_columns[i], _output_tuple_desc->slots()[id]->id());
        }
    } else {
        for (size_t i = 0; i < group_by_columns.size(); i++) {
            result_chunk->append_column(group_by_columns[i], _intermediate_tuple_desc->slots()[i]->id());
        }
        for (size_t i = 0; i < agg_result_columns.size(); i++) {
            size_t id = group_by_columns.size() + i;
            result_chunk->append_column(agg_result_columns[i], _intermediate_tuple_desc->slots()[id]->id());
        }
    }
    return result_chunk;
}

void Aggregator::_reset_exprs() {
    SCOPED_TIMER(_agg_stat->expr_release_timer);
    for (auto& _group_by_column : _group_by_columns) {
        _group_by_column = nullptr;
    }

    for (size_t i = 0; i < _agg_input_columns.size(); i++) {
        for (size_t j = 0; j < _agg_input_columns[i].size(); j++) {
            _agg_input_columns[i][j] = nullptr;
            _agg_input_raw_columns[i][j] = nullptr;
        }
    }
}

Status Aggregator::_evaluate_group_by_exprs(Chunk* chunk) {
    SCOPED_TIMER(_agg_stat->expr_compute_timer);
    // Compute group by columns
    for (size_t i = 0; i < _group_by_expr_ctxs.size(); i++) {
        ASSIGN_OR_RETURN(_group_by_columns[i], _group_by_expr_ctxs[i]->evaluate(chunk));
        DCHECK(_group_by_columns[i] != nullptr);
        if (_group_by_columns[i]->is_constant()) {
            // All hash table could handle only null, and we don't know the real data
            // type for only null column, so we don't unpack it.
            if (!_group_by_columns[i]->only_null()) {
                auto* const_column = static_cast<ConstColumn*>(_group_by_columns[i].get());
                const_column->data_column()->assign(chunk->num_rows(), 0);
                _group_by_columns[i] = const_column->data_column();
            }
        }
        // Scalar function compute will return non-nullable column
        // for nullable column when the real whole chunk data all not-null.
        if (_group_by_types[i].is_nullable && !_group_by_columns[i]->is_nullable()) {
            // TODO: optimized the memory usage
            _group_by_columns[i] =
                    NullableColumn::create(_group_by_columns[i], NullColumn::create(_group_by_columns[i]->size(), 0));
        } else if (!_group_by_types[i].is_nullable && _group_by_columns[i]->is_nullable()) {
            return Status::InternalError(fmt::format("error nullablel column, index: {}, slot: {}", i,
                                                     _group_by_expr_ctxs[i]->root()->debug_string()));
        }
    }

    return Status::OK();
}

Status Aggregator::evaluate_agg_fn_exprs(Chunk* chunk) {
    bool use_intermediate = _use_intermediate_as_input();
    return evaluate_agg_fn_exprs(chunk, use_intermediate);
}

Status Aggregator::evaluate_agg_fn_exprs(Chunk* chunk, bool use_intermediate) {
    auto& agg_expr_ctxs = use_intermediate ? _intermediate_agg_expr_ctxs : _agg_expr_ctxs;
    for (size_t i = 0; i < agg_expr_ctxs.size(); i++) {
        RETURN_IF_ERROR(evaluate_agg_input_column(chunk, agg_expr_ctxs[i], i));
    }
    return Status::OK();
}

bool is_group_columns_fixed_size(std::vector<ExprContext*>& group_by_expr_ctxs, std::vector<ColumnType>& group_by_types,
                                 size_t* max_size, bool* has_null) {
    size_t size = 0;
    *has_null = false;

    for (size_t i = 0; i < group_by_expr_ctxs.size(); i++) {
        ExprContext* ctx = group_by_expr_ctxs[i];
        if (group_by_types[i].is_nullable) {
            *has_null = true;
            size += 1; // 1 bytes for  null flag.
        }
        LogicalType ltype = ctx->root()->type().type;
        if (ctx->root()->type().is_complex_type()) {
            return false;
        }
        size_t byte_size = get_size_of_fixed_length_type(ltype);
        if (byte_size == 0) return false;
        size += byte_size;
    }
    *max_size = size;
    return true;
}

template <typename HashVariantType>
void Aggregator::_init_agg_hash_variant(HashVariantType& hash_variant) {
    auto type = _aggr_phase == AggrPhase1 ? HashVariantType::Type::phase1_slice : HashVariantType::Type::phase2_slice;
    if (_group_by_expr_ctxs.size() == 1) {
        type = HashVariantResolver<HashVariantType>::instance().get_unary_type(
                _aggr_phase, _group_by_types[0].result_type.type, _has_nullable_key);
    }

    bool has_null_column = false;
    int fixed_byte_size = 0;
    // this optimization don't need to be limited to multi-column group by.
    // single column like float/double/decimal/largeint could also be applied to.
    if (type == HashVariantType::Type::phase1_slice || type == HashVariantType::Type::phase2_slice) {
        size_t max_size = 0;
        if (is_group_columns_fixed_size(_group_by_expr_ctxs, _group_by_types, &max_size, &has_null_column)) {
            // we need reserve a byte for serialization length for nullable columns
            if (max_size < 4 || (!has_null_column && max_size == 4)) {
                type = _aggr_phase == AggrPhase1 ? HashVariantType::Type::phase1_slice_fx4
                                                 : HashVariantType::Type::phase2_slice_fx4;
            } else if (max_size < 8 || (!has_null_column && max_size == 8)) {
                type = _aggr_phase == AggrPhase1 ? HashVariantType::Type::phase1_slice_fx8
                                                 : HashVariantType::Type::phase2_slice_fx8;
            } else if (max_size < 16 || (!has_null_column && max_size == 16)) {
                type = _aggr_phase == AggrPhase1 ? HashVariantType::Type::phase1_slice_fx16
                                                 : HashVariantType::Type::phase2_slice_fx16;
            }
            if (!has_null_column) {
                fixed_byte_size = max_size;
            }
        }
    }

    VLOG_ROW << "hash type is "
             << static_cast<typename std::underlying_type<typename HashVariantType::Type>::type>(type);
    hash_variant.init(_state, type, _agg_stat);

    hash_variant.visit([&](auto& variant) {
        if constexpr (is_combined_fixed_size_key<std::decay_t<decltype(*variant)>>) {
            variant->has_null_column = has_null_column;
            variant->fixed_byte_size = fixed_byte_size;
        }
    });
}

void Aggregator::build_hash_map(size_t chunk_size, bool agg_group_by_with_limit) {
    if (agg_group_by_with_limit) {
        if (_hash_map_variant.size() >= _limit) {
            build_hash_map_with_selection(chunk_size);
            return;
        } else {
            _streaming_selection.assign(chunk_size, 0);
        }
    }

    _hash_map_variant.visit([&](auto& hash_map_with_key) {
        using MapType = std::remove_reference_t<decltype(*hash_map_with_key)>;
        hash_map_with_key->build_hash_map(chunk_size, _group_by_columns, _mem_pool.get(), AllocateState<MapType>(this),
                                          &_tmp_agg_states);
    });
}

void Aggregator::build_hash_map(size_t chunk_size, std::atomic<int64_t>& shared_limit_countdown,
                                bool agg_group_by_with_limit) {
    if (agg_group_by_with_limit && _params->enable_pipeline_share_limit) {
        _build_hash_map_with_shared_limit(chunk_size, shared_limit_countdown);
        return;
    }
    build_hash_map(chunk_size, agg_group_by_with_limit);
}

void Aggregator::_build_hash_map_with_shared_limit(size_t chunk_size, std::atomic<int64_t>& shared_limit_countdown) {
    auto start_size = _hash_map_variant.size();
    if (_hash_map_variant.size() >= _limit || shared_limit_countdown.load(std::memory_order_relaxed) <= 0) {
        build_hash_map_with_selection(chunk_size);
        return;
    } else {
        _streaming_selection.resize(chunk_size);
    }
    _hash_map_variant.visit([&](auto& hash_map_with_key) {
        using MapType = std::remove_reference_t<decltype(*hash_map_with_key)>;
        hash_map_with_key->build_hash_map_with_limit(chunk_size, _group_by_columns, _mem_pool.get(),
                                                     AllocateState<MapType>(this), &_tmp_agg_states,
                                                     &_streaming_selection, _limit);
    });
    shared_limit_countdown.fetch_sub(_hash_map_variant.size() - start_size, std::memory_order_relaxed);
}

void Aggregator::build_hash_map_with_selection(size_t chunk_size) {
    _hash_map_variant.visit([&](auto& hash_map_with_key) {
        using MapType = std::remove_reference_t<decltype(*hash_map_with_key)>;
        hash_map_with_key->build_hash_map_with_selection(chunk_size, _group_by_columns, _mem_pool.get(),
                                                         AllocateState<MapType>(this), &_tmp_agg_states,
                                                         &_streaming_selection);
    });
}

// When meets not found group keys, mark the first pos into `_streaming_selection` and insert into the hashmap
// so the following group keys(same as the first not found group keys) are not marked as non-founded.
// This can be used for stream mv so no need to find multi times for the same non-found group keys.
void Aggregator::build_hash_map_with_selection_and_allocation(size_t chunk_size, bool agg_group_by_with_limit) {
    _hash_map_variant.visit([&](auto& hash_map_with_key) {
        using MapType = std::remove_reference_t<decltype(*hash_map_with_key)>;
        hash_map_with_key->build_hash_map_with_selection_and_allocation(chunk_size, _group_by_columns, _mem_pool.get(),
                                                                        AllocateState<MapType>(this), &_tmp_agg_states,
                                                                        &_streaming_selection);
    });
}

Status Aggregator::convert_hash_map_to_chunk(int32_t chunk_size, ChunkPtr* chunk,
                                             bool force_use_intermediate_as_output) {
    SCOPED_TIMER(_agg_stat->get_results_timer);

    RETURN_IF_ERROR(_hash_map_variant.visit([&](auto& variant_value) {
        auto& hash_map_with_key = *variant_value;
        using HashMapWithKey = std::remove_reference_t<decltype(hash_map_with_key)>;

        auto it = std::any_cast<RawHashTableIterator>(_it_hash);
        auto end = _state_allocator.end();

        const auto hash_map_size = _hash_map_variant.size();
        auto num_rows = std::min<size_t>(hash_map_size - _num_rows_processed, chunk_size);
        auto use_intermediate = force_use_intermediate_as_output || _use_intermediate_as_output();
        Columns group_by_columns = _create_group_by_columns(num_rows);
        Columns agg_result_columns = _create_agg_result_columns(num_rows, use_intermediate);

        int32_t read_index = 0;
        {
            SCOPED_TIMER(_agg_stat->iter_timer);
            hash_map_with_key.results.resize(chunk_size);
            // get key/value from hashtable
            while ((it != end) & (read_index < chunk_size)) {
                auto* value = it.value();
                hash_map_with_key.results[read_index] = *reinterpret_cast<typename HashMapWithKey::KeyType*>(value);
                _tmp_agg_states[read_index] = value;
                ++read_index;
                it.next();
            }
        }

        if (read_index > 0) {
            {
                SCOPED_TIMER(_agg_stat->group_by_append_timer);
                hash_map_with_key.insert_keys_to_columns(hash_map_with_key.results, group_by_columns, read_index);
            }

            {
                SCOPED_TIMER(_agg_stat->agg_append_timer);
                SCOPED_THREAD_LOCAL_STATE_ALLOCATOR_SETTER(_allocator.get());
                if (!use_intermediate) {
                    for (size_t i = 0; i < _agg_fn_ctxs.size(); i++) {
                        TRY_CATCH_BAD_ALLOC(_agg_functions[i]->batch_finalize(_agg_fn_ctxs[i], read_index,
                                                                              _tmp_agg_states, _agg_states_offsets[i],
                                                                              agg_result_columns[i].get()));
                    }
                } else {
                    for (size_t i = 0; i < _agg_fn_ctxs.size(); i++) {
                        TRY_CATCH_BAD_ALLOC(_agg_functions[i]->batch_serialize(_agg_fn_ctxs[i], read_index,
                                                                               _tmp_agg_states, _agg_states_offsets[i],
                                                                               agg_result_columns[i].get()));
                    }
                }
            }
        }

        RETURN_IF_ERROR(check_has_error());
        _is_ht_eos = (it == end);

        // If there is null key, output it last
        if constexpr (HashMapWithKey::has_single_null_key) {
            if (_is_ht_eos && hash_map_with_key.null_key_data != nullptr) {
                // The output chunk size couldn't larger than _state->chunk_size()
                if (read_index < _state->chunk_size()) {
                    // For multi group by key, we don't need to special handle null key
                    DCHECK(group_by_columns.size() == 1);
                    DCHECK(group_by_columns[0]->is_nullable());
                    group_by_columns[0]->append_default();
                    SCOPED_THREAD_LOCAL_STATE_ALLOCATOR_SETTER(_allocator.get());
                    if (!use_intermediate) {
                        TRY_CATCH_BAD_ALLOC(_finalize_to_chunk(hash_map_with_key.null_key_data, agg_result_columns));
                    } else {
                        TRY_CATCH_BAD_ALLOC(_serialize_to_chunk(hash_map_with_key.null_key_data, agg_result_columns));
                    }
                    RETURN_IF_ERROR(check_has_error());
                    ++read_index;
                } else {
                    // Output null key in next round
                    _is_ht_eos = false;
                }
            }
        }

        _it_hash = it;
        auto result_chunk = _build_output_chunk(group_by_columns, agg_result_columns, use_intermediate);
        _num_rows_returned += read_index;
        _num_rows_processed += read_index;
        *chunk = std::move(result_chunk);

        return Status::OK();
    }));

    return Status::OK();
}

void Aggregator::build_hash_set(size_t chunk_size) {
    _hash_set_variant.visit(
            [&](auto& hash_set) { hash_set->build_hash_set(chunk_size, _group_by_columns, _mem_pool.get()); });
}

void Aggregator::build_hash_set_with_selection(size_t chunk_size) {
    _hash_set_variant.visit([&](auto& hash_set) {
        hash_set->build_hash_set_with_selection(chunk_size, _group_by_columns, _mem_pool.get(), &_streaming_selection);
    });
}

void Aggregator::convert_hash_set_to_chunk(int32_t chunk_size, ChunkPtr* chunk) {
    SCOPED_TIMER(_agg_stat->get_results_timer);

    _hash_set_variant.visit([&](auto& variant_value) {
        auto& hash_set = *variant_value;
        using HashSetWithKey = std::remove_reference_t<decltype(hash_set)>;
        using Iterator = typename HashSetWithKey::Iterator;
        auto it = std::any_cast<Iterator>(_it_hash);
        auto end = hash_set.hash_set.end();
        const auto hash_set_size = _hash_set_variant.size();
        auto num_rows = std::min<size_t>(hash_set_size - _num_rows_processed, chunk_size);
        Columns group_by_columns = _create_group_by_columns(num_rows);

        // Computer group by columns and aggregate result column
        int32_t read_index = 0;
        hash_set.results.resize(chunk_size);
        while (it != end && read_index < chunk_size) {
            // hash_set.insert_key_to_columns(*it, group_by_columns);
            hash_set.results[read_index] = *it;
            ++read_index;
            ++it;
        }

        {
            SCOPED_TIMER(_agg_stat->group_by_append_timer);
            hash_set.insert_keys_to_columns(hash_set.results, group_by_columns, read_index);
        }

        _is_ht_eos = (it == end);

        // IF there is null key, output it last
        if constexpr (HashSetWithKey::has_single_null_key) {
            if (_is_ht_eos && hash_set.has_null_key) {
                // The output chunk size couldn't larger than _state->chunk_size()
                if (read_index < _state->chunk_size()) {
                    // For multi group by key, we don't need to special handle null key
                    DCHECK(group_by_columns.size() == 1);
                    DCHECK(group_by_columns[0]->is_nullable());
                    group_by_columns[0]->append_default();
                    ++read_index;
                } else {
                    // Output null key in next round
                    _is_ht_eos = false;
                }
            }
        }

        _it_hash = it;

        ChunkPtr result_chunk = std::make_shared<Chunk>();
        // For different agg phase, we should use different TupleDescriptor
        auto use_intermediate = _use_intermediate_as_output();
        if (!use_intermediate) {
            for (size_t i = 0; i < group_by_columns.size(); i++) {
                result_chunk->append_column(group_by_columns[i], _output_tuple_desc->slots()[i]->id());
            }
        } else {
            for (size_t i = 0; i < group_by_columns.size(); i++) {
                result_chunk->append_column(group_by_columns[i], _intermediate_tuple_desc->slots()[i]->id());
            }
        }
        _num_rows_returned += read_index;
        _num_rows_processed += read_index;
        *chunk = std::move(result_chunk);
    });
}

void Aggregator::_release_agg_memory() {
    // If all function states are of POD type,
    // then we don't have to traverse the hash table to call destroy method.
    //
    SCOPED_THREAD_LOCAL_STATE_ALLOCATOR_SETTER(_allocator.get());
    _hash_map_variant.visit([&](auto& hash_map_with_key) {
        bool skip_destroy = std::all_of(_agg_functions.begin(), _agg_functions.end(),
                                        [](auto* func) { return func->is_pod_state(); });
        if (hash_map_with_key != nullptr && !skip_destroy) {
            auto null_data_ptr = hash_map_with_key->get_null_key_data();
            if (null_data_ptr != nullptr) {
                for (int i = 0; i < _agg_functions.size(); i++) {
                    _agg_functions[i]->destroy(_agg_fn_ctxs[i], null_data_ptr + _agg_states_offsets[i]);
                }
            }
            auto it = _state_allocator.begin();
            auto end = _state_allocator.end();

            while (it != end) {
                for (int i = 0; i < _agg_functions.size(); i++) {
                    _agg_functions[i]->destroy(_agg_fn_ctxs[i], it.value() + _agg_states_offsets[i]);
                }
                it.next();
            }
        }
    });
}

} // namespace starrocks
