// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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

    // ---- #3780 edit round: A1 settle order, A2 closed intent, lane (b) gaps

    private static final TimeSpan BOUND = TimeSpan.timeSpan(5).seconds();
    private static final Duration BOUND_DURATION = Duration.ofSeconds(5);

    private final List<String> observed = new CopyOnWriteArrayList<>();
    private final List<String> observedInFlight = new CopyOnWriteArrayList<>();
    private final List<String> observedPending = new CopyOnWriteArrayList<>();

    /// A dependent step chained on a promise runs on the thread that resolves it, inside the resolve: it reads the sink at the first
    /// moment its caller could have seen the outcome.
    private Promise<Unit> observeSink(String who) {
        observed.add(who);
        if (sink.inFlight(TARGET).isPresent()) {
            observedInFlight.add(who);
        }
        if (sink.pending().contains(TARGET)) {
            observedPending.add(who);
        }

        return Promise.unitPromise();
    }

    private void awaitObserved(int count) {
        await().atMost(BOUND_DURATION)
               .untilAsserted(() -> assertThat(observed).as("every chained observer ran").hasSize(count));
    }

    /// A1, accepted: the opener, a joiner and a promise chained on the witness's outcome observe their outcome only after the request
    /// left `intents`. Red mutants: the callers resolved before the intent is removed, at the accepted settle.
    @Test
    void request_acceptedWitness_noCallerAndNoChainedPromiseObservesItsOutcomeWhileTheRequestIsInFlight() {
        var opener = sink.request(TARGET, owning("A"));
        var joiner = sink.request(TARGET, owning("B"));
        var outcome = sink.inFlight(TARGET).unwrap();

        opener.fold(_ -> observeSink("opener"));
        joiner.fold(_ -> observeSink("joiner"));
        outcome.fold(_ -> observeSink("outcome"));
        port.accept(0);

        awaitObserved(3);
        assertThat(observedInFlight).as("callers that saw the request still in flight when their outcome arrived").isEmpty();
    }

    /// A1, refused: the same for a refused witness, with `pending()` as well (nothing is registered, so a leftover intent is the only
    /// way the target stays pending). Red mutants: the callers failed before the intent is removed; the outcome resolved before the
    /// callers are settled.
    @Test
    void request_refusedWitness_noCallerAndNoChainedPromiseObservesItsOutcomeWhileTheRequestIsInFlight() {
        var opener = sink.request(TARGET, owning("A"));
        var joiner = sink.request(TARGET, owning("B"));
        var outcome = sink.inFlight(TARGET).unwrap();

        opener.fold(_ -> observeSink("opener"));
        joiner.fold(_ -> observeSink("joiner"));
        outcome.fold(_ -> observeSink("outcome"));
        port.refuse(0, REFUSED);

        awaitObserved(3);
        assertThat(observedInFlight).as("callers that saw the request still in flight when their refusal arrived").isEmpty();
        assertThat(observedPending).as("callers that saw the target still pending when their refusal arrived").isEmpty();
    }

    /// A1, the observed read of the refused path, repeated: the test thread reads right after the refusal's `await`. Under the old order
    /// this failed in about 3 of 100 runs; it now reads the settled state every time.
    @Test
    void request_refusedWitness_theStateReadRightAfterTheAwaitIsSettled_onEveryRepeat() {
        var stale = 0;

        for (int round = 0; round < 2000; round++) {
            var repeatPort = ScriptedFencePort.scriptedFencePort();
            var repeatSink = FencedDrainSink.fencedDrainSink(repeatPort, _ -> {}, Set::of, new AtomicReference<MembershipFsm>());
            var request = repeatSink.request(TARGET, DrainGate.always());

            repeatPort.refuse(0, REFUSED);
            request.await(BOUND);
            if (repeatSink.inFlight(TARGET).isPresent() || !repeatSink.pending().isEmpty()) {
                stale++;
            }
        }
        assertThat(stale).as("rounds in which the request was still in flight after its refusal was awaited").isZero();
    }

    /// A2 (P1), refused: a request chained on a refusal opens a fresh witness and is answered by it. The intent was removed before the
    /// chained request ran, so it opens, never joins the dead one.
    @Test
    void request_chainedOnARefusal_opensAFreshWitness_andIsAnswered() {
        var first = sink.request(TARGET, owning("A"));
        var chained = first.fold(_ -> sink.request(TARGET, owning("B")));

        port.refuse(0, REFUSED);
        await().atMost(BOUND_DURATION).until(() -> port.submitCount() == 2);
        port.accept(1);

        assertThat(chained.await(BOUND).isSuccess()).as("expected: the request chained on the refusal is answered by a fresh witness").isTrue();
        assertThat(registryAdds).containsExactly(TARGET);
    }

    private final AtomicReference<Promise<Unit>> lateRequest = new AtomicReference<>();

    private Set<NodeId> arriveWhileClosed(AtomicReference<FencedDrainSink> sinkRef, AtomicBoolean armed) {
        if (armed.compareAndSet(true, false)) {
            lateRequest.set(sinkRef.get().request(TARGET, owning("late")));
        }

        return Set.copyOf(registry);
    }

    /// A2 (P1), accepted: a request that arrives after the accepted witness took its last batch (the intent is closed) and before the
    /// intent is removed is sent to a fresh decision. The registry read of the accepted continuation is the one point in that window
    /// where code outside the sink runs. Red mutants: `retryAfterClosed` answers a promise nobody resolves (a hang, caught here by the
    /// bound, not by the test timeout); `retryAfterClosed` does not remove the closed intent.
    @Test
    void request_arrivingWhileTheAcceptedWitnessIsClosedButNotRemoved_isAnsweredByAFreshWitness() {
        var sinkRef = new AtomicReference<FencedDrainSink>();
        var armed = new AtomicBoolean();
        var windowSink = FencedDrainSink.fencedDrainSink(port,
                                                         this::registryAdd,
                                                         () -> arriveWhileClosed(sinkRef, armed),
                                                         new AtomicReference<>(fsm));

        sinkRef.set(windowSink);
        var first = windowSink.request(TARGET, notOwning("first"));

        armed.set(true);
        port.accept(0);

        assertThat(lateRequest.get()).as("the late request was made inside the window").isNotNull();
        assertThat(port.submitCount()).as("expected: the request that found the closed intent opened a fresh witness").isEqualTo(2);
        assertThat(lateRequest.get().isResolved()).as("it waits for its own witness").isFalse();
        port.accept(1);

        assertThat(lateRequest.get().await(BOUND).isSuccess()).as("expected: the late request is answered by its fresh witness").isTrue();
        assertThat(failureOf(first.await(BOUND))).as("the first caller's gate wrote nothing").isEqualTo(AttemptCancelled.attemptCancelled());
        assertThat(registryAdds).as("only the late request's gate wrote").containsExactly(TARGET);
    }

    /// Lane (b), sink-joiner-gets-witness-outcome: the opener's gate answers `true` and the joiner's `false`; the joiner is answered
    /// through its own gate (`AttemptCancelled`) while the witness's outcome is a success. Red mutant: the joiner is handed the
    /// witness outcome.
    @Test
    void request_openerGateTrueJoinerGateFalse_joinerFailsWithAttemptCancelled_whileTheWitnessOutcomeSucceeds() {
        var opener = sink.request(TARGET, owning("A"));
        var joiner = sink.request(TARGET, notOwning("B"));
        var outcome = sink.inFlight(TARGET).unwrap();

        port.accept(0);

        assertThat(opener.await(BOUND).isSuccess()).isTrue();
        assertThat(failureOf(joiner.await(BOUND))).as("the joiner is answered through its own gate").isEqualTo(AttemptCancelled.attemptCancelled());
        assertThat(outcome.await(BOUND).isSuccess()).as("the witness outcome is the registry's: the opener's gate wrote").isTrue();
    }

    // ---- the intent's close protocol

    private static FencedDrainSink.Joined someCaller() {
        return FencedDrainSink.Joined.joined(DrainGate.always());
    }

    /// The close protocol: a take that answers empty closes the intent, and a join after it is refused (the caller would never be run).
    /// Red mutants: `join` ignores `closed`; `takeOrClose` never closes.
    @Test
    void intent_joinAfterATakeThatAnsweredEmpty_isRefused() {
        var intent = FencedDrainSink.Intent.intent(someCaller());

        assertThat(intent.takeOrClose()).as("the first take hands out the opener").hasSize(1);
        assertThat(intent.join(someCaller())).as("a join between two takes is accepted").isTrue();
        assertThat(intent.takeOrClose()).as("the joiner is in the next batch").hasSize(1);
        assertThat(intent.takeOrClose()).as("nothing joined since: this take closes").isEmpty();
        assertThat(intent.join(someCaller())).as("expected: a join after the closing take is refused, nobody would run it").isFalse();
    }

    @Test
    void intent_closeAll_handsOutTheCallersThatNeverRan_andRefusesLaterJoins() {
        var intent = FencedDrainSink.Intent.intent(someCaller());

        assertThat(intent.closeAll()).hasSize(1);
        assertThat(intent.join(someCaller())).as("expected: a join after closeAll is refused").isFalse();
    }

    // ---- the race the window test cannot reach: many callers on a stale closed intent

    private static final int RACE_ROUNDS = 3000;
    private static final int RACERS = 8;

    /// Many requests found the closed intent at once. Each retries: exactly one removes the closed intent and opens the fresh witness;
    /// the others, which hold the closed intent, must join or reopen against the registry, never remove the fresh intent. The accepted
    /// witness's registry read is the window (the pump thread is held there while the racers run), so the intent is closed and present
    /// for all of them. A witness per racer, or a racer never answered, is the failure. Red mutants: `retryAfterClosed` removes any
    /// intent, not the closed one; `join` ignores `closed`; `takeOrClose` never closes (the racers' answers never come).
    @Test
    @Timeout(120)
    void request_manyRacersOnAClosedIntent_openOneFreshWitness_andAllAreAnswered() {
        var failures = new ArrayList<String>();

        for (int round = 0; round < RACE_ROUNDS; round++) {
            raceRound(round, failures);
        }
        assertThat(failures).as("rounds that opened a witness per racer, or left a racer unanswered").isEmpty();
    }

    private void raceRound(int round, List<String> failures) {
        var racePort = ScriptedFencePort.scriptedFencePort();
        var raceRegistry = ConcurrentHashMap.<NodeId> newKeySet();
        var sinkRef = new AtomicReference<FencedDrainSink>();
        var armed = new AtomicBoolean();
        var racers = new CopyOnWriteArrayList<Promise<Unit>>();
        var windowWitnesses = new AtomicInteger();
        var raceSink = FencedDrainSink.fencedDrainSink(racePort,
                                                       raceRegistry::add,
                                                       () -> holdTheWindowOpen(sinkRef, armed, racers, racePort, windowWitnesses, raceRegistry),
                                                       new AtomicReference<MembershipFsm>());

        sinkRef.set(raceSink);
        var first = raceSink.request(TARGET, writesNothing());

        armed.set(true);
        racePort.accept(0);
        for (int index = 1; index < racePort.submitCount(); index++) {
            racePort.accept(index);
        }
        recordRaceOutcome(round, failures, racers, windowWitnesses.get());
        first.await(BOUND);
    }

    private Set<NodeId> holdTheWindowOpen(AtomicReference<FencedDrainSink> sinkRef,
                                          AtomicBoolean armed,
                                          List<Promise<Unit>> racers,
                                          ScriptedFencePort racePort,
                                          AtomicInteger windowWitnesses,
                                          Set<NodeId> raceRegistry) {
        if (armed.compareAndSet(true, false)) {
            runRacers(sinkRef.get(), racers);
            windowWitnesses.set(racePort.submitCount());
        }

        return Set.copyOf(raceRegistry);
    }

    private static void runRacers(FencedDrainSink raceSink, List<Promise<Unit>> racers) {
        var go = new CountDownLatch(1);
        var threads = new ArrayList<Thread>();

        for (int index = 0; index < RACERS; index++) {
            threads.add(Thread.ofPlatform().start(() -> racers.add(requestAfter(go, raceSink))));
        }
        go.countDown();
        threads.forEach(FencedDrainSinkTest::joinBounded);
    }

    private static Promise<Unit> requestAfter(CountDownLatch go, FencedDrainSink raceSink) {
        awaitLatch(go);

        return raceSink.request(TARGET, DrainGate.always());
    }

    private static void awaitLatch(CountDownLatch go) {
        try {
            go.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinBounded(Thread thread) {
        try {
            thread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void recordRaceOutcome(int round, List<String> failures, List<Promise<Unit>> racers, int windowWitnesses) {
        if (windowWitnesses != 2) {
            failures.add("round " + round + ": " + windowWitnesses + " witnesses submitted by the end of the window, expected 2 (the first and one fresh)");
        }
        var unanswered = racers.stream().filter(racer -> !racer.await(BOUND).isSuccess()).count();

        if (unanswered > 0 || racers.size() != RACERS) {
            failures.add("round " + round + ": " + unanswered + " of " + racers.size() + " racers unanswered");
        }
    }

    private static DrainGate writesNothing() {
        return new DrainGate() {
            @Override
            public boolean runIfOwned(Runnable write) {
                return false;
            }

            @Override
            public boolean ownerMonitorHeld() {
                return false;
            }
        };
    }
}
