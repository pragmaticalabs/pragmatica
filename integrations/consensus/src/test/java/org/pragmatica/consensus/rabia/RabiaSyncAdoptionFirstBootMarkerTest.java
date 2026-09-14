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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.ParticipationMarker.Participation;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Propose;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound1;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound2;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1212 — the first-boot marker's effect on the #667 adoption bound, pinned as WHAT GOES RED.
///
/// The scenario throughout is the one that wedges `emberCluster(3, ...)`: n=3, ONE peer answering and
/// already LIVE, the third held back so a second response can never arrive. Under #1171 alone an
/// amnesiac joiner needs `clusterSize / 2 + 1` = 2 responses there and waits forever. The two arms
/// below are the same scenario differing ONLY in the marker, which is what makes them a controlled
/// comparison rather than two observations:
///
/// - [AProvablyNewNodeJoinsOnTheColdBound] — marker says NEVER_PARTICIPATED: **activates on 1
///   response**. Goes RED if the marker is ignored, or if the carve-out in
///   `responsesRequiredWithALiveResponder` is reverted — the bound returns to 2 and nothing arrives.
/// - [AWipedNodeIsStillHeldToTheAmnesiacBound] — marker absent/UNKNOWN: **stays inactive**. Goes RED
///   if absence is ever read as newness, which is the failure that would reopen #667 wider than it
///   is today.
///
/// Neither arm's red set is a superset of the other's: the first reddens when the relaxation is
/// missing, the second when it is too broad. A change that breaks one leaves the other green.
class RabiaSyncAdoptionFirstBootMarkerTest {
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final long ACTIVATION_TIMEOUT_MILLIS = 5_000;
    private static final long STAYS_INACTIVE_WINDOW_MILLIS = 300;
    private static final byte[] LIVE_SNAPSHOT = "live".getBytes(StandardCharsets.UTF_8);
    private static final String MARKER = ".aether-participation";
    private static final org.pragmatica.serialization.SliceCodec SERIALIZER =
        TestSerializers.stringCommandSerializer(TestCommand.class, TestCommand::value, TestCommand::new);

    private final List<RabiaEngine<TestCommand>> engines = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopEngines() {
        engines.forEach(engine -> engine.stop().await());
    }

    @Nested
    class AProvablyNewNodeJoinsOnTheColdBound {
        /// THE RELAXATION ARM. Exactly the forge scenario. One LIVE responder, the third node held
        /// back, self brand new. `clusterSize / 2` = 1 response is enough because a node that
        /// provably never voted was in no commit quorum, so the single responder necessarily holds
        /// any commit that matters.
        @Test
        void n3_oneLiveResponder_selfProvablyNew_activatesOnASingleResponse(@TempDir Path dir) {
            var engine = coldStarted(3, ParticipationMarker.fileBacked(dir.resolve(MARKER), true));

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));

            assertThat(awaitActive(engine))
                .as("a provably-new node adopts on clusterSize/2 = 1 response, so a 3-node cluster "
                    + "with one node held back can still form")
                .isTrue();
        }

        /// The marker must stop being NEVER_PARTICIPATED the instant the node activates, or a resync
        /// (`triggerResync` re-enters `doSynchronize` from an ACTIVE engine) would let an
        /// already-voting node re-claim newness within a single process lifetime.
        @Test
        void activatingConsumesTheNewness(@TempDir Path dir) {
            var marker = ParticipationMarker.fileBacked(dir.resolve(MARKER), true);
            var engine = coldStarted(3, marker);

            assertThat(marker.resolve()).isEqualTo(Participation.NEVER_PARTICIPATED);

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));

            assertThat(awaitActive(engine)).isTrue();
            assertThat(marker.resolve())
                .as("activation records participation, so a later resync cannot re-claim newness")
                .isEqualTo(Participation.PARTICIPATED);
        }
    }

    @Nested
    class AWipedNodeIsStillHeldToTheAmnesiacBound {
        /// THE SAFETY ARM, and the one that must never be relaxed to make the forge tests pass.
        /// Identical to the relaxation arm in every respect except the marker: a wiped node presents
        /// as absence, absence means WIPED, and it waits for a real response quorum.
        @Test
        void n3_oneLiveResponder_selfWiped_staysInactive(@TempDir Path dir) throws InterruptedException {
            var engine = coldStarted(3, ParticipationMarker.fileBacked(dir.resolve(MARKER), false));

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));

            assertThat(staysInactive(engine))
                .as("a node that may have voted and lost the record needs clusterSize/2+1 = 2 "
                    + "responses — #667's hole stays shut")
                .isTrue();
        }

        /// The no-marker-wired default, which is what production gets until the deployment path
        /// supplies one. Must behave exactly as #1171 did.
        @Test
        void n3_oneLiveResponder_noMarkerWired_staysInactive() throws InterruptedException {
            var engine = coldStarted(3, ParticipationMarker.unknown());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));

            assertThat(staysInactive(engine))
                .as("wiring no marker must not relax anything")
                .isTrue();
        }

        /// A node that already participated, restarted with its marker intact. This is the returning
        /// amnesiac #667 exists to refuse, and the case an operator re-running node creation would
        /// wrongly turn back into a new node if the marker did not dominate the assertion.
        @Test
        void n3_oneLiveResponder_selfAlreadyParticipated_staysInactive(@TempDir Path dir) throws InterruptedException {
            var file = dir.resolve(MARKER);

            ParticipationMarker.fileBacked(file, true)
                               .recordParticipation()
                               .unwrap();

            var engine = coldStarted(3, ParticipationMarker.fileBacked(file, true));

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));

            assertThat(staysInactive(engine))
                .as("an existing marker wins over a re-asserted creation, so this node is not new")
                .isTrue();
        }
    }

    @Nested
    class ActivationIsTheChokePointForVoting {
        /// Pins the premise the W2 write point rests on: a node cannot vote before it activates, so
        /// recording participation inside `activate()` is strictly stronger than recording on the
        /// vote path. Asserted rather than assumed, because "two call sites today" is not "two call
        /// sites forever".
        ///
        /// The zero-claim carries its own positive control: the SAME filter, over the SAME network
        /// type, must find votes once an engine has activated. If the control stops firing this test
        /// becomes vacuous and says so by failing, rather than passing while examining nothing.
        @Test
        void anEngineThatNeverActivatesNeverVotes(@TempDir Path dir) throws InterruptedException {
            var wiped = startEngine(3, ParticipationMarker.fileBacked(dir.resolve("wiped"), false));

            wiped.engine().processSyncResponse(live(NODE_2, Phase.ZERO, LIVE_SNAPSHOT));
            driveTowardsAVote(wiped);

            assertThat(staysInactive(wiped.engine()))
                .as("precondition: the wiped engine must still be Syncing")
                .isTrue();
            assertThat(voteCount(wiped.network()))
                .as("given the very inputs that make the control vote, an engine that never left "
                    + "Syncing must still never have voted")
                .isZero();

            var active = startEngine(3, ParticipationMarker.fileBacked(dir.resolve("active"), true));

            active.engine().processSyncResponse(live(NODE_2, Phase.ZERO, LIVE_SNAPSHOT));

            assertThat(awaitActive(active.engine()))
                .as("the control must actually activate, or it controls for nothing")
                .isTrue();

            driveTowardsAVote(active);

            assertThat(awaitCondition(() -> voteCount(active.network()) > 0))
                .as("POSITIVE CONTROL for the zero above — same network type, same filter, same "
                    + "inputs, differing ONLY in whether the engine activated. If this fails the "
                    + "zero proves nothing and this test is vacuous.")
                .isTrue();
        }

        /// A Rabia node votes only once it holds a QUORUM of proposals for the phase — at n=3 that is
        /// its own plus one peer's. Activating alone produces no vote, which is why an earlier version
        /// of this control sat at zero and correctly failed itself.
        private void driveTowardsAVote(StartedEngine started) throws InterruptedException {
            started.engine().handleSubmit(new RabiaEngineIO.SubmitCommands<>(List.of(new TestCommand("own"))));

            Thread.sleep(50);

            started.engine()
                   .processPropose(new Propose<>(NODE_2,
                                                 Phase.ZERO,
                                                 Batch.create(SERIALIZER, List.of(new TestCommand("peer")))));
        }
    }

    private static long voteCount(TestClusterNetwork network) {
        return network.getMessages()
                      .stream()
                      .filter(message -> message instanceof VoteRound1 || message instanceof VoteRound2)
                      .count();
    }

    private record StartedEngine(RabiaEngine<TestCommand> engine, TestClusterNetwork network) {}

    private RabiaEngine<TestCommand> coldStarted(int clusterSize, ParticipationMarker marker) {
        return startEngine(clusterSize, marker).engine();
    }

    private StartedEngine startEngine(int clusterSize, ParticipationMarker marker) {
        var network = new TestClusterNetwork();
        var engine = new RabiaEngine<>(new TestTopologyManager(NODE_1, clusterSize),
                                       network,
                                       new TestStateMachine(),
                                       ProtocolConfig.consensusConfig(timeSpan(60).seconds(),
                                                                      timeSpan(50).millis(),
                                                                      Option.some(marker)),
                                       ConsensusMetrics.noop(),
                                       false,
                                       RabiaPersistence.inMemory(),
                                       timeSpan(50).millis());

        engines.add(engine);
        engine.clusterState(ClusterStateNotification.active());

        if (clusterSize > 1) {
            assertThat(awaitCondition(() -> network.getMessages()
                                                   .stream()
                                                   .anyMatch(SyncRequest.class::isInstance)))
                .as("engine must have started its sync round before responses are delivered")
                .isTrue();
        }

        return new StartedEngine(engine, network);
    }

    private static SyncResponse<TestCommand> live(NodeId sender, Phase phase, byte[] snapshot) {
        return new SyncResponse<>(sender, SavedState.savedState(snapshot, phase, List.of()), ResponderState.LIVE);
    }

    private static boolean awaitActive(RabiaEngine<TestCommand> engine) {
        return awaitCondition(engine::isActive);
    }

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

            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                return false;
            }
        }

        return condition.getAsBoolean();
    }
}
