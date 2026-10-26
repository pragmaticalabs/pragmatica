/*
 *  Copyright (c) 2026 Sergiy Yevtushenko.
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
 *
 */
package org.pragmatica.lang.utils;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.utils.SqlStates.Kind;

import static org.assertj.core.api.Assertions.assertThat;

class SqlStatesTest {
    @Test
    void classify_class23_isConstraintViolation() {
        assertThat(SqlStates.classify("23505")).isEqualTo(Kind.CONSTRAINT_VIOLATION);
        assertThat(SqlStates.isTransient("23505")).isFalse();
    }

    @Test
    void classify_class40_isATransientRollback() {
        assertThat(SqlStates.classify("40001")).isEqualTo(Kind.ROLLBACK);
        assertThat(SqlStates.classify("40P01")).isEqualTo(Kind.ROLLBACK);
        assertThat(SqlStates.isTransient("40001")).isTrue();
    }

    @Test
    void classify_connectionAndServerGoneStates_areTransientConnectionFailures() {
        for (var state : new String[]{"08001", "08003", "08004", "08006", "57P01", "57P02", "57P03", "53300"}) {
            assertThat(SqlStates.classify(state)).as(state).isEqualTo(Kind.TRANSIENT_CONNECTION);
            assertThat(SqlStates.isTransient(state)).as(state).isTrue();
        }
    }

    @Test
    void classify_unretryableStates_areNeverTransient() {
        for (var state : new String[]{"08007", "08P01", "57014", "57P04", "42601", "22012"}) {
            assertThat(SqlStates.classify(state)).as(state).isEqualTo(Kind.OTHER);
            assertThat(SqlStates.isTransient(state)).as(state).isFalse();
        }
    }
}
