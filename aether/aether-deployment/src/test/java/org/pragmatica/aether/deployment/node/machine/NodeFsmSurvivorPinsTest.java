// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G1;

/// Pins for `NodeFsm.failedRetry`. Each test names the mutation that must turn it red.
class NodeFsmSurvivorPinsTest {
    private static Transition retry(NodeHarness h, InstanceRecord rec) {
        var timer = TimerId.timerId(TimerId.TimerKind.FAILED_RETRY, ARTIFACT, h.model.incarnation(), 0L);

        return h.fsm.step(h.model.withInstance(ARTIFACT, rec), new NodeFsmInput.TimerFired(timer));
    }

    private static InstanceRecord owing(NodeHarness h) {
        return InstanceRecord.instanceRecord()
                             .withDesired(Option.some(new SliceDesiredValue(SliceGoal.ACTIVE, G1, h.wall)))
                             .withUnconfirmed(Option.some(Unconfirmed.unconfirmed(SliceObservedState.FAILED, 3L, Option.none())));
    }

    /// Red when: `rec.isIdle() && rec.unconfirmed().isPresent()` becomes `||` (machine-49): a key with a write in flight would
    /// be re-driven, or an idle key owing nothing would not be dropped.
    @Test
    @DisplayName("N19: the retry timer is dropped for a busy key and for a key owing nothing; an idle key owing a write is re-driven")
    void failedRetry_redrives_onlyAnIdleKeyThatOwesAWrite() {
        var h = new NodeHarness().establish();
        var inFlight = InFlight.inFlight(77L, 3L, SliceObservedState.FAILED, List.of(), InFlight.Purpose.FAILED, 0);
        var busy = retry(h, owing(h).withWrite(Option.some(inFlight)));
        var owesNothing = retry(h, InstanceRecord.instanceRecord().withDesired(Option.some(new SliceDesiredValue(SliceGoal.ACTIVE, G1, h.wall))));
        var redriven = retry(h, owing(h));

        assertThat(busy.dropped()).as("a write is in flight").isTrue();
        assertThat(busy.model().instance(ARTIFACT).unconfirmed().isPresent()).as("and the owed write is left alone").isTrue();
        assertThat(owesNothing.dropped()).as("nothing owed").isTrue();
        assertThat(redriven.dropped()).as("idle and owing: R-6.0 runs").isFalse();
    }
}
