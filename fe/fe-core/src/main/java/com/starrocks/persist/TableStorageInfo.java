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

package com.starrocks.persist;

import com.google.gson.annotations.SerializedName;
import com.staros.proto.FilePathInfo;
import com.starrocks.common.io.Text;
import com.starrocks.common.io.Writable;
import com.starrocks.persist.gson.GsonPostProcessable;
import com.starrocks.persist.gson.GsonPreProcessable;
import com.starrocks.persist.gson.GsonUtils;

import java.io.DataInput;
import java.io.IOException;

public class TableStorageInfo implements Writable, GsonPreProcessable, GsonPostProcessable {
    @SerializedName(value = "tableId")
    private long tableId;

    private FilePathInfo filePathInfo;
    @SerializedName(value = "filePathInfoBytes")
    private byte[] filePathInfoBytes;

    public TableStorageInfo(long tableId, FilePathInfo filePathInfo) {
        this.tableId = tableId;
        this.filePathInfo = filePathInfo;
    }

    public long getTableId() {
        return tableId;
    }

    public FilePathInfo getFilePathInfo() {
        return filePathInfo;
    }

    @Override
    public void gsonPreProcess() throws IOException {
        if (filePathInfo != null) {
            filePathInfoBytes = filePathInfo.toByteArray();
        }
    }

    @Override
    public void gsonPostProcess() throws IOException {
        if (filePathInfoBytes != null) {
            filePathInfo = FilePathInfo.parseFrom(filePathInfoBytes);
        }
    }

    public static TableStorageInfo read(DataInput in) throws IOException {
        return GsonUtils.GSON.fromJson(Text.readString(in), TableStorageInfo.class);
    }
}
