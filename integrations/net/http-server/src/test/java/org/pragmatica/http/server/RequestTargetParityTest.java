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

package org.pragmatica.http.server;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2419: one request target, over HTTP/1.1 (a real server and a raw socket) and over HTTP/3 (the server's own request-stream
/// handler in an `EmbeddedChannel`, as `Http3RequestHandlerTest` does: the module has no QUIC client that writes a chosen
/// `:path`, so the QUIC transport itself is not exercised, only the step that builds the `HttpRequest` from the stream),
/// reaches the handler as the same `path()` and `queryParams()`: percent-decoded once, `+` literal in the path and a space in
/// the query. A target that cannot be decoded is answered 400 on both and never dispatched.
class RequestTargetParityTest {
    private static final String TARGET = "/echo/a%2Fb/c%41/p%2Bq%20r?x=a%20b%2Bc&y=%41&z=a+b&flag";

    private final List<HttpRequest> overH1 = new CopyOnWriteArrayList<>();
    private final List<HttpRequest> overH3 = new CopyOnWriteArrayList<>();

    @Test
    void sameTarget_reachesTheHandlerIdentically_overH1AndH3() throws IOException {
        sendOverH1(TARGET);
        sendOverH3(TARGET);

        assertThat(overH1).hasSize(1);
        assertThat(overH3).hasSize(1);
        assertThat(overH3.getFirst().path()).as("path(): H3 vs H1").isEqualTo(overH1.getFirst().path());
        assertThat(overH3.getFirst().queryParams().asMap()).as("queryParams(): H3 vs H1")
                                                           .isEqualTo(overH1.getFirst().queryParams().asMap());
    }

    @Test
    void target_isDecodedOnce_onBothTransports() throws IOException {
        sendOverH1(TARGET);
        sendOverH3(TARGET);

        for (var request : List.of(overH1.getFirst(), overH3.getFirst())) {
            assertThat(request.path()).isEqualTo("/echo/a/b/cA/p+q r");
            assertThat(request.queryParams().asMap()).containsEntry("x", List.of("a b+c"))
                                                     .containsEntry("y", List.of("A"))
                                                     .containsEntry("z", List.of("a b"))
                                                     .containsEntry("flag", List.of(""));
        }
    }

    @Test
    void escapedPercent_isNotDecodedTwice_onBothTransports() throws IOException {
        sendOverH1("/api/v1/deploy/%2541?q=%2541");
        sendOverH3("/api/v1/deploy/%2541?q=%2541");

        for (var request : List.of(overH1.getFirst(), overH3.getFirst())) {
            assertThat(request.path()).isEqualTo("/api/v1/deploy/%41");
            assertThat(request.queryParams().get("q").or("?")).isEqualTo("%41");
        }
    }

    @Test
    void undecodableTarget_isAnswered400_andNeverDispatched_onBothTransports() throws IOException {
        for (var malformed : List.of("/x%zz", "/x%", "/x?a=%zz")) {
            assertThat(statusOverH1(malformed)).as("H1 %s", malformed).isEqualTo("400");
            assertThat(statusOverH3(malformed)).as("H3 %s", malformed).isEqualTo("400");
        }

        assertThat(overH1).as("a malformed target never reaches the handler over H1").isEmpty();
        assertThat(overH3).as("a malformed target never reaches the handler over H3").isEmpty();
    }

    /// A rejected request is answered exactly once and nothing after it is served: a well-formed request pipelined behind a
    /// malformed one on the same connection gets no response and no handler call (the connection is closed after the 400).
    @Test
    void undecodableTarget_isAnsweredOnce_andNothingPipelinedBehindItIsServed_overH1() throws IOException {
        var response = rawOverH1("GET /x%zz HTTP/1.1\r\nHost: localhost\r\n\r\n"
                                 + "GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        assertThat(response.split("HTTP/1\\.1 ", -1)).as("one status line in %s", response).hasSize(2);
        assertThat(statusCode(response)).isEqualTo("400");
        assertThat(overH1).isEmpty();
    }

    @Test
    void undecodableTarget_isAnsweredOnce_overH3() {
        var channel = h3Channel();
        var headers = new DefaultHttp3Headers();

        headers.method("GET");
        headers.path("/x%zz");
        channel.writeInbound(new DefaultHttp3HeadersFrame(headers));
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);

        var statuses = new java.util.ArrayList<String>();

        for (Object frame = channel.readOutbound(); frame != null; frame = channel.readOutbound()) {
            if (frame instanceof Http3HeadersFrame headersFrame) {
                statuses.add(headersFrame.headers().status().toString());
            }
        }

        channel.finishAndReleaseAll();
        assertThat(statuses).containsExactly("400");
        assertThat(overH3).isEmpty();
    }

    /// Netty's query decoder keeps at most 1024 parameters and drops the rest without failing. Both transports hand the
    /// handler the same truncated list (over HTTP/1.1 the 4096-byte request line limit only allows this many with
    /// near-empty parameters), so a target past the bound is served, not rejected, and identically.
    @Test
    void queryBeyondTheDecodersBound_isTruncatedIdentically_onBothTransports() throws IOException {
        var target = "/q?" + "a&".repeat(1100);

        sendOverH1(target);
        sendOverH3(target);

        assertThat(overH1.getFirst().queryParams().getAll("a")).hasSize(1024);
        assertThat(overH3.getFirst().queryParams().asMap()).isEqualTo(overH1.getFirst().queryParams().asMap());
    }

    @Test
    void requestTarget_failsWithMalformedRequestTarget_carryingTheDecoderFailure() {
        RequestTarget.requestTarget("/x%zz")
                     .onSuccess(target -> assertThat(target).as("a malformed escape must not decode").isNull())
                     .onFailure(cause -> assertThat(cause).isInstanceOf(HttpServerError.MalformedRequestTarget.class)
                                                          .satisfies(c -> assertThat(c.message()).contains("%zz")));
    }

    @Test
    void undecodableTarget_releasesTheBufferedBody_overH3() {
        var channel = h3Channel();
        var headers = new DefaultHttp3Headers();

        headers.method("POST");
        headers.path("/x%zz");
        channel.writeInbound(new DefaultHttp3HeadersFrame(headers));
        channel.writeInbound(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[16])));
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);

        assertThat(((Http3Server.Http3RequestHandler) channel.pipeline().first()).holdsBodyBuffer()).isFalse();
        assertThat(overH3).isEmpty();
        channel.finishAndReleaseAll();
    }

    @Test
    void missingPath_isServedAsRoot_overH3() {
        var channel = h3Channel();
        var headers = new DefaultHttp3Headers();

        headers.method("GET");
        channel.writeInbound(new DefaultHttp3HeadersFrame(headers));
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
        channel.finishAndReleaseAll();

        assertThat(overH3).hasSize(1);
        assertThat(overH3.getFirst().path()).isEqualTo("/");
    }

    private EmbeddedChannel h3Channel() {
        return new EmbeddedChannel(new Http3Server.Http3RequestHandler((request, writer) -> {
                                                                           overH3.add(request);
                                                                           writer.okText("ok");
                                                                       },
                                                                       1024,
                                                                       timeSpan(60).seconds(),
                                                                       BodyRateLimit.bodyRateLimit(0, timeSpan(1).seconds(), timeSpan(1).seconds())));
    }

    private void sendOverH1(String target) throws IOException {
        statusOverH1(target);
    }

    private String statusOverH1(String target) throws IOException {
        return statusCode(rawOverH1("GET " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"));
    }

    /// Writes `requests` to a fresh server in one go and returns everything it sent before it closed the connection.
    private String rawOverH1(String requests) throws IOException {
        var server = HttpServer.httpServer(HttpServerConfig.httpServerConfig("target-parity", 0),
                                           (request, writer) -> {
                                               overH1.add(request);
                                               writer.ok("{}");
                                           })
                               .await(timeSpan(10).seconds())
                               .unwrap();

        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(requests.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        } finally {
            server.stop().await(timeSpan(10).seconds());
        }
    }

    /// The status code of a raw HTTP/1.1 response, or `no response` when the connection closed without one.
    private static String statusCode(String response) {
        return response.length() >= 12
               ? response.substring(9, 12)
               : "no response";
    }

    private void sendOverH3(String target) {
        statusOverH3(target);
    }

    private String statusOverH3(String target) {
        var channel = h3Channel();
        var headers = new DefaultHttp3Headers();

        headers.method("GET");
        headers.path(target);
        channel.writeInbound(new DefaultHttp3HeadersFrame(headers));
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);

        Object first = channel.readOutbound();

        channel.finishAndReleaseAll();
        assertThat(first).isInstanceOf(Http3HeadersFrame.class);

        return ((Http3HeadersFrame) first).headers().status().toString();
    }
}
