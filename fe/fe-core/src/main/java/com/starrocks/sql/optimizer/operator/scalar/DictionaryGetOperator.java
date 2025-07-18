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


package com.starrocks.sql.optimizer.operator.scalar;

import com.google.common.collect.Lists;
import com.starrocks.catalog.Type;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.operator.OperatorType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DictionaryGetOperator extends ArgsScalarOperator {
    private long dictionaryId;
    private long dictionaryTxnId;
    private int keySize;
    private boolean nullIfNotExist;

    public DictionaryGetOperator(List<ScalarOperator> arguments, Type returnType, long dictionaryId, long dictionaryTxnId,
                                  int keySize, boolean nullIfNotExist) {
        super(OperatorType.DICTIONARY_GET, returnType);
        this.arguments = new ArrayList<>(arguments);
        this.dictionaryId = dictionaryId;
        this.dictionaryTxnId = dictionaryTxnId;
        this.keySize = keySize;
        this.nullIfNotExist = nullIfNotExist;
        incrDepth(arguments);
    }

    @Override
    public boolean isNullable() {
        return false;
    }

    @Override
    public String toString() {
        return "DICTIONARY_GET";
    }

    @Override
    public int hashCodeSelf() {
        return Objects.hash(getType(), dictionaryId, dictionaryTxnId, keySize);
    }

    @Override
    public boolean equalsSelf(Object other) {
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        if (this == other) {
            return true;
        }
        final DictionaryGetOperator dictionaryOp = (DictionaryGetOperator) other;
        return dictionaryOp.getType().equals(getType()) &&
               this.dictionaryId == dictionaryOp.getDictionaryId() &&
               this.dictionaryTxnId == dictionaryOp.getDictionaryTxnId() &&
               this.keySize == dictionaryOp.getKeySize();
    }

    @Override
    public ScalarOperator clone() {
        DictionaryGetOperator operator = (DictionaryGetOperator) super.clone();
        // Deep copy here
        List<ScalarOperator> newArguments = Lists.newArrayList();
        this.arguments.forEach(p -> newArguments.add(p.clone()));
        operator.arguments = newArguments;
        operator.dictionaryId = this.dictionaryId;
        operator.dictionaryTxnId = this.dictionaryTxnId;
        operator.keySize = this.keySize;
        return operator;
    }

    @Override
    public ColumnRefSet getUsedColumns() {
        ColumnRefSet used = new ColumnRefSet();
        for (ScalarOperator child : arguments) {
            used.union(child.getUsedColumns());
        }
        return used;
    }

    public long getDictionaryId() {
        return this.dictionaryId;
    }

    public long getDictionaryTxnId() {
        return this.dictionaryTxnId;
    }

    public int getKeySize() {
        return this.keySize;
    }

    public boolean getNullIfNotExist() {
        return this.nullIfNotExist;
    }

    @Override
    public <R, C> R accept(ScalarOperatorVisitor<R, C> visitor, C context) {
        return visitor.visitDictionaryGetOperator(this, context);
    }

}