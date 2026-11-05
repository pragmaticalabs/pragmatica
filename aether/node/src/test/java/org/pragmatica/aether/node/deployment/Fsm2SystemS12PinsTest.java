// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.swim.SwimConfig;

import static org.assertj.core.api.Assertions.assertThat;

/// F1 mutation survivor pin (run s2, `nodedep-Fsm2System-02`): the suspicion lag's probe lead is `maxCores - 1` SWIM periods. The value is
/// spelled out term by term here, not recomputed through `Fsm2System.suspicionLag`: the fixtures derive their bounds through that same method,
/// so a wrong lead moves the fixture and the code together and no comparison goes red. Red when the lead counts `maxCores + 1` periods.
class Fsm2SystemS12PinsTest {
    @Test
    void suspicionLag_isTheSeamIntervalPlusOnePeriodPlusTheProbeLeadPlusTwoProbeTimeoutsPlusTheSplitTimeout() {
        var swim = SwimConfig.DEFAULT;
        var split = TimeSpan.timeSpan(7).seconds();
        var maxCores = 5;
        var expectedNanos = TimeSpan.timeSpan(SeamPoller.INTERVAL_MS).millis().nanos()
                            + swim.period().nanos()
                            + 4 * swim.period().nanos()
                            + 2 * swim.probeTimeout().nanos()
                            + split.nanos();

        assertThat(Fsm2System.suspicionLag(swim, maxCores, split).nanos()).isEqualTo(expectedNanos);
    }

    @Test
    void suspicionLag_hasNoProbeLead_forASingleCore() {
        var swim = SwimConfig.DEFAULT;
        var split = TimeSpan.timeSpan(7).seconds();
        var expectedNanos = TimeSpan.timeSpan(SeamPoller.INTERVAL_MS).millis().nanos()
                            + swim.period().nanos()
                            + 2 * swim.probeTimeout().nanos()
                            + split.nanos();

        assertThat(Fsm2System.suspicionLag(swim, 1, split).nanos()).isEqualTo(expectedNanos);
    }
}
