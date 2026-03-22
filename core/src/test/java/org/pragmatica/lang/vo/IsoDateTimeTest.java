/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
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

package org.pragmatica.lang.vo;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class IsoDateTimeTest {

    @Nested
    class ParseFromString {

        @Test
        void isoDateTime_success_withOffset() {
            IsoDateTime.isoDateTime("2026-03-22T14:30:00+01:00")
                       .onFailureRun(Assertions::fail)
                       .onSuccess(dt -> assertEquals(14, dt.value().getHour()));
        }

        @Test
        void isoDateTime_success_utc() {
            IsoDateTime.isoDateTime("2026-03-22T13:30:00Z")
                       .onFailureRun(Assertions::fail)
                       .onSuccess(dt -> assertEquals(ZoneOffset.UTC, dt.value().getOffset()));
        }

        @Test
        void isoDateTime_failure_dateOnly() {
            IsoDateTime.isoDateTime("2026-03-22")
                       .onSuccessRun(Assertions::fail)
                       .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void isoDateTime_failure_invalidString() {
            IsoDateTime.isoDateTime("not-a-date")
                       .onSuccessRun(Assertions::fail)
                       .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void isoDateTime_failure_empty() {
            IsoDateTime.isoDateTime("")
                       .onSuccessRun(Assertions::fail)
                       .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void isoDateTime_failure_null() {
            IsoDateTime.isoDateTime((String) null)
                       .onSuccessRun(Assertions::fail)
                       .onFailure(cause -> assertNotNull(cause.message()));
        }
    }

    @Nested
    class FactoryMethods {

        @Test
        void now_success_producesValidValue() {
            var now = IsoDateTime.now();
            assertNotNull(now.value());
            assertNotNull(now.toString());
        }

        @Test
        void nowUtc_success_utcOffset() {
            var nowUtc = IsoDateTime.nowUtc();
            assertEquals(ZoneOffset.UTC, nowUtc.value().getOffset());
        }

        @Test
        void isoDateTime_wrap_existingOffsetDateTime() {
            var odt = java.time.OffsetDateTime.of(2026, 3, 22, 10, 0, 0, 0, ZoneOffset.ofHours(2));
            var wrapped = IsoDateTime.isoDateTime(odt);
            assertEquals(odt, wrapped.value());
        }

        @Test
        void isoDateTime_toString_isoFormat() {
            IsoDateTime.isoDateTime("2026-03-22T14:30:00+01:00")
                       .onFailureRun(Assertions::fail)
                       .onSuccess(dt -> assertNotNull(dt.toString()));
        }
    }
}
