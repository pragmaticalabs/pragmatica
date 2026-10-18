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

import org.pragmatica.http.HttpRequest;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// I1/I16 at the server's HTTP/3 request-stream handler, driven in an `EmbeddedChannel` (the module has no QUIC
/// client): an over-limit body is answered 413 and never dispatched, trailers never replace the request headers,
/// and the body buffer is released on every exit.
class Http3RequestHandlerTest {
    private static final int LIMIT = 1024;

    private final List<HttpRequest> dispatched = new CopyOnWriteArrayList<>();

    private EmbeddedChannel channel() {
        return new EmbeddedChannel(new Http3Server.Http3RequestHandler((request, response) -> {
            dispatched.add(request);
            response.okText("ok");
        }, LIMIT));
    }

    private static DefaultHttp3HeadersFrame requestHeaders() {
        var headers = new DefaultHttp3Headers();

        headers.method("POST");
        headers.path("/upload");
        headers.set("x-original", "request");

        return new DefaultHttp3HeadersFrame(headers);
    }

    private static DefaultHttp3DataFrame data(int size) {
        return new DefaultHttp3DataFrame(Unpooled.buffer(size).writeZero(size));
    }

    private static String responseStatus(EmbeddedChannel channel) {
        Object first = channel.readOutbound();

        assertThat(first).isInstanceOf(Http3HeadersFrame.class);

        return ((Http3HeadersFrame) first).headers().status().toString();
    }

    @Test
    void overLimitBody_isAnswered413_andNeverDispatched() {
        var channel = channel();

        channel.writeInbound(requestHeaders());
        for (int i = 0; i < 4; i++) {
            channel.writeInbound(data(1000));
        }
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);

        assertThat(dispatched).as("a body over the limit must never reach the handler, truncated or not").isEmpty();
        assertThat(responseStatus(channel)).isEqualTo("413");
        assertThat(((Http3Server.Http3RequestHandler) channel.pipeline().first()).holdsBodyBuffer()).isFalse();
        channel.finishAndReleaseAll();
    }

    @Test
    void bodyExactlyAtLimit_isDispatchedWhole() {
        var channel = channel();

        channel.writeInbound(requestHeaders());
        channel.writeInbound(data(1000));
        channel.writeInbound(data(24));
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);

        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.getFirst().body()).hasSize(LIMIT);
        assertThat(responseStatus(channel)).isEqualTo("200");
        channel.finishAndReleaseAll();
    }

    @Test
    void trailers_doNotReplaceRequestHeaders() {
        var channel = channel();
        var trailers = new DefaultHttp3Headers();

        trailers.set("x-trailer", "checksum");
        channel.writeInbound(requestHeaders());
        channel.writeInbound(data(10));
        channel.writeInbound(new DefaultHttp3HeadersFrame(trailers));
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);

        assertThat(dispatched).hasSize(1);
        var request = dispatched.getFirst();
        assertThat(request.path()).isEqualTo("/upload");
        assertThat(request.headers().get("x-original")).isEqualTo(Option.some("request"));
        assertThat(request.body()).hasSize(10);
        channel.finishAndReleaseAll();
    }

    @Test
    void streamClosedWithoutHeaders_releasesBufferedBody() {
        var channel = channel();
        var handler = (Http3Server.Http3RequestHandler) channel.pipeline().first();

        channel.writeInbound(data(10));
        assertThat(handler.holdsBodyBuffer()).isTrue();
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);

        assertThat(dispatched).isEmpty();
        assertThat(handler.holdsBodyBuffer()).isFalse();
        channel.finishAndReleaseAll();
    }

    @Test
    void streamTornDownBeforeInputClosed_releasesBufferedBody() {
        var channel = channel();
        var handler = (Http3Server.Http3RequestHandler) channel.pipeline().first();

        channel.writeInbound(requestHeaders());
        channel.writeInbound(data(10));
        channel.close();

        assertThat(dispatched).isEmpty();
        assertThat(handler.holdsBodyBuffer()).isFalse();
    }
}
