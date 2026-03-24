/*
 *  Copyright (c) 2020-2026 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.lang.parse;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.parse.DataSize.DataSizeError;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataSizeTest {

    @Nested
    class SuccessfulParsing {

        @Test
        void dataSize_succeeds_forBytes() {
            DataSize.dataSize("1024B")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ds -> assertEquals(1024L, ds.bytes()));
        }

        @Test
        void dataSize_succeeds_forKilobytes() {
            DataSize.dataSize("512KB")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ds -> assertEquals(512L * 1024, ds.bytes()));
        }

        @Test
        void dataSize_succeeds_forMegabytes() {
            DataSize.dataSize("10MB")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ds -> assertEquals(10L * 1024 * 1024, ds.bytes()));
        }

        @Test
        void dataSize_succeeds_forGigabytes() {
            DataSize.dataSize("2GB")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ds -> assertEquals(2L * 1024 * 1024 * 1024, ds.bytes()));
        }

        @Test
        void dataSize_succeeds_forTerabytes() {
            DataSize.dataSize("1TB")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ds -> assertEquals(1024L * 1024 * 1024 * 1024, ds.bytes()));
        }

        @Test
        void dataSize_succeeds_forPlainNumber() {
            DataSize.dataSize("16384")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ds -> assertEquals(16384L, ds.bytes()));
        }

        @Test
        void dataSize_succeeds_caseInsensitive() {
            DataSize.dataSize("5mb")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ds -> assertEquals(5L * 1024 * 1024, ds.bytes()));
        }

        @Test
        void dataSize_succeeds_withWhitespace() {
            DataSize.dataSize("  10 MB  ")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ds -> assertEquals(10L * 1024 * 1024, ds.bytes()));
        }

        @Test
        void dataSize_succeeds_forZero() {
            DataSize.dataSize("0")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ds -> assertEquals(0L, ds.bytes()));
        }

        @Test
        void dataSize_succeeds_forZeroMB() {
            DataSize.dataSize("0MB")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ds -> assertEquals(0L, ds.bytes()));
        }
    }

    @Nested
    class BytesAsInt {

        @Test
        void bytesAsInt_returns_intValue_forSmallSizes() {
            DataSize.dataSize("5MB")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ds -> assertEquals(5 * 1024 * 1024, ds.bytesAsInt()));
        }

        @Test
        void bytesAsInt_clamps_toMaxInt_forLargeSizes() {
            DataSize.dataSize("4GB")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ds -> assertEquals(Integer.MAX_VALUE, ds.bytesAsInt()));
        }
    }

    @Nested
    class FailedParsing {

        @Test
        void dataSize_fails_forNull() {
            DataSize.dataSize(null)
                    .onSuccess(_ -> Assertions.fail())
                    .onFailure(cause -> assertTrue(cause instanceof DataSizeError));
        }

        @Test
        void dataSize_fails_forEmpty() {
            DataSize.dataSize("")
                    .onSuccess(_ -> Assertions.fail())
                    .onFailure(cause -> assertTrue(cause instanceof DataSizeError));
        }

        @Test
        void dataSize_fails_forBlank() {
            DataSize.dataSize("   ")
                    .onSuccess(_ -> Assertions.fail())
                    .onFailure(cause -> assertTrue(cause instanceof DataSizeError));
        }

        @Test
        void dataSize_fails_forInvalidUnit() {
            DataSize.dataSize("10XB")
                    .onSuccess(_ -> Assertions.fail())
                    .onFailure(cause -> assertTrue(cause instanceof DataSizeError.InvalidFormat));
        }

        @Test
        void dataSize_fails_forNonNumericValue() {
            DataSize.dataSize("abcMB")
                    .onSuccess(_ -> Assertions.fail())
                    .onFailure(cause -> assertTrue(cause instanceof DataSizeError.InvalidFormat));
        }
    }

    @Nested
    class Formatting {

        @Test
        void toString_formats_bytes() {
            DataSize.dataSize("1000B")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ds -> assertEquals("1000B", ds.toString()));
        }

        @Test
        void toString_formats_kilobytes() {
            DataSize.dataSize("1KB")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ds -> assertEquals("1KB", ds.toString()));
        }

        @Test
        void toString_formats_megabytes() {
            DataSize.dataSize("10MB")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ds -> assertEquals("10MB", ds.toString()));
        }

        @Test
        void toString_formats_gigabytes() {
            DataSize.dataSize("2GB")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ds -> assertEquals("2GB", ds.toString()));
        }

        @Test
        void toString_formats_terabytes() {
            DataSize.dataSize("1TB")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ds -> assertEquals("1TB", ds.toString()));
        }

        @Test
        void toString_formats_zero() {
            DataSize.dataSize("0")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ds -> assertEquals("0B", ds.toString()));
        }
    }
}
