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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// The ingest gate's bounds in virtual time, on HTTP/1.1: deterministic where [RequestGateWireTest] runs on the wall
/// clock. Each bound answers 408 (upload bounds) or 413 (size) and never forwards what follows; the permit a classification
/// took is released exactly once on every path: body complete, a bound broken, a refusal, the connection lost.
class RequestGateVirtualTimeTest {
    private final AtomicInteger classifications = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();

    private EmbeddedChannel channel(long maxBytes, BodyBounds bounds) {
        var config = HttpServerConfig.httpServerConfig("gate", 0).withClassifier(head -> {
            classifications.incrementAndGet();

            return new Disposition.Aggregate(maxBytes, Option.option(bounds), releases::incrementAndGet);
        });
        var channel = new EmbeddedChannel(new RequestGate(config));

        channel.freezeTime();

        return channel;
    }

    private static BodyBounds uploadBounds() {
        return BodyBounds.bodyBounds(timeSpan(30).seconds(),
                                     BodyRateLimit.bodyRateLimit(1024, timeSpan(30).seconds(), timeSpan(30).seconds()),
                                     timeSpan(5).minutes());
    }

    private static DefaultHttpRequest head(String... extraHeaders) {
        var head = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/up");

        for (int index = 0; index < extraHeaders.length; index += 2) {
            head.headers().set(extraHeaders[index], extraHeaders[index + 1]);
        }

        return head;
    }

    private static DefaultHttpContent chunk(int size) {
        return new DefaultHttpContent(Unpooled.buffer(size).writeZero(size));
    }

    private static void advance(EmbeddedChannel channel, long seconds) {
        channel.advanceTimeBy(seconds, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
    }

    private static int forwardedBytes(EmbeddedChannel channel) {
        var bytes = 0;
        Object message;

        while ((message = channel.readInbound()) != null) {
            if (message instanceof DefaultHttpContent content) {
                bytes += content.content().readableBytes();
            }
            ReferenceCountUtil.release(message);
        }

        return bytes;
    }

    private static FullHttpResponse answer(EmbeddedChannel channel) {
        return channel.readOutbound();
    }

    @Test
    void idleBound_answers408_releasesThePermit_andForwardsNothingMore() {
        var channel = channel(1_000_000, uploadBounds());

        channel.writeInbound(head(HttpHeaderNames.CONTENT_LENGTH.toString(), "1000"));
        channel.writeInbound(chunk(10));
        advance(channel, 29);

        assertThat(channel.<Object>readOutbound()).as("not yet idle for 30 s").isNull();

        advance(channel, 2);
        var response = answer(channel);

        assertThat(response.status().code()).isEqualTo(408);
        assertThat(releases.get()).isEqualTo(1);
        channel.writeInbound(chunk(10));
        assertThat(forwardedBytes(channel)).as("only the 10 bytes before the refusal were forwarded, none after").isEqualTo(10);
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void minimumRate_answers408_afterTheFirstWindow() {
        var channel = channel(1_000_000, uploadBounds());

        channel.writeInbound(head(HttpHeaderNames.CONTENT_LENGTH.toString(), "1000"));
        for (int second = 0; second < 29; second++) {
            channel.writeInbound(chunk(1));
            advance(channel, 1);
        }

        assertThat(channel.<Object>readOutbound()).as("inside the 30 s grace").isNull();

        for (int second = 0; second < 3 && channel.isActive(); second++) {
            channel.writeInbound(chunk(1));
            advance(channel, 1);
        }

        var response = answer(channel);

        assertThat(response.status().code()).as("1 B/s over the first 30 s is below 1 KiB/s").isEqualTo(408);
        assertThat(releases.get()).isEqualTo(1);
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void maxDuration_answers408_atASteadyRateJustAboveTheMinimum() {
        var channel = channel(100_000_000, uploadBounds());

        channel.writeInbound(head(HttpHeaderNames.CONTENT_LENGTH.toString(), "90000000"));
        for (int second = 0; second < 299; second++) {
            channel.writeInbound(chunk(2048));
            advance(channel, 1);
        }

        assertThat(channel.<Object>readOutbound()).as("idle and rate are satisfied, and 5 min have not passed").isNull();

        channel.writeInbound(chunk(2048));
        advance(channel, 2);
        var response = answer(channel);

        assertThat(response.status().code()).isEqualTo(408);
        assertThat(releases.get()).isEqualTo(1);
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void completeBody_releasesThePermitOnce_andIsNeverTimedOutAfterwards() {
        var channel = channel(1_000_000, uploadBounds());

        channel.writeInbound(head(HttpHeaderNames.CONTENT_LENGTH.toString(), "5"));
        channel.writeInbound(chunk(5));
        channel.writeInbound(new DefaultLastHttpContent());
        advance(channel, 600);

        assertThat(releases.get()).isEqualTo(1);
        assertThat(channel.<Object>readOutbound()).as("a complete body is not timed out while its handler runs").isNull();
        assertThat(channel.isActive()).isTrue();
        channel.finishAndReleaseAll();
    }

    @Test
    void declaredLengthOverTheLimit_isAnswered413OnTheHead_andNothingIsForwarded() {
        var channel = channel(1000, uploadBounds());

        channel.writeInbound(head(HttpHeaderNames.CONTENT_LENGTH.toString(), "2000", HttpHeaderNames.EXPECT.toString(), "100-continue"));
        var response = answer(channel);

        assertThat(response.status().code()).isEqualTo(413);
        assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualToIgnoringCase("close");
        assertThat(channel.<Object>readOutbound()).as("no 100 Continue, no second response").isNull();
        channel.writeInbound(chunk(2000));
        assertThat(channel.<Object>readInbound()).as("the refused request never reaches the aggregator").isNull();
        assertThat(releases.get()).isEqualTo(1);
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void chunkedBodyCrossingTheLimit_isAnswered413AtTheCrossingChunk() {
        var channel = channel(1000, uploadBounds());

        channel.writeInbound(head(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked"));
        channel.writeInbound(chunk(600));
        assertThat(channel.<Object>readOutbound()).isNull();
        channel.writeInbound(chunk(600));
        var response = answer(channel);

        assertThat(response.status().code()).isEqualTo(413);
        assertThat(forwardedBytes(channel)).as("the chunk that crossed the limit was not forwarded").isEqualTo(600);
        assertThat(releases.get()).isEqualTo(1);
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void rejectedRequest_isAnsweredOnce_neverForwarded_andItsBodyIsDiscarded() {
        var config = HttpServerConfig.httpServerConfig("gate", 0).withClassifier(head -> {
            classifications.incrementAndGet();

            return Disposition.reject(writer -> writer.error(HttpStatus.UNAUTHORIZED, "no credentials"));
        });
        var channel = new EmbeddedChannel(new RequestGate(config));

        channel.writeInbound(head(HttpHeaderNames.CONTENT_LENGTH.toString(), "50000000", HttpHeaderNames.EXPECT.toString(), "100-continue"));
        channel.writeInbound(chunk(4096));
        channel.writeInbound(new DefaultLastHttpContent());
        var response = answer(channel);

        assertThat(response.status().code()).isEqualTo(401);
        assertThat(channel.<Object>readOutbound()).isNull();
        assertThat(channel.<Object>readInbound()).as("nothing reached the aggregator").isNull();
        assertThat(classifications.get()).as("classified once, on the head").isEqualTo(1);
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void connectionLostMidBody_releasesThePermit() {
        var channel = channel(1_000_000, uploadBounds());

        channel.writeInbound(head(HttpHeaderNames.CONTENT_LENGTH.toString(), "1000"));
        channel.writeInbound(chunk(10));
        channel.close();

        assertThat(releases.get()).isEqualTo(1);
        channel.finishAndReleaseAll();
    }

    /// With no classifier configured every request is aggregated up to `maxContentLength`, as before.
    @Test
    void noClassifier_boundsEveryRequestByTheListenerLimit() {
        var channel = new EmbeddedChannel(new RequestGate(HttpServerConfig.httpServerConfig("gate", 0)
                                                                          .withMaxContentLength(1000)));

        channel.writeInbound(head(HttpHeaderNames.CONTENT_LENGTH.toString(), "1001"));
        var response = answer(channel);

        assertThat(response.status().code()).isEqualTo(413);
        response.release();
        channel.finishAndReleaseAll();
    }
}
