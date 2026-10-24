/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
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
 *
 */

package org.pragmatica.http;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.net.tcp.QuicSslContextFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2386: our client against our server, which refuses a request with more than one `Host` line (RFC 9112 §3.2). A
/// caller that supplies its own `host` header gets exactly one on the wire, carrying the caller's value; the client
/// used to set the URI's host and then add the caller's, so the request was refused 400.
///
/// The request is a minimal [HttpRequest] of our own: the JDK's builder refuses a `Host` header unless
/// `jdk.httpclient.allowRestrictedHeaders` names it, but [HttpOperations] accepts any request.
class NettyHttpOperationsHostTest {
    private final List<org.pragmatica.http.HttpRequest> received = new CopyOnWriteArrayList<>();
    private final BiConsumer<org.pragmatica.http.HttpRequest, ResponseWriter> recorder = (request, writer) -> {
        received.add(request);
        writer.ok("{}");
    };
    private HttpServer server;
    private NettyHttpOperations client;

    @AfterEach
    void stop() {
        client.close().await(timeSpan(10).seconds());
        server.stop().await(timeSpan(10).seconds());
    }

    @Nested
    class Http1 {
        @Test
        void callerHost_isTheOneHostOnTheWire() {
            assertThat(sendWith(Map.of("Host", List.of("api.example")))).isEqualTo(200);
            assertThat(received.getFirst().headers().getAll("host")).containsExactly("api.example");
        }

        @Test
        void callerHost_inLowerCase_isTheOneHostOnTheWire() {
            assertThat(sendWith(Map.of("host", List.of("api.example")))).isEqualTo(200);
            assertThat(received.getFirst().headers().getAll("host")).containsExactly("api.example");
        }

        @Test
        void noCallerHost_sendsTheUriHost() {
            assertThat(sendWith(Map.of("x-other", List.of("v")))).isEqualTo(200);
            assertThat(received.getFirst().headers().getAll("host")).containsExactly("127.0.0.1");
        }

        private int sendWith(Map<String, List<String>> headers) {
            server = HttpServer.httpServer(HttpServerConfig.httpServerConfig("client-host-h1", 0), recorder)
                               .await(timeSpan(10).seconds())
                               .unwrap();
            client = NettyHttpOperations.nettyHttpOperations();

            return send(URI.create("http://127.0.0.1:" + server.port() + "/"), headers);
        }
    }

    @Nested
    class Http3Transport {
        /// HTTP/3 carries the host as `:authority`; a `host` field beside a different authority is refused by the
        /// server's codec (RFC 9114 §4.3.1), and an uppercase name by the client's own. The caller's value becomes
        /// the authority.
        @Test
        void callerHost_becomesTheAuthority_andNoHostFieldIsSent() {
            var port = startServerAndClient();

            assertThat(send(URI.create("https://127.0.0.1:" + port + "/"), Map.of("Host", List.of("api.example"))))
                .isEqualTo(200);
            assertThat(received.getFirst().headers().getAll(":authority")).containsExactly("api.example");
            assertThat(received.getFirst().headers().getAll("host")).isEmpty();
        }

        /// With no caller `host`, the URI's host is the authority, and the caller's other headers still arrive.
        @Test
        void noCallerHost_sendsTheUriHostAsAuthority_andKeepsTheCallersOtherHeaders() {
            var port = startServerAndClient();

            assertThat(send(URI.create("https://127.0.0.1:" + port + "/"), Map.of("x-other", List.of("v"))))
                .isEqualTo(200);
            assertThat(received.getFirst().headers().getAll(":authority")).containsExactly("127.0.0.1");
            assertThat(received.getFirst().headers().getAll("x-other")).containsExactly("v");
        }

        private int startServerAndClient() {
            var port = freeUdpPort();

            server = HttpServer.http3Server(HttpServerConfig.httpServerConfig("client-host-h3", port),
                                            QuicSslContextFactory.createSelfSignedServer(Http3.supportedApplicationProtocols())
                                                                 .unwrap(),
                                            recorder)
                               .await(timeSpan(10).seconds())
                               .unwrap();
            client = NettyHttpOperations.nettyHttpOperations(QuicSslContextBuilder.forClient()
                                                                                  .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                                                                  .applicationProtocols(Http3.supportedApplicationProtocols())
                                                                                  .build());

            return port;
        }
    }

    private int send(URI uri, Map<String, List<String>> headers) {
        return client.send(new CallerRequest(uri, headers), BodyHandlers.ofString())
                     .await(timeSpan(10).seconds())
                     .map(HttpResult::statusCode)
                     .or(-1);
    }

    private static int freeUdpPort() {
        try (var socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /// A GET carrying exactly the headers the caller gave.
    private static final class CallerRequest extends HttpRequest {
        private final URI uri;
        private final HttpHeaders headers;

        CallerRequest(URI uri, Map<String, List<String>> headers) {
            this.uri = uri;
            this.headers = HttpHeaders.of(headers, (_, _) -> true);
        }

        @Override
        public Optional<BodyPublisher> bodyPublisher() {
            return Optional.empty();
        }

        @Override
        public String method() {
            return "GET";
        }

        @Override
        public Optional<Duration> timeout() {
            return Optional.empty();
        }

        @Override
        public boolean expectContinue() {
            return false;
        }

        @Override
        public URI uri() {
            return uri;
        }

        @Override
        public Optional<java.net.http.HttpClient.Version> version() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }
    }
}
