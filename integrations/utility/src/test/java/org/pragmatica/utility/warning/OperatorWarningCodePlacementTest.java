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

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.utility.warning.OperatorWarningCode.*;

/// v2713c survivor o01: a target above what the cluster can place needs the operator's attention (WARNING); only its recovery is INFO.
class OperatorWarningCodePlacementTest {
    @Test
    void sliceTargetExceedsPlacement_isAWarning_andItsRecoveryIsInfoClosingIt() {
        assertThat(SLICE_TARGET_EXCEEDS_PLACEMENT.code()).isEqualTo("slice-target-exceeds-placement");
        assertThat(SLICE_TARGET_EXCEEDS_PLACEMENT.subsystem()).isEqualTo("deployment");
        assertThat(SLICE_TARGET_EXCEEDS_PLACEMENT.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(SLICE_TARGET_PLACEMENT_RESTORED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(SLICE_TARGET_PLACEMENT_RESTORED.recoveryOf()).isEqualTo(Option.some(SLICE_TARGET_EXCEEDS_PLACEMENT));
    }
}
