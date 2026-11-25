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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.JoinGenerationGuard;
import org.pragmatica.consensus.net.LabelAgreement;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.testing.PollUntil;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

/// #3742 and #3525 (L1): the pre-sync check is the only way into a sync round, and with agreeing labels it refuses a node whose
/// handshaken peer differs, in both directions, and holds the node back (no ballot, one warning per wait episode) until a quorum
/// has handshaken. Every node here is a real [RabiaEngine] driven through its production entry (a cluster ACTIVE edge); the guard
/// is the production [JoinGenerationGuard] over a table of the Hello labels the node has seen.
class RabiaEngineLabelAgreementTest {
    private static final String LABEL = "backup";
    private static final NodeId N1 = nodeId("node-1").unwrap();
    private static final NodeId N2 = nodeId("node-2").unwrap();
    private static final NodeId N3 = nodeId("node-3").unwrap();
    private static final TimeSpan SHORT_WAIT = TimeSpan.timeSpan(200).millis();
    private static final TimeSpan LONG_WAIT = TimeSpan.timeSpan(30).seconds();

    private final List<Node> nodes = new CopyOnWriteArrayList<>();
    private LifecycleRig rig;

    @AfterEach
    void tearDown() {
        nodes.forEach(node -> node.engine.stop().await());
        if (rig != null) {
            rig.stop();
        }
    }

    // ---- #3742: every entry into Syncing passes the pre-sync check ----

    /// #3742: a far-future Propose reached `triggerResync` from any engine state and started a sync round on a node whose
    /// pre-sync check had not passed. It must neither sync nor cast anything.
    @Test
    void farFuturePropose_beforeThePreSyncCheckPassed_startsNoSyncRoundAndCastsNothing() {
        var gate = Promise.<Unit>promise();

        rig = LifecycleRig.rig(false);
        rig.engine.preSyncCheck(() -> gate);
        rig.started();
        rig.active();

        rig.proposeAt(LifecycleRig.FAR);

        assertThat(rig.mode()).isEqualTo("Stopped");
        assertThat(rig.network.getMessages()).as("no sync request and no ballot while the check is pending").isEmpty();
    }

    /// Control for the test above: the refusal is the unpassed check, not the Propose or the rig. Once the check passes the
    /// same far-future Propose resyncs.
    @Test
    void farFuturePropose_afterThePreSyncCheckPassed_stillStartsASyncRound() {
        rig = LifecycleRig.rig(false);
        rig.engine.preSyncCheck(() -> Promise.success(Unit.unit()));
        rig.started();
        rig.active();
        rig.emptyAnswers();
        assertThat(rig.mode()).as("control: the engine activated").isEqualTo("Idle");
        rig.mark();

        rig.proposeAt(LifecycleRig.FAR);

        assertThat(rig.mode()).isEqualTo("Syncing");
    }

    /// The Propose must not be remembered: when the check passes later the node syncs through the ordinary ACTIVE edge.
    @Test
    void farFuturePropose_beforeTheCheck_thenTheCheckPasses_syncsThroughTheActiveEdge() {
        var gate = Promise.<Unit>promise();

        rig = LifecycleRig.rig(false);
        rig.engine.preSyncCheck(() -> gate);
        rig.started();
        rig.active();
        rig.proposeAt(LifecycleRig.FAR);

        gate.succeed(Unit.unit());

        assertThat(await(() -> "Syncing".equals(rig.mode()))).as("the check passed, the stored ACTIVE edge syncs").isTrue();
    }

    // ---- #3525: label agreement ----

    /// Three nodes, one holding a different value: every node has handshaken the other two, so every node sees a mismatch
    /// and refuses to start, the mismatched node included. Nobody casts a ballot or even starts a sync round.
    @Test
    void firstElection_oneMismatchedLabel_everyNodeRefusesAndNobodyCastsAnything() {
        var odd = node(N1, "a", SHORT_WAIT);
        var first = node(N2, "b", SHORT_WAIT);
        var second = node(N3, "b", SHORT_WAIT);

        nodes.forEach(node -> nodes.forEach(peer -> node.handshaken(peer)));
        nodes.forEach(Node::activeEdge);

        assertThat(List.of(odd, first, second)).allSatisfy(node -> assertThat(node.refusal()).isInstanceOfSatisfying(
            ConsensusError.LabelAgreementRefused.class,
            refused -> assertThat(refused.label()).isEqualTo(LABEL)));
        assertThat(odd.refusal()).isInstanceOfSatisfying(ConsensusError.LabelAgreementRefused.class,
                                                         refused -> assertThat(refused.member()).isEqualTo(N2));
        assertThat(first.refusal()).isInstanceOfSatisfying(ConsensusError.LabelAgreementRefused.class,
                                                           refused -> assertThat(refused.member()).isEqualTo(N1));
        assertThat(nodes).allSatisfy(node -> assertThat(node.network.getMessages()).as("nothing cast by " + node.self).isEmpty());
        assertThat(nodes).allSatisfy(node -> assertThat(await(node.engine::isStopRequested)).as("refusal stops the engine, right after it fails the start")
                                                                                         .isTrue());
    }

    /// A peer that lacks the label differs from a peer that has it, on both sides of the pair.
    @Test
    void peerWithoutTheLabel_refusesTheNodeThatHasIt_andTheNodeRefusesThePeer() {
        var holder = node(N1, "a", SHORT_WAIT);
        var bare = node(N2, null, SHORT_WAIT);

        holder.handshaken(bare);
        bare.handshaken(holder);
        holder.activeEdge();
        bare.activeEdge();

        assertThat(holder.refusal()).isInstanceOfSatisfying(ConsensusError.LabelAgreementRefused.class,
                                                            refused -> assertThat(refused.ownValue()).isEqualTo(Option.some("a")));
        assertThat(bare.refusal()).isInstanceOfSatisfying(ConsensusError.LabelAgreementRefused.class,
                                                          refused -> assertThat(refused.memberValue()).isEqualTo(Option.some("a")));
        assertThat(holder.network.getMessages()).isEmpty();
        assertThat(bare.network.getMessages()).isEmpty();
    }

    /// The deadline passes with nobody handshaken: no ballot, no sync round, and one WARNING naming the two missing peers,
    /// not repeated while the episode lasts (several poll intervals pass).
    @Test
    void deadlinePasses_nobodyHandshaken_castsNothingAndWarnsOnceNamingTheMissingPeers() {
        var lonely = node(N1, "a", SHORT_WAIT);

        lonely.activeEdge();

        assertThat(await(() -> lonely.warnings.size() >= 1)).as("the warning is raised once the bound has passed").isTrue();
        lonely.awaitMorePolls();

        assertThat(lonely.warnings).hasSize(1);
        assertThat(lonely.warnings.getFirst().code()).isEqualTo(OperatorWarningCode.CONSENSUS_QUORUM_INCOMPLETE);
        assertThat(lonely.warnings.getFirst().message()).contains("node-2, node-3");
        assertThat(lonely.network.getMessages()).as("no ballot, no sync round").isEmpty();
        assertThat(lonely.engine.isStopRequested()).as("waiting is not refusing").isFalse();
    }

    /// One of the two peers handshakes after the deadline: that is a quorum of three (this node and one peer), the wait ends
    /// and the node syncs as it does today.
    @Test
    void quorumHandshakesAfterTheDeadline_theWaitEndsAndTheNodeSyncs() {
        var waiting = node(N1, "a", SHORT_WAIT);
        var peer = node(N2, "a", SHORT_WAIT);

        waiting.activeEdge();
        assertThat(await(() -> waiting.warnings.size() >= 1)).isTrue();
        assertThat(waiting.network.getMessages()).isEmpty();

        waiting.handshaken(peer);

        assertThat(await(waiting::syncStarted)).as("the quorum agrees, so the node syncs").isTrue();
        assertThat(await(() -> waiting.count(OperatorWarningCode.CONSENSUS_QUORUM_COMPLETE) == 1)).as("the recovery reaches the sink, which hands off on its own thread")
                                                                                                  .isTrue();
        assertThat(waiting.warnings.stream().map(OperatorWarning::code))
            .as("the warning is closed by its recovery, once, before the node syncs")
            .containsExactly(OperatorWarningCode.CONSENSUS_QUORUM_INCOMPLETE, OperatorWarningCode.CONSENSUS_QUORUM_COMPLETE);
        assertThat(waiting.warnings.getLast().subject()).isEqualTo(waiting.warnings.getFirst().subject());
    }

    /// #3525 round 1 (F1): the label wait lives no longer than the engine that consumes it. With a quorum that never handshakes
    /// the wait has no deadline, so stopping the engine must end it: no further poll, no warning past the bound, whether the
    /// quorum is incomplete or later completes.
    @Test
    void engineStoppedDuringTheWait_theWaitEndsAndRaisesNothing() {
        var waiting = node(N1, "a", SHORT_WAIT);

        waiting.activeEdge();
        assertThat(await(() -> waiting.lookups.get() > 0)).as("the wait is polling").isTrue();
        var stoppedAt = System.nanoTime();
        waiting.engine.stop().await();

        assertThat(await(() -> waiting.guard.get().isResolved())).as("stopping the engine ends the wait").isTrue();
        var pollsAtEnd = waiting.lookups.get();

        assertThat(await(() -> System.nanoTime() - stoppedAt > SHORT_WAIT.nanos() * 3)).as("the bound, and then some, has passed").isTrue();
        assertThat(waiting.warnings).as("nothing is raised on a stopped engine").isEmpty();
        assertThat(waiting.lookups.get()).as("no poll after the wait ended").isEqualTo(pollsAtEnd);
    }

    /// A new wait episode warns again: the first episode ends with the quorum, a later one (a fresh guard run) waits again
    /// and raises its own warning.
    @Test
    void aNewWaitEpisode_warnsAgain() {
        var waiting = node(N1, "a", SHORT_WAIT);
        var view = new ConcurrentHashMap<NodeId, Map<String, String>>();
        var own = Map.of(LABEL, "a");

        var first = JoinGenerationGuard.admit(Set.of(N2, N3),
                                              id -> Option.option(view.get(id)),
                                              SHORT_WAIT,
                                              JoinGenerationGuard.WIRE_GENERATION,
                                              LabelAgreement.labelAgreement(own, Set.of(LABEL), 1,
                                                                         waiting.engine::raiseJoinQuorumIncomplete,
                                                                         waiting.engine::raiseJoinQuorumComplete),
                                              waiting.engine::isStopRequested);

        assertThat(await(() -> waiting.count(OperatorWarningCode.CONSENSUS_QUORUM_INCOMPLETE) == 1)).isTrue();
        view.put(N2, helloLabels("a"));
        assertThat(first.await(TimeSpan.timeSpan(5).seconds()).isSuccess()).isTrue();
        view.clear();

        JoinGenerationGuard.admit(Set.of(N2, N3),
                                  id -> Option.option(view.get(id)),
                                  SHORT_WAIT,
                                  JoinGenerationGuard.WIRE_GENERATION,
                                  LabelAgreement.labelAgreement(own, Set.of(LABEL), 1,
                                                                         waiting.engine::raiseJoinQuorumIncomplete,
                                                                         waiting.engine::raiseJoinQuorumComplete),
                                  waiting.engine::isStopRequested);

        assertThat(await(() -> waiting.count(OperatorWarningCode.CONSENSUS_QUORUM_INCOMPLETE) == 2)).as("the second episode raises its own warning")
                                                                                                    .isTrue();
        assertThat(waiting.count(OperatorWarningCode.CONSENSUS_QUORUM_COMPLETE)).as("the first episode, and only it, was closed").isEqualTo(1);
    }

    /// Agreeing labels equal everywhere: startup proceeds exactly as without the labels.
    @Test
    void equalLabels_startupProceeds() {
        var first = node(N1, "a", LONG_WAIT);
        var second = node(N2, "a", LONG_WAIT);
        var third = node(N3, "a", LONG_WAIT);

        nodes.forEach(node -> nodes.forEach(peer -> node.handshaken(peer)));
        nodes.forEach(Node::activeEdge);

        assertThat(List.of(first, second, third)).allSatisfy(node -> assertThat(await(node::syncStarted)).isTrue());
        assertThat(nodes).allSatisfy(node -> assertThat(node.engine.isStopRequested()).isFalse());
        assertThat(nodes).allSatisfy(node -> assertThat(node.warnings).isEmpty());
    }

    // ---- fixture ----

    private Node node(NodeId self, String labelValue, TimeSpan wait) {
        var created = new Node(self, labelValue, wait);

        nodes.add(created);

        return created;
    }

    private static Map<String, String> helloLabels(String labelValue) {
        return labelValue == null
               ? Map.of(NodeInfo.LABEL_WIRE_GENERATION, Integer.toString(JoinGenerationGuard.WIRE_GENERATION))
               : Map.of(NodeInfo.LABEL_WIRE_GENERATION,
                        Integer.toString(JoinGenerationGuard.WIRE_GENERATION),
                        LABEL,
                        labelValue);
    }

    private static boolean await(BooleanSupplier condition) {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();

        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        return condition.getAsBoolean();
    }

    /// One engine of a three-node cluster with the production pre-sync check over the Hello labels it has seen.
    private static final class Node {
        final NodeId self;
        final String labelValue;
        final RabiaEnginePauseResumeTest.TestClusterNetwork network = new RabiaEnginePauseResumeTest.TestClusterNetwork();
        final Map<NodeId, Map<String, String>> seen = new ConcurrentHashMap<>();
        final AtomicInteger lookups = new AtomicInteger();
        final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
        final RabiaEngine<RabiaEnginePauseResumeTest.TestCommand> engine;
        final Promise<Unit> started;
        /// The wait of the latest pre-sync check run: the promise the engine's check returned.
        final AtomicReference<Promise<Unit>> guard = new AtomicReference<>();

        Node(NodeId self, String labelValue, TimeSpan wait) {
            this.self = self;
            this.labelValue = labelValue;
            this.engine = new RabiaEngine<>(new RabiaEnginePauseResumeTest.TestTopologyManager(self, 3),
                                            network,
                                            new RabiaEnginePauseResumeTest.TestStateMachine(),
                                            ProtocolConfig.testConfig());
            engine.useOperatorWarningSink(OperatorWarningSink.handingOffTo(warnings::add));
            var agreement = LabelAgreement.labelAgreement(helloLabels(labelValue),
                                                          Set.of(LABEL),
                                                          1,
                                                          engine::raiseJoinQuorumIncomplete,
                                                          engine::raiseJoinQuorumComplete);
            var others = Set.of(N1, N2, N3);

            engine.preSyncCheck(() -> guard(JoinGenerationGuard.admit(without(others, self),
                                                                      this::lookup,
                                                                      wait,
                                                                      JoinGenerationGuard.WIRE_GENERATION,
                                                                      agreement,
                                                                      engine::isStopRequested)));
            this.started = engine.start();
        }

        private Promise<Unit> guard(Promise<Unit> wait) {
            guard.set(wait);

            return wait;
        }

        long count(OperatorWarningCode code) {
            return warnings.stream().filter(warning -> warning.code() == code).count();
        }

        private static Set<NodeId> without(Set<NodeId> all, NodeId self) {
            return all.stream().filter(id -> !id.equals(self)).collect(Collectors.toSet());
        }

        /// This node has completed its handshake with `peer`, whose Hello labels it now knows (a node is not its own peer).
        void handshaken(Node peer) {
            if (!peer.self.equals(self)) {
                seen.put(peer.self, helloLabels(peer.labelValue));
            }
        }

        private Option<Map<String, String>> lookup(NodeId id) {
            lookups.incrementAndGet();

            return Option.option(seen.get(id));
        }

        /// Waits until the guard has polled several more times (the unit in which "stays waiting" is observed, not a sleep).
        void awaitMorePolls() {
            var baseline = lookups.get();

            PollUntil.pollUntil(lookups::get, count -> count >= baseline + 30, Duration.ofSeconds(5)).assertMet("guard polls");
        }

        void activeEdge() {
            engine.clusterState(ClusterStateNotification.active());
        }

        boolean syncStarted() {
            return network.getMessages().stream().anyMatch(RabiaProtocolMessage.Asynchronous.SyncRequest.class::isInstance);
        }

        /// The cause startup was refused with.
        Object refusal() {
            var cause = new Object[1];

            started.await(TimeSpan.timeSpan(5).seconds())
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("startup must be refused"))
                   .onFailure(failure -> cause[0] = failure);

            return cause[0];
        }
    }
}
