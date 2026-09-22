// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// #1456 — `stop()` is not ordered against an in-flight `start()`. A bind completing after `stop()`
/// has already run is published into a state that owns nothing, so nothing ever stops it: the
/// listener stays bound and unreachable for the life of the process. Seen in CI as
/// `EmberClusterSwimStartFailureTest` failing on a port that could not be reclaimed, with one node's
/// app-http, QUIC and SWIM ports held at once.
///
/// **How the detection is made deterministic, since a sub-millisecond race proves nothing by being
/// green once.** `beforePublishForTest` is a gate inside the exact window the race lives in — after
/// the bind has completed, before the server is published. The test installs the WHOLE of `stop()`
/// as that gate's body, so "stop ran between bind and publish" holds by program order rather than by
/// scheduling luck; there is no sleep, no latch and no second thread to lose a race with. The
/// assertion is by consequence (the TCP port must be rebindable), never by reading internal state.
///
/// Two controls keep a green from being vacuous: the gate asserts the port is genuinely BOUND while
/// it runs — so a pass cannot come from a bind that never happened — and it asserts `stop()` returned
/// success, which is the very report the defect makes while leaking.
class AppHttpServerStopDuringBindTest {
    private static final NodeId SELF_NODE = NodeId.nodeId("stop-during-bind-node").unwrap();
    /// Its own block, clear of `AppHttpServerTest`'s 18080 and of every computed range in this module.
    private static final int TEST_PORT = 18131;
    private static final long RECLAIM_WAIT_MS = 10_000;

    @Test
    @Timeout(60)
    void stop_releasesThePort_whenTheBindLandsAfterStopHasRun() throws Exception {
        var server = appHttpServerOnTestPort();
        var boundAtGate = new AtomicBoolean();
        var stopAtGate = new AtomicReference<Promise<Unit>>();

        ((AppHttpServerAdapter) server).beforePublishForTest(() -> stopInsideTheBindWindow(server,
                                                                                           boundAtGate,
                                                                                           stopAtGate));

        server.start().await();

        assertThat(boundAtGate.get())
            .as("control: TCP %d must already be held when the gate runs, or this test would be "
                + "asserting the release of a port that was never taken", TEST_PORT)
            .isTrue();
        assertThat(stopAtGate.get())
            .as("the gate must have run, or no stop() happened inside the bind window")
            .isNotNull();
        assertThat(stopAtGate.get().await().isSuccess())
            .as("stop() reports success even while leaking — that is the defect, and the port check "
                + "below is what actually distinguishes the two")
            .isTrue();
        assertThat(rebindable(TEST_PORT, RECLAIM_WAIT_MS))
            .as("TCP %d must be reclaimable within %d ms: a bind landing after stop() must be closed "
                + "by whoever publishes it, not left bound with nothing owning it",
                TEST_PORT,
                RECLAIM_WAIT_MS)
            .isTrue();
    }

    /// Runs between the bind completing and the just-bound server being published — the window #1456
    /// lives in. Records that the port is genuinely held here, then starts the whole of `stop()`; its
    /// state transition is synchronous, so by the time this returns `stop()` has already decided what
    /// there was to stop.
    private static void stopInsideTheBindWindow(AppHttpServer server,
                                                AtomicBoolean bound,
                                                AtomicReference<Promise<Unit>> stop) {
        bound.set(!rebindable(TEST_PORT, 0));
        stop.set(server.stop());
    }

    private static AppHttpServer appHttpServerOnTestPort() {
        return AppHttpServer.appHttpServer(AppHttpConfig.insecureAppHttpConfig(TEST_PORT),
                                           ForwardingTimeouts.forwardingTimeouts(),
                                           SELF_NODE,
                                           HttpRouteRegistry.httpRouteRegistry(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.<DeploymentManager> none());
    }

    /// Closing a channel can trail the stop promise by a few milliseconds, so poll — bounded. A zero
    /// budget makes this a single immediate probe, which is how the gate's control reads the port.
    private static boolean rebindable(int port, long budgetMs) {
        var deadline = System.nanoTime() + budgetMs * 1_000_000L;

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
        try (var socket = new ServerSocket(port)) {
            return socket.isBound();
        } catch (IOException taken) {
            return false;
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
