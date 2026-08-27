/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
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

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #660 — sync ADOPTION counts self exactly once.
///
/// `syncQuorumSize()` demanded `clusterSize / 2 + 1` sync RESPONSES, but responses only ever arrive from
/// PEERS (`broadcastPayload` iterates `peers`; self is never a peer), so the gate silently required
/// `quorum + 1` live nodes. A bare-majority cold start deadlocked in `Syncing` forever: consensus never
/// reached ACTIVE, so no `QuorumEstablished` was dispatched, no leader was elected and no reconciler ran,
/// while every link and every SWIM view stayed healthy.
///
/// Two properties are pinned here and they are inseparable — see
/// [RabiaEngine#syncPeerResponsesRequired] and [RabiaEngine#ownStateFloor]:
///
/// 1. **Liveness** — `clusterSize / 2` peer responses suffice, because self completes the majority.
/// 2. **Safety** — self therefore carries weight in the adoption decision, as a FLOOR. With the responses
///    alone now a minority, the intersection argument holds only over `{self} ∪ responders`, so a node
///    whose own state is more advanced than every response must REFUSE to adopt rather than regress onto
///    it. Relaxing (1) without (2) would trade the deadlock for silent state loss.
///
/// Self is a floor and never an adopted candidate: `persistence.save` never runs on commit, so a node's
/// persisted snapshot lags its live state machine by an unbounded amount and installing it would be its
/// own regression. Refusing installs nothing and keeps what the node already has.
///
/// Both the arrival gate ([RabiaEngine#handleSyncResponse]) and the retry gate
/// ([RabiaEngine#doSynchronize]) route through the same `adoptionThresholdMet()` predicate, so these
/// tests cover both even though they deliver responses through the arrival path.
///
/// The negative assertion here is a bounded observation window rather than a poll: "never activates" has
/// no moment of arrival, and activation in that case would be a correctness failure rather than a slow
/// success, so a longer window buys nothing.
class RabiaSyncAdoptionQuorumTest {

    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();

    private static final long ACTIVATION_TIMEOUT_MILLIS = 5_000;
    private static final long STAYS_INACTIVE_WINDOW_MILLIS = 300;

    private final List<RabiaEngine<TestCommand>> engines = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopEngines() {
        engines.forEach(engine -> engine.stop().await());
    }

    @Nested
    class Liveness {

        /// The #660 headline: a 5-node cluster cold-starting with a BARE majority live. Two peers answer;
        /// self is the third member of the majority. Before the fix this demanded three responses — four
        /// live nodes — and the trio sat in `Syncing` for hours.
        @Test
        void bareMajorityColdStart_activates_whenQuorumMinusOnePeersAnswer() {
            var engine = coldStarted(5);

            engine.processSyncResponse(new SyncResponse<>(NODE_2, SavedState.empty()));
            engine.processSyncResponse(new SyncResponse<>(NODE_3, SavedState.empty()));

            assertThat(awaitActive(engine))
                .as("2 peer responses + self = 3 of 5 = a majority; requiring a 3rd response demanded 4 live nodes")
                .isTrue();
        }

        /// A 3-node cluster with one node down. Self plus one responder is 2 of 3. Before the fix this
        /// demanded both peers — every node of a 3-node cluster — so a single node down deadlocked it.
        @Test
        void threeNodeCluster_activates_onOnePeerResponse() {
            var engine = coldStarted(3);

            engine.processSyncResponse(new SyncResponse<>(NODE_2, SavedState.empty()));

            assertThat(awaitActive(engine))
                .as("1 peer response + self = 2 of 3 = a majority")
                .isTrue();
        }

        /// The same off-by-one made the single-node threshold unsatisfiable: `clusterSize <= 1` returned
        /// 1, and a one-node cluster has zero peers to produce that one response. It could never leave
        /// `Syncing`. Self alone is the majority of a 1-node cluster, so the requirement is zero.
        @Test
        void singleNodeCluster_activates_withNoPeersAtAll() {
            var engine = coldStarted(1);

            assertThat(awaitActive(engine))
                .as("a 1-node cluster has no peers; demanding even one response is unsatisfiable")
                .isTrue();
        }

        /// Integer division is least obvious at EVEN cluster sizes, where `clusterSize / 2` is exactly
        /// half and self is the tie-breaking member. 2 responses + self = 3 of 4 — a majority with room
        /// to spare, since a majority of 4 is 3.
        @Test
        void fourNodeCluster_activates_onTwoPeerResponses() {
            var engine = coldStarted(4);

            engine.processSyncResponse(new SyncResponse<>(NODE_2, SavedState.empty()));
            engine.processSyncResponse(new SyncResponse<>(NODE_3, SavedState.empty()));

            assertThat(awaitActive(engine))
                .as("2 responses + self = 3 of 4 = a majority")
                .isTrue();
        }

        /// The smallest even cluster: a majority of 2 is 2, so self plus its single peer is the whole
        /// cluster and one response is required.
        @Test
        void twoNodeCluster_activates_onOnePeerResponse() {
            var engine = coldStarted(2);

            engine.processSyncResponse(new SyncResponse<>(NODE_2, SavedState.empty()));

            assertThat(awaitActive(engine))
                .as("1 response + self = 2 of 2 = a majority")
                .isTrue();
        }
    }

    @Nested
    class Safety {

        /// The gate is a majority of the CLUSTER, not of whoever answered. One response plus self is 2 of
        /// 5 — a minority — and must not adopt. The discriminating half is in [Liveness], which proves the
        /// gate still OPENS at a genuine majority; without it this test would pass against an engine that
        /// never activates at all.
        @Test
        void oneResponse_isAMinorityOfFive_andMustNotActivate() throws InterruptedException {
            var engine = coldStarted(5);

            engine.processSyncResponse(new SyncResponse<>(NODE_2, SavedState.empty()));

            assertThat(staysInactive(engine))
                .as("1 response + self = 2 of 5 is a minority — adopting state on it is the bug #660 must not introduce")
                .isTrue();
        }

        /// The safety half of #660, and the reason the threshold change is sound. Self is counted toward
        /// the majority, so self's own history must be able to REFUSE a response set that is behind it.
        /// Here self holds phase 42 while both responders are at phase 0: adopting a response would
        /// silently discard a committed phase this node may be the sole surviving witness of.
        ///
        /// Self is a FLOOR, not an adopted candidate — so the assertion is that NOTHING was installed.
        /// Adopting self's own persisted snapshot would be its own bug: `persistence.save` never runs on
        /// commit, so that snapshot lags the live state machine by an unbounded amount and installing it
        /// would overwrite live state with a staler picture.
        ///
        /// The responders carry a NON-EMPTY snapshot on purpose. `restoreState` skips `restoreSnapshot`
        /// entirely when the adopted state's snapshot is empty, so stale-but-EMPTY responders would leave
        /// nothing installed whether the floor held or not, and this assertion would pass against an
        /// engine with no floor at all. A mutation run caught exactly that: with the floor deleted, this
        /// test stayed green until the responders were given real bytes to install.
        @Test
        void ownMoreAdvancedState_isNotRegressed_byStalerResponses() {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(5, stateMachine, persistedAt(Phase.phase(42), SELF_SNAPSHOT));
            var stale = SavedState.<TestCommand>savedState(PEER_SNAPSHOT, Phase.phase(10), List.of());

            engine.processSyncResponse(new SyncResponse<>(NODE_2, stale));
            engine.processSyncResponse(new SyncResponse<>(NODE_3, stale));

            assertThat(awaitActive(engine))
                .as("the node must still ACTIVATE — refusing to regress is not a reason to stay dead")
                .isTrue();
            assertThat(stateMachine.lastRestored())
                .as("self at phase 42 outranks both responders at phase 10, so their snapshot must NOT be installed")
                .isNull();
        }

        /// The discriminator for the test above: when a RESPONSE is the most advanced state, it must be
        /// adopted. Without this, `ownMoreAdvancedState_isNotRegressed` would pass just as well against an
        /// engine that never installs anything and never syncs at all — a different, equally fatal bug.
        @Test
        void responseState_isAdopted_whenMoreAdvancedThanOwnState() {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(5, stateMachine, persistedAt(Phase.phase(7), SELF_SNAPSHOT));

            engine.processSyncResponse(new SyncResponse<>(NODE_2, SavedState.empty()));
            engine.processSyncResponse(new SyncResponse<>(NODE_3,
                                                          SavedState.savedState(PEER_SNAPSHOT,
                                                                                Phase.phase(99),
                                                                                List.of())));

            assertThat(awaitActive(engine))
                .as("engine must reach ACTIVE so the adopted candidate is observable")
                .isTrue();
            assertThat(stateMachine.lastRestored())
                .as("a responder at phase 99 outranks self at phase 7 — self must not pin the engine to its own past")
                .isEqualTo(PEER_SNAPSHOT);
        }

        /// The boundary between the two tests above, and the one the implementation is easiest to get
        /// wrong on. A response EQUAL to self's floor carries the same committed prefix, so it is not a
        /// regression and must be adopted — the refusal is on `<`, not `<=`. Getting this wrong would
        /// make every equal-phase rejoin skip its snapshot install.
        @Test
        void responseAtTheSamePhaseAsOwnState_isAdopted_becauseItIsNotARegression() {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(5, stateMachine, persistedAt(Phase.phase(5), SELF_SNAPSHOT));

            engine.processSyncResponse(new SyncResponse<>(NODE_2, SavedState.empty()));
            engine.processSyncResponse(new SyncResponse<>(NODE_3,
                                                          SavedState.savedState(PEER_SNAPSHOT,
                                                                                Phase.phase(5),
                                                                                List.of())));

            assertThat(awaitActive(engine))
                .as("engine must reach ACTIVE so the adopted candidate is observable")
                .isTrue();
            assertThat(stateMachine.lastRestored())
                .as("an equal phase is an equal committed prefix — adopting it loses nothing and is not a regression")
                .isEqualTo(PEER_SNAPSHOT);
        }
    }

    private static final byte[] SELF_SNAPSHOT = "self-state".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PEER_SNAPSHOT = "peer-state".getBytes(StandardCharsets.UTF_8);

    private RabiaEngine<TestCommand> coldStarted(int clusterSize) {
        return coldStarted(clusterSize, new TestStateMachine(), RabiaPersistence.inMemory());
    }

    /// Cold-starts an engine and waits until its sync round is actually in flight. Responses delivered
    /// before `doClusterConnected` broadcasts would be silently dropped — it CLEARS `syncResponses`
    /// first — so the caller must not guess at a delay.
    ///
    /// The long retry interval keeps exactly ONE sync round in flight for the whole test: `doSynchronize`
    /// discards accumulated responses whenever a retry finds fewer than the threshold, so a short
    /// interval would let a round intervene between two responses and start the count over. Waiting
    /// longer could never repair that — the discarded response is gone and the test sends no more.
    /// The single-node case is the exception: it has no responses to preserve and needs the retry tick
    /// to run its adoption, so it gets a short interval.
    private RabiaEngine<TestCommand> coldStarted(int clusterSize,
                                                 StateMachine<TestCommand> stateMachine,
                                                 RabiaPersistence<TestCommand> persistence) {
        var network = new TestClusterNetwork();
        var retryInterval = clusterSize <= 1
                            ? timeSpan(100).millis()
                            : timeSpan(60).seconds();
        var engine = new RabiaEngine<>(new TestTopologyManager(NODE_1, clusterSize),
                                       network,
                                       stateMachine,
                                       ProtocolConfig.consensusConfig(timeSpan(60).seconds(), retryInterval),
                                       ConsensusMetrics.noop(),
                                       false,
                                       persistence,
                                       timeSpan(50).millis());

        engines.add(engine);
        engine.clusterState(ClusterStateNotification.active());

        assertThat(awaitCondition(() -> network.getMessages()
                                               .stream()
                                               .anyMatch(SyncRequest.class::isInstance)))
            .as("engine must have started its sync round before responses are delivered")
            .isTrue();

        return engine;
    }

    private static RabiaPersistence<TestCommand> persistedAt(Phase phase, byte[] snapshot) {
        record fixed(Phase phase, byte[] snapshot) implements RabiaPersistence<TestCommand> {
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

        return new fixed(phase, snapshot);
    }

    /// Records what the engine actually installed, which is the only externally visible answer to "which
    /// candidate was adopted" — the engine exposes no current-phase accessor.
    private static final class RecordingStateMachine extends TestStateMachine {
        private volatile byte[] lastRestored;

        @Override
        public Result<Unit> restoreSnapshot(byte[] snapshot) {
            lastRestored = snapshot;

            return super.restoreSnapshot(snapshot);
        }

        byte[] lastRestored() {
            return lastRestored;
        }
    }

    private static boolean awaitActive(RabiaEngine<TestCommand> engine) {
        return awaitCondition(engine::isActive);
    }

    /// "Never activates" has no moment of arrival, so this is a bounded observation window rather than a
    /// poll. It SLEEPS rather than spinning: a busy-spin on a 1-2 core CI container can starve the
    /// engine's single-threaded executor, so the test would pass because the engine never RAN rather
    /// than because the gate held — a negative test that cannot tell those apart proves nothing. The
    /// window is short by design: activation here would be a correctness failure, not a slow success.
    private static boolean staysInactive(RabiaEngine<TestCommand> engine) throws InterruptedException {
        Thread.sleep(STAYS_INACTIVE_WINDOW_MILLIS);

        return !engine.isActive();
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
