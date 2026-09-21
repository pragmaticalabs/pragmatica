/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
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
package org.pragmatica.consensus.rabia;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Propose;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// #1020 — boot restores this node's OWN durable checkpoint before it collects sync responses.
///
/// `RabiaEngine` read `persistence.load()` for three things — the sync-response payload it serves
/// to peers, the adoption floor, and the boot future-history detector — and never to populate its
/// own state machine. So the branch that activates "on this node's own state" (every response
/// behind self, or a single-node cluster with no peer to adopt from) activated an EMPTY state
/// machine at phase 0: "the majority's most advanced state is already here" was true of the disk
/// and false of the process. After a full-cluster stop with `[backup]` enabled, whether a committed
/// KV record (an API key minted through `/api/v1/cluster/keys`) came back depended on the first
/// responder's persisted phase happening to EQUAL self's — a staggered graceful stop breaks the tie
/// through the last node's quorum-loss pause save, and the node holding the most advanced snapshot
/// came up empty and answered 403 for a key it had acknowledged.
///
/// The fix mirrors #1390's B1 (`ensureRecovered` / `recoverLocalState`, verdict M19): the checkpoint
/// is installed once, on the apply thread, before the first `SyncRequest`, and `currentPhase` is set
/// to its phase. The primary pin is named after #1390's
/// `cleanStopRestoresCheckpointBeforeStalePeersCanActivateOrReplayOldSlots`; rc4 has no slots or
/// voting journal, so the replay arm does not exist here. The controls keep the adoption rule where
/// it was — a responder AHEAD of self is still the source, an empty persisted snapshot installs
/// nothing — and a checkpoint that EXISTS but cannot be read is a refusal to start, never a cold
/// start over history the node cannot see.
class RabiaSyncAdoptionOwnSnapshotTest {
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();
    private static final NodeId NODE_4 = nodeId("node-4").unwrap();
    private static final long ACTIVATION_TIMEOUT_MILLIS = 5_000;
    private static final byte[] OWN_SNAPSHOT = "own".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PEER_SNAPSHOT = "peer".getBytes(StandardCharsets.UTF_8);
    private static final Phase OWN_PHASE = Phase.phase(5);

    private final List<RabiaEngine<TestCommand>> engines = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopEngines() {
        engines.forEach(engine -> engine.stop()
                                        .await());
    }

    /// n=3, self durable at phase 5, the one responder needed by the cold rule is BEHIND at phase 4.
    /// The adoption floor refuses the response — correctly — and the node activates on its own
    /// state. That state is on disk, not in the process: it must be installed.
    @Test
    void cleanStopRestoresCheckpointBeforeStalePeersCanActivate() {
        var stateMachine = new RecordingStateMachine();
        var engine = coldStarted(3, stateMachine, durableAt(OWN_PHASE, OWN_SNAPSHOT));

        engine.processSyncResponse(cold(NODE_2, Phase.phase(4), PEER_SNAPSHOT));
        assertThat(awaitActive(engine)).as("the cold rule is met and self is the floor").isTrue();
        assertThat(stateMachine.lastRestored()).as("activating on own state must install the persisted snapshot, not leave the state machine empty")
                  .isEqualTo(OWN_SNAPSHOT);
        assertThat(engine.currentPhaseForTesting()).as("the live phase must advance to the persisted phase, or the node sits at 0 behind its own commits")
                  .isEqualTo(OWN_PHASE);
    }

    /// n=1: no peer can ever answer, so activation rides the sync retry tick with zero responses.
    /// A single-node restart from disk is the purest form of "own state": nothing else exists.
    @Test
    void singleNode_activatesWithOwnPersistedSnapshotInstalled() {
        var stateMachine = new RecordingStateMachine();
        var engine = coldStarted(1, stateMachine, durableAt(OWN_PHASE, OWN_SNAPSHOT), timeSpan(50).millis());

        assertThat(awaitActive(engine)).as("a single-node cluster activates on its own state").isTrue();
        assertThat(stateMachine.lastRestored()).isEqualTo(OWN_SNAPSHOT);
        assertThat(engine.currentPhaseForTesting()).isEqualTo(OWN_PHASE);
    }

    /// CONTROL — the adoption rule is untouched: a responder AHEAD of self is still the source. Boot
    /// installed self's checkpoint first (the #1390 shape restores before any response is collected),
    /// then adoption installed the responder's over it — two installs, the peer's last.
    @Test
    void responderAhead_adoptsTheResponder_notOwnSnapshot() {
        var stateMachine = new RecordingStateMachine();
        var engine = coldStarted(3, stateMachine, durableAt(OWN_PHASE, OWN_SNAPSHOT));

        engine.processSyncResponse(cold(NODE_2, Phase.phase(10), PEER_SNAPSHOT));
        assertThat(awaitActive(engine)).isTrue();
        assertThat(stateMachine.lastRestored()).as("a response ahead of self remains the source")
                  .isEqualTo(PEER_SNAPSHOT);
        assertThat(stateMachine.restoreCount()).as("own checkpoint at boot, then the ahead responder's").isEqualTo(2);
        assertThat(engine.currentPhaseForTesting()).isEqualTo(Phase.phase(10));
    }

    /// CONTROL — a persisted phase with an EMPTY snapshot (the shape `reconfigure` saves) installs
    /// nothing: `restoreSnapshot` on zero bytes is not a restore, and the phase still carries forward.
    @Test
    void ownSnapshotEmpty_activatesWithoutInstalling_phaseCarriesForward() {
        var stateMachine = new RecordingStateMachine();
        var engine = coldStarted(3, stateMachine, durableAt(OWN_PHASE, new byte[0]));

        engine.processSyncResponse(cold(NODE_2, Phase.phase(4), PEER_SNAPSHOT));
        assertThat(awaitActive(engine)).isTrue();
        assertThat(stateMachine.lastRestored()).as("zero bytes are not a snapshot to install").isNull();
        assertThat(engine.currentPhaseForTesting()).isEqualTo(OWN_PHASE);
    }

    /// CONTROL for the guard — a resync from ACTIVE must NOT regress onto a STALE disk snapshot. The
    /// live phase reaches 99 by adopting a live majority; a far-future Propose forces a resync; a live
    /// majority behind at 50 is refused by the floor, and the node re-activates on its own state. Its
    /// own PERSISTED state sits at phase 5 — `persistence.save` never runs on commit, so the disk lags
    /// the process — and installing it would overwrite phase 99 with phase 5. The persisted snapshot is
    /// installed only when it is AHEAD of the live phase; here it is behind, so nothing is installed.
    /// The persistence fixture ignores `save`, which keeps the disk arm stale through the first adoption.
    @Test
    void resyncFromActive_ownStaleDiskSnapshotIsNotInstalledOverTheLivePhase() {
        var stateMachine = new RecordingStateMachine();
        var engine = coldStarted(5, stateMachine, durableAt(OWN_PHASE, OWN_SNAPSHOT));

        engine.processSyncResponse(live(NODE_2, Phase.phase(99), PEER_SNAPSHOT));
        engine.processSyncResponse(live(NODE_3, Phase.phase(99), PEER_SNAPSHOT));
        engine.processSyncResponse(live(NODE_4, Phase.phase(99), PEER_SNAPSHOT));
        assertThat(awaitActive(engine)).isTrue();
        assertThat(stateMachine.lastRestored()).isEqualTo(PEER_SNAPSHOT);
        assertThat(engine.currentPhaseForTesting()).isEqualTo(Phase.phase(99));
        stateMachine.forgetRestored();
        // Far-future Propose: `MAX_PHASE_AHEAD` past the live phase → triggerResync → Syncing.
        engine.processPropose(new Propose<>(NODE_2, Phase.phase(99 + 200), farFutureBatch()));
        assertThat(awaitCondition(() -> !engine.isActive())).as("far-future Propose forces a resync").isTrue();
        engine.processSyncResponse(live(NODE_2, Phase.phase(50), PEER_SNAPSHOT));
        engine.processSyncResponse(live(NODE_3, Phase.phase(50), PEER_SNAPSHOT));
        engine.processSyncResponse(live(NODE_4, Phase.phase(50), PEER_SNAPSHOT));
        assertThat(awaitActive(engine)).as("the node re-activates on its own state").isTrue();
        assertThat(stateMachine.lastRestored()).as("live phase 99 outranks both the responders' 50 and the disk's 5 — nothing may be installed")
                  .isNull();
        assertThat(engine.currentPhaseForTesting()).as("the live phase is kept, not regressed to the disk's")
                  .isEqualTo(Phase.phase(99));
    }

    /// CONTROL — an amnesiac self (in-memory persistence, nothing on disk) behaves exactly as before:
    /// the cold rule adopts the response, whatever its phase.
    @Test
    void amnesiacSelf_adoptsTheResponder() {
        var stateMachine = new RecordingStateMachine();
        var engine = coldStarted(3, stateMachine, RabiaPersistence.inMemory());

        engine.processSyncResponse(cold(NODE_2, Phase.phase(4), PEER_SNAPSHOT));
        assertThat(awaitActive(engine)).isTrue();
        assertThat(stateMachine.lastRestored()).isEqualTo(PEER_SNAPSHOT);
    }

    /// A checkpoint that EXISTS but cannot be read is a refusal: `start()` fails with the cause, the
    /// engine never leaves `Stopped`, and no `SyncRequest` goes out — a node must not cold-start over
    /// history it cannot see and then answer peers as if it had none.
    @Test
    void unreadableCheckpoint_refusesToStart_withTheCause() throws InterruptedException {
        var stateMachine = new RecordingStateMachine();
        var network = new TestClusterNetwork();
        var engine = engine(3, stateMachine, unreadable(), network);

        engine.clusterState(ClusterStateNotification.active());
        var started = engine.start()
                            .await(timeSpan(2).seconds());

        String message = started.fold(Cause::message, _ -> "");

        assertThat(started.isFailure()).as("start must FAIL, not hang or succeed: %s", started).isTrue();
        assertThat(message).contains(UNREADABLE_MESSAGE);
        assertThat(staysInactive(engine)).isTrue();
        assertThat(stateMachine.lastRestored()).as("nothing installed over an unreadable checkpoint").isNull();
        assertThat(network.getMessages().stream().anyMatch(SyncRequest.class::isInstance))
            .as("a refused engine does not open a sync round")
            .isFalse();
    }

    /// CONTROL for the refusal — an ABSENT checkpoint is the legitimate empty: the sync round opens
    /// and the node starts amnesiac, exactly as before.
    @Test
    void absentCheckpoint_startsTheSyncRound() {
        var stateMachine = new RecordingStateMachine();
        var engine = coldStarted(3, stateMachine, RabiaPersistence.inMemory());

        engine.processSyncResponse(cold(NODE_2, Phase.phase(4), PEER_SNAPSHOT));

        assertThat(awaitActive(engine)).isTrue();
    }

    private static final String UNREADABLE_MESSAGE = "state.toml exists but cannot be restored";

    /// Persistence whose checkpoint exists but cannot be decoded: `loadVerified` fails, `load` (the
    /// responder/floor path) still answers none — the same split `GitBackedPersistence` has.
    private static RabiaPersistence<TestCommand> unreadable() {
        record unreadable() implements RabiaPersistence<TestCommand> {
            @Override
            public Result<Unit> save(StateMachine<TestCommand> stateMachine,
                                     Phase lastCommittedPhase,
                                     Collection<Batch<TestCommand>> pendingBatches) {
                return Result.success(Unit.unit());
            }

            @Override
            public Result<Option<SavedState<TestCommand>>> loadVerified() {
                return Causes.cause(UNREADABLE_MESSAGE).result();
            }

            @Override
            public Option<SavedState<TestCommand>> load() {
                return Option.none();
            }
        }

        return new unreadable();
    }

    /// Persistence reporting a fixed durable snapshot: a node that restarted from disk.
    private static RabiaPersistence<TestCommand> durableAt(Phase phase, byte[] snapshot) {
        record durable(Phase phase, byte[] snapshot) implements RabiaPersistence<TestCommand> {
            @Override
            public Result<Unit> save(StateMachine<TestCommand> stateMachine,
                                     Phase lastCommittedPhase,
                                     Collection<Batch<TestCommand>> pendingBatches) {
                return Result.success(Unit.unit());
            }

            @Override
            public Option<SavedState<TestCommand>> load() {
                return Option.some(SavedState.savedState(snapshot, phase, List.of()));
            }
        }

        return new durable(phase, snapshot);
    }

    private static SyncResponse<TestCommand> live(NodeId sender, Phase phase, byte[] snapshot) {
        return new SyncResponse<>(sender,
                                  SavedState.savedState(snapshot, phase, List.of()),
                                  ResponderState.LIVE);
    }

    private static Batch<TestCommand> farFutureBatch() {
        return new Batch<>(Batch.Id.randomId(),
                           List.of(CorrelationId.randomCorrelationId()),
                           System.nanoTime(),
                           List.of(new TestCommand("resync")));
    }

    private static SyncResponse<TestCommand> cold(NodeId sender, Phase phase, byte[] snapshot) {
        return new SyncResponse<>(sender,
                                  SavedState.savedState(snapshot, phase, List.of()),
                                  ResponderState.COLD);
    }

    private RabiaEngine<TestCommand> coldStarted(int clusterSize,
                                                 StateMachine<TestCommand> stateMachine,
                                                 RabiaPersistence<TestCommand> persistence) {
        return coldStarted(clusterSize, stateMachine, persistence, timeSpan(60).seconds());
    }

    private RabiaEngine<TestCommand> coldStarted(int clusterSize,
                                                 StateMachine<TestCommand> stateMachine,
                                                 RabiaPersistence<TestCommand> persistence,
                                                 TimeSpan syncRetryInterval) {
        var network = new TestClusterNetwork();
        var engine = engine(clusterSize, stateMachine, persistence, network, syncRetryInterval);

        engine.clusterState(ClusterStateNotification.active());
        if (clusterSize > 1) {
            assertThat(awaitCondition(() -> network.getMessages()
                                                   .stream()
                                                   .anyMatch(SyncRequest.class::isInstance))).as("engine must have started its sync round before responses are delivered")
                      .isTrue();
        }

        return engine;
    }

    private RabiaEngine<TestCommand> engine(int clusterSize,
                                            StateMachine<TestCommand> stateMachine,
                                            RabiaPersistence<TestCommand> persistence,
                                            TestClusterNetwork network) {
        return engine(clusterSize, stateMachine, persistence, network, timeSpan(60).seconds());
    }

    private RabiaEngine<TestCommand> engine(int clusterSize,
                                            StateMachine<TestCommand> stateMachine,
                                            RabiaPersistence<TestCommand> persistence,
                                            TestClusterNetwork network,
                                            TimeSpan syncRetryInterval) {
        var engine = new RabiaEngine<>(new TestTopologyManager(NODE_1, clusterSize),
                                       network,
                                       stateMachine,
                                       ProtocolConfig.consensusConfig(timeSpan(60).seconds(), syncRetryInterval),
                                       ConsensusMetrics.noop(),
                                       false,
                                       persistence,
                                       timeSpan(50).millis());

        engines.add(engine);

        return engine;
    }

    private static boolean staysInactive(RabiaEngine<TestCommand> engine) throws InterruptedException {
        Thread.sleep(300);

        return !engine.isActive();
    }

    private static final class RecordingStateMachine extends TestStateMachine {
        private volatile byte[] lastRestored;
        private volatile int restoreCount;

        @Override
        public Result<Unit> restoreSnapshot(byte[] snapshot) {
            lastRestored = snapshot;
            restoreCount++;

            return super.restoreSnapshot(snapshot);
        }

        byte[] lastRestored() {
            return lastRestored;
        }

        void forgetRestored() {
            lastRestored = null;
        }

        int restoreCount() {
            return restoreCount;
        }
    }

    private static boolean awaitActive(RabiaEngine<TestCommand> engine) {
        return awaitCondition(engine::isActive);
    }

    private static boolean awaitCondition(BooleanSupplier condition) {
        var deadline = System.nanoTime() + MILLISECONDS.toNanos(ACTIVATION_TIMEOUT_MILLIS);

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }

            Thread.onSpinWait();
        }

        return condition.getAsBoolean();
    }
}
