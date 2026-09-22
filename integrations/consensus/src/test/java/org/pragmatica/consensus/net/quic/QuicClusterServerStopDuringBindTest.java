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

package org.pragmatica.consensus.net.quic;

import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1456 — the cluster transport's half of the same defect: `handleBind` stored the just-bound UDP
/// channel in a field that `initiateShutdown` had already read and found empty, so `stop()` closed
/// nothing, reported success, and the channel stayed bound with nothing owning it.
///
/// **How the interleaving is forced.** `beforePublishForTest` is a gate in exactly the bind→publish
/// window. The test parks the start there on a latch, runs `stop()` **to completion** on its own
/// thread, and only then releases the gate. No sleep, no guessed ordering: the release cannot happen
/// until `stop()` has returned.
///
/// An earlier version of this test ran `stop()` from inside the gate and relied on program order.
/// That was wrong, and a loaded run caught it: `Promise.promise(Consumer)` is
/// `promise().async(consumer)`, so `QuicClusterServerInstance.stop()` *schedules* `initiateShutdown`
/// rather than running it inline. It passed in isolation and failed under load — the exact
/// timing-dependence this class exists to rule out. Hence the latch.
///
/// **Why a SHARED event loop group.** With an owned group, `shutdownEventLoop` calls
/// `shutdownGracefully()`, whose termination future cannot complete while this gate parks one of that
/// group's loops — the test would deadlock. It also *masks* the defect: shutting the group down
/// closes the channels registered to it, so the port comes back even when the orphan is never
/// closed. Measured, 2026-09-23: with an owned group and the fix reverted, the UDP port assertion
/// PASSED and only the start's verdict reddened. The shared-loop configuration takes that accident
/// away, so the port claim here is a claim about the publish path and nothing else. The divergence
/// from production is stated rather than hidden: `QuicClusterNetwork` passes `Option.empty()` and so
/// owns its group.
@Timeout(90)
class QuicClusterServerStopDuringBindTest {
    private static final NodeId SERVER_NODE = NodeId.randomNodeId();
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(30).seconds();
    private static final long RECLAIM_WAIT_MS = 10_000;
    private static final long LATCH_WAIT_SECONDS = 30;

    private EventLoopGroup sharedGroup;

    @BeforeEach
    void setUp() {
        sharedGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        sharedGroup.shutdownGracefully().await(AWAIT_TIMEOUT.millis(), TimeUnit.MILLISECONDS);
    }

    @Test
    void start_closesTheChannelAndFails_whenStopRunsBeforeTheBindIsPublished() throws Exception {
        var port = freeUdpPort();
        var server = quicClusterServerOn(port);
        var reachedGate = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var boundAtGate = new AtomicBoolean();

        ((QuicClusterServerInstance) server).beforePublishForTest(() -> holdAtGate(port,
                                                                                   boundAtGate,
                                                                                   reachedGate,
                                                                                   release));

        var start = CompletableFuture.supplyAsync(() -> server.start(port).await(AWAIT_TIMEOUT));

        assertThat(reachedGate.await(LATCH_WAIT_SECONDS, TimeUnit.SECONDS))
            .as("start() must reach the bound-but-not-yet-published gate")
            .isTrue();
        assertThat(boundAtGate.get())
            .as("control: UDP %d must already be held at the gate, or this test would be asserting "
                + "the release of a port that was never taken", port)
            .isTrue();

        server.stop().await(AWAIT_TIMEOUT).onFailure(cause -> fail("stop() failed: " + cause.message()));

        release.countDown();
        var started = start.get(LATCH_WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(rebindable(port))
            .as("UDP %d must be reclaimable within %d ms: a bind landing after stop() must be closed "
                + "by whoever publishes it", port, RECLAIM_WAIT_MS)
            .isTrue();
        started.onSuccess(_ -> fail("start() must not report a running server when stop() won the race: "
                                    + "succeeding here is what armed QuicClusterNetwork's reconciler and "
                                    + "keepalive on a transport that was already stopped"))
               .onFailure(cause -> assertThat(cause).isEqualTo(QuicTransportError.General.STOPPED_DURING_START));
    }

    /// Runs between the bind completing and the just-bound channel being published — the window
    /// #1456 lives in. Records that the port is genuinely held, signals the test, and parks until
    /// the test has finished running `stop()`.
    private static void holdAtGate(int port, AtomicBoolean bound, CountDownLatch reached, CountDownLatch release) {
        bound.set(!bindable(port));
        reached.countDown();

        try {
            release.await(LATCH_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private QuicClusterServer quicClusterServerOn(int port) {
        var codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), combinedCodecs());

        return QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("stop-during-bind"))
                              .fold(cause -> fail("Server SSL context creation failed: " + cause.message()),
                                    ssl -> QuicClusterServer.quicClusterServer(SERVER_NODE,
                                                                               new NodeAddress("127.0.0.1", port),
                                                                               Map.of(),
                                                                               codec,
                                                                               codec,
                                                                               QuicTransportMetrics.quicTransportMetrics(),
                                                                               ssl,
                                                                               Option.some(sharedGroup),
                                                                               (_, _, _) -> {},
                                                                               (_, _) -> {}));
    }

    private static List<SliceCodec.TypeCodec<?>> combinedCodecs() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();

        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);

        return all;
    }

    /// A closed channel's port can trail the stop promise by a few milliseconds; poll for it, bounded.
    private static boolean rebindable(int port) {
        var deadline = System.nanoTime() + RECLAIM_WAIT_MS * 1_000_000L;

        while (true) {
            if (bindable(port)) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            sleepQuietly();
        }
    }

    private static boolean bindable(int port) {
        try (var socket = new DatagramSocket(port)) {
            return socket.isBound();
        } catch (SocketException taken) {
            return false;
        }
    }

    private static int freeUdpPort() throws SocketException {
        try (var socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
