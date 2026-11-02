// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.aether.node.health.fsm.SwimHealthState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.AesGcmGossipEncryptor;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.RotatingGossipEncryptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1308 — a node whose SWIM listener failed to start must neither announce its join nor keep
/// running. Every arm drives a REAL [CoreSwimHealthDetector] binding a REAL UDP port through the
/// production [AetherNode#startSwim]; the failing arm makes the bind fail by holding the port, which
/// is the collision that surfaced this in #1289. Only the exit is a seam (`failNode`).
class AetherNodeStartSwimTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER = new NodeId("node-2");
    /// Covers the transport bind's own 5 s await plus async callback delivery.
    private static final long WAIT_SECONDS = 10;
    private static final Cause SWIM_BIND_FAILED = Causes.cause("SWIM UDP port already bound (test cause)");
    private static final Cause FORMATION_FAILED = Causes.cause("cluster formation failed (test cause)");

    /// #1312: gossip under a key epoch the node does not hold, from a cluster that rotated its key.
    private static final byte[] NODE_KEY = filled((byte) 0x11);
    private static final int NODE_KEY_ID = 20260914;
    private static final byte[] ROTATED_KEY = filled((byte) 0x22);
    private static final int ROTATED_KEY_ID = 1;

    private final AtomicInteger divergences = new AtomicInteger();
    private final CountDownLatch diverged = new CountDownLatch(1);
    private final AtomicInteger announcements = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final CountDownLatch announced = new CountDownLatch(1);
    private final CountDownLatch failed = new CountDownLatch(1);

    @Test
    void startSwim_swimPortAlreadyBound_failsTheNodeAndDoesNotAnnounce() throws Exception {
        var swimPort = freeUdpPort();
        var detector = detectorWithSwimPort(swimPort);

        try (var holder = new DatagramSocket(swimPort)) {
            AetherNode.startSwim(detector, network(), encryptor(), this::announce, this::fail, this::diverge, Promise.promise());

            assertThat(failed.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("a SWIM start that cannot bind UDP %d must fail the node", swimPort)
                .isTrue();
            // start() returns a resolved promise, so both callbacks have already run inline by now;
            // the window only gives a wrongly-asynchronous announcement a chance to show up.
            assertThat(announced.await(500, TimeUnit.MILLISECONDS))
                .as("a node without a SWIM listener must not announce its join")
                .isFalse();
        }

        assertThat(failures.get()).isEqualTo(1);
        assertThat(announcements.get()).isZero();
        assertThat(detector.lifecycleState()).isInstanceOf(SwimHealthState.Stopped.class);
    }

    /// A node stopped before SWIM started is not a SWIM failure: `failNode` (production: halt, Ember: stop
    /// of the node) must not run, the node's start outcome stays unsettled, and no join is announced.
    @Test
    void startSwim_detectorClosedByEarlyStop_doesNotFailTheNodeOrAnnounce() throws Exception {
        var detector = detectorWithSwimPort(freeUdpPort());
        var outcome = Promise.<Unit>promise();

        detector.stop();

        var result = AetherNode.startSwim(detector, network(), encryptor(), this::announce, this::fail, this::diverge, outcome);

        assertThat(result.await().isSuccess()).as("a refused start is a no-op success").isTrue();
        assertThat(failures.get()).as("a stopping node must not be failed or halted").isZero();
        assertThat(announcements.get()).isZero();
        assertThat(outcome.isResolved()).as("the start outcome is left to consensus formation").isFalse();
    }

    @Test
    void startSwim_swimPortFree_announcesTheJoinAndDoesNotFailTheNode() throws Exception {
        var detector = detectorWithSwimPort(freeUdpPort());

        try {
            AetherNode.startSwim(detector, network(), encryptor(), this::announce, this::fail, this::diverge, Promise.promise());

            assertThat(announced.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("a started SWIM must announce the join")
                .isTrue();
            assertThat(failed.await(500, TimeUnit.MILLISECONDS))
                .as("a started SWIM must not fail the node")
                .isFalse();
            assertThat(announcements.get()).isEqualTo(1);
        } finally {
            detector.stop();
        }
    }

    /// `startSwim` announces the join from start()'s success, which relies on start() never resolving
    /// before the FSM is Running: an earlier announcement finds no protocol and is parked as pending
    /// after the only replay point has passed. This holds today because start() binds synchronously
    /// and returns an already-resolved promise, so its `onSuccess(dispatch)` runs inline. The state is
    /// captured by a `map` on the returned promise, so a start() that ever resolved first and
    /// dispatched later would be caught here rather than as a lost join.
    @Test
    void start_resolvedSuccess_detectorIsAlreadyRunning() throws Exception {
        var detector = detectorWithSwimPort(freeUdpPort());

        try {
            var stateAtResolution = detector.start(Option.none(), GossipEncryptor.none())
                                            .map(_ -> detector.lifecycleState())
                                            .await();

            assertThat(stateAtResolution.isSuccess()).as("start() on a free port, result %s", stateAtResolution)
                                                     .isTrue();
            assertThat(stateAtResolution.unwrap())
                .as("lifecycle the moment start() resolves")
                .isInstanceOf(SwimHealthState.Running.class);
        } finally {
            detector.stop();
        }
    }

    @Test
    void start_resolvedFailure_detectorIsAlreadyStopped() throws Exception {
        var swimPort = freeUdpPort();
        var detector = detectorWithSwimPort(swimPort);

        try (var holder = new DatagramSocket(swimPort)) {
            var stateAtResolution = detector.start(Option.none(), GossipEncryptor.none())
                                            .fold(result -> Promise.success(result.map(_ -> detector.lifecycleState())
                                                                                  .or(detector::lifecycleState)))
                                            .await()
                                            .unwrap();

            assertThat(stateAtResolution)
                .as("lifecycle the moment a failed start() resolves")
                .isInstanceOf(SwimHealthState.Stopped.class);
        }
    }

    /// rev1343 NIT-R2 — a failed SWIM start fails the node's start outcome BEFORE `failNode` runs.
    /// In Ember `failNode` blocks for the node's own stop (~6 s), and peers' hellos can complete
    /// formation inside that window; the outcome is first-settlement-wins, so an outcome settled only
    /// after `failNode` could be won by formation and report a started node that is being stopped.
    /// `failNode` here records whether the outcome was already settled when it ran. Reverting the
    /// order (registering `startOutcome::fail` after `refuseToRunWithoutSwim`) turns this red.
    @Test
    void startSwim_swimPortAlreadyBound_failsTheStartOutcomeBeforeFailNodeRuns() throws Exception {
        var swimPort = freeUdpPort();
        var detector = detectorWithSwimPort(swimPort);
        var outcome = Promise.<Unit> promise();
        var outcomeSettledWhenFailNodeRan = new AtomicBoolean();

        try (var holder = new DatagramSocket(swimPort)) {
            AetherNode.startSwim(detector,
                                 network(),
                                 encryptor(),
                                 this::announce,
                                 () -> outcomeSettledWhenFailNodeRan.set(outcome.isResolved()),
                                 this::diverge,
                                 outcome);

            var settled = outcome.await(timeSpan(WAIT_SECONDS).seconds());

            assertThat(settled.isFailure()).as("the start outcome carries the SWIM failure: %s", settled).isTrue();
            assertThat(settled.fold(Cause::message, _ -> "success"))
                .as("the cause names the bind failure, not a timeout")
                .contains("Address already in use");
            assertThat(outcomeSettledWhenFailNodeRan)
                .as("the outcome must be settled by the time failNode runs, or formation can win the race")
                .isTrue();
        }
    }

    /// #1312 — the gossip-key divergence gate exits through the node's own `gossipKeyDivergedExit`, never
    /// `System.exit`: in a single-JVM host (Ember/Forge) that exit is a per-node stop, and a `System.exit`
    /// would take every co-hosted node down. Drives the production [AetherNode#startSwim] with a REAL
    /// detector on a REAL UDP port, fed REAL datagrams encrypted under a rotated cluster key; only the
    /// gate's arming clock is moved past its delay. `failNode` must stay untouched: divergence is not a
    /// SWIM start failure, and a guard routed to the wrong hook would stop the node with the wrong exit.
    @Test
    void startSwim_gossipKeyDiverges_exitsThroughTheNodesDivergenceHook() throws Exception {
        var swimPort = freeUdpPort();
        var detector = detectorWithSwimPort(swimPort);
        var gateClock = new AtomicLong();

        try (var sender = new DatagramSocket()) {
            AetherNode.startSwim(detector,
                                 network(),
                                 RotatingGossipEncryptor.rotatingGossipEncryptor(aesGcm(NODE_KEY, NODE_KEY_ID)),
                                 this::announce,
                                 this::fail,
                                 this::diverge,
                                 gateClock::get,
                                 Promise.promise());
            assertThat(announced.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("SWIM started").isTrue();
            gateClock.set(GossipKeyDivergenceGuard.ARMING_DELAY_NANOS + 1);

            var datagram = aesGcm(ROTATED_KEY, ROTATED_KEY_ID).encrypt("probe".getBytes(StandardCharsets.UTF_8))
                                                              .unwrap();

            for (var i = 0; i < GossipKeyDivergenceGuard.UNKNOWN_KEY_THRESHOLD * 2; i++) {
                sender.send(new DatagramPacket(datagram, datagram.length, InetAddress.getLoopbackAddress(), swimPort));
            }

            assertThat(diverged.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("gossip under an unheld key epoch must exit through the node's divergence hook")
                .isTrue();
            assertThat(divergences.get()).as("the gate fires once").isEqualTo(1);
            assertThat(failures.get()).as("divergence is not a SWIM start failure").isZero();
        } finally {
            detector.stop();
        }
    }

    /// #1200 — the seed side. A datagram under a key id the node does not hold becomes the operator event `gossip-key-unknown-from-peer`
    /// naming the sender, through the sink the node bound to its detector; the wiring from the detector to the transport is what is pinned
    /// here (the observer itself is covered where it lives). A datagram it CAN open then closes it with the recovery.
    @Test
    void startSwim_peerGossipsUnderAnUnheldKey_raisesTheOperatorEvent_andTheRecoveryWhenItDecrypts() throws Exception {
        var swimPort = freeUdpPort();
        var detector = detectorWithSwimPort(swimPort);
        var published = new java.util.concurrent.CopyOnWriteArrayList<org.pragmatica.utility.warning.OperatorWarning>();

        detector.setOperatorWarningSink(org.pragmatica.utility.warning.OperatorWarningSink.handingOffTo(published::add));

        try (var sender = new DatagramSocket()) {
            AetherNode.startSwim(detector,
                                 network(),
                                 RotatingGossipEncryptor.rotatingGossipEncryptor(aesGcm(NODE_KEY, NODE_KEY_ID)),
                                 this::announce,
                                 this::fail,
                                 this::diverge,
                                 Promise.promise());
            assertThat(announced.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("SWIM started").isTrue();

            send(sender, swimPort, aesGcm(ROTATED_KEY, ROTATED_KEY_ID).encrypt("probe".getBytes(StandardCharsets.UTF_8)).unwrap());
            awaitWarnings(published, 1);

            assertThat(published).singleElement().satisfies(warning -> {
                assertThat(warning.code()).isEqualTo(org.pragmatica.utility.warning.OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER);
                assertThat(warning.subject()).startsWith("127.0.0.1:");
                assertThat(warning.code().code()).isEqualTo("gossip-key-unknown-from-peer");
                assertThat(warning.code().level()).isEqualTo(org.pragmatica.utility.warning.WarningLevel.WARNING);
            });

            send(sender, swimPort, aesGcm(NODE_KEY, NODE_KEY_ID).encrypt("probe".getBytes(StandardCharsets.UTF_8)).unwrap());
            awaitWarnings(published, 2);

            assertThat(published).extracting(org.pragmatica.utility.warning.OperatorWarning::code)
                                 .containsExactly(org.pragmatica.utility.warning.OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER,
                                                  org.pragmatica.utility.warning.OperatorWarningCode.GOSSIP_KEY_PEER_DECRYPTED);
            assertThat(published.get(1).code().code()).isEqualTo("gossip-key-peer-decrypted");
            assertThat(published.get(1).code().level()).isEqualTo(org.pragmatica.utility.warning.WarningLevel.INFO);
            assertThat(published.get(1).subject()).as("the recovery closes the same subject").isEqualTo(published.get(0).subject());
        } finally {
            detector.stop();
        }
    }

    /// #1200 — feed 2 of a replacement's dial targets (feed 1 is pinned in `ReplacementDialMechanismTest`): SWIM seeding announces every configured
    /// core as a discovered member the moment the protocol starts, without any datagram having decrypted. Removing it, and the configured-descriptor
    /// seed, is what turns `EmberGossipKeyRotatedJoinTest` red.
    @Test
    void start_configuredCores_areSeededAsDiscoveredMembers_beforeAnyDatagramDecrypts() throws Exception {
        var detector = detectorWithSwimPort(freeUdpPort());
        var discovered = new java.util.concurrent.CopyOnWriteArrayList<NodeId>();

        detector.addObservationListener(observation -> {
            if (observation instanceof org.pragmatica.swim.SwimObservation.MemberDiscovered member) {
                discovered.add(member.nodeInfo().id());
            }
        });

        try {
            assertThat(detector.start(Option.none(), GossipEncryptor.none()).await().isSuccess()).isTrue();
            assertThat(discovered).as("the configured peer, announced as discovered, with nothing received").contains(PEER);
            assertThat(discovered).as("never self").doesNotContain(SELF);
        } finally {
            detector.stop();
        }
    }

    /// #1200 — feed 2, the peer-directory site (the cores the cluster published, which a node learns beyond its own `--peers`): a directory observed
    /// BEFORE the start is seeded when the protocol starts, and one observed AFTER it is seeded at once. Neither needs a datagram to decrypt.
    @Test
    void peerDirectory_isSeededAsDiscoveredMembers_whetherObservedBeforeOrAfterTheStart() throws Exception {
        var detector = detectorWithSwimPort(freeUdpPort());
        var discovered = new java.util.concurrent.CopyOnWriteArrayList<NodeId>();
        var before = new NodeId("node-3");
        var after = new NodeId("node-4");

        detector.addObservationListener(observation -> {
            if (observation instanceof org.pragmatica.swim.SwimObservation.MemberDiscovered member) {
                discovered.add(member.nodeInfo().id());
            }
        });
        detector.observePeerDirectory(List.of(directoryEntry(before, 9003)));

        try {
            assertThat(detector.start(Option.none(), GossipEncryptor.none()).await().isSuccess()).isTrue();
            assertThat(discovered).as("observed before the start").contains(before);

            detector.observePeerDirectory(List.of(directoryEntry(before, 9003), directoryEntry(after, 9004)));

            assertThat(discovered).as("observed after the start, seeded at once").contains(after);
        } finally {
            detector.stop();
        }
    }

    private static NodeInfo directoryEntry(NodeId id, int port) {
        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("127.0.0.3", port).unwrap());
    }

    private static void send(DatagramSocket sender, int port, byte[] datagram) throws Exception {
        sender.send(new DatagramPacket(datagram, datagram.length, InetAddress.getLoopbackAddress(), port));
    }

    private static void awaitWarnings(java.util.List<?> published, int expected) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);

        while (published.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    /// #1308 (rev1343 BLOCKING-1) — the node's start outcome is `formationUnlessSwimFails(formation,
    /// outcome)`: formation settles it, and a SWIM failure `startSwim` has already put into it wins
    /// over a later formation success. In a single-JVM host `failNode` only stops this node, whose
    /// formation then never resolves, so an outcome that waited on formation alone hung forever.
    /// Reverting the join (`startSwimTrigger` back to a fire-and-forget Runnable) has no unit-level
    /// seam; the wiring is pinned end-to-end by `EmberClusterSwimStartFailureTest` in `aether/ember`.
    @Test
    void formationUnlessSwimFails_swimFailureAlreadyLanded_formationSuccessDoesNotOverrideIt() {
        var formation = Promise.<Unit> promise();
        var outcome = Promise.<Unit> promise();

        outcome.fail(SWIM_BIND_FAILED);
        AetherNode.formationUnlessSwimFails(formation, outcome);
        formation.succeed(Unit.unit());

        assertThat(outcome.await(timeSpan(1).seconds()))
            .as("the start fails with the SWIM cause; formation cannot override it")
            .isEqualTo(SWIM_BIND_FAILED.result());
    }

    /// A SWIM start SUCCESS settles nothing: the node has started only once formation resolves.
    @Test
    void formationUnlessSwimFails_swimStartSucceeds_startFollowsFormation() {
        var formation = Promise.<Unit> promise();
        var outcome = Promise.<Unit> promise();

        assertThat(AetherNode.formationUnlessSwimFails(formation, outcome)).isSameAs(outcome);
        assertThat(outcome.isResolved()).as("SWIM up alone is not a started node").isFalse();

        formation.succeed(Unit.unit());

        assertThat(outcome.await(timeSpan(1).seconds())).isEqualTo(Result.success(Unit.unit()));
    }

    /// Formation's own failure still reaches the caller when SWIM never reported (e.g. the QUIC
    /// transport never became ready, so the SWIM trigger never fired).
    @Test
    void formationUnlessSwimFails_swimStartPending_formationFailureFailsTheStart() {
        var formation = Promise.<Unit> promise();
        var outcome = AetherNode.formationUnlessSwimFails(formation, Promise.promise());

        formation.fail(FORMATION_FAILED);

        assertThat(outcome.await(timeSpan(1).seconds())).isEqualTo(FORMATION_FAILED.result());
    }

    private void announce() {
        announcements.incrementAndGet();
        announced.countDown();
    }

    private void diverge() {
        divergences.incrementAndGet();
        diverged.countDown();
    }

    private void fail() {
        failures.incrementAndGet();
        failed.countDown();
    }

    private static CoreSwimHealthDetector detectorWithSwimPort(int swimPort) {
        var self = NodeInfo.nodeInfo(SELF,
                                     NodeAddress.nodeAddress("127.0.0.1", swimPort - CoreSwimHealthDetector.SWIM_PORT_OFFSET)
                                                .unwrap());
        var peer = NodeInfo.nodeInfo(PEER, NodeAddress.nodeAddress("127.0.0.2", 9001).unwrap());
        var topology = new TopologyConfig(SELF, 2, timeSpan(1).seconds(), timeSpan(10).seconds(), List.of(self, peer));

        return CoreSwimHealthDetector.coreSwimHealthDetector(MessageRouter.mutable(),
                                                             topology,
                                                             Mockito.mock(Serializer.class),
                                                             Mockito.mock(Deserializer.class));
    }

    private static ClusterNetwork network() {
        var network = Mockito.mock(ClusterNetwork.class);

        Mockito.when(network.server()).thenReturn(Option.none());

        return network;
    }

    private static RotatingGossipEncryptor encryptor() {
        return RotatingGossipEncryptor.rotatingGossipEncryptor(GossipEncryptor.none());
    }

    private static GossipEncryptor aesGcm(byte[] key, int keyId) {
        return AesGcmGossipEncryptor.aesGcmGossipEncryptor(key, keyId).unwrap();
    }

    private static byte[] filled(byte value) {
        var bytes = new byte[32];

        java.util.Arrays.fill(bytes, value);

        return bytes;
    }

    private static int freeUdpPort() throws SocketException {
        try (var socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
