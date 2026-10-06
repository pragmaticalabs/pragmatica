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
package org.pragmatica.utility.warning;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The code catalogue is how operators identify a warning (#1574), so the codes must be unique and stable.
class OperatorWarningCodeTest {
    @Test
    void codes_areUnique() {
        var byCode = Arrays.stream(OperatorWarningCode.values())
                           .collect(Collectors.groupingBy(OperatorWarningCode::code, Collectors.counting()));

        assertThat(byCode.entrySet()
                         .stream()
                         .filter(entry -> entry.getValue() > 1)
                         .map(Map.Entry::getKey))
            .as("two constants sharing a code would make their events indistinguishable")
            .isEmpty();
    }

    @Test
    void codes_areKebabCase() {
        assertThat(Arrays.stream(OperatorWarningCode.values())
                         .map(OperatorWarningCode::code))
            .allMatch(code -> code.matches("[a-z][a-z0-9]*(-[a-z0-9]+)*"));
    }

    @Test
    void subsystems_areKebabCase() {
        assertThat(Arrays.stream(OperatorWarningCode.values())
                         .map(OperatorWarningCode::subsystem))
            .allMatch(subsystem -> subsystem.matches("[a-z][a-z0-9]*(-[a-z0-9]+)*"));
    }

    /// #752: a recovery code closes a condition of its own subsystem, and a condition is not itself a recovery, so a
    /// recovery is never itself waiting for one.
    @Test
    void recoveryCodes_closeAnOrdinaryConditionOfTheirOwnSubsystem() {
        var recoveries = Arrays.stream(OperatorWarningCode.values())
                               .filter(code -> code.recoveryOf().isPresent())
                               .toList();

        assertThat(recoveries).as("control: the catalogue has a recovery").isNotEmpty();
        assertThat(recoveries).allSatisfy(recovery -> {
            var closes = recovery.recoveryOf().unwrap();
            assertThat(closes.subsystem()).isEqualTo(recovery.subsystem());
            assertThat(closes.recoveryOf().isPresent()).isFalse();
            assertThat(closes.hasRecovery()).isTrue();
            assertThat(recovery.hasRecovery()).isFalse();
        });
    }

    @Test
    void streamConsumerRecovery_isInfo_andClosesTheDivergence() {
        assertThat(OperatorWarningCode.STREAM_CONSUMER_STATE_REPAIRED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.STREAM_CONSUMER_STATE_REPAIRED.recoveryOf().unwrap())
            .isEqualTo(OperatorWarningCode.STREAM_CONSUMER_STATE_DIVERGED);
    }

    /// Positive control for [#codes_areUnique]: the same grouping reports a duplicate when one exists.
    @Test
    void uniquenessCheck_detectsADuplicate() {
        var duplicated = Arrays.stream(new String[]{"a-b", "c-d", "a-b"})
                               .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        assertThat(duplicated.entrySet()
                             .stream()
                             .filter(entry -> entry.getValue() > 1)
                             .map(Map.Entry::getKey))
            .containsExactly("a-b");
    }
}
