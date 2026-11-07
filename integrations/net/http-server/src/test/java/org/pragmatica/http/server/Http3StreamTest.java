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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// A streamed request body ([Disposition.Stream]) at the HTTP/3 request-stream handler, driven in an `EmbeddedChannel`
/// with a [ManualSink]: the HTTP/3 twin of [RequestGateStreamTest]. [Http3StreamWireTest] covers the same over a real
/// QUIC connection.
class Http3StreamTest {
    private final ManualSink sink = new ManualSink();
    private Http3Server.Http3RequestHandler handler;

    private EmbeddedChannel channel(long maxBytes, Option<BodyBounds> bounds) {
        handler = new Http3Server.Http3RequestHandler((_, writer) -> writer.okText("aggregated"),
                                                      _ -> new Disposition.Stream(maxBytes, bounds, sink),
                                                      timeSpan(60).seconds(),
                                                      BodyRateLimit.bodyRateLimit(0, timeSpan(1).seconds(), timeSpan(1).seconds()));
        var channel = new EmbeddedChannel(handler);

        channel.freezeTime();

        return channel;
    }

    private EmbeddedChannel channel() {
        return channel(1_000_000, Option.none());
    }

    private static DefaultHttp3HeadersFrame headers(String path, long contentLength) {
        var headers = new DefaultHttp3Headers();

        headers.method("POST");
        headers.path(path);
        if (contentLength >= 0) {
            headers.set("content-length", String.valueOf(contentLength));
        }

        return new DefaultHttp3HeadersFrame(headers);
    }

    private static ByteBuf bytes(int size) {
        return Unpooled.buffer(size).writeZero(size);
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

    private static boolean reading(EmbeddedChannel channel) {
        return channel.config().isAutoRead();
    }

    @Test
    void frames_areAcceptedOneAtATimeInOrder_readingIsOffUntilDrained_andCompleteWaitsForTheLastAccept() {
        var channel = channel();
        var first = bytes(1000);
        var second = bytes(700);

        channel.writeInbound(headers("/up", -1));
        channel.writeInbound(new DefaultHttp3DataFrame(first));
        channel.writeInbound(new DefaultHttp3DataFrame(second));
        var trailers = new DefaultHttp3HeadersFrame();

        trailers.headers().add("x-checksum", "abc");
        channel.writeInbound(trailers);
        endInput(channel);

        assertThat(sink.chunkSizes()).containsExactly(1000);
        assertThat(reading(channel)).isFalse();
        assertThat(sink.terminals()).as("the input ended, but a chunk is still being accepted").isEmpty();

        sink.succeedNext();
        channel.runPendingTasks();

        assertThat(first.refCnt()).isZero();
        assertThat(sink.chunkSizes()).containsExactly(1000, 700);
        assertThat(sink.terminals()).isEmpty();

        sink.succeedNext();
        channel.runPendingTasks();

        assertThat(second.refCnt()).isZero();
        assertThat(sink.terminals()).containsExactly("complete");
        assertThat(sink.completed().map(request -> request.trailers().get("x-checksum").or("")).or("")).isEqualTo("abc");
        assertThat(sink.completed().map(request -> request.path()).or("")).isEqualTo("/up");
        assertThat(sink.completed().map(request -> request.body().length).or(-1)).isZero();

        sink.writer().onPresent(writer -> writer.okText("done"));

        assertThat(status(channel)).isEqualTo("200");
        assertThat(sink.maxPending()).isEqualTo(1);
        assertThat(sink.violations()).isEmpty();
        assertThat(handler.holdsBodyBuffer()).isFalse();
        channel.close();
        assertThat(sink.terminals()).as("the teardown after completion adds no abort").containsExactly("complete");
        channel.finishAndReleaseAll();
    }

    @Test
    void readingResumes_onceTheWaitingFramesAreAccepted() {
        var channel = channel();

        channel.writeInbound(headers("/up", -1));
        channel.writeInbound(new DefaultHttp3DataFrame(bytes(10)));
        assertThat(reading(channel)).isFalse();
        sink.succeedNext();
        channel.runPendingTasks();

        assertThat(reading(channel)).isTrue();
        channel.finishAndReleaseAll();
    }

    /// The stream is torn down while an accept is outstanding: what waits is released at once, the outstanding buffer
    /// when its accept resolves, and the abort comes after that.
    @Test
    void streamTornDown_whileAnAcceptIsOutstanding_abortsDisconnectedOnceAfterItResolves_andReleasesEverything() {
        var channel = channel();
        var first = bytes(1000);
        var second = bytes(1000);

        channel.writeInbound(headers("/up", 5000));
        channel.writeInbound(new DefaultHttp3DataFrame(first));
        channel.writeInbound(new DefaultHttp3DataFrame(second));
        channel.close();

        assertThat(second.refCnt()).isZero();
        assertThat(first.refCnt()).isEqualTo(1);
        assertThat(sink.terminals()).isEmpty();
        assertThat(handler.holdsBodyBuffer()).isTrue();

        sink.succeedNext();
        channel.runPendingTasks();

        assertThat(first.refCnt()).isZero();
        assertThat(sink.terminals()).containsExactly("abort:DISCONNECTED");
        assertThat(sink.violations()).isEmpty();
        assertThat(handler.holdsBodyBuffer()).isFalse();
    }

    @Test
    void acceptFailure_handsTheSinkItsResponse_andReleasesEverything() {
        var channel = channel();
        var first = bytes(1000);
        var second = bytes(1000);
        var after = bytes(1000);

        channel.writeInbound(headers("/up", 5000));
        channel.writeInbound(new DefaultHttp3DataFrame(first));
        channel.writeInbound(new DefaultHttp3DataFrame(second));
        sink.failNext();
        channel.runPendingTasks();

        assertThat(sink.terminals()).containsExactly("fail");
        assertThat(first.refCnt()).isZero();
        assertThat(second.refCnt()).isZero();

        sink.writer().onPresent(writer -> writer.error(HttpStatus.SERVICE_UNAVAILABLE, "no"));

        assertThat(status(channel)).isEqualTo("503");
        channel.writeInbound(new DefaultHttp3DataFrame(after));
        assertThat(after.refCnt()).as("what arrives after the failure is dropped").isZero();
        endInput(channel);
        channel.close();
        assertThat(sink.terminals()).as("neither the input's end nor the close adds a terminal call").containsExactly("fail");
        assertThat(handler.holdsBodyBuffer()).isFalse();
    }

    @Test
    void frameCrossingTheLimit_whileAnAcceptIsOutstanding_isAnswered413_andAbortsTooLargeAfterIt() {
        var channel = channel(1500, Option.none());
        var first = bytes(1000);
        var crossing = bytes(1000);

        channel.writeInbound(headers("/up", -1));
        channel.writeInbound(new DefaultHttp3DataFrame(first));
        channel.writeInbound(new DefaultHttp3DataFrame(crossing));

        assertThat(status(channel)).isEqualTo("413");
        assertThat(crossing.refCnt()).isZero();
        assertThat(sink.terminals()).isEmpty();

        sink.succeedNext();
        channel.runPendingTasks();

        assertThat(first.refCnt()).isZero();
        assertThat(sink.terminals()).containsExactly("abort:TOO_LARGE");
        assertThat(sink.violations()).isEmpty();
        channel.finishAndReleaseAll();
    }

    @Test
    void declaredLengthOverTheLimit_isAnswered413OnTheHeaders_andAbortsTooLarge() {
        var channel = channel(1000, Option.none());

        channel.writeInbound(headers("/up", 1001));

        assertThat(status(channel)).isEqualTo("413");
        assertThat(sink.terminals()).containsExactly("abort:TOO_LARGE");
        channel.finishAndReleaseAll();
    }

    @Test
    void idleBound_answers408_andAbortsTimedOut() {
        var channel = channel(1_000_000,
                              Option.some(BodyBounds.bodyBounds(timeSpan(30).seconds(),
                                                                BodyRateLimit.bodyRateLimit(0, timeSpan(1).seconds(), timeSpan(1).seconds()),
                                                                timeSpan(0).seconds())));

        channel.writeInbound(headers("/up", 5000));
        channel.writeInbound(new DefaultHttp3DataFrame(bytes(10)));
        sink.succeedNext();
        channel.runPendingTasks();
        channel.advanceTimeBy(31, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();

        assertThat(status(channel)).isEqualTo("408");
        assertThat(sink.terminals()).containsExactly("abort:TIMED_OUT");
        channel.finishAndReleaseAll();
    }

    @Test
    void undecodableTarget_isAnswered400_andAbortsMalformed() {
        var channel = channel();

        channel.writeInbound(headers("/bad%zz", 10));

        assertThat(status(channel)).isEqualTo("400");
        assertThat(sink.terminals()).containsExactly("abort:MALFORMED");
        channel.finishAndReleaseAll();
    }

    /// A framing error (raised by Netty's codec into `exceptionCaught`) mid-body: the stream is closed and the sink aborted
    /// MALFORMED, after its outstanding accept.
    @Test
    void framingErrorMidBody_abortsMalformed_afterTheOutstandingAccept() {
        var channel = channel();
        var first = bytes(100);

        channel.writeInbound(headers("/up", -1));
        channel.writeInbound(new DefaultHttp3DataFrame(first));
        channel.pipeline().fireExceptionCaught(new IllegalStateException("frame error"));

        assertThat(sink.terminals()).isEmpty();
        sink.succeedNext();
        channel.runPendingTasks();

        assertThat(first.refCnt()).isZero();
        assertThat(sink.terminals()).containsExactly("abort:MALFORMED");
        assertThat(sink.violations()).isEmpty();
        ReferenceCountUtil.release(channel.readOutbound());
    }
}
