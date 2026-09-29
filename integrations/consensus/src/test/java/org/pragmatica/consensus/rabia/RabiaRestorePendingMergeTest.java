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

import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.NewBatch;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Propose;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound1;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound2;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Result;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1430 — a resync entered from ACTIVE restores the sync source's pending copy of a batch this node is
/// still waiting on. When that copy carries only a FOREIGN correlation id (our `NewBatch` never reached
/// the source), a plain `put()` replaced the local entry, so the command applied and the local caller
/// timed out with `ApplyTimeout` — #958's shape on the restore path. Found by the rev1409 probe P3.
///
/// The restore now goes through `reconcileSnapshotPending` → `learnProposedBatch`, which UNIONS the
/// correlation ids (09a2185fb). Mutation that reddens the FOREIGN case: replace `learnProposedBatch`
/// with `pendingBatches.put(batch.id(), batch)` in `reconcileSnapshotPending`.
///
/// The three scenarios share one fixture and differ only in the source's pending copy, so the FOREIGN
/// verdict is attributable: MERGED (the copy already carries our id) settles regardless of how the
/// restore stores it, and EMPTY (no copy at all) is the frontier-advancing restore that the engine
/// resolves as `SnapshotOutcomeUnknown` by design, since the skipped slots may already hold the request.
class RabiaRestorePendingMergeTest {
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();
    private static final Phase FAR_FUTURE = Phase.phase(200);
    private static final long WAIT_MILLIS = 5_000;

    private TestClusterNetwork network;
    private TestStateMachine stateMachine;
    private RabiaEngine<TestCommand> engine;

    private enum SourceCopy { FOREIGN, MERGED, EMPTY }

    @BeforeEach
    void setUp() {
        network = new TestClusterNetwork();
        stateMachine = new TestStateMachine();
        engine = new RabiaEngine<>(new TestTopologyManager(NODE_1, 3),
                                   network,
                                   stateMachine,
                                   ProtocolConfig.protocolConfig(timeSpan(60).seconds(),
                                                                 timeSpan(100).millis(),
                                                                 100,
                                                                 ProtocolConfig.DEFAULT_MAX_PENDING_BATCHES,
                                                                 timeSpan(2).seconds())
                                                 .unwrap());
    }

    @AfterEach
    void tearDown() {
        engine.stop().await();
    }

    /// The #1430 defect: red with `ApplyTimeout` while the restore `put()`s the source's copy.
    @Test
    void restoredForeignCopy_keepsTheLocalCallerAndSettlesIt() {
        var command = new TestCommand("identical");
        var local = restoreAcrossResync(command, SourceCopy.FOREIGN);

        assertThat(stateMachine.getProcessedCommands()).as("the command applied").containsExactly(command);
        assertThat(local.await(timeSpan(5).seconds()))
            .as("#1430: the local caller must receive the outcome of the command it submitted, not ApplyTimeout")
            .isEqualTo(Result.success(List.of("result:identical")));
    }

    /// CONTROL — the source's copy already carries our correlation id, so the local caller settles
    /// whatever the restore does with the entry. Its green is what isolates the FOREIGN red to the
    /// correlation ids the restore keeps.
    @Test
    void restoredMergedCopy_settlesTheLocalCaller() {
        var command = new TestCommand("identical");
        var local = restoreAcrossResync(command, SourceCopy.MERGED);

        assertThat(stateMachine.getProcessedCommands()).containsExactly(command);
        assertThat(local.await(timeSpan(5).seconds())).isEqualTo(Result.success(List.of("result:identical")));
    }

    /// CONTROL — the double-apply sibling #1430 asked about. With no copy at the source and a snapshot
    /// frontier past our slot, the request may already be inside the skipped slots, so the engine drops
    /// it and reports the honest unknown outcome instead of re-proposing it: nothing applies twice.
    @Test
    void frontierAdvancingRestoreWithoutACopy_reportsUnknownOutcomeAndDoesNotRepropose() {
        var command = new TestCommand("identical");
        var local = restoreAcrossResync(command, SourceCopy.EMPTY);
        var outcome = local.await(timeSpan(5).seconds());

        assertThat(outcome.isFailure()).as("outcome %s", outcome).isTrue();
        outcome.onFailure(cause -> assertThat(cause).isInstanceOf(ConsensusError.SnapshotOutcomeUnknown.class));
        assertThat(engine.pendingBatchCountForTesting()).as("the request is not re-proposed").isZero();
        assertThat(stateMachine.getProcessedCommands()).as("nothing re-applied the dropped request").isEmpty();
    }

    /// `apply()` → far-future `Propose` (past `MAX_PHASE_AHEAD`) → resync from ACTIVE with the local batch
    /// still pending → two COLD responses carrying the chosen copy at the far-future frontier → the
    /// decision for that slot (when a copy survives) is driven with the SOURCE's copy.
    private org.pragmatica.lang.Promise<List<String>> restoreAcrossResync(TestCommand command, SourceCopy mode) {
        activate();
        network.clearMessages();
        var local = engine.<String>apply(List.of(command));

        assertThat(await(() -> !broadcastBatches().isEmpty())).as("the local batch was broadcast").isTrue();
        var mine = broadcastBatches().getFirst();

        engine.processPropose(new Propose<>(NODE_2, FAR_FUTURE, mine));
        assertThat(await(() -> !engine.isActive())).as("the far-future proposal sent the engine to resync").isTrue();
        assertThat(engine.pendingBatchCountForTesting()).as("the local batch survives into Syncing").isEqualTo(1);

        var copy = switch (mode) {
            case FOREIGN -> new Batch<>(mine.id(), List.of(CorrelationId.randomCorrelationId()), mine.timestamp(), mine.commands());
            case MERGED -> new Batch<>(mine.id(),
                                       List.of(CorrelationId.randomCorrelationId(), mine.correlationIds().getFirst()),
                                       mine.timestamp(),
                                       mine.commands());
            case EMPTY -> mine;
        };
        var state = SavedState.savedState(new byte[0], FAR_FUTURE, mode == SourceCopy.EMPTY ? List.of() : List.of(copy));

        engine.processSyncResponse(new SyncResponse<>(NODE_2, state, ResponderState.COLD));
        engine.processSyncResponse(new SyncResponse<>(NODE_3, state, ResponderState.COLD));
        assertThat(await(engine::isActive)).as("the engine adopted the source's state").isTrue();
        assertThat(engine.currentPhaseForTesting()).isEqualTo(FAR_FUTURE);

        if (mode != SourceCopy.EMPTY) {
            driveV1(FAR_FUTURE, copy);
            await(local::isResolved);
        }

        return local;
    }

    private void activate() {
        engine.clusterState(ClusterStateNotification.active());
        assertThat(await(() -> network.getMessages()
                                      .stream()
                                      .anyMatch(RabiaProtocolMessage.Asynchronous.SyncRequest.class::isInstance)))
            .as("the engine started its sync round")
            .isTrue();
        engine.processSyncResponse(new SyncResponse<>(NODE_2, SavedState.empty(), ResponderState.COLD));
        engine.processSyncResponse(new SyncResponse<>(NODE_3, SavedState.empty(), ResponderState.COLD));
        assertThat(await(engine::isActive)).as("the engine activated").isTrue();
    }

    @SuppressWarnings("unchecked")
    private List<Batch<TestCommand>> broadcastBatches() {
        return network.getMessages()
                      .stream()
                      .filter(NewBatch.class::isInstance)
                      .map(message -> ((NewBatch<TestCommand>) message).batch())
                      .toList();
    }

    private void driveV1(Phase phase, Batch<TestCommand> batch) {
        engine.processPropose(new Propose<>(NODE_2, phase, batch));
        engine.processVoteRound1(new VoteRound1(NODE_2, phase, StateValue.V1));
        engine.processVoteRound1(new VoteRound1(NODE_3, phase, StateValue.V1));
        engine.processVoteRound2(new VoteRound2(NODE_2, phase, StateValue.V1));
        engine.processVoteRound2(new VoteRound2(NODE_3, phase, StateValue.V1));
    }

    private static boolean await(BooleanSupplier condition) {
        var deadline = System.nanoTime() + MILLISECONDS.toNanos(WAIT_MILLIS);

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }

            Thread.onSpinWait();
        }

        return condition.getAsBoolean();
    }
}
