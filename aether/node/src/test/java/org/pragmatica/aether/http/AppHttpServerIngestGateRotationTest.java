// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.Map;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.adapter.RouteDecorator;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.VersioningMetricsSink;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The listener a certificate rotation rebuilds applies the ingest gate as the one it started with: the listener's
/// `max_request_size` is 1 KiB, the route `/upload/` declares 1 MiB, and a 4 KiB body passes only while the route's own
/// limit governs (a rebuilt listener without the classifier answers it 413 from the general limit). Over HTTP/1.1 (TLS) and
/// HTTP/3 alike, before and after the rotation.
@Timeout(240)
class AppHttpServerIngestGateRotationTest {
    private static final int MIB = 1024 * 1024;

    private AppHttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop().await(timeSpan(30).seconds());
        }
    }

    private AppHttpServer server(int port, HttpProtocol protocol) {
        var config = AppHttpConfig.appHttpConfig(true, port, Map.of(), 1024, SecurityMode.NONE, Option.none(), protocol).unwrap();
        var publisher = new IngestGateRoutes(new OkRouter(), SecurityPolicy.publicRoute());

        return AppHttpServer.appHttpServer(config,
                                           ForwardingTimeouts.forwardingTimeouts(),
                                           NodeId.nodeId("ingest-rotation-node").unwrap(),
                                           HttpRouteRegistry.httpRouteRegistry(),
                                           Option.some(publisher),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.some(TlsConfig.selfSignedServer()),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.<DeploymentManager> none());
    }

    @Test
    void rotatedHttp1Listener_stillAppliesTheRoutesDeclaredLimit() throws Exception {
        server = server(0, HttpProtocol.H1);
        TlsProbe.requireStarted(server.start().await(timeSpan(30).seconds()), "app listener");
        var port = TlsProbe.requireBoundPort(server.boundPort(), "app listener");

        assertThat(TlsProbe.httpsPostStatus(port, 4096)).as("control: before the rotation the route's 1 MiB governs").isEqualTo(200);
        assertThat(TlsProbe.httpsPostStatus(port, 2 * MIB)).as("and its limit is in force").isEqualTo(413);

        assertThat(server.rotateCertificate(TlsProbe.validBundle("ingest-rotation-h1")).await(timeSpan(60).seconds()).isSuccess())
            .as("control: a valid bundle rotates").isTrue();
        var rotatedPort = TlsProbe.requireBoundPort(server.boundPort(), "rotated app listener");

        assertThat(TlsProbe.httpsPostStatus(rotatedPort, 4096)).as("after the rotation the route's limit still governs").isEqualTo(200);
        assertThat(TlsProbe.httpsPostStatus(rotatedPort, 2 * MIB)).isEqualTo(413);
    }

    @Test
    void rotatedHttp3Listener_stillAppliesTheRoutesDeclaredLimit() throws Exception {
        var port = TlsProbe.freeTcpPort();

        server = server(port, HttpProtocol.H3);
        TlsProbe.requireStarted(server.start().await(timeSpan(30).seconds()), "app listener");

        assertThat(post(port, "/upload/x", 4096)).as("control: before the rotation the route's 1 MiB governs").isEqualTo("200");
        assertThat(post(port, "/elsewhere/x", 4096)).as("control: the general 1 KiB limit is in force elsewhere").isEqualTo("413");

        assertThat(server.rotateCertificate(TlsProbe.validBundle("ingest-rotation-h3")).await(timeSpan(60).seconds()).isSuccess())
            .as("control: a valid bundle rotates").isTrue();

        assertThat(post(port, "/upload/x", 4096)).as("after the rotation the route's limit still governs").isEqualTo("200");
        assertThat(post(port, "/elsewhere/x", 4096)).isEqualTo("413");
    }

    private static String post(int port, String path, int size) throws Exception {
        return H3Probe.exchange(port, stream -> {
            var headers = H3Probe.requestHeaders(port, "POST");

            headers.headers().path(path);
            headers.headers().add("content-length", String.valueOf(size));
            stream.write(headers);
            stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[size])))
                  .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        }).status();
    }

    private static final class OkRouter implements SliceRouter {
        @Override
        public Promise<HttpResponseData> handle(HttpRequestContext request) {
            return Promise.success(HttpResponseData.httpResponseData(200, "served"));
        }

        @Override
        public SliceVersionRegistry versionRegistry() {
            return SliceVersionRegistry.UNVERSIONED;
        }

        @Override
        public SliceRouter withObservability(String sliceName, VersioningMetricsSink sink) {
            return this;
        }

        @Override
        public SliceRouter withInvocationCells(RouteDecorator decorator) {
            return this;
        }
    }
}
