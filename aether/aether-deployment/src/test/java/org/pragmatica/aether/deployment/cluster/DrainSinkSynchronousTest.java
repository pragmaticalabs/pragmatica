// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;

/// #3754 C1, lane (a) follow-up, TB5g-a: the sink's half of the owner-monitor guard of [DrainSink#synchronous]. A gate whose
/// `ownerMonitorHeld()` answers `true` is refused with [FenceFailure.CalledUnderOwnerMonitor], logged at ERROR with the target, before
/// the gate's `runIfOwned`, the registry add or the FSM call. A gate answering `false` from `runIfOwned` gives
/// [FenceFailure.AttemptCancelled]; [DrainGate#always] answers `ownerMonitorHeld()` `false` and runs the write.
@Timeout(20)
class DrainSinkSynchronousTest {
    private static final NodeId TARGET = new NodeId("drained-node");

    private final List<NodeId> registryAdds = new ArrayList<>();
    private final List<NodeId> fsmCalls = new ArrayList<>();
    private final DrainSink sink = DrainSink.synchronous(registryAdds::add, fsmCalls::add);

    /// A gate whose two answers are fixed by the test and which counts how often `runIfOwned` was asked.
    private static final class ScriptedGate implements DrainGate {
        private final boolean monitorHeld;
        private final boolean owned;
        final AtomicInteger runIfOwnedCalls = new AtomicInteger();

        ScriptedGate(boolean monitorHeld, boolean owned) {
            this.monitorHeld = monitorHeld;
            this.owned = owned;
        }

        @Override
        public boolean runIfOwned(Runnable write) {
            runIfOwnedCalls.incrementAndGet();
            if (owned) {
                write.run();
            }
            return owned;
        }

        @Override
        public boolean ownerMonitorHeld() {
            return monitorHeld;
        }
    }

    @Test
    void request_gateHoldingOwnerMonitor_failsWithCalledUnderOwnerMonitorAndTouchesNothing() {
        var gate = new ScriptedGate(true, true);

        var outcome = sink.request(TARGET, gate).await();

        assertThat(outcome.isFailure()).isTrue();
        outcome.onFailure(cause -> assertThat(cause).isEqualTo(FenceFailure.CalledUnderOwnerMonitor.calledUnderOwnerMonitor(TARGET)));
        assertThat(gate.runIfOwnedCalls).hasValue(0);
        assertThat(registryAdds).isEmpty();
        assertThat(fsmCalls).isEmpty();
    }

    @Test
    void request_gateHoldingOwnerMonitor_logsAtErrorWithTheTarget() {
        try (var capture = LogCapture.capture(DrainSink.class, Level.ERROR, false)) {
            sink.request(TARGET, new ScriptedGate(true, true)).await();

            assertThat(capture.messages(Level.ERROR)).singleElement()
                                                     .asString()
                                                     .contains(TARGET.toString());
        }
    }

    @Test
    void request_gateNotOwningFromRunIfOwned_failsWithAttemptCancelledAndCallsNoFsm() {
        var gate = new ScriptedGate(false, false);

        var outcome = sink.request(TARGET, gate).await();

        assertThat(outcome.isFailure()).isTrue();
        outcome.onFailure(cause -> assertThat(cause).isEqualTo(FenceFailure.AttemptCancelled.attemptCancelled()));
        assertThat(gate.runIfOwnedCalls).hasValue(1);
        assertThat(registryAdds).isEmpty();
        assertThat(fsmCalls).isEmpty();
    }

    @Test
    void request_alwaysGate_registersThenCallsFsmAndSucceeds() {
        var gate = DrainGate.always();

        var outcome = sink.request(TARGET, gate).await();

        assertThat(gate.ownerMonitorHeld()).isFalse();
        assertThat(outcome).isEqualTo(Result.success(Unit.unit()));
        assertThat(registryAdds).containsExactly(TARGET);
        assertThat(fsmCalls).containsExactly(TARGET);
    }

    @Test
    void inFlight_synchronousSink_answersNone() {
        sink.request(TARGET, DrainGate.always()).await();

        assertThat(sink.inFlight(TARGET).isEmpty()).isTrue();
    }

    /// #2315: `release` reaches the holder the three-argument sink was given; the two-argument sink holds nothing and releases nothing.
    @Test
    void release_callsTheHolder_whenOneWasGiven_andNothingOtherwise() {
        var released = new ArrayList<NodeId>();

        DrainSink.synchronous(registryAdds::add, fsmCalls::add, released::add).release(TARGET);
        sink.release(TARGET);

        assertThat(released).containsExactly(TARGET);
        assertThat(registryAdds).isEmpty();
    }
}
