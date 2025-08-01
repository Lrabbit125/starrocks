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

#include "exprs/binary_functions.h"

#include "column/binary_column.h"
#include "column/column_builder.h"
#include "column/column_helper.h"
#include "column/nullable_column.h"
#include "exprs/encryption_functions.h"
#include "exprs/function_helper.h"
#include "exprs/string_functions.h"

namespace starrocks {

// to_binary
StatusOr<ColumnPtr> BinaryFunctions::to_binary(FunctionContext* context, const Columns& columns) {
    auto state = reinterpret_cast<BinaryFormatState*>(context->get_function_state(FunctionContext::THREAD_LOCAL));
    auto& src_column = columns[0];
    const int size = src_column->size();
    ColumnBuilder<TYPE_VARBINARY> result(size);
    auto to_binary_type = state->to_binary_type;
    switch (to_binary_type) {
    case BinaryFormatType::UTF8:
        return src_column;
    case BinaryFormatType::ENCODE64:
        return EncryptionFunctions::from_base64(context, columns);
    default:
        return StringFunctions::unhex(context, columns);
    }
    return Status::OK();
}

// to_binary_prepare
Status BinaryFunctions::to_binary_prepare(FunctionContext* context, FunctionContext::FunctionStateScope scope) {
    if (scope != FunctionContext::THREAD_LOCAL) {
        return Status::OK();
    }
    auto* state = new BinaryFormatState();
    context->set_function_state(scope, state);

    if (!context->is_notnull_constant_column(1)) {
        return Status::OK();
    }

    auto column = context->get_constant_column(1);
    auto to_binary_type = ColumnHelper::get_const_value<TYPE_VARCHAR>(column);
    std::string to_binary_type_str = to_binary_type.to_string();
    state->to_binary_type = BinaryFormatState::to_binary_format(to_binary_type_str);

    return Status::OK();
}

Status BinaryFunctions::to_binary_close(FunctionContext* context, FunctionContext::FunctionStateScope scope) {
    if (scope == FunctionContext::THREAD_LOCAL) {
        auto* state = reinterpret_cast<BinaryFormatState*>(context->get_function_state(FunctionContext::THREAD_LOCAL));
        delete state;
    }
    return Status::OK();
}

// to_binary
StatusOr<ColumnPtr> BinaryFunctions::from_binary(FunctionContext* context, const Columns& columns) {
    auto state = reinterpret_cast<BinaryFormatState*>(context->get_function_state(FunctionContext::THREAD_LOCAL));
    auto& src_column = columns[0];
    const int size = src_column->size();
    ColumnBuilder<TYPE_VARBINARY> result(size);
    auto to_binary_type = state->to_binary_type;
    switch (to_binary_type) {
    case BinaryFormatType::UTF8:
        return src_column;
    case BinaryFormatType::ENCODE64:
        return EncryptionFunctions::to_base64(context, columns);
    default:
        return StringFunctions::hex_string(context, columns);
    }
    return Status::OK();
}

// to_binary_prepare
Status BinaryFunctions::from_binary_prepare(FunctionContext* context, FunctionContext::FunctionStateScope scope) {
    if (scope != FunctionContext::THREAD_LOCAL) {
        return Status::OK();
    }
    auto* state = new BinaryFormatState();
    context->set_function_state(scope, state);

    if (!context->is_notnull_constant_column(1)) {
        return Status::OK();
    }

    auto column = context->get_constant_column(1);
    auto to_binary_type = ColumnHelper::get_const_value<TYPE_VARCHAR>(column);
    std::string to_binary_type_str = to_binary_type.to_string();
    state->to_binary_type = BinaryFormatState::to_binary_format(to_binary_type_str);

    return Status::OK();
}

Status BinaryFunctions::from_binary_close(FunctionContext* context, FunctionContext::FunctionStateScope scope) {
    if (scope == FunctionContext::THREAD_LOCAL) {
        auto* state = reinterpret_cast<BinaryFormatState*>(context->get_function_state(FunctionContext::THREAD_LOCAL));
        delete state;
    }
    return Status::OK();
}

StatusOr<ColumnPtr> BinaryFunctions::iceberg_truncate_binary(FunctionContext* context, const Columns& columns) {
    ColumnPtr c0 = columns[0];
    ColumnPtr c1 = columns[1];
    NullColumnPtr null_flags;
    bool has_null = false;
    PREPARE_COLUMN_WITH_CONST_AND_NULL_FOR_ICEBERG_FUNC(c0, c1);
    (void)has_null;
    const int size = c0->size();
    int32_t width = c1->get(0).get_int32();
    uint8_t* raw_null_flags = null_flags->get_data().data();
    auto col = ColumnHelper::cast_to_raw<TYPE_BINARY>(c0);
    ColumnBuilder<TYPE_BINARY> result(size);
    auto& raw_c0 = col->get_proxy_data();

#define SLICE_SIZE_MIN(x, y) x < y ? x : y
    for (auto i = 0; i < size; i++) {
        if (raw_null_flags[i]) {
            result.append_null();
        } else {
            Slice src_value = raw_c0[i];
            result.append(Slice(src_value.get_data(), SLICE_SIZE_MIN(width, src_value.get_size())));
        }
    }
#undef SLICE_SIZE_MIN
    return result.build(ColumnHelper::is_all_const(columns));
}

} // namespace starrocks

#include "gen_cpp/opcode/BinaryFunctions.inc"
