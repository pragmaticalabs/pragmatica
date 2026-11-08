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
package org.pragmatica.lang;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PatchTest {
    @Test
    void factories_giveTheThreeStates() {
        assertThat(Patch.<String>absent()).isInstanceOf(Patch.Absent.class)
                                          .hasToString("Absent()");
        assertThat(Patch.<String>nullValue()).isInstanceOf(Patch.Null.class)
                                             .hasToString("Null()");
        assertThat(Patch.value("a")).isEqualTo(new Patch.Value<>("a"))
                                    .hasToString("Value(a)");
    }

    @Test
    void factories_absentAndNull_areDistinctSingletons() {
        assertThat(Patch.<String>absent()).isSameAs(Patch.<Integer>absent())
                                          .isNotEqualTo(Patch.nullValue());
        assertThat(Patch.<String>nullValue()).isSameAs(Patch.<Integer>nullValue());
    }

    @Test
    void fold_eachState_choosesItsOwnFunction() {
        assertThat(Patch.<Integer>absent().fold(() -> "absent", () -> "null", value -> "value " + value)).isEqualTo("absent");
        assertThat(Patch.<Integer>nullValue().fold(() -> "absent", () -> "null", value -> "value " + value)).isEqualTo("null");
        assertThat(Patch.value(7).fold(() -> "absent", () -> "null", value -> "value " + value)).isEqualTo("value 7");
    }

    @Test
    void map_transformsOnlyAValue() {
        assertThat(Patch.value(7).map(value -> value * 2)).isEqualTo(Patch.value(14));
        assertThat(Patch.<Integer>absent().map(value -> value * 2)).isEqualTo(Patch.absent());
        assertThat(Patch.<Integer>nullValue().map(value -> value * 2)).isEqualTo(Patch.nullValue());
    }

    @Test
    void applyTo_absentKeeps_nullClears_valueReplaces() {
        var current = Option.some("old");

        assertThat(Patch.<String>absent().applyTo(current)).isEqualTo(Option.some("old"));
        assertThat(Patch.<String>absent().applyTo(Option.none())).isEqualTo(Option.none());
        assertThat(Patch.<String>nullValue().applyTo(current)).isEqualTo(Option.none());
        assertThat(Patch.value("new").applyTo(current)).isEqualTo(Option.some("new"));
        assertThat(Patch.value("new").applyTo(Option.none())).isEqualTo(Option.some("new"));
    }
}
