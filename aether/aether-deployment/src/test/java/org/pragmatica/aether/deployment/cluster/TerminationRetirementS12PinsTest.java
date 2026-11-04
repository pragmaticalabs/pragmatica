// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// F1 mutation survivor pin (run s1, `wiring-TerminationRetirement-13`): the overdue bound is inclusive. A retirement refused when exactly
/// `after` has elapsed since it started is overdue and raises the warning. Red when the comparison is strict (`>`), which raises nothing at the
/// bound itself; the existing suite only refuses a minute or more past the bound, so it cannot tell the two apart.
class TerminationRetirementS12PinsTest {
    private static final NodeId GONE = new NodeId("worker-9");
    private static final TimeSpan BOUND = TimeSpan.timeSpan(2).minutes();

    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final AtomicLong clockNs = new AtomicLong();

    @Test
    void refused_raisesTheOverdueWarning_whenExactlyTheBoundHasElapsed() {
        var overdue = TerminationRetirement.Overdue.overdue(OperatorWarningSink.handingOffTo(warnings::add), clockNs::get, BOUND);
        var watch = overdue.watch(GONE);

        clockNs.addAndGet(BOUND.nanos());
        watch.refused(TerminationRetirement.Refused.FACTORY.apply(GONE));

        await().atMost(Duration.ofSeconds(5)).until(() -> !warnings.isEmpty());
        assertThat(warnings).extracting(OperatorWarning::code).containsExactly(OperatorWarningCode.TERMINATION_RETIREMENT_OVERDUE);
    }
}
