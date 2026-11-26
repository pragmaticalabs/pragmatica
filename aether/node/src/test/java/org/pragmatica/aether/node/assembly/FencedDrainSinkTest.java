// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.deployment.cluster.DrainGate;
import org.pragmatica.aether.deployment.cluster.FenceFailure.AttemptCancelled;
import org.pragmatica.aether.deployment.cluster.FenceFailure.CalledUnderOwnerMonitor;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/// #3754 C1, lane (b2), design r6 §7 rows TB1, TB3, TB11, TB12, TB13, TB5g and TB5f-r: the [FencedDrainSink] over a [ScriptedFencePort]
/// whose witnesses the test settles by hand, a registry of two functions, and a membership FSM double that records its calls and
/// whether the owner's monitor was held at each. The route-level rows (TB2, TB8, TB8r) are pinned in
/// `NodeLifecycleRoutesFencedDrainTest` and `NodeLifecycleRoutesDrainRouteTest`.
@Timeout(20)
class FencedDrainSinkTest {
    private static final NodeId TARGET = NodeId.nodeId("node-target").unwrap();
    private static final Cause REFUSED = Causes.cause("witness refused");

    private final ScriptedFencePort port = ScriptedFencePort.scriptedFencePort();
    private final Set<NodeId> registry = ConcurrentHashMap.newKeySet();
    private final List<NodeId> registryAdds = new CopyOnWriteArrayList<>();
    private final List<NodeId> fsmCalls = new CopyOnWriteArrayList<>();
    private final List<Boolean> fsmHeldTheOwnerMonitor = new CopyOnWriteArrayList<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final Object ownerMonitor = new Object();
    private final MembershipFsm fsm = recordingFsm();
    private final FencedDrainSink sink = FencedDrainSink.fencedDrainSink(port,
                                                                         this::registryAdd,
                                                                         this::drainTargets,
                                                                         new AtomicReference<>(fsm));

    private void registryAdd(NodeId target) {
        events.add("registry-add");
        registryAdds.add(target);
        registry.add(target);
    }

    private Set<NodeId> drainTargets() {
        return Set.copyOf(registry);
    }

    private MembershipFsm recordingFsm() {
        var recording = mock(MembershipFsm.class);

        doAnswer(invocation -> recordFsmCall(invocation.getArgument(0))).when(recording)
                                                                        .onDrainRequested(any());

        return recording;
    }

    private Object recordFsmCall(NodeId target) {
        events.add("fsm");
        fsmCalls.add(target);
        fsmHeldTheOwnerMonitor.add(Thread.holdsLock(ownerMonitor));

        return null;
    }

    /// A gate whose answers the test fixes. `runIfOwned` runs the write under `ownerMonitor` when it owns, and logs when it is
    /// entered and left; `ownerMonitorHeld` answers `false` for its first `falseAnswers` reads and `true` from then on.
    private final class ScriptedGate implements DrainGate {
        private final String name;
        private final boolean owned;
        private final int falseAnswers;
        final AtomicInteger runIfOwnedCalls = new AtomicInteger();
        private final AtomicInteger monitorReads = new AtomicInteger();

        private ScriptedGate(String name, boolean owned, int falseAnswers) {
            this.name = name;
            this.owned = owned;
            this.falseAnswers = falseAnswers;
        }

        @Override
        public boolean runIfOwned(Runnable write) {
            runIfOwnedCalls.incrementAndGet();
            events.add(name + ":gate-enter");
            runWrite(write);
            events.add(name + ":gate-exit");

            return owned;
        }

        private void runWrite(Runnable write) {
            if (owned) {
                synchronized (ownerMonitor) {
                    write.run();
                }
            }
        }

        @Override
        public boolean ownerMonitorHeld() {
            return monitorReads.incrementAndGet() > falseAnswers;
        }
    }

    private ScriptedGate owning(String name) {
        return new ScriptedGate(name, true, Integer.MAX_VALUE);
    }

    private ScriptedGate notOwning(String name) {
        return new ScriptedGate(name, false, Integer.MAX_VALUE);
    }

    private ScriptedGate holdingTheMonitor(String name) {
        return new ScriptedGate(name, true, 0);
    }

    private ScriptedGate holdingTheMonitorOnlyAfterEntry(String name) {
        return new ScriptedGate(name, true, 1);
    }

    private static Cause failureOf(Result<?> result) {
        var holder = new AtomicReference<Cause>();

        result.onFailure(holder::set);

        return holder.get();
    }

    // ---- TB1

    /// TB1, accepted: nothing is written before the witness is accepted; then one registry write and one FSM call, the FSM call after
    /// the gate returned. Red mutants: the write before the witness; the FSM call inside the gate.
    @Test
    void request_freshTarget_writesAndCallsTheFsmOnlyAfterTheWitnessIsAccepted_inThatOrder() {
        var gate = owning("A");
        var request = sink.request(TARGET, gate);

        assertThat(port.submitCount()).as("one witness").isEqualTo(1);
        assertThat(registryAdds).as("no write before the witness is accepted").isEmpty();
        assertThat(fsmCalls).as("no FSM call before the witness is accepted").isEmpty();
        assertThat(gate.runIfOwnedCalls).as("the gate waits for the accept").hasValue(0);
        assertThat(request.isResolved()).as("the request waits for the commit").isFalse();

        port.accept(0);

        assertThat(request.await().isSuccess()).isTrue();
        assertThat(registryAdds).containsExactly(TARGET);
        assertThat(fsmCalls).containsExactly(TARGET);
        assertThat(events).as("the registry write is under the gate, the FSM call after the gate returned")
                          .containsExactly("A:gate-enter", "registry-add", "A:gate-exit", "fsm");
        assertThat(sink.inFlight(TARGET).isEmpty()).as("the intent ends with the accepted witness").isTrue();
        assertThat(sink.pending()).as("registered, so still pending").containsExactly(TARGET);
    }

    /// TB1, refused: neither the write nor the FSM call, no gate run, and the intent removed. Red mutant: the intent kept.
    @Test
    void request_witnessRefused_writesNothing_callsNoFsm_andRemovesTheIntent() {
        var gate = owning("A");
        var request = sink.request(TARGET, gate);

        port.refuse(0, REFUSED);

        assertThat(failureOf(request.await())).isEqualTo(REFUSED);
        assertThat(registryAdds).isEmpty();
        assertThat(fsmCalls).isEmpty();
        assertThat(gate.runIfOwnedCalls).as("a refused witness runs no gate").hasValue(0);
        assertThat(sink.inFlight(TARGET).isEmpty()).as("the intent is removed").isTrue();
        assertThat(sink.pending()).as("nothing in flight and nothing registered").isEmpty();

        sink.request(TARGET, owning("B"));

        assertThat(port.submitCount()).as("with the intent gone, the next request opens a new witness").isEqualTo(2);
    }

    // ---- TB3

    /// TB3, in flight: a repeat while the witness is in flight submits no second witness. Red mutant: a witness per call.
    @Test
    void request_repeatWhileInFlight_submitsNoSecondWitness() {
        sink.request(TARGET, owning("A"));
        sink.request(TARGET, owning("B"));

        assertThat(port.submitCount()).isEqualTo(1);
    }

    /// TB3, registered: a repeat once the target is registered submits no second witness. Red mutant: a witness per call.
    @Test
    void request_repeatOnceRegistered_submitsNoSecondWitness() {
        sink.request(TARGET, owning("A"));
        port.accept(0);

        var repeat = sink.request(TARGET, owning("B"));

        assertThat(repeat.await().isSuccess()).isTrue();
        assertThat(port.submitCount()).isEqualTo(1);
    }

    // ---- TB11

    /// TB11, read order: a registry whose `drainTargets()` read performs the move (the witness is accepted: registry write, then
    /// the intent removed) and returns the pre-move snapshot. `pending()` reads the intents first, so it still contains the target.
    /// Red mutant: the registry read first.
    @Test
    void pending_aMoveDuringTheRegistryRead_stillContainsTheTarget() {
        var armed = new AtomicBoolean();
        var moves = new AtomicInteger();
        var movingSink = FencedDrainSink.fencedDrainSink(port,
                                                         this::registryAdd,
                                                         () -> readThenMove(armed, moves),
                                                         new AtomicReference<>(fsm));

        movingSink.request(TARGET, owning("A"));
        armed.set(true);

        assertThat(movingSink.pending()).as("the intent was read before the move, the registry snapshot before it too")
                                        .containsExactly(TARGET);
        assertThat(moves).as("the move happened during the registry read").hasValue(1);
        assertThat(registry).as("the move registered the target").containsExactly(TARGET);
        assertThat(movingSink.inFlight(TARGET).isEmpty()).as("the move ended the intent").isTrue();
    }

    private Set<NodeId> readThenMove(AtomicBoolean armed, AtomicInteger moves) {
        var snapshot = Set.copyOf(registry);

        if (armed.compareAndSet(true, false)) {
            moves.incrementAndGet();
            port.accept(0);
        }

        return snapshot;
    }

    /// TB11, write order: during the registry write the target is still in flight, so a concurrent `pending()` sees it in the
    /// intents before the registry holds it. Red mutant: the intent removed before the registry write.
    @Test
    void pending_duringTheRegistryWrite_containsTheTargetThroughTheIntent() {
        var sinkRef = new AtomicReference<FencedDrainSink>();
        var seenDuringTheWrite = new CopyOnWriteArrayList<Boolean>();
        var observingSink = FencedDrainSink.fencedDrainSink(port,
                                                            target -> observeThenRegister(sinkRef, seenDuringTheWrite, target),
                                                            this::drainTargets,
                                                            new AtomicReference<>(fsm));

        sinkRef.set(observingSink);
        observingSink.request(TARGET, owning("A"));
        port.accept(0);

        assertThat(seenDuringTheWrite).as("pending() read inside the registry write, before the registry holds the target")
                                      .containsExactly(true);
    }

    private void observeThenRegister(AtomicReference<FencedDrainSink> sinkRef, List<Boolean> seen, NodeId target) {
        seen.add(sinkRef.get().pending().contains(target));
        registryAdd(target);
    }

    // ---- TB12

    /// TB12, shared witness: two requests with two gates share one witness, and each gate runs once. Red mutant: the second request
    /// takes the first's promise and drops its own gate.
    @Test
    void request_twoGatesOnOneWitness_runEachGateOnce() {
        var gateA = owning("A");
        var gateB = owning("B");
        var requestA = sink.request(TARGET, gateA);
        var requestB = sink.request(TARGET, gateB);

        assertThat(port.submitCount()).as("one witness for both").isEqualTo(1);

        port.accept(0);

        assertThat(requestA.await().isSuccess()).isTrue();
        assertThat(requestB.await().isSuccess()).isTrue();
        assertThat(gateA.runIfOwnedCalls).hasValue(1);
        assertThat(gateB.runIfOwnedCalls).as("the second request's own gate ran").hasValue(1);
    }

    /// TB12, each answer through its own gate: with gate A `false` and gate B `true`, A fails with `AttemptCancelled` and B succeeds.
    /// Red mutant: the second request takes the first's promise and drops its own gate.
    @Test
    void request_gateAFalseGateBTrue_failsAWithAttemptCancelled_andSucceedsB() {
        var gateB = owning("B");
        var requestA = sink.request(TARGET, notOwning("A"));
        var requestB = sink.request(TARGET, gateB);

        port.accept(0);

        assertThat(failureOf(requestA.await())).isEqualTo(AttemptCancelled.attemptCancelled());
        assertThat(requestB.await().isSuccess()).isTrue();
        assertThat(gateB.runIfOwnedCalls).hasValue(1);
        assertThat(registryAdds).as("only gate B wrote").containsExactly(TARGET);
        assertThat(fsmCalls).as("only gate B's request reached the FSM").containsExactly(TARGET);
    }

    // ---- TB13

    /// TB13: a registered target runs the gate once, adds no intent, and starts no witness. Red mutant: the intent added before the
    /// registry check.
    @Test
    void request_registeredTarget_runsTheGateOnce_addsNoIntent_submitsNoWitness() {
        var gate = owning("A");

        registry.add(TARGET);

        var request = sink.request(TARGET, gate);

        assertThat(request.await().isSuccess()).isTrue();
        assertThat(gate.runIfOwnedCalls).hasValue(1);
        assertThat(port.submitCount()).as("no witness").isZero();
        assertThat(sink.inFlight(TARGET).isEmpty()).as("no intent").isTrue();
    }

    // ---- TB5g

    /// TB5g, fresh target: a gate that answers `ownerMonitorHeld()` `true` at entry fails with `CalledUnderOwnerMonitor`, logged at
    /// ERROR, with no intent, no submit, no registry add and no FSM call. Red mutant: the guard read after `runIfOwned`.
    @Test
    void request_monitorHeldAtEntry_freshTarget_failsAndTouchesNothing() {
        var gate = holdingTheMonitor("H");

        try (var capture = LogCapture.capture(FencedDrainSink.class, Level.ERROR, false)) {
            var outcome = sink.request(TARGET, gate).await();

            assertThat(failureOf(outcome)).isEqualTo(CalledUnderOwnerMonitor.calledUnderOwnerMonitor(TARGET));
            assertThat(capture.messages(Level.ERROR)).singleElement()
                                                     .asString()
                                                     .contains(TARGET.toString());
        }
        assertThat(gate.runIfOwnedCalls).hasValue(0);
        assertThat(port.submitCount()).as("no submit").isZero();
        assertThat(sink.inFlight(TARGET).isEmpty()).as("no intent").isTrue();
        assertThat(registryAdds).isEmpty();
        assertThat(fsmCalls).isEmpty();
    }

    /// TB5g, joining target: the refused request does not join the witness in flight, and the witness still runs only its own caller.
    @Test
    void request_monitorHeldAtEntry_joiningTarget_failsAndJoinsNothing() {
        var first = owning("A");
        var held = holdingTheMonitor("H");

        sink.request(TARGET, first);

        try (var capture = LogCapture.capture(FencedDrainSink.class, Level.ERROR, false)) {
            var outcome = sink.request(TARGET, held).await();

            assertThat(failureOf(outcome)).isEqualTo(CalledUnderOwnerMonitor.calledUnderOwnerMonitor(TARGET));
            assertThat(capture.messages(Level.ERROR)).singleElement()
                                                     .asString()
                                                     .contains(TARGET.toString());
        }
        assertThat(port.submitCount()).as("no second submit").isEqualTo(1);
        assertThat(registryAdds).as("no registry add before the witness is accepted").isEmpty();

        port.accept(0);

        assertThat(held.runIfOwnedCalls).as("the refused gate never ran").hasValue(0);
        assertThat(first.runIfOwnedCalls).hasValue(1);
        assertThat(registryAdds).as("the one add is the first caller's").containsExactly(TARGET);
        assertThat(fsmCalls).containsExactly(TARGET);
    }

    /// TB5g, registered target: the refused request runs no gate, adds nothing, calls no FSM.
    @Test
    void request_monitorHeldAtEntry_registeredTarget_failsAndTouchesNothing() {
        var gate = holdingTheMonitor("H");

        registry.add(TARGET);

        try (var capture = LogCapture.capture(FencedDrainSink.class, Level.ERROR, false)) {
            var outcome = sink.request(TARGET, gate).await();

            assertThat(failureOf(outcome)).isEqualTo(CalledUnderOwnerMonitor.calledUnderOwnerMonitor(TARGET));
            assertThat(capture.messages(Level.ERROR)).singleElement()
                                                     .asString()
                                                     .contains(TARGET.toString());
        }
        assertThat(gate.runIfOwnedCalls).hasValue(0);
        assertThat(port.submitCount()).isZero();
        assertThat(registryAdds).isEmpty();
        assertThat(fsmCalls).isEmpty();
    }

    /// TB5g, the accept continuation: a gate that answers `true` only after entry is refused before its `runIfOwned`, so the registry
    /// is not written. Red mutants: the guard read at entry only; the guard read after `runIfOwned`.
    @Test
    void request_monitorHeldOnlyOnAccept_isRefusedBeforeRunIfOwned_withNoRegistryAdd() {
        var gate = holdingTheMonitorOnlyAfterEntry("X");
        var request = sink.request(TARGET, gate);

        assertThat(port.submitCount()).as("the entry read answered false: a witness was opened").isEqualTo(1);

        try (var capture = LogCapture.capture(FencedDrainSink.class, Level.ERROR, false)) {
            port.accept(0);

            assertThat(failureOf(request.await())).isEqualTo(CalledUnderOwnerMonitor.calledUnderOwnerMonitor(TARGET));
            assertThat(capture.messages(Level.ERROR)).singleElement()
                                                     .asString()
                                                     .contains(TARGET.toString());
        }
        assertThat(gate.runIfOwnedCalls).as("refused before runIfOwned").hasValue(0);
        assertThat(registryAdds).as("no registry add").isEmpty();
        assertThat(fsmCalls).as("no FSM call").isEmpty();
        assertThat(sink.inFlight(TARGET).isEmpty()).as("the intent ended").isTrue();
    }

    // ---- TB5f-r

    /// TB5f-r, registered path (design §3.2 row 1): the FSM is called after the gate's `runIfOwned` returned, never while the gate's
    /// monitor is held. Red mutant: the FSM call inside the gate.
    @Test
    void request_registeredTarget_callsTheFsmOutsideTheGatesMonitor() {
        registry.add(TARGET);

        assertThat(sink.request(TARGET, owning("A")).await().isSuccess()).isTrue();

        assertThat(fsmCalls).as("the FSM was called, so the monitor reading below is not vacuous").containsExactly(TARGET);
        assertThat(fsmHeldTheOwnerMonitor).containsExactly(false);
    }

    /// TB5f-r, fresh path: the same, through the accepted witness.
    @Test
    void request_freshTarget_callsTheFsmOutsideTheGatesMonitor() {
        var request = sink.request(TARGET, owning("A"));

        port.accept(0);

        assertThat(request.await().isSuccess()).isTrue();
        assertThat(fsmCalls).containsExactly(TARGET);
        assertThat(fsmHeldTheOwnerMonitor).containsExactly(false);
    }
}
