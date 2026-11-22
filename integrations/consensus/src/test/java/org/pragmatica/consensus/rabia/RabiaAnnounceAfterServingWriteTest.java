// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.consensus.rabia;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2318 — the announce of a restore runs AFTER the serving state is written and published. `EngineServingWritePathTest`
/// pins that order as source text, which a mutant that moves the announce earlier can slip past while the text still
/// matches; this test observes it. At the moment the announce's two effects run (the notification replay and then the
/// post-restore listener) it reads the engine's own state and the events published so far: the engine must already
/// stand in the serving state, and the matching `ConsensusActive` / `ConsensusObserving` event must already have been
/// emitted. All six sites that call the announce are driven: the voter's activation, the observer's activation, a
/// resume from pause, an observer's promotion to voter, an added voter's promotion out of `Observing`, and a voter
/// demoted to observer that applies a decision. The first two get their pending restore from a real sync restore; the
/// other four have no public path that leaves a restore unannounced while the engine is already serving, so the test
/// marks one pending through the engine's private `restoreAnnouncePending` flag (and, for the last two, flips the
/// private `observerMode` flag, which a roster change flips in production). That seeding is the only reflection.
class RabiaAnnounceAfterServingWriteTest {
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();
    private static final Phase PEER_PHASE = Phase.phase(10);
    private static final byte[] PEER_SNAPSHOT = {1, 2, 3};
    private static final long TIMEOUT_MILLIS = 5_000;

    private final List<RabiaEngine<TestCommand>> engines = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        engines.forEach(engine -> engine.stop().await());
    }

    @Test
    void voterActivation_announceSeesTheServingStateAndTheActiveEventAlreadyPublished() {
        var observation = new Observation();
        var engine = engineWith(observation, false);

        engine.processSyncResponse(cold(NODE_2));

        assertAnnounced(observation, "state=active last=ConsensusActive");
    }

    @Test
    void observerActivation_announceSeesTheServingStateAndTheObservingEventAlreadyPublished() {
        var observation = new Observation();
        var engine = engineWith(observation, true);

        engine.processSyncResponse(cold(NODE_2));
        engine.processSyncResponse(cold(NODE_3));

        assertAnnounced(observation, "state=observing last=ConsensusObserving");
    }

    /// `doResumeFromPause`: an activated voter that lost quorum and regained it.
    @Test
    void resumeFromPause_announceSeesTheServingStateAndTheActiveEventAlreadyPublished() {
        var observation = new Observation();
        var engine = engineWith(observation, false);

        engine.processSyncResponse(cold(NODE_2));
        assertAnnounced(observation, "state=active last=ConsensusActive");
        engine.clusterState(ClusterStateNotification.passive());
        assertThat(awaitCondition(engine::isPaused)).as("quorum loss pauses the activated voter").isTrue();
        observation.reset();
        markRestorePending(engine);
        engine.clusterState(ClusterStateNotification.active());

        assertAnnounced(observation, "state=active last=ConsensusActive");
    }

    /// `promoteObserverToActive`: an observing node that is authorized to vote is promoted without a re-sync.
    @Test
    void observerPromotion_announceSeesTheServingStateAndTheActiveEventAlreadyPublished() {
        var observation = new Observation();
        var engine = engineWith(observation, true);

        engine.processSyncResponse(cold(NODE_2));
        engine.processSyncResponse(cold(NODE_3));
        assertAnnounced(observation, "state=observing last=ConsensusObserving");
        observation.reset();
        markRestorePending(engine);
        engine.authorizeActivation();

        assertAnnounced(observation, "state=active last=ConsensusActive");
    }

    /// `authorizeActivation`: authorizing a declared observer clears `observerMode`, so the promoted node is a voter
    /// afterwards. No pending restore is marked and no pause is queued; the announce ordering is not involved.
    @Test
    void observerPromotion_clearsObserverMode() {
        var observation = new Observation();
        var engine = engineWith(observation, true);

        engine.processSyncResponse(cold(NODE_2));
        engine.processSyncResponse(cold(NODE_3));
        assertAnnounced(observation, "state=observing last=ConsensusObserving");
        assertThat(flag(engine, "observerMode")).as("a gated observer starts in observer mode").isTrue();
        engine.authorizeActivation();

        assertThat(flag(engine, "observerMode")).as("authorization promotes the observer to a voter").isFalse();
    }

    /// `promoteAddedVoter`: an observer whose roster entry was added applies the next decision as a voter.
    @Test
    void addedVoter_announceSeesTheServingStateAndTheActiveEventAlreadyPublished() {
        var observation = new Observation();
        var engine = engineWith(observation, true);

        engine.processSyncResponse(cold(NODE_2));
        engine.processSyncResponse(cold(NODE_3));
        assertAnnounced(observation, "state=observing last=ConsensusObserving");
        observation.reset();
        markRestorePending(engine);
        setFlag(engine, "observerMode", false);
        engine.processDecision(frontierDecision(engine));

        assertAnnounced(observation, "state=active last=ConsensusActive");
    }

    /// `advancePhaseAsObserver`: a serving voter that a roster change turned into an observer applies a decision and
    /// moves to `Observing`. From an already-observing node the write changes nothing, so this is the arm that can tell.
    @Test
    void demotedVoter_announceSeesTheObservingStateAndTheDemotedEventAlreadyPublished() {
        var observation = new Observation();
        var engine = engineWith(observation, false);

        engine.processSyncResponse(cold(NODE_2));
        assertAnnounced(observation, "state=active last=ConsensusActive");
        observation.reset();
        markRestorePending(engine);
        setFlag(engine, "observerMode", true);
        engine.processDecision(frontierDecision(engine));

        assertAnnounced(observation, "state=observing last=ConsensusDemoted");
    }

    /// The replay and then the listener ran, and each saw `expected` at the moment it ran.
    private static void assertAnnounced(Observation observation, String expected) {
        assertThat(awaitCondition(() -> !observation.hooks.isEmpty())).as("the announce ran").isTrue();
        assertThat(observation.replays).as("replay saw the serving state and its event").singleElement().asString().startsWith(expected);
        assertThat(observation.hooks).as("listener saw the serving state and its event").singleElement().asString().startsWith(expected);
    }

    private static Decision<TestCommand> frontierDecision(RabiaEngine<TestCommand> engine) {
        return new Decision<>(NODE_2, engine.currentPhaseForTesting(), StateValue.V0, StateMachine.Batch.<TestCommand>emptyBatch());
    }

    private static void markRestorePending(RabiaEngine<TestCommand> engine) {
        try {
            var field = RabiaEngine.class.getDeclaredField("restoreAnnouncePending");

            field.setAccessible(true);
            ((AtomicBoolean) field.get(engine)).set(true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot mark the restore pending", e);
        }
    }

    private static boolean flag(RabiaEngine<TestCommand> engine, String name) {
        try {
            var field = RabiaEngine.class.getDeclaredField(name);

            field.setAccessible(true);
            return field.getBoolean(engine);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot read " + name, e);
        }
    }

    private static void setFlag(RabiaEngine<TestCommand> engine, String name, boolean value) {
        try {
            var field = RabiaEngine.class.getDeclaredField(name);

            field.setAccessible(true);
            field.setBoolean(engine, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot set " + name, e);
        }
    }

    private RabiaEngine<TestCommand> engineWith(Observation observation, boolean gatedObserver) {
        var network = new TestClusterNetwork();
        var machine = new ObservingStateMachine(observation);
        var engine = new RabiaEngine<>(new TestTopologyManager(NODE_1, 3),
                                       network,
                                       machine,
                                       ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()),
                                       ConsensusMetrics.noop(),
                                       gatedObserver,
                                       emptyDisk(),
                                       timeSpan(50).millis(),
                                       event -> observation.events.add(event.getClass().getSimpleName()));

        engines.add(engine);
        observation.engine.set(engine);
        engine.onStateRestored(() -> observation.hooks.add(observation.describe()));
        engine.clusterState(ClusterStateNotification.active());
        if (gatedObserver) {
            engine.authorizeObservation();
        }
        assertThat(awaitCondition(() -> network.getMessages().stream().anyMatch(SyncRequest.class::isInstance)))
            .as("the engine started its sync round before responses are delivered")
            .isTrue();

        return engine;
    }

    private static SyncResponse<TestCommand> cold(NodeId sender) {
        return new SyncResponse<>(sender, SavedState.savedState(PEER_SNAPSHOT, PEER_PHASE, List.of()), ResponderState.COLD);
    }

    private static RabiaPersistence<TestCommand> emptyDisk() {
        record emptyDisk() implements RabiaPersistence<TestCommand> {
            @Override
            public Result<Unit> save(StateMachine<TestCommand> stateMachine,
                                                         Phase lastCommittedPhase,
                                                         Collection<StateMachine.Batch<TestCommand>> pendingBatches) {
                return Result.success(Unit.unit());
            }

            @Override
            public Option<SavedState<TestCommand>> load() {
                return Option.none();
            }
        }

        return new emptyDisk();
    }

    private static boolean awaitCondition(BooleanSupplier condition) {
        var deadline = System.nanoTime() + MILLISECONDS.toNanos(TIMEOUT_MILLIS);

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }

            Thread.onSpinWait();
        }

        return condition.getAsBoolean();
    }

    /// What the engine looked like, and which events it had published, each time the announce ran.
    private static final class Observation {
        final AtomicReference<RabiaEngine<TestCommand>> engine = new AtomicReference<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<String> replays = new CopyOnWriteArrayList<>();
        final List<String> hooks = new CopyOnWriteArrayList<>();

        void reset() {
            replays.clear();
            hooks.clear();
        }

        String describe() {
            var current = engine.get();
            var state = current.isActive()
                        ? "active"
                        : current.isObserving()
                          ? "observing"
                          : current.isPaused()
                            ? "paused"
                            : "other";

            return "state=" + state + " last=" + (events.isEmpty()
                                                  ? "none"
                                                  : events.getLast()) + " events=" + events;
        }
    }

    private static final class ObservingStateMachine extends TestStateMachine {
        private final Observation observation;

        ObservingStateMachine(Observation observation) {
            this.observation = observation;
        }

        @Override
        public Unit replayNotifications() {
            observation.replays.add(observation.describe());

            return Unit.unit();
        }
    }
}
