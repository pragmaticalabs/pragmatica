// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http;

import java.util.Map;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The app HTTP/3 listener, at start and as a certificate rotation rebuilds it, applies the `[app-http]` request
/// bounds to a real request stream, from a real HTTP/3 client. Each bound is set to a value the server default would
/// answer differently, so a listener built from a default config fails the assertion:
/// - `max_request_size` 1 KiB (default 64 KiB): a 4 KiB body is answered 413, where the default dispatches it;
/// - `read_timeout` 2 s (default 60 s): a body that stalls is answered 408, where the default leaves it to QUIC's 5 s
///   idle close, which answers nothing;
/// - `min_body_rate` 0, which disables the bound (default 1 KiB/s after a 10 s grace): a body trickled at 40 B/s for
///   12 s is dispatched, where the default answers it 408 at the end of its grace.
class AppHttpServerH3BoundsTest {
    private static final int LIMIT = 1024;
    private static final int OVER_LIMIT = 4096;
    private static final int TRICKLE_CHUNK = 20;
    private static final int TRICKLE_CHUNKS = 24;
    private static final long TRICKLE_INTERVAL_MS = 500;

    @Test
    @Timeout(120)
    void startedListener_appliesTheConfiguredBounds_toARealStream() throws Exception {
        var port = TlsProbe.freeTcpPort();
        var server = appHttpServer(port);

        try {
            assertThat(server.start().await(timeSpan(30).seconds()).isSuccess()).as("control: the server starts").isTrue();

            assertConfiguredBounds(port);
        } finally {
            server.stop().await(timeSpan(30).seconds());
        }
    }

    @Test
    @Timeout(180)
    void rotatedListener_appliesTheConfiguredBounds_toARealStream() throws Exception {
        var port = TlsProbe.freeTcpPort();
        var server = appHttpServer(port);

        try {
            assertThat(server.start().await(timeSpan(30).seconds()).isSuccess()).as("control: the server starts").isTrue();
            var original = H3Probe.presentedCertificate(port);

            assertThat(server.rotateCertificate(TlsProbe.validBundle("h3-bounds-rotation-node"))
                             .await(timeSpan(60).seconds())
                             .isSuccess()).as("control: a valid bundle rotates").isTrue();
            assertThat(TlsProbe.sameCertificate(original, H3Probe.presentedCertificate(port)))
                .as("control: the HTTP/3 listener was rebuilt").isFalse();

            assertConfiguredBounds(port);
        } finally {
            server.stop().await(timeSpan(30).seconds());
        }
    }

    private static void assertConfiguredBounds(int port) throws Exception {
        var dispatched = H3Probe.exchange(port, stream -> body(stream, port, 16)).status();

        assertThat(dispatched).as("control: what a request the server dispatches is answered")
                              .matches("\\d{3}")
                              .isNotIn("408", "413");

        var oversized = H3Probe.exchange(port, stream -> body(stream, port, OVER_LIMIT));

        assertThat(oversized.status()).as("max_request_size reaches the stream").isEqualTo("413");

        var stalled = H3Probe.exchange(port, stream -> stall(stream, port));

        assertThat(stalled.status()).as("read_timeout reaches the stream").isEqualTo("408");
        assertThat(stalled.body()).contains("idle");

        var trickled = H3Probe.exchange(port, stream -> trickle(stream, port));

        assertThat(trickled.status()).as("min_body_rate 0 reaches the stream: the trickle is dispatched")
                                     .isEqualTo(dispatched);
    }

    private static void body(QuicStreamChannel stream, int port, int size) {
        stream.write(H3Probe.requestHeaders(port, "POST"));
        stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[size])))
              .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
    }

    /// A first body frame, then nothing: the stream stays open with its body incomplete.
    private static void stall(QuicStreamChannel stream, int port) {
        stream.write(H3Probe.requestHeaders(port, "POST"));
        stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[16])));
    }

    /// 480 bytes over 12 s, one frame every 500 ms: inside the 1 KiB limit, never idle for the 2 s read timeout, and
    /// past the default rate's 10 s grace.
    private static void trickle(QuicStreamChannel stream, int port) throws InterruptedException {
        stream.writeAndFlush(H3Probe.requestHeaders(port, "POST"));

        for (int i = 0; i < TRICKLE_CHUNKS; i++) {
            Thread.sleep(TRICKLE_INTERVAL_MS);
            stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[TRICKLE_CHUNK])));
        }

        stream.shutdownOutput();
    }

    private static AppHttpServer appHttpServer(int port) {
        var config = AppHttpConfig.appHttpConfig(true, port, Map.of(), LIMIT, SecurityMode.NONE, Option.none(), HttpProtocol.H3)
                                  .unwrap()
                                  .withReadTimeout(timeSpan(2).seconds())
                                  .withMinBodyRate(0);

        return AppHttpServer.appHttpServer(config,
                                           ForwardingTimeouts.forwardingTimeouts(),
                                           NodeId.nodeId("h3-bounds-node").unwrap(),
                                           HttpRouteRegistry.httpRouteRegistry(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.some(TlsConfig.selfSignedServer()),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.<DeploymentManager> none());
    }
}
