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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UuidTest {

    @Nested
    class ParseFromString {

        @Test
        void uuid_success_validString() {
            Uuid.uuid("550e8400-e29b-41d4-a716-446655440000")
                .onFailureRun(Assertions::fail)
                .onSuccess(u -> assertEquals("550e8400-e29b-41d4-a716-446655440000", u.toString()));
        }

        @Test
        void uuid_success_uppercase() {
            Uuid.uuid("550E8400-E29B-41D4-A716-446655440000")
                .onFailureRun(Assertions::fail)
                .onSuccess(u -> assertNotNull(u.value()));
        }

        @Test
        void uuid_failure_invalidString() {
            Uuid.uuid("not-a-uuid")
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void uuid_failure_empty() {
            Uuid.uuid("")
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void uuid_failure_null() {
            Uuid.uuid((String) null)
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertNotNull(cause.message()));
        }
    }

    @Nested
    class WrapAndGenerate {

        @Test
        void uuid_wrap_existingUUID() {
            var javaUuid = java.util.UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
            var wrapped = Uuid.uuid(javaUuid);
            assertEquals(javaUuid, wrapped.value());
        }

        @Test
        void randomUuid_success_producesValidUuid() {
            var uuid = Uuid.randomUuid();
            assertNotNull(uuid.value());
            assertNotNull(uuid.toString());
        }

        @Test
        void randomUuid_success_uniqueValues() {
            var first = Uuid.randomUuid();
            var second = Uuid.randomUuid();
            assertNotNull(first.value());
            assertNotNull(second.value());
            // Two random UUIDs should not be equal
            Assertions.assertNotEquals(first.value(), second.value());
        }

        @Test
        void uuid_toString_matchesValue() {
            var javaUuid = java.util.UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
            var uuid = Uuid.uuid(javaUuid);
            assertEquals(javaUuid.toString(), uuid.toString());
        }
    }
}
