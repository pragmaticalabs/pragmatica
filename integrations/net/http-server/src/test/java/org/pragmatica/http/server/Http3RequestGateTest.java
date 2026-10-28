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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// The ingest gate at the HTTP/3 request-stream handler, driven in an `EmbeddedChannel` in virtual time (see
/// [Http3RequestGateWireTest] for the same behaviours over a real QUIC connection): classification on the HEADERS
/// frame, the declared length checked there, the size counter on DATA frames, and the idle, minimum-rate and duration
/// bounds, with the permit released exactly once on every path.
class Http3RequestGateTest {
    private final List<HttpRequest> dispatched = new CopyOnWriteArrayList<>();
    private final AtomicInteger classifications = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();

    private EmbeddedChannel channel(long maxBytes, BodyBounds bounds) {
        var classifier = (RequestClassifier) _ -> {
            classifications.incrementAndGet();

            return new Disposition.Aggregate(maxBytes, Option.option(bounds), releases::incrementAndGet);
        };

        return channel(classifier);
    }

    private EmbeddedChannel channel(RequestClassifier classifier) {
        var channel = new EmbeddedChannel(new Http3Server.Http3RequestHandler(this::handle,
                                                                              classifier,
                                                                              timeSpan(60).seconds(),
                                                                              BodyRateLimit.bodyRateLimit(0, timeSpan(1).seconds(), timeSpan(1).seconds())));

        channel.freezeTime();

        return channel;
    }

    private void handle(HttpRequest request, ResponseWriter response) {
        dispatched.add(request);
        response.okText("ok");
    }

    private static BodyBounds uploadBounds() {
        return BodyBounds.bodyBounds(timeSpan(30).seconds(),
                                     BodyRateLimit.bodyRateLimit(1024, timeSpan(30).seconds(), timeSpan(30).seconds()),
                                     timeSpan(5).minutes());
    }

    private static DefaultHttp3HeadersFrame headers(long contentLength) {
        var headers = new DefaultHttp3Headers();

        headers.method("POST");
        headers.path("/up");
        if (contentLength >= 0) {
            headers.set("content-length", String.valueOf(contentLength));
        }

        return new DefaultHttp3HeadersFrame(headers);
    }

    private static DefaultHttp3DataFrame data(int size) {
        return new DefaultHttp3DataFrame(Unpooled.buffer(size).writeZero(size));
    }

    private static void advance(EmbeddedChannel channel, long seconds) {
        channel.advanceTimeBy(seconds, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
    }

    private static void endInput(EmbeddedChannel channel) {
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
    }

    private static String status(EmbeddedChannel channel) {
        Object first = channel.readOutbound();

        assertThat(first).isInstanceOf(Http3HeadersFrame.class);

        return ((Http3HeadersFrame) first).headers().status().toString();
    }

    @Test
    void rejectedRequest_isAnsweredOnTheHeaders_neverDispatched_andItsBodyIsDiscarded() {
        var channel = channel(_ -> {
            classifications.incrementAndGet();

            return Disposition.reject(writer -> writer.error(HttpStatus.UNAUTHORIZED, "no credentials"));
        });

        channel.writeInbound(headers(50_000_000));

        assertThat(status(channel)).as("answered before any DATA frame").isEqualTo("401");
        channel.writeInbound(data(4096));
        endInput(channel);

        assertThat(dispatched).isEmpty();
        assertThat(classifications.get()).as("classified once, on the HEADERS frame").isEqualTo(1);
        assertThat(((Http3Server.Http3RequestHandler) channel.pipeline().first()).holdsBodyBuffer()).isFalse();
        channel.finishAndReleaseAll();
    }

    @Test
    void declaredLengthOverTheLimit_isAnswered413OnTheHeaders_beforeAnyBodyFrame() {
        var channel = channel(1000, uploadBounds());

        channel.writeInbound(headers(2000));

        assertThat(status(channel)).isEqualTo("413");
        assertThat(releases.get()).isEqualTo(1);
        endInput(channel);
        assertThat(dispatched).isEmpty();
        channel.finishAndReleaseAll();
    }

    @Test
    void declaredLengthExactlyAtTheLimit_isDispatchedWhole() {
        var channel = channel(1000, uploadBounds());

        channel.writeInbound(headers(1000));
        channel.writeInbound(data(1000));
        endInput(channel);

        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.getFirst().body()).hasSize(1000);
        assertThat(status(channel)).isEqualTo("200");
        assertThat(releases.get()).isEqualTo(1);
        channel.finishAndReleaseAll();
    }

    @Test
    void undeclaredBodyCrossingTheLimit_isAnswered413AtTheCrossingFrame() {
        var channel = channel(1000, uploadBounds());

        channel.writeInbound(headers(-1));
        channel.writeInbound(data(600));
        channel.writeInbound(data(401));
        endInput(channel);

        assertThat(status(channel)).isEqualTo("413");
        assertThat(dispatched).isEmpty();
        assertThat(releases.get()).isEqualTo(1);
        channel.finishAndReleaseAll();
    }

    @Test
    void idleBound_answers408() {
        var channel = channel(1_000_000, uploadBounds());

        channel.writeInbound(headers(1000));
        channel.writeInbound(data(10));
        advance(channel, 29);
        assertThat(channel.<Object>readOutbound()).as("not yet idle for 30 s").isNull();

        advance(channel, 2);

        assertThat(status(channel)).isEqualTo("408");
        assertThat(releases.get()).isEqualTo(1);
        channel.finishAndReleaseAll();
    }

    @Test
    void minimumRate_answers408_afterTheFirstWindow() {
        var channel = channel(1_000_000, uploadBounds());

        channel.writeInbound(headers(1000));
        for (int second = 0; second < 31 && channel.isActive(); second++) {
            channel.writeInbound(data(1));
            advance(channel, 1);
        }

        assertThat(status(channel)).isEqualTo("408");
        assertThat(releases.get()).isEqualTo(1);
        channel.finishAndReleaseAll();
    }

    @Test
    void maxDuration_answers408_atASteadyRateJustAboveTheMinimum() {
        var channel = channel(100_000_000, uploadBounds());

        channel.writeInbound(headers(90_000_000));
        for (int second = 0; second < 299; second++) {
            channel.writeInbound(data(2048));
            advance(channel, 1);
        }

        assertThat(channel.<Object>readOutbound()).as("idle and rate are satisfied, and 5 min have not passed").isNull();

        channel.writeInbound(data(2048));
        advance(channel, 2);

        assertThat(status(channel)).isEqualTo("408");
        assertThat(releases.get()).isEqualTo(1);
        channel.finishAndReleaseAll();
    }

    @Test
    void completeBody_releasesThePermitOnce_andIsNotTimedOutAfterwards() {
        var channel = channel(1_000_000, uploadBounds());

        channel.writeInbound(headers(5));
        channel.writeInbound(data(5));
        endInput(channel);
        advance(channel, 600);

        assertThat(releases.get()).isEqualTo(1);
        assertThat(dispatched).hasSize(1);
        channel.finishAndReleaseAll();
    }

    @Test
    void streamTornDownMidBody_releasesThePermit() {
        var channel = channel(1_000_000, uploadBounds());

        channel.writeInbound(headers(1000));
        channel.writeInbound(data(10));
        channel.close();

        assertThat(releases.get()).isEqualTo(1);
        channel.finishAndReleaseAll();
    }
}
