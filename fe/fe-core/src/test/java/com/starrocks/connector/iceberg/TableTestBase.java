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

package com.starrocks.connector.iceberg;

import com.google.common.collect.ImmutableMap;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileMetadata;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ManifestWriter;
import org.apache.iceberg.Metrics;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;

import static org.apache.iceberg.types.Types.NestedField.required;

public class TableTestBase {
    // Schema passed to create tables
    public static final Schema SCHEMA_A =
            new Schema(required(1, "id", Types.IntegerType.get()), required(2, "data", Types.StringType.get()));

    public static final Schema SCHEMA_B =
            new Schema(required(1, "k1", Types.IntegerType.get()), required(2, "k2", Types.IntegerType.get()));

    public static final Schema SCHEMA_D =
            new Schema(required(1, "k1", Types.IntegerType.get()), required(2, "ts",
                    Types.TimestampType.withZone()));

    public static final Schema SCHEMA_E =
            new Schema(required(1, "k1", Types.IntegerType.get()), required(2, "ts",
                    Types.TimestampType.withoutZone()));

    public static final Schema SCHEMA_F =
            new Schema(required(1, "k1", Types.IntegerType.get()), required(2, "dt", Types.DateType.get()));

    public static final Schema SCHEMA_H =
            new Schema(required(1, "k1", Types.IntegerType.get()),
                    required(2, "k2", Types.StringType.get()),
                    required(3, "k3", Types.StringType.get()),
                    required(4, "k4", Types.StringType.get()),
                    required(5, "k5", Types.StringType.get()));

    public static final Schema SCHEMA_I =
            new Schema(required(1, "id", Types.IntegerType.get()),
                    required(2, "k1", Types.IntegerType.get()),
                    required(3, "k2", Types.StringType.get()),
                    required(4, "ts1", Types.TimestampType.withZone()),
                    required(5, "ts2", Types.TimestampType.withZone()),
                    required(6, "ts3", Types.TimestampType.withZone()),
                    required(7, "ts4", Types.TimestampType.withZone()),
                    required(8, "data", Types.StringType.get()));

    protected static final int BUCKETS_NUMBER = 16;
    protected static final int BUCKETS_NUMBER2 = 64;

    // Partition spec used to create tables
    protected static final PartitionSpec SPEC_A =
            PartitionSpec.builderFor(SCHEMA_A).bucket("data", BUCKETS_NUMBER).build();
    protected static final PartitionSpec SPEC_B =
            PartitionSpec.builderFor(SCHEMA_B).identity("k2").build();
    protected static final PartitionSpec SPEC_B_1 = PartitionSpec.builderFor(SCHEMA_B).build();

    protected static final PartitionSpec SPEC_D_1 =
            PartitionSpec.builderFor(SCHEMA_D).identity("ts").build();
    protected static final PartitionSpec SPEC_D_2 =
            PartitionSpec.builderFor(SCHEMA_D).year("ts").build();
    protected static final PartitionSpec SPEC_D_3 =
            PartitionSpec.builderFor(SCHEMA_D).month("ts").build();
    protected static final PartitionSpec SPEC_D_4 =
            PartitionSpec.builderFor(SCHEMA_D).day("ts").build();
    protected static final PartitionSpec SPEC_D_5 =
            PartitionSpec.builderFor(SCHEMA_D).hour("ts").build();

    protected static final PartitionSpec SPEC_E_1 =
            PartitionSpec.builderFor(SCHEMA_E).identity("ts").build();
    protected static final PartitionSpec SPEC_E_2 =
            PartitionSpec.builderFor(SCHEMA_E).year("ts").build();
    protected static final PartitionSpec SPEC_E_3 =
            PartitionSpec.builderFor(SCHEMA_E).month("ts").build();
    protected static final PartitionSpec SPEC_E_4 =
            PartitionSpec.builderFor(SCHEMA_E).day("ts").build();
    protected static final PartitionSpec SPEC_E_5 =
            PartitionSpec.builderFor(SCHEMA_E).hour("ts").build();

    protected static final PartitionSpec SPEC_F =
            PartitionSpec.builderFor(SCHEMA_F).day("dt").build();

    protected static final PartitionSpec SPEC_F_1 =
            PartitionSpec.builderFor(SCHEMA_F).identity("dt").build();

    protected static final PartitionSpec SPEC_I =
            PartitionSpec.builderFor(SCHEMA_I).bucket("id", BUCKETS_NUMBER)
                    .bucket("k1", BUCKETS_NUMBER2)
                    .truncate("k2", 10)
                    .year("ts1").month("ts2").day("ts3").hour("ts4")
                    .build();

    public static final DataFile FILE_A =
            DataFiles.builder(SPEC_A)
                    .withPath("/path/to/data-a.parquet")
                    .withFileSizeInBytes(10)
                    .withPartitionPath("data_bucket=0") // easy way to set partition data for now
                    .withRecordCount(2)
                    .build();

    public static DeleteFile FILE_A_DELETES =
            FileMetadata.deleteFileBuilder(SPEC_A)
                    .ofPositionDeletes()
                    .withPath("/path/to/data-a-deletes.orc")
                    .withFormat(FileFormat.ORC)
                    .withFileSizeInBytes(10)
                    .withPartitionPath("data_bucket=0") // easy way to set partition data for now
                    .withRecordCount(1)
                    .build();
    // Equality delete files.
    public static DeleteFile FILE_A2_DELETES =
            FileMetadata.deleteFileBuilder(SPEC_A)
                    .ofEqualityDeletes(1)
                    .withPath("/path/to/data-a2-deletes.orc")
                    .withFormat(FileFormat.ORC)
                    .withFileSizeInBytes(10)
                    .withPartitionPath("data_bucket=0")
                    .withRecordCount(1)
                    .build();

    public static final DataFile FILE_A_1 =
            DataFiles.builder(SPEC_A)
                    .withPath("/path/to/data-a1.parquet")
                    .withFileSizeInBytes(10)
                    .withPartitionPath("data_bucket=0") // easy way to set partition data for now
                    .withRecordCount(2)
                    .build();

    public static final DataFile FILE_A_2 =
            DataFiles.builder(SPEC_A)
                    .withPath("/path/to/data-a2.parquet")
                    .withFileSizeInBytes(10)
                    .withPartitionPath("data_bucket=1") // easy way to set partition data for now
                    .withRecordCount(2)
                    .build();

    public static final DataFile FILE_B_1 =
            DataFiles.builder(SPEC_B)
                    .withPath("/path/to/data-b1.parquet")
                    .withFileSizeInBytes(20)
                    .withPartitionPath("k2=2")
                    .withRecordCount(3)
                    .build();

    public static final DataFile FILE_B_2 =
            DataFiles.builder(SPEC_B)
                    .withPath("/path/to/data-b2.parquet")
                    .withFileSizeInBytes(20)
                    .withPartitionPath("k2=3")
                    .withRecordCount(4)
                    .build();

    public static final Metrics TABLE_B_METRICS =  new Metrics(
            2L,
            ImmutableMap.of(1, 50L, 2, 50L),
            ImmutableMap.of(1, 2L, 2, 2L),
            ImmutableMap.of(1, 0L, 2, 0L),
            ImmutableMap.of(1, 0L, 2, 0L),
            ImmutableMap.of(1, Conversions.toByteBuffer(Types.IntegerType.get(), 1),
                    2, Conversions.toByteBuffer(Types.IntegerType.get(), 2)),
            ImmutableMap.of(1, Conversions.toByteBuffer(Types.IntegerType.get(), 2),
                    2, Conversions.toByteBuffer(Types.IntegerType.get(), 2))
    );

    public static final DataFile FILE_B_3 =
            DataFiles.builder(SPEC_B)
                    .withPath("/path/to/data-b3.parquet")
                    .withFileSizeInBytes(20)
                    .withPartitionPath("k2=3")
                    .withRecordCount(2)
                    .withMetrics(TABLE_B_METRICS)
                    .build();

    public static final DataFile FILE_B_4 =
            DataFiles.builder(SPEC_B)
                    .withPath("/path/to/data-b4.parquet")
                    .withFileSizeInBytes(20)
                    .withPartitionPath("k2=3")
                    .withRecordCount(2)
                    .withMetrics(TABLE_B_METRICS)
                    .build();

    public static final DataFile FILE_B_5 = DataFiles.builder(SPEC_B)
            .withPath("/path/to/data-b5.parquet")
            .withFileSizeInBytes(20)
            .withRecordCount(4)
            .build();

    public static final DeleteFile FILE_C_1 = FileMetadata.deleteFileBuilder(SPEC_B).ofPositionDeletes()
            .withPath("delete.orc")
            .withFileSizeInBytes(1024)
            .withRecordCount(1)
            .withPartitionPath("k2=2")
            .withFormat(FileFormat.ORC)
            .build();

    static final FileIO FILE_IO = new TestTables.LocalFileIO();

    @TempDir
    public File temp;

    protected File tableDir = null;
    protected File metadataDir = null;
    public TestTables.TestTable mockedNativeTableA = null;
    public TestTables.TestTable mockedNativeTableB = null;
    public TestTables.TestTable mockedNativeTableC = null;
    public TestTables.TestTable mockedNativeTableD = null;
    public TestTables.TestTable mockedNativeTableE = null;
    public TestTables.TestTable mockedNativeTableF = null;
    public TestTables.TestTable mockedNativeTableG = null;
    public TestTables.TestTable mockedNativeTableH = null;
    public TestTables.TestTable mockedNativeTableI = null;
    public TestTables.TestTable mockedNativeTableJ = null;
    public TestTables.TestTable mockedNativeTableK = null;
    public TestTables.TestTable mockedNativeTableMultiPartition = null;

    protected final int formatVersion = 1;

    @BeforeEach
    public void setupTable() throws Exception {
        this.tableDir = newFolder(temp, "junit");
        tableDir.delete(); // created by table create

        this.metadataDir = new File(tableDir, "metadata");
        this.mockedNativeTableA = create(SCHEMA_A, SPEC_A, "ta", 2);
        this.mockedNativeTableB = create(SCHEMA_B, SPEC_B, "tb", 1);
        this.mockedNativeTableC = create(SCHEMA_B, SPEC_B, "tc", 2);
        this.mockedNativeTableD = create(SCHEMA_D, SPEC_D_5, "td", 1);
        this.mockedNativeTableE = create(SCHEMA_D, SPEC_D_1, "te", 1);
        this.mockedNativeTableF = create(SCHEMA_F, SPEC_F, "tf", 1);
        this.mockedNativeTableG = create(SCHEMA_B, SPEC_B_1, "tg", 1);
        this.mockedNativeTableH = create(SCHEMA_H, PartitionSpec.unpartitioned(), "th", 1);
        this.mockedNativeTableI = create(SCHEMA_F, SPEC_F_1, "ti", 1);
        this.mockedNativeTableJ = create(SCHEMA_E, SPEC_E_3, "tj", 1);
        this.mockedNativeTableK = create(SCHEMA_E, SPEC_E_2, "tk", 1);
        this.mockedNativeTableMultiPartition = create(SCHEMA_I, SPEC_I, "tmp", 1);
    }

    @AfterEach
    public void cleanupTables() {
        TestTables.clearTables();
    }

    protected TestTables.TestTable create(Schema schema, PartitionSpec spec, String tableName, int formatVersion) {
        return TestTables.create(tableDir, tableName, schema, spec, formatVersion);
    }

    ManifestFile writeManifest(DataFile... files) throws IOException {
        return writeManifest(null, files);
    }

    ManifestFile writeManifest(Long snapshotId, DataFile... files) throws IOException {
        File manifestFile = newFile(temp, "input.m0.avro");
        Assertions.assertTrue(manifestFile.delete());
        OutputFile outputFile = mockedNativeTableA.ops().io().newOutputFile(manifestFile.getCanonicalPath());

        ManifestWriter<DataFile> writer =
                ManifestFiles.write(formatVersion, mockedNativeTableA.spec(), outputFile, snapshotId);
        try {
            for (DataFile file : files) {
                writer.add(file);
            }
        } finally {
            writer.close();
        }

        return writer.toManifestFile();
    }

    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + root);
        }
        return result;
    }

    private static File newFile(File parent, String child) throws IOException {
        File result = new File(parent, child);
        result.createNewFile();
        return result;
    }
}
