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
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// I1/I16/I3 at the server's HTTP/3 request-stream handler, driven in an `EmbeddedChannel` (the module has no QUIC
/// client): an over-limit body is answered 413 and never dispatched, trailers never replace the request headers,
/// and the body buffer is released on every exit.
class Http3RequestHandlerTest {
    private static final int LIMIT = 1024;

    private final List<HttpRequest> dispatched = new CopyOnWriteArrayList<>();

    private EmbeddedChannel channel() {
        return channel(timeSpan(60).seconds());
    }

    private EmbeddedChannel channel(TimeSpan readTimeout) {
        return channel(LIMIT, readTimeout, BodyRateLimit.bodyRateLimit(0, timeSpan(1).seconds(), timeSpan(1).seconds()));
    }

    private EmbeddedChannel channel(int limit, TimeSpan readTimeout, BodyRateLimit rate) {
        return new EmbeddedChannel(new Http3Server.Http3RequestHandler((request, response) -> {
            dispatched.add(request);
            response.okText("ok");
        }, limit, readTimeout, rate));
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

    /// I3: a stream that keeps trickling (QUIC's 5 s idle never fires) is bounded by the request deadline.
    @Test
    void bodyIdleLongerThanReadTimeout_isAnswered408_andNeverDispatched() throws InterruptedException {
        var channel = channel(timeSpan(100).millis());
        var handler = (Http3Server.Http3RequestHandler) channel.pipeline().first();

        channel.writeInbound(requestHeaders());
        channel.writeInbound(data(10));
        Thread.sleep(300);
        channel.runScheduledPendingTasks();
        channel.writeInbound(data(10));
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);

        assertThat(dispatched).as("a request past its body deadline must not be dispatched").isEmpty();
        assertThat(responseStatus(channel)).isEqualTo("408");
        assertThat(handler.holdsBodyBuffer()).isFalse();
        channel.finishAndReleaseAll();
    }

    @Test
    void bodyCompleteBeforeGoingIdle_isDispatched() throws InterruptedException {
        var channel = channel(timeSpan(300).millis());

        channel.writeInbound(requestHeaders());
        channel.writeInbound(data(10));
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
        Thread.sleep(400);
        channel.runScheduledPendingTasks();

        assertThat(dispatched).hasSize(1);
        assertThat(responseStatus(channel)).isEqualTo("200");
        Object rest;
        while ((rest = channel.readOutbound()) != null) {
            assertThat(rest).as("no late 408 after the deadline was cancelled").isNotInstanceOf(Http3HeadersFrame.class);
            ReferenceCountUtil.release(rest);
        }
        channel.finishAndReleaseAll();
    }

    /// F6: a 1 B/s trickle is never idle, but it is far below the minimum rate: 408, stream input reset, no dispatch.
    /// Virtual time, default limits (1 KiB/s after 10 s, 60 s idle).
    @Test
    void trickleOfOneBytePerSecond_isAnswered408_andNeverDispatched() {
        var channel = channel(LIMIT, timeSpan(60).seconds(), BodyRateLimit.DEFAULT);

        channel.freezeTime();
        channel.writeInbound(requestHeaders());
        for (int second = 0; second < 30 && channel.outboundMessages().isEmpty(); second++) {
            channel.writeInbound(data(1));
            channel.advanceTimeBy(1, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
        }
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);

        assertThat(dispatched).isEmpty();
        assertThat(responseStatus(channel)).isEqualTo("408");
        channel.finishAndReleaseAll();
    }

    /// F6: a slow but legitimate upload above the minimum rate completes however long it takes: 5 MiB at 50 KiB/s,
    /// about 100 s of virtual time, under the default limits.
    @Test
    void uploadOfFiveMebibytesAtFiftyKibibytesPerSecond_isDispatchedWhole() {
        var fiveMiB = 5 * 1024 * 1024;
        var channel = channel(fiveMiB, timeSpan(60).seconds(), BodyRateLimit.DEFAULT);
        var frame = 5 * 1024;

        channel.freezeTime();
        channel.writeInbound(requestHeaders());
        for (int sent = 0; sent < fiveMiB; sent += frame) {
            channel.writeInbound(data(frame));
            channel.advanceTimeBy(100, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
        }
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);

        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.getFirst().body()).hasSize(fiveMiB);
        assertThat(responseStatus(channel)).isEqualTo("200");
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
