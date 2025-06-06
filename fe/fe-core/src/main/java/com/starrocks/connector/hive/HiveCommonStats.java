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

package com.starrocks.connector.hive;

public class HiveCommonStats {
    private static final HiveCommonStats EMPTY = new HiveCommonStats(-1, -1, 0);

    // Row num is first obtained from the table or partition's parameters.
    // If the num is null or -1, it will be estimated from total size of the partition or table's files.
    private final long rowNums;

    private final long totalFileBytes;

    private final long numFiles;

    public HiveCommonStats(long rowNums, long totalSize, long numFiles) {
        this.rowNums = rowNums;
        this.totalFileBytes = totalSize;
        this.numFiles = numFiles;
    }

    public static HiveCommonStats empty() {
        return EMPTY;
    }

    public long getRowNums() {
        return rowNums;
    }

    public long getTotalFileBytes() {
        return totalFileBytes;
    }

    public long getNumFiles() {
        return numFiles;
    }

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("HiveCommonStats{");
        sb.append("rowNums=").append(rowNums);
        sb.append(", totalFileBytes=").append(totalFileBytes);
        sb.append(", numFiles=").append(numFiles);
        sb.append('}');
        return sb.toString();
    }
}
