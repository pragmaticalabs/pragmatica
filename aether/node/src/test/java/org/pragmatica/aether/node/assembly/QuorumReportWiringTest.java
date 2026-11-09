// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossDetector;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.aether.node.health.WorkerEndpointDirectory;
import org.pragmatica.aether.node.health.fsm.SwimHealthState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimProtocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2936 wiring: [`MembershipAssembly#wireQuorumReports`] makes the membership FSM read every peer's SWIM quorum-loss
/// report and makes a core (never a worker) publish its own; [`SliceRuntimeAssembly#accessibleCandidates`] narrows the
/// forward filter by that report on both the core and the worker path. Driven over a started detector's live
/// `SwimProtocol`: the peer's report arrives as gossip, the node's own report is read as a self-incarnation bump.
class QuorumReportWiringTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER = new NodeId("node-2");
    private static final InetSocketAddress PEER_SWIM = new InetSocketAddress("127.0.0.2", 9101);
    private static final long ANNOUNCED_INCARNATION = 1L;

    private CoreSwimHealthDetector detector;
    private SwimProtocol swim;
    private MembershipFsm membership;
    private QuorumLossDetector quorumLossDetector;
    private WorkerEndpointDirectory directory;

    @BeforeEach
    void setUp() throws SocketException, InterruptedException {
        var swimPort = freeUdpPort();
        var self = NodeInfo.nodeInfo(SELF,
                                     NodeAddress.nodeAddress("127.0.0.1", swimPort - CoreSwimHealthDetector.SWIM_PORT_OFFSET)
                                                .unwrap());
        var peer = NodeInfo.nodeInfo(PEER, NodeAddress.nodeAddress("127.0.0.2", 9001).unwrap());
        var topologyConfig = new TopologyConfig(SELF, 2, timeSpan(1).seconds(), timeSpan(10).seconds(), List.of(self, peer));

        detector = CoreSwimHealthDetector.coreSwimHealthDetector(MessageRouter.mutable(),
                                                                 topologyConfig,
                                                                 Mockito.mock(Serializer.class),
                                                                 Mockito.mock(Deserializer.class));
        detector.start(Option.none(), GossipEncryptor.none())
                .await()
                .onFailure(cause -> fail("start() failed: " + cause.message()));
        swim = runningProtocol();
        swim.announceJoin(self, "wiring", ANNOUNCED_INCARNATION, List.of());
        membership = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                                 System::currentTimeMillis,
                                                 Long.MAX_VALUE,
                                                 TimeSpan.timeSpan(40).millis());
        quorumLossDetector = QuorumLossDetector.quorumLossDetector(MembershipConfig.membershipConfig(), () -> 3, () -> 3);
        directory = WorkerEndpointDirectory.workerEndpointDirectory();
        directory.install(List.of(peer));
        membership.onSwimHealthy(PEER, 1L);
    }

    @AfterEach
    void tearDown() {
        quorumLossDetector.stop();
        detector.stop();
    }

    @Test
    void core_readsAPeersReport_andPublishesItsOwn() {
        MembershipAssembly.wireQuorumReports(false, membership, quorumLossDetector, detector);
        assertThat(accessible(false)).as("a healthy peer passes the core forward filter").containsExactly(PEER);
        assertThat(accessible(true)).as("a healthy peer passes the worker forward filter").containsExactly(PEER);

        peerGossips(2L, Map.of("quorum", "lost"));

        assertThat(accessible(false)).as("a peer reporting quorum loss is not forwardable from a core").isEmpty();
        assertThat(accessible(true)).as("nor from a worker, although it is connected and in the directory").isEmpty();

        loseOwnQuorum();

        assertThat(swim.selfIncarnation()).as("a core publishes its own report with an incarnation bump")
                                          .isEqualTo(ANNOUNCED_INCARNATION + 1);
    }

    @Test
    void worker_readsAPeersReport_butPublishesNothing() {
        MembershipAssembly.wireQuorumReports(true, membership, quorumLossDetector, detector);

        peerGossips(2L, Map.of("quorum", "lost"));

        assertThat(accessible(true)).as("a worker reads the peer's report too").isEmpty();

        loseOwnQuorum();

        assertThat(swim.selfIncarnation()).as("a worker never writes to the quorum: no report, no bump")
                                          .isEqualTo(ANNOUNCED_INCARNATION);
    }

    /// Item 2 of the PR #2940 fix round: an excluded node's routes come back once it regains quorum writes. The stated
    /// bound is one SWIM dissemination of the subject's clearing bump; on the receiver the first strictly newer update
    /// re-admits it at once, on both forward paths.
    @Test
    void excludedPeer_regainsQuorum_isForwardableAgainOnItsNextNewerUpdate() {
        MembershipAssembly.wireQuorumReports(false, membership, quorumLossDetector, detector);
        peerGossips(2L, Map.of("quorum", "lost"));
        assertThat(accessible(false)).isEmpty();
        assertThat(accessible(true)).isEmpty();

        peerGossips(2L, Map.of());

        assertThat(accessible(false)).as("a relay at the held incarnation does not re-admit").isEmpty();

        peerGossips(3L, Map.of());

        assertThat(accessible(false)).as("the clearing bump re-admits it on the core forward path").containsExactly(PEER);
        assertThat(accessible(true)).as("and on the worker forward path").containsExactly(PEER);
    }

    /// PR #2940 ruling: a #1790-demoted (or configured) observer reports `quorum=observer` — passive by role, not
    /// quorum-less — so it stays forwardable; a later genuine loss after re-promotion still excludes it.
    @Test
    void demotedObserverPeer_staysForwardable_andAGenuineLossStillExcludes() {
        MembershipAssembly.wireQuorumReports(false, membership, quorumLossDetector, detector);

        peerGossips(2L, Map.of("quorum", "observer"));

        assertThat(accessible(false)).as("a demoted observer is forwardable from a core").containsExactly(PEER);
        assertThat(accessible(true)).as("and from a worker").containsExactly(PEER);

        peerGossips(3L, Map.of("quorum", "lost"));

        assertThat(accessible(false)).as("control: a voter's quorum loss is still excluded").isEmpty();
    }

    @Test
    void core_publishesItsOwnDemotion_withABump() {
        MembershipAssembly.wireQuorumReports(false, membership, quorumLossDetector, detector);
        quorumLossDetector.reevaluate();

        quorumLossDetector.onConsensusEdge(false, QuorumLossDetector.ConsensusRole.OBSERVER);

        assertThat(swim.selfIncarnation()).as("the observer report is a change, advertised with one bump")
                                          .isEqualTo(ANNOUNCED_INCARNATION + 1);
    }

    private List<NodeId> accessible(boolean worker) {
        return SliceRuntimeAssembly.accessibleCandidates(List.of(PEER), membership, worker, directory, () -> Set.of(PEER));
    }

    private void peerGossips(long incarnation, Map<String, String> labels) {
        var update = SwimMessage.MembershipUpdate.membershipUpdate(PEER, MemberState.ALIVE, incarnation, PEER_SWIM, 0L, labels);

        swim.onMessage(PEER_SWIM, SwimMessage.Ack.ack(PEER, 1L, List.of(update)));
    }

    /// Arm the detector at full membership, then report the consensus presence lost.
    private void loseOwnQuorum() {
        quorumLossDetector.reevaluate();
        assertThat(quorumLossDetector.isArmed()).isTrue();
        quorumLossDetector.onQuorumPresence(false);
    }

    private SwimProtocol runningProtocol() throws InterruptedException {
        for (int i = 0; i < 50 && !(detector.lifecycleState() instanceof SwimHealthState.Running); i++) {
            Thread.sleep(20);
        }

        if (detector.lifecycleState() instanceof SwimHealthState.Running running) {
            return running.swim();
        }

        return fail("detector not Running: " + detector.lifecycleState().getClass().getSimpleName());
    }

    private static int freeUdpPort() throws SocketException {
        try (var socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
