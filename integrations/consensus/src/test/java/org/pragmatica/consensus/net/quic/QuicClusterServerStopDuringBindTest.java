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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1456 — the cluster transport's half of the same defect: `handleBind` stored the just-bound UDP
/// channel in a field that `initiateShutdown` had already read and found empty, so `stop()` closed
/// nothing, reported success, and the channel stayed bound with nothing owning it. In CI one node
/// held its QUIC port alongside its management and app-http ports, and its missing-peer reconciler —
/// armed by the same post-start hooks — was still dialling 2m10s later, inside later test classes.
///
/// **How the detection is made deterministic**, a sub-millisecond race being worth nothing as a
/// one-off green: `beforePublishForTest` is a gate in exactly the bind→publish window, and the whole
/// of `stop()` runs as that gate's body. "stop ran between bind and publish" therefore holds by
/// program order, with no sleep, no latch and no second thread. The assertion is by consequence —
/// the UDP port must be rebindable — plus the start's own verdict, which must now be a failure
/// rather than the success that used to arm a transport nobody owned.
@Timeout(60)
class QuicClusterServerStopDuringBindTest {
    private static final NodeId SERVER_NODE = NodeId.randomNodeId();
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(20).seconds();
    private static final long RECLAIM_WAIT_MS = 10_000;

    @Test
    void start_closesTheChannelAndFails_whenStopRunsBeforeTheBindIsPublished() throws Exception {
        var port = freeUdpPort();
        var server = quicClusterServerOn(port);
        var boundAtGate = new AtomicBoolean();
        var stopAtGate = new AtomicReference<Promise<Unit>>();

        ((QuicClusterServerInstance) server).beforePublishForTest(() -> stopInsideTheBindWindow(server,
                                                                                                port,
                                                                                                boundAtGate,
                                                                                                stopAtGate));

        var started = server.start(port).await(AWAIT_TIMEOUT);

        assertThat(boundAtGate.get())
            .as("control: UDP %d must already be held when the gate runs, or this test would be "
                + "asserting the release of a port that was never taken", port)
            .isTrue();
        assertThat(stopAtGate.get())
            .as("the gate must have run, or no stop() happened inside the bind window")
            .isNotNull();
        stopAtGate.get().await(AWAIT_TIMEOUT).onFailure(cause -> fail("stop() failed: " + cause.message()));

        // The port claim is checked FIRST, deliberately: it is the primary consequence, and asserting
        // the start's verdict ahead of it would abort the test before the port was ever read — which
        // is exactly what a mutation probe on 2026-09-23 did, leaving the port claim unmeasured.
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
    /// #1456 lives in. Records that the port is genuinely held here, then starts the whole of
    /// `stop()`; its decision about what there is to stop is taken synchronously, so by the time this
    /// returns `stop()` has already looked and found nothing.
    private static void stopInsideTheBindWindow(QuicClusterServer server,
                                                int port,
                                                AtomicBoolean bound,
                                                AtomicReference<Promise<Unit>> stop) {
        bound.set(!bindable(port));
        stop.set(server.stop());
    }

    private static QuicClusterServer quicClusterServerOn(int port) {
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
                                                                               Option.empty(),
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
