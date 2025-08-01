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


package com.starrocks.sql.ast;

import com.starrocks.sql.parser.NodePosition;

import static com.starrocks.common.util.Util.normalizeName;

public class RecoverDbStmt extends DdlStmt {
    private String catalog;
    private String dbName;

    public RecoverDbStmt(String dbName) {
        this(dbName, NodePosition.ZERO);
    }

    public RecoverDbStmt(String dbName, NodePosition pos) {
        super(pos);
        this.dbName = normalizeName(dbName);
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbname) {
        this.dbName = normalizeName(dbname);
    }

    public String getCatalogName() {
        return this.catalog;
    }

    public void setCatalogName(String catalogName) {
        this.catalog = normalizeName(catalogName);
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitRecoverDbStatement(this, context);
    }
}
