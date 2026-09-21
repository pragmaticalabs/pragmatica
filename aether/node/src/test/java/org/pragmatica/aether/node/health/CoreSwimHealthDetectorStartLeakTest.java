// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.health;

import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.node.health.fsm.SwimHealthState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.GossipEncryptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1308 — the two ways a start() could leave the SWIM UDP port bound with nothing owning it. Both
/// drive a REAL detector binding a REAL port; the gated `afterBind` seam holds the start between
/// the bind and the protocol, and "released" is measured by re-binding the port.
class CoreSwimHealthDetectorStartLeakTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER = new NodeId("node-2");

    @Test
    void stop_whileStarting_releasesThePortOnceTheInFlightStartCompletes() throws Exception {
        var swimPort = freeUdpPort();
        var detector = detectorWithSwimPort(swimPort);
        var reachedBind = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        detector.afterBindForTest(() -> holdUntilReleased(reachedBind, release));

        var start = CompletableFuture.supplyAsync(() -> detector.start(Option.none(), GossipEncryptor.none())
                                                                .await());

        assertThat(reachedBind.await(10, TimeUnit.SECONDS)).as("start() must reach the bound-port gate").isTrue();
        assertThat(detector.lifecycleState()).isInstanceOf(SwimHealthState.Starting.class);

        detector.stop();

        assertThat(detector.lifecycleState()).isInstanceOf(SwimHealthState.Stopped.class);

        release.countDown();
        start.get(10, TimeUnit.SECONDS);

        assertThat(detector.lifecycleState())
            .as("a start that lost the race to stop() must not revive the detector")
            .isInstanceOf(SwimHealthState.Stopped.class);
        assertThat(rebindable(swimPort))
            .as("SWIM UDP %d must be released when stop() arrived during Starting", swimPort)
            .isTrue();
    }

    @Test
    void start_failingAfterASuccessfulBind_releasesThePort() throws Exception {
        var swimPort = freeUdpPort();
        var detector = detectorWithSwimPort(swimPort);

        detector.afterBindForTest(() -> Causes.cause("injected post-bind failure").result());

        var started = detector.start(Option.none(), GossipEncryptor.none())
                              .await();

        assertThat(started.isFailure()).as("start() result %s", started).isTrue();
        assertThat(detector.lifecycleState()).isInstanceOf(SwimHealthState.Stopped.class);
        assertThat(rebindable(swimPort))
            .as("SWIM UDP %d must be released when start() fails after binding it", swimPort)
            .isTrue();
    }

    private static Result<Unit> holdUntilReleased(CountDownLatch reached, CountDownLatch release) {
        reached.countDown();

        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return Result.unitResult();
    }

    /// Transport stop is asynchronous, so poll briefly for the port to become bindable again.
    private static boolean rebindable(int port) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            try (var socket = new DatagramSocket(port)) {
                return true;
            } catch (SocketException taken) {
                Thread.sleep(50);
            }
        }

        return false;
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

    private static int freeUdpPort() throws SocketException {
        try (var socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
