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

package com.starrocks.load.loadv2.dpp;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;

public class HllTest {

    @Test
    public void testFindFirstNonZeroBitPosition() {
        Assertions.assertTrue(Hll.getLongTailZeroNum(0) == 0);
        Assertions.assertTrue(Hll.getLongTailZeroNum(1) == 0);
        Assertions.assertTrue(Hll.getLongTailZeroNum(1L << 30) == 30);
        Assertions.assertTrue(Hll.getLongTailZeroNum(1L << 62) == 62);
    }

    @Test
    public void HllBasicTest() throws IOException {
        // test empty
        Hll emptyHll = new Hll();

        Assertions.assertTrue(emptyHll.getType() == Hll.HLL_DATA_EMPTY);
        Assertions.assertTrue(emptyHll.estimateCardinality() == 0);

        ByteArrayOutputStream emptyOutputStream = new ByteArrayOutputStream();
        DataOutput output = new DataOutputStream(emptyOutputStream);
        emptyHll.serialize(output);
        DataInputStream emptyInputStream =
                new DataInputStream(new ByteArrayInputStream(emptyOutputStream.toByteArray()));
        Hll deserializedEmptyHll = new Hll();
        deserializedEmptyHll.deserialize(emptyInputStream);
        Assertions.assertTrue(deserializedEmptyHll.getType() == Hll.HLL_DATA_EMPTY);

        // test explicit
        Hll explicitHll = new Hll();
        for (int i = 0; i < Hll.HLL_EXPLICLIT_INT64_NUM; i++) {
            explicitHll.updateWithHash(i);
        }
        Assertions.assertTrue(explicitHll.getType() == Hll.HLL_DATA_EXPLICIT);
        Assertions.assertTrue(explicitHll.estimateCardinality() == Hll.HLL_EXPLICLIT_INT64_NUM);

        ByteArrayOutputStream explicitOutputStream = new ByteArrayOutputStream();
        DataOutput explicitOutput = new DataOutputStream(explicitOutputStream);
        explicitHll.serialize(explicitOutput);
        DataInputStream explicitInputStream =
                new DataInputStream(new ByteArrayInputStream(explicitOutputStream.toByteArray()));
        Hll deserializedExplicitHll = new Hll();
        deserializedExplicitHll.deserialize(explicitInputStream);
        Assertions.assertTrue(deserializedExplicitHll.getType() == Hll.HLL_DATA_EXPLICIT);

        // test sparse
        Hll sparseHll = new Hll();
        for (int i = 0; i < Hll.HLL_SPARSE_THRESHOLD; i++) {
            sparseHll.updateWithHash(i);
        }
        Assertions.assertTrue(sparseHll.getType() == Hll.HLL_DATA_FULL);
        // 2% error rate
        Assertions.assertTrue(sparseHll.estimateCardinality() > Hll.HLL_SPARSE_THRESHOLD * (1 - 0.02) &&
                sparseHll.estimateCardinality() < Hll.HLL_SPARSE_THRESHOLD * (1 + 0.02));

        ByteArrayOutputStream sparseOutputStream = new ByteArrayOutputStream();
        DataOutput sparseOutput = new DataOutputStream(sparseOutputStream);
        sparseHll.serialize(sparseOutput);
        DataInputStream sparseInputStream =
                new DataInputStream(new ByteArrayInputStream(sparseOutputStream.toByteArray()));
        Hll deserializedSparseHll = new Hll();
        deserializedSparseHll.deserialize(sparseInputStream);
        Assertions.assertTrue(deserializedSparseHll.getType() == Hll.HLL_DATA_SPARSE);
        Assertions.assertTrue(sparseHll.estimateCardinality() == deserializedSparseHll.estimateCardinality());

        // test full
        Hll fullHll = new Hll();
        for (int i = 1; i <= Short.MAX_VALUE; i++) {
            fullHll.updateWithHash(i);
        }
        Assertions.assertTrue(fullHll.getType() == Hll.HLL_DATA_FULL);
        // the result 32748 is consistent with C++ 's implementation
        Assertions.assertTrue(fullHll.estimateCardinality() == 32748);
        Assertions.assertTrue(fullHll.estimateCardinality() > Short.MAX_VALUE * (1 - 0.02) &&
                fullHll.estimateCardinality() < Short.MAX_VALUE * (1 + 0.02));

        ByteArrayOutputStream fullHllOutputStream = new ByteArrayOutputStream();
        DataOutput fullHllOutput = new DataOutputStream(fullHllOutputStream);
        fullHll.serialize(fullHllOutput);
        DataInputStream fullHllInputStream =
                new DataInputStream(new ByteArrayInputStream(fullHllOutputStream.toByteArray()));
        Hll deserializedFullHll = new Hll();
        deserializedFullHll.deserialize(fullHllInputStream);
        Assertions.assertTrue(deserializedFullHll.getType() == Hll.HLL_DATA_FULL);
        Assertions.assertTrue(deserializedFullHll.estimateCardinality() == fullHll.estimateCardinality());

    }

    // keep logic same with C++ version
    // add additional compare logic with C++ version's estimateValue
    @Test
    public void testCompareEstimateValueWithBe() throws IOException {
        //empty
        {
            Hll hll = new Hll();
            long estimateValue = hll.estimateCardinality();
            byte[] serializedByte = serializeHll(hll);
            hll = deserializeHll(serializedByte);

            Assertions.assertTrue(estimateValue == hll.estimateCardinality());
        }

        // explicit [0. 100)
        Hll explicitHll = new Hll();
        {
            for (int i = 0; i < 100; i++) {
                explicitHll.updateWithHash(i);
            }
            Assertions.assertTrue(explicitHll.estimateCardinality() == 100);
            // check serialize
            byte[] serializeHll = serializeHll(explicitHll);
            explicitHll = deserializeHll(serializeHll);
            Assertions.assertTrue(explicitHll.estimateCardinality() == 100);

            Hll otherHll = new Hll();
            for (int i = 0; i < 100; i++) {
                otherHll.updateWithHash(i);
            }
            explicitHll.merge(otherHll);
            // compare with C++ version result
            Assertions.assertTrue(explicitHll.estimateCardinality() == 100);
        }

        // sparse [1024, 2048)
        Hll sparseHll = new Hll();
        {
            for (int i = 0; i < 1024; i++) {
                sparseHll.updateWithHash(i + 1024);
            }

            long preValue = sparseHll.estimateCardinality();
            // check serialize
            byte[] serializedHll = serializeHll(sparseHll);
            Assertions.assertTrue(serializedHll.length < Hll.HLL_REGISTERS_COUNT + 1);

            sparseHll = deserializeHll(serializedHll);
            Assertions.assertTrue(sparseHll.estimateCardinality() == preValue);
            Assertions.assertTrue(sparseHll.getType() == Hll.HLL_DATA_SPARSE);

            Hll otherHll = new Hll();
            for (int i = 0; i < 1024; i++) {
                otherHll.updateWithHash(i + 1024);
            }
            sparseHll.updateWithHash(1024);
            sparseHll.merge(otherHll);
            long cardinality = sparseHll.estimateCardinality();
            Assertions.assertTrue(preValue == cardinality);
            // 2% error rate
            Assertions.assertTrue(cardinality > 1000 && cardinality < 1045);
            // compare with C++ version result
            Assertions.assertTrue(cardinality == 1023);
        }

        // full [64 * 1024, 128 * 1024)
        Hll fullHll = new Hll();
        {
            for (int i = 0; i < 64 * 1024; i++) {
                fullHll.updateWithHash(64 * 1024 + i);
            }

            long preValue = fullHll.estimateCardinality();
            // check serialize
            byte[] serializedHll = serializeHll(fullHll);
            fullHll = deserializeHll(serializedHll);
            Assertions.assertTrue(fullHll.estimateCardinality() == preValue);
            Assertions.assertTrue(serializedHll.length == Hll.HLL_REGISTERS_COUNT + 1);

            // 2% error rate
            Assertions.assertTrue(preValue > 62 * 1024 && preValue < 66 * 1024);

            // compare with C++ version result
            Assertions.assertTrue(preValue == 66112);
        }

        // merge explicit to empty_hll
        {
            Hll newExplicit = new Hll();
            newExplicit.merge(explicitHll);
            Assertions.assertTrue(newExplicit.estimateCardinality() == 100);

            // merge another explicit
            {
                Hll otherHll = new Hll();
                for (int i = 100; i < 200; i++) {
                    otherHll.updateWithHash(i);
                }
                // this is converted to full
                otherHll.merge(newExplicit);
                Assertions.assertTrue(otherHll.estimateCardinality() > 190);
                // compare with C++ version result
                Assertions.assertTrue(otherHll.estimateCardinality() == 201);
            }
            // merge full
            {
                newExplicit.merge(fullHll);
                Assertions.assertTrue(newExplicit.estimateCardinality() > fullHll.estimateCardinality());
                // compare with C++ version result
                Assertions.assertTrue(newExplicit.estimateCardinality() == 66250);
            }
        }

        // merge sparse into empty
        {
            Hll newSparseHll = new Hll();
            newSparseHll.merge(sparseHll);
            Assertions.assertTrue(sparseHll.estimateCardinality() == newSparseHll.estimateCardinality());
            // compare with C++ version result
            Assertions.assertTrue(newSparseHll.estimateCardinality() == 1023);

            // merge explicit
            newSparseHll.merge(explicitHll);
            Assertions.assertTrue(newSparseHll.estimateCardinality() > sparseHll.estimateCardinality());
            // compare with C++ version result
            Assertions.assertTrue(newSparseHll.estimateCardinality() == 1123);

            // merge full
            newSparseHll.merge(fullHll);
            Assertions.assertTrue(newSparseHll.estimateCardinality() > fullHll.estimateCardinality());
            // compare with C++ version result
            Assertions.assertTrue(newSparseHll.estimateCardinality() == 67316);
        }

    }

    private byte[] serializeHll(Hll hll) throws IOException {
        ByteArrayOutputStream fullHllOutputStream = new ByteArrayOutputStream();
        DataOutput fullHllOutput = new DataOutputStream(fullHllOutputStream);
        hll.serialize(fullHllOutput);
        return fullHllOutputStream.toByteArray();
    }

    private Hll deserializeHll(byte[] hllBytes) throws IOException {
        DataInputStream fullHllInputStream = new DataInputStream(new ByteArrayInputStream(hllBytes));
        Hll hll = new Hll();
        hll.deserialize(fullHllInputStream);
        return hll;
    }

}
