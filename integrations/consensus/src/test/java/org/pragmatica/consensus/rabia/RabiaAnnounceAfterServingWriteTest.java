// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.consensus.rabia;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
/// emitted. Both transitions that a restore reaches through the engine's one write path are driven, the voter's
/// activation and the observer's activation.
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

        assertThat(awaitCondition(() -> observation.hookObservation.get() != null)).as("the announce ran").isTrue();
        assertThat(observation.replayObservation.get()).as("replay: serving state written").startsWith("serving=true");
        assertThat(observation.replayObservation.get()).as("replay: active event already published").contains("ConsensusActive");
        assertThat(observation.hookObservation.get()).as("listener: serving state written").startsWith("serving=true");
        assertThat(observation.hookObservation.get()).as("listener: active event already published").contains("ConsensusActive");
    }

    @Test
    void observerActivation_announceSeesTheServingStateAndTheObservingEventAlreadyPublished() {
        var observation = new Observation();
        var engine = engineWith(observation, true);

        engine.processSyncResponse(cold(NODE_2));
        engine.processSyncResponse(cold(NODE_3));

        assertThat(awaitCondition(() -> observation.hookObservation.get() != null)).as("the announce ran").isTrue();
        assertThat(observation.replayObservation.get()).as("replay: serving state written").startsWith("serving=true");
        assertThat(observation.replayObservation.get()).as("replay: observing event already published").contains("ConsensusObserving");
        assertThat(observation.hookObservation.get()).as("listener: serving state written").startsWith("serving=true");
        assertThat(observation.hookObservation.get()).as("listener: observing event already published").contains("ConsensusObserving");
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
        engine.onStateRestored(() -> observation.hookObservation.set(observation.describe()));
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

    /// What the engine looked like, and which events it had published, when the announce ran.
    private static final class Observation {
        final AtomicReference<RabiaEngine<TestCommand>> engine = new AtomicReference<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicReference<String> replayObservation = new AtomicReference<>();
        final AtomicReference<String> hookObservation = new AtomicReference<>();

        String describe() {
            var current = engine.get();

            return "serving=" + (current.isActive() || current.isObserving()) + " events=" + events;
        }
    }

    private static final class ObservingStateMachine extends TestStateMachine {
        private final Observation observation;

        ObservingStateMachine(Observation observation) {
            this.observation = observation;
        }

        @Override
        public Unit replayNotifications() {
            observation.replayObservation.compareAndSet(null, observation.describe());

            return Unit.unit();
        }
    }
}
