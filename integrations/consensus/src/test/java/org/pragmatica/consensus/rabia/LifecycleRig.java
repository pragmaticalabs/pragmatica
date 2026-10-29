// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.consensus.rabia;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Propose;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Shared fixture of the L-0 lifecycle tests: one engine under a recording network, a recording event sink, a counting
/// participation marker and a counting state machine, with 60 s timers so that no tick fires unless a test drives it.
/// Every observation goes through public accessors, package-private `*ForTesting` seams, or reflection on the private
/// `engineState` field (the same technique `RabiaStaleSyncInputTest` uses); no main file is touched.
final class LifecycleRig {
    static final NodeId N1 = nodeId("node-1").unwrap();
    static final NodeId N2 = nodeId("node-2").unwrap();
    static final NodeId N3 = nodeId("node-3").unwrap();
    static final NodeId N4 = nodeId("node-4").unwrap();
    static final NodeId N5 = nodeId("node-5").unwrap();
    static final Cause DISK_FULL = Causes.cause("no space left on device");
    static final long FAR = 500;

    volatile java.util.Set<NodeId> peers = java.util.Set.of();
    final TestClusterNetwork network = new TestClusterNetwork() {
        @Override
        public java.util.Set<NodeId> connectedPeers() {
            return peers;
        }
    };
    final List<String> events = new CopyOnWriteArrayList<>();
    final CountingMarker marker = new CountingMarker();
    final CountingMachine machine = new CountingMachine();
    final AtomicInteger hooks = new AtomicInteger();
    final RabiaEngine<TestCommand> engine;
    Promise<Unit> startPromise;
    private int seenEvents;
    private int seenCompletions;
    private int seenMessages;

    private LifecycleRig(boolean gated, int topologySize, RabiaPersistence<TestCommand> persistence, NodeId self) {
        engine = new RabiaEngine<>(new TestTopologyManager(self, topologySize),
                                   network,
                                   machine,
                                   ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds(), Option.some(marker)),
                                   ConsensusMetrics.noop(),
                                   gated,
                                   persistence,
                                   timeSpan(60).seconds(),
                                   event -> events.add(event.getClass().getSimpleName()));
        engine.onStateRestored(hooks::incrementAndGet);
    }

    static LifecycleRig rig(boolean gated) {
        return new LifecycleRig(gated, 3, RabiaPersistence.inMemory(), N1);
    }

    static LifecycleRig rig(boolean gated, RabiaPersistence<TestCommand> persistence) {
        return new LifecycleRig(gated, 3, persistence, N1);
    }

    static LifecycleRig fiveNodeRig() {
        return new LifecycleRig(false, 5, RabiaPersistence.inMemory(), N1);
    }

    /// A genesis-pending node `N2` (configured cores N1..N3, discovered N2, N4, N5) as `GenesisJoinRuleTest` builds it.
    static LifecycleRig genesisPendingRig() {
        var created = new LifecycleRig(false, 5, RabiaPersistence.inMemory(), N2);

        created.peers = java.util.Set.of(N1, N2, N3, N4, N5);
        created.engine.deferGenesis(() -> java.util.Set.of(N2, N4, N5), 3, Option.none(), java.util.Set.of(N1, N2, N3));

        return created;
    }

    LifecycleRig started() {
        startPromise = engine.start();

        return this;
    }

    // ---- observations ----

    /// The private `engineState` record's simple name: `Stopped`, `Syncing`, `Idle`, `InPhase`, `Observing`, `Paused`.
    String mode() {
        try {
            var field = RabiaEngine.class.getDeclaredField("engineState");

            field.setAccessible(true);

            return ((AtomicReference<?>) field.get(engine)).get().getClass().getSimpleName();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /// One transcript record: the mode after the step, the engine events and outgoing messages emitted SINCE the previous
    /// `delta()` call, whether `start()` resolved, and the effect counters.
    String delta() {
        var allEvents = new ArrayList<>(events);
        var allMessages = network.getMessages().stream().map(m -> m.getClass().getSimpleName()).toList();
        var newEvents = allEvents.subList(Math.min(seenEvents, allEvents.size()), allEvents.size());
        var newMessages = allMessages.subList(Math.min(seenMessages, allMessages.size()), allMessages.size());

        var allCompletions = new ArrayList<>(machine.completions);
        var newCompletions = allCompletions.subList(Math.min(seenCompletions, allCompletions.size()), allCompletions.size());

        seenCompletions = allCompletions.size();
        seenEvents = allEvents.size();
        seenMessages = allMessages.size();

        return "mode=" + mode()
               + " events=" + newEvents
               + " msgs=" + newMessages
               + " completions=" + newCompletions
               + " start=" + (startPromise == null ? "none" : startPromise.isResolved() ? "resolved" : "pending")
               + " marker=" + marker.recordCalls.get()
               + " replays=" + machine.replays.get()
               + " hooks=" + hooks.get()
               + " restores=" + machine.restores.get();
    }

    /// Forget everything emitted so far: the next `delta()` shows only what happens after this call.
    LifecycleRig mark() {
        delta();

        return this;
    }

    // ---- drivers ----

    void settle() {
        engine.settleForTesting().await();
        engine.settleForTesting().await();
    }

    void active() {
        engine.clusterState(ClusterStateNotification.active());
        settle();
    }

    void passive() {
        engine.clusterState(ClusterStateNotification.passive());
        settle();
    }

    void answer(NodeId peer, long phase, byte[] snapshot) {
        engine.processSyncResponse(new SyncResponse<>(peer, SavedState.savedState(snapshot, Phase.phase(phase), List.of()), ResponderState.COLD));
    }

    void emptyAnswers() {
        engine.processSyncResponse(new SyncResponse<>(N2, SavedState.empty(), ResponderState.COLD));
        engine.processSyncResponse(new SyncResponse<>(N3, SavedState.empty(), ResponderState.COLD));
        settle();
    }

    void snapshotAnswers(long phase) {
        var snapshot = "peer".getBytes(StandardCharsets.UTF_8);

        answer(N2, phase, snapshot);
        answer(N3, phase, snapshot);
        settle();
    }

    void decisionAt(long ahead) {
        var phase = Phase.phase(engine.currentPhaseForTesting().value() + ahead);

        engine.processDecision(new Decision<>(N2, 0, phase, StateValue.V0, StateMachine.Batch.<TestCommand>emptyBatch(), Option.none()));
        settle();
    }

    void proposeAt(long ahead) {
        var phase = Phase.phase(engine.currentPhaseForTesting().value() + ahead);

        engine.processPropose(new Propose<>(N2, 0, phase, StateMachine.Batch.<TestCommand>emptyBatch(), Option.none()));
        settle();
    }

    /// Puts a quorum notification into the engine's held slot, as the race between `handleClusterActive` and an
    /// authorization leaves it (the unauthorized check passes, the authorization clears the slot, the store lands after).
    @SuppressWarnings("unchecked")
    void holdQuorumNotification() {
        try {
            var field = RabiaEngine.class.getDeclaredField("pendingQuorum");

            field.setAccessible(true);
            ((org.pragmatica.lang.concurrent.AtomicHolder<ClusterStateNotification>) field.get(engine)).set(ClusterStateNotification.active());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    void tick() {
        invoke("synchronize");
    }

    void episodeStart() {
        invoke("doClusterConnected");
    }

    private void invoke(String name) {
        try {
            Method method = RabiaEngine.class.getDeclaredMethod(name);

            method.setAccessible(true);
            engine.safeExecuteForTesting(() -> run(method));
            settle();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void run(Method method) {
        try {
            method.invoke(engine);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    void stop() {
        engine.stop().await();
    }

    // ---- stubs ----

    static final class CountingMarker implements ParticipationMarker {
        final AtomicInteger recordCalls = new AtomicInteger();
        volatile boolean refuse;

        @Override
        public Participation resolve() {
            return Participation.UNKNOWN;
        }

        @Override
        public Result<Unit> recordParticipation() {
            recordCalls.incrementAndGet();

            return refuse
                   ? Causes.cause("participation record not writable").result()
                   : Result.success(Unit.unit());
        }
    }

    static final class CountingMachine extends TestStateMachine {
        final AtomicInteger restores = new AtomicInteger();
        final AtomicInteger replays = new AtomicInteger();
        final List<Long> completions = new CopyOnWriteArrayList<>();
        volatile boolean undecodable;

        /// The W1 hook: recorded so the transcript pins when (and with which frontier) it fires.
        @Override
        public Unit onPhaseCompleted(long nextSlot) {
            completions.add(nextSlot);

            return Unit.unit();
        }

        @Override
        public Result<Unit> restoreCommittedSnapshot(byte[] snapshot, long nextSlot) {
            restores.incrementAndGet();
            if (undecodable) {
                return Causes.cause("cannot decode snapshot row").result();
            }

            return super.restoreCommittedSnapshot(snapshot, nextSlot);
        }

        @Override
        public Unit replayNotifications() {
            replays.incrementAndGet();

            return Unit.unit();
        }
    }

    static RabiaPersistence<TestCommand> failingSave() {
        record failingSave() implements RabiaPersistence<TestCommand> {
            @Override
            public Result<Unit> save(StateMachine<TestCommand> stateMachine, Phase lastCommittedPhase, Collection<StateMachine.Batch<TestCommand>> pendingBatches) {
                return DISK_FULL.result();
            }

            @Override
            public Option<SavedState<TestCommand>> load() {
                return Option.none();
            }
        }

        return new failingSave();
    }
}
