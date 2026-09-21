// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    private final AtomicInteger announcements = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final CountDownLatch announced = new CountDownLatch(1);
    private final CountDownLatch failed = new CountDownLatch(1);

    @Test
    void startSwim_swimPortAlreadyBound_failsTheNodeAndDoesNotAnnounce() throws Exception {
        var swimPort = freeUdpPort();
        var detector = detectorWithSwimPort(swimPort);

        try (var holder = new DatagramSocket(swimPort)) {
            AetherNode.startSwim(detector, network(), encryptor(), this::announce, this::fail, Promise.promise());

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

    @Test
    void startSwim_swimPortFree_announcesTheJoinAndDoesNotFailTheNode() throws Exception {
        var detector = detectorWithSwimPort(freeUdpPort());

        try {
            AetherNode.startSwim(detector, network(), encryptor(), this::announce, this::fail, Promise.promise());

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

    private static int freeUdpPort() throws SocketException {
        try (var socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
