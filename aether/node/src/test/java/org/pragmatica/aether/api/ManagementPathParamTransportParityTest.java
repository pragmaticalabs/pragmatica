// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.http.H3Probe;
import org.pragmatica.aether.http.TlsProbe;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.net.tcp.QuicSslContextFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2420 with #2419: a management path parameter reaches the route handler decoded exactly once, whichever transport
/// carried the request. The server is a real HTTP/1.1 listener and a real HTTP/3 listener (a QUIC handshake and a standard
/// HTTP/3 client), each given a handler that does what `ManagementServer` does with the request: `ManagementRoute.match`
/// on `HttpRequest.path()`, then the `id` parameter. `ManagementServer` itself is not booted.
class ManagementPathParamTransportParityTest {
    private static final String DEPLOY = "/api/v1/deploy/";

    @Test
    @Timeout(120)
    void escapedPercent_isDecodedOnce_overH1AndH3() throws Exception {
        // %2541 is the encoding of the text "%41"
        assertParamOnBothTransports("%2541", "%41");
    }

    @Test
    @Timeout(120)
    void escapedPlus_staysAPlus_overH1AndH3() throws Exception {
        assertParamOnBothTransports("a%2Bb", "a+b");
    }

    /// Control: a singly-encoded space decodes to a space on both, so the assertions above can go green.
    @Test
    @Timeout(120)
    void escapedSpace_isDecodedOnce_overH1AndH3() throws Exception {
        assertParamOnBothTransports("a%20b", "a b");
    }

    private static void assertParamOnBothTransports(String onTheWire, String expectedParam) throws Exception {
        assertThat(paramOverH1(onTheWire)).as("HTTP/1.1 param for %s", onTheWire).isEqualTo(expectedParam);
        assertThat(paramOverH3(onTheWire)).as("HTTP/3 param for %s", onTheWire).isEqualTo(expectedParam);
    }

    private static void managementHandler(HttpRequest request, ResponseWriter writer) {
        writer.okText(ManagementRoute.match(HttpMethod.GET, request.path())
                                     .map(matched -> matched.param("id").or("<no id>"))
                                     .or("<no match>"));
    }

    private static String paramOverH1(String onTheWire) throws IOException {
        var server = HttpServer.httpServer(HttpServerConfig.httpServerConfig("mgmt-parity-h1", 0),
                                           ManagementPathParamTransportParityTest::managementHandler)
                               .await(timeSpan(10).seconds())
                               .unwrap();

        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream()
                  .write(("GET " + DEPLOY + onTheWire + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            return bodyOf(new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            server.stop().await(timeSpan(10).seconds());
        }
    }

    private static String bodyOf(String response) {
        return response.substring(response.indexOf("\r\n\r\n") + 4);
    }

    private static String paramOverH3(String onTheWire) throws Exception {
        var port = TlsProbe.freeTcpPort();
        var server = HttpServer.http3Server(HttpServerConfig.httpServerConfig("mgmt-parity-h3", port),
                                            quicContext(),
                                            ManagementPathParamTransportParityTest::managementHandler)
                               .await(timeSpan(30).seconds())
                               .unwrap();

        try {
            return H3Probe.exchange(port, stream -> get(stream, port, DEPLOY + onTheWire)).body();
        } finally {
            server.stop().await(timeSpan(30).seconds());
        }
    }

    private static void get(QuicStreamChannel stream, int port, String path) {
        var headers = H3Probe.requestHeaders(port, "GET");

        headers.headers().path(path);
        stream.writeAndFlush(headers).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
    }

    private static QuicSslContext quicContext() {
        return QuicSslContextFactory.createSelfSignedServer(Http3.supportedApplicationProtocols()).unwrap();
    }
}
