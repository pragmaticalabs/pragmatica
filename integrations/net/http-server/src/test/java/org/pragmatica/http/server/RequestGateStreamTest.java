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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// A streamed request body ([Disposition.Stream]) at the HTTP/1.1 ingest gate, driven in an `EmbeddedChannel` with a
/// [ManualSink] whose accepts the test resolves: the order and pacing of accepts, when reading is off, that every
/// received buffer is released (its reference count reaches zero) on every exit, and that the sink gets exactly one
/// terminal call, never while an accept is outstanding. [RequestGateStreamWireTest] covers the same over a real socket.
class RequestGateStreamTest {
    private final ManualSink sink = new ManualSink();
    private final List<Object> passedOn = new ArrayList<>();
    private RequestGate gate;

    private EmbeddedChannel channel(long maxBytes, Option<BodyBounds> bounds) {
        var config = HttpServerConfig.httpServerConfig("gate", 0).withClassifier(head -> "/agg".equals(head.path())
                                                                                         ? Disposition.aggregate(1000)
                                                                                         : new Disposition.Stream(maxBytes, bounds, sink));
        gate = new RequestGate(config);
        var channel = new EmbeddedChannel(gate, new Recorder());

        channel.freezeTime();

        return channel;
    }

    private EmbeddedChannel channel() {
        return channel(1_000_000, Option.none());
    }

    /// Records what the gate passed on: messages and user events.
    private final class Recorder extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            passedOn.add(msg);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            passedOn.add(evt);
        }
    }

    private static HttpRequest head(String uri, String... extraHeaders) {
        var head = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri);

        for (int index = 0; index < extraHeaders.length; index += 2) {
            head.headers().add(extraHeaders[index], extraHeaders[index + 1]);
        }

        return head;
    }

    private static ByteBuf bytes(int size) {
        return Unpooled.buffer(size).writeZero(size);
    }

    /// The gate of the channel made last; kept, since a closed channel's pipeline no longer holds it.
    private RequestGate gate(EmbeddedChannel channel) {
        return gate;
    }

    private static boolean reading(EmbeddedChannel channel) {
        return channel.config().isAutoRead();
    }

    private static FullHttpResponse response(EmbeddedChannel channel) {
        return channel.readOutbound();
    }

    @Test
    void chunks_areAcceptedOneAtATimeInOrder_readingIsOffUntilDrained_andEachBufferIsReleasedWhenItsAcceptResolves() {
        var channel = channel();
        var first = bytes(1000);
        var second = bytes(700);
        var last = bytes(300);

        channel.writeInbound(head("/up", "Transfer-Encoding", "chunked"));
        channel.writeInbound(new DefaultHttpContent(first));
        channel.writeInbound(new DefaultHttpContent(second));

        assertThat(sink.chunkSizes()).as("one accept outstanding, for the first chunk").containsExactly(1000);
        assertThat(reading(channel)).as("reading is off while a chunk is pending").isFalse();
        assertThat(first.refCnt()).isEqualTo(1);
        assertThat(second.refCnt()).isEqualTo(1);

        sink.succeedNext();
        channel.runPendingTasks();

        assertThat(first.refCnt()).as("released once its accept resolved").isZero();
        assertThat(sink.chunkSizes()).containsExactly(1000, 700);
        assertThat(reading(channel)).isFalse();

        sink.succeedNext();
        channel.runPendingTasks();

        assertThat(second.refCnt()).isZero();
        assertThat(reading(channel)).as("reading is back on once drained").isTrue();

        var trailing = new DefaultLastHttpContent(last);

        trailing.trailingHeaders().add("X-Checksum", "abc");
        channel.writeInbound(trailing);

        assertThat(sink.terminals()).as("no completion before the last chunk is accepted").isEmpty();
        sink.succeedNext();
        channel.runPendingTasks();

        assertThat(last.refCnt()).isZero();
        assertThat(sink.terminals()).containsExactly("complete");
        assertThat(sink.completed().map(request -> request.trailers().get("x-checksum").or("")).or("")).isEqualTo("abc");
        assertThat(sink.completed().map(request -> request.body().length).or(-1)).isZero();
        assertThat(reading(channel)).as("reading stays off while the sink answers").isFalse();

        sink.writer().onPresent(writer -> writer.okText("done"));
        var answer = response(channel);

        assertThat(answer.status().code()).isEqualTo(200);
        assertThat(reading(channel)).as("reading is back on once the answer is written").isTrue();
        assertThat(sink.maxPending()).isEqualTo(1);
        assertThat(sink.violations()).isEmpty();
        assertThat(gate(channel).holdsBodyBuffer()).isFalse();
        assertThat(passedOn).as("nothing of a streamed request reaches the aggregator").isEmpty();
        channel.close();
        assertThat(sink.terminals()).as("the close after completion adds no abort").containsExactly("complete");
        answer.release();
        channel.finishAndReleaseAll();
    }

    /// A composite buffer is handed over one component at a time, without copying, and released when the last of its
    /// views was accepted.
    @Test
    void compositeChunk_isAcceptedOneViewPerComponent_andReleasedAfterTheLast() {
        var channel = channel();
        var composite = Unpooled.compositeBuffer().addComponents(true, bytes(300), bytes(200));

        channel.writeInbound(head("/up", "Content-Length", "5000"));
        channel.writeInbound(new DefaultHttpContent(composite));
        sink.succeedNext();
        channel.runPendingTasks();

        assertThat(sink.chunkSizes()).containsExactly(300, 200);
        assertThat(composite.refCnt()).as("still held for the second view").isEqualTo(1);

        sink.succeedNext();
        channel.runPendingTasks();

        assertThat(composite.refCnt()).isZero();
        assertThat(reading(channel)).isTrue();
        channel.finishAndReleaseAll();
    }

    /// A body with no bytes completes at once; its empty buffer is released, and reading is off while the sink answers.
    @Test
    void emptyBody_completesAtOnce_releasesItsBuffer_andReadingIsOffWhileTheSinkAnswers() {
        var channel = channel();
        var empty = Unpooled.buffer(0);

        channel.writeInbound(head("/up"));
        channel.writeInbound(new DefaultLastHttpContent(empty));

        assertThat(sink.terminals()).containsExactly("complete");
        assertThat(sink.chunkSizes()).as("an empty chunk is not offered").isEmpty();
        assertThat(empty.refCnt()).isZero();
        assertThat(reading(channel)).as("reading is off while the sink answers").isFalse();
        channel.finishAndReleaseAll();
    }

    /// The body's bounds end with the body: a sink that takes its time answering is not cut by the idle bound.
    @Test
    void bodyBounds_endWithTheBody_soASlowAnswerIsNotRefused() {
        var channel = channel(1_000_000,
                              Option.some(BodyBounds.bodyBounds(timeSpan(30).seconds(),
                                                                BodyRateLimit.bodyRateLimit(0, timeSpan(1).seconds(), timeSpan(1).seconds()),
                                                                timeSpan(0).seconds())));

        channel.writeInbound(head("/up", "Content-Length", "10"));
        channel.writeInbound(new DefaultLastHttpContent(bytes(10)));
        sink.succeedNext();
        channel.runPendingTasks();
        channel.advanceTimeBy(60, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();

        assertThat(channel.<Object>readOutbound()).as("no 408 while the sink answers").isNull();
        assertThat(sink.terminals()).containsExactly("complete");
        channel.finishAndReleaseAll();
    }

    /// With reading off, nothing else ends a connection whose sink never answers: the response timeout does, with 504,
    /// for the response `complete` hands over and for the one `fail` hands over.
    @Test
    void sinkThatNeverAnswers_isAnswered504AfterTheResponseTimeout_onCompleteAndOnFail() {
        var completed = channel();

        ResponseSequence.install(completed, timeSpan(5).seconds());
        completed.writeInbound(head("/up", "Content-Length", "10"));
        completed.writeInbound(new DefaultLastHttpContent(bytes(10)));
        sink.succeedNext();
        completed.runPendingTasks();
        completed.advanceTimeBy(6, TimeUnit.SECONDS);
        completed.runScheduledPendingTasks();
        var timedOut = response(completed);

        assertThat(timedOut.status().code()).isEqualTo(504);
        timedOut.release();
        completed.finishAndReleaseAll();

        var failed = channel();

        ResponseSequence.install(failed, timeSpan(5).seconds());
        failed.writeInbound(head("/up", "Content-Length", "10"));
        failed.writeInbound(new DefaultLastHttpContent(bytes(10)));
        sink.failNext();
        failed.runPendingTasks();
        failed.advanceTimeBy(6, TimeUnit.SECONDS);
        failed.runScheduledPendingTasks();
        var failTimedOut = response(failed);

        assertThat(failTimedOut.status().code()).isEqualTo(504);
        assertThat(sink.terminals()).containsExactly("complete", "fail");
        failTimedOut.release();
        failed.finishAndReleaseAll();
    }

    /// A pipelined streamed request replayed with a chunk of its body: reading stays off until that chunk is accepted.
    @Test
    void pipelinedStreamedRequest_replayedWithAChunk_keepsReadingOffUntilItIsAccepted() {
        var channel = channel();

        channel.writeInbound(head("/up", "Content-Length", "5"));
        channel.writeInbound(new DefaultLastHttpContent(bytes(5)));
        sink.succeedNext();
        channel.runPendingTasks();
        channel.writeInbound(head("/up", "Content-Length", "5000"));
        channel.writeInbound(new DefaultHttpContent(bytes(100)));
        sink.writer().onPresent(writer -> writer.okText("done"));
        ReferenceCountUtil.release(channel.readOutbound());

        assertThat(sink.chunkSizes()).as("the replayed chunk is being accepted").containsExactly(5, 100);
        assertThat(reading(channel)).as("reading stays off while it is").isFalse();

        sink.succeedNext();
        channel.runPendingTasks();

        assertThat(reading(channel)).isTrue();
        channel.finishAndReleaseAll();
    }

    /// Messages held while the sink answers are released when the connection is lost.
    @Test
    void heldMessages_areReleasedWhenTheConnectionIsLost() {
        var channel = channel();
        var nextBody = new DefaultLastHttpContent(bytes(3));

        channel.writeInbound(head("/up", "Content-Length", "5"));
        channel.writeInbound(new DefaultLastHttpContent(bytes(5)));
        sink.succeedNext();
        channel.runPendingTasks();
        channel.writeInbound(head("/agg", "Content-Length", "3"));
        channel.writeInbound(nextBody);
        channel.close();

        assertThat(nextBody.refCnt()).isZero();
        assertThat(gate(channel).holdsBodyBuffer()).isFalse();
        assertThat(sink.terminals()).containsExactly("complete");
    }

    /// The connection is lost while an accept is outstanding: what waits is released at once, the outstanding buffer when
    /// its accept resolves, and the abort comes after that.
    @Test
    void connectionLost_whileAnAcceptIsOutstanding_abortsDisconnectedOnceAfterItResolves_andReleasesEverything() {
        var channel = channel();
        var first = bytes(1000);
        var second = bytes(1000);

        channel.writeInbound(head("/up", "Content-Length", "5000"));
        channel.writeInbound(new DefaultHttpContent(first));
        channel.writeInbound(new DefaultHttpContent(second));
        channel.close();

        assertThat(second.refCnt()).as("the waiting chunk is released at once").isZero();
        assertThat(first.refCnt()).as("the outstanding chunk is still the sink's").isEqualTo(1);
        assertThat(sink.terminals()).as("no terminal call while the accept is outstanding").isEmpty();
        assertThat(gate(channel).holdsBodyBuffer()).isTrue();

        sink.succeedNext();
        channel.runPendingTasks();

        assertThat(first.refCnt()).isZero();
        assertThat(sink.terminals()).containsExactly("abort:DISCONNECTED");
        assertThat(sink.violations()).isEmpty();
        assertThat(gate(channel).holdsBodyBuffer()).isFalse();
        assertThat(sink.chunkSizes()).as("the waiting chunk was never offered").containsExactly(1000);
    }

    @Test
    void acceptFailure_handsTheSinkAClosingResponse_releasesEverything_andReadsNoMore() {
        var channel = channel();
        var first = bytes(1000);
        var second = bytes(1000);
        var after = bytes(1000);

        channel.writeInbound(head("/up", "Content-Length", "5000"));
        channel.writeInbound(new DefaultHttpContent(first));
        channel.writeInbound(new DefaultHttpContent(second));
        sink.failNext();
        channel.runPendingTasks();

        assertThat(sink.terminals()).containsExactly("fail");
        assertThat(first.refCnt()).isZero();
        assertThat(second.refCnt()).isZero();
        assertThat(reading(channel)).isFalse();

        sink.writer().onPresent(writer -> writer.error(org.pragmatica.http.HttpStatus.SERVICE_UNAVAILABLE, "no"));
        var answer = response(channel);

        assertThat(answer.status().code()).isEqualTo(503);
        assertThat(answer.headers().get(HttpHeaderNames.CONNECTION)).isEqualToIgnoringCase("close");
        channel.writeInbound(new DefaultHttpContent(after));
        assertThat(after.refCnt()).as("what arrives after the failure is dropped").isZero();
        channel.close();
        assertThat(sink.terminals()).as("the close adds no second terminal call").containsExactly("fail");
        assertThat(gate(channel).holdsBodyBuffer()).isFalse();
        answer.release();
    }

    /// A chunk crossing the limit while an accept is outstanding: 413 at once, and the abort after the accept resolved.
    @Test
    void chunkCrossingTheLimit_whileAnAcceptIsOutstanding_isAnswered413_andAbortsTooLargeAfterIt() {
        var channel = channel(1500, Option.none());
        var first = bytes(1000);
        var crossing = bytes(1000);

        channel.writeInbound(head("/up", "Transfer-Encoding", "chunked"));
        channel.writeInbound(new DefaultHttpContent(first));
        channel.writeInbound(new DefaultHttpContent(crossing));

        var answer = response(channel);

        assertThat(answer.status().code()).isEqualTo(413);
        assertThat(crossing.refCnt()).isZero();
        assertThat(sink.terminals()).isEmpty();

        sink.succeedNext();
        channel.runPendingTasks();

        assertThat(first.refCnt()).isZero();
        assertThat(sink.terminals()).containsExactly("abort:TOO_LARGE");
        assertThat(sink.violations()).isEmpty();
        answer.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void declaredLengthOverTheLimit_isAnswered413OnTheHead_andAbortsTooLarge() {
        var channel = channel(1000, Option.none());

        channel.writeInbound(head("/up", "Content-Length", "1001", "Expect", "100-continue"));
        var answer = response(channel);

        assertThat(answer.status().code()).as("refused, never told to continue").isEqualTo(413);
        assertThat(sink.terminals()).containsExactly("abort:TOO_LARGE");
        answer.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void expectContinue_onAnAdmittedStream_isAnswered100() {
        var channel = channel();

        channel.writeInbound(head("/up", "Content-Length", "10", "Expect", "100-continue"));
        var interim = response(channel);

        assertThat(interim.status().code()).isEqualTo(100);
        assertThat(sink.terminals()).isEmpty();
        interim.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void idleBound_answers408_andAbortsTimedOut() {
        var channel = channel(1_000_000,
                              Option.some(BodyBounds.bodyBounds(timeSpan(30).seconds(),
                                                                BodyRateLimit.bodyRateLimit(0, timeSpan(1).seconds(), timeSpan(1).seconds()),
                                                                timeSpan(0).seconds())));

        channel.writeInbound(head("/up", "Content-Length", "5000"));
        channel.writeInbound(new DefaultHttpContent(bytes(10)));
        sink.succeedNext();
        channel.runPendingTasks();
        channel.advanceTimeBy(31, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        var answer = response(channel);

        assertThat(answer.status().code()).isEqualTo(408);
        assertThat(sink.terminals()).containsExactly("abort:TIMED_OUT");
        answer.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void undecodableChunk_isAnswered400_andAbortsMalformed() {
        var channel = channel();
        var broken = new DefaultLastHttpContent(bytes(10));

        broken.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("bad chunk size")));
        channel.writeInbound(head("/up", "Transfer-Encoding", "chunked"));
        channel.writeInbound(broken);
        var answer = response(channel);

        assertThat(answer.status().code()).isEqualTo(400);
        assertThat(broken.refCnt()).isZero();
        assertThat(sink.terminals()).containsExactly("abort:MALFORMED");
        answer.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void malformedTargetOrSecondHost_isAnswered400_andAbortsMalformed() {
        var channel = channel();

        channel.writeInbound(head("/bad%zz", "Content-Length", "10"));
        var answer = response(channel);

        assertThat(answer.status().code()).isEqualTo(400);
        assertThat(sink.terminals()).containsExactly("abort:MALFORMED");
        answer.release();
        channel.finishAndReleaseAll();

        var hosts = channel();

        hosts.writeInbound(head("/up", "Host", "a", "Host", "b"));
        var second = response(hosts);

        assertThat(second.status().code()).isEqualTo(400);
        assertThat(sink.terminals()).containsExactly("abort:MALFORMED", "abort:MALFORMED");
        second.release();
        hosts.finishAndReleaseAll();
    }

    /// A pipelined request the decoder delivered while the sink answers is held, not passed on, and goes on in order once
    /// the answer is written.
    @Test
    void pipelinedRequest_isHeldWhileTheSinkAnswers_andPassedOnOnceTheAnswerIsWritten() {
        var channel = channel();
        var nextHead = head("/agg", "Content-Length", "3");
        var nextBody = new DefaultLastHttpContent(bytes(3));

        channel.writeInbound(head("/up", "Content-Length", "5"));
        channel.writeInbound(new DefaultLastHttpContent(bytes(5)));
        sink.succeedNext();
        channel.runPendingTasks();
        channel.writeInbound(nextHead);
        channel.writeInbound(nextBody);

        assertThat(sink.terminals()).containsExactly("complete");
        assertThat(passedOn).as("held while the sink answers").isEmpty();
        assertThat(gate(channel).holdsBodyBuffer()).isTrue();
        assertThat(reading(channel)).isFalse();

        sink.writer().onPresent(writer -> writer.okText("done"));
        var answer = response(channel);

        assertThat(answer.status().code()).isEqualTo(200);
        assertThat(passedOn).as("passed on in order once the answer was written").containsExactly(nextHead, nextBody);
        assertThat(reading(channel)).isTrue();
        assertThat(gate(channel).holdsBodyBuffer()).isFalse();
        answer.release();
        passedOn.forEach(ReferenceCountUtil::release);
        channel.finishAndReleaseAll();
    }

    /// A pipelined request behind a streamed one that asked to close is dropped, and its buffer released.
    @Test
    void pipelinedRequest_behindAConnectionClose_isDroppedAndReleased() {
        var channel = channel();
        var nextBody = new DefaultLastHttpContent(bytes(3));

        channel.writeInbound(head("/up", "Content-Length", "5", "Connection", "close"));
        channel.writeInbound(new DefaultLastHttpContent(bytes(5)));
        sink.succeedNext();
        channel.runPendingTasks();
        channel.writeInbound(head("/agg", "Content-Length", "3"));
        channel.writeInbound(nextBody);
        sink.writer().onPresent(writer -> writer.okText("done"));
        var answer = response(channel);

        assertThat(answer.status().code()).isEqualTo(200);
        assertThat(passedOn).isEmpty();
        assertThat(nextBody.refCnt()).isZero();
        answer.release();
        channel.finishAndReleaseAll();
    }

    /// The reader-idle event is not passed on while the gate itself holds reading off; it is once reading is back on.
    @Test
    void readerIdle_isNotPassedOnWhileTheGateHoldsReadingOff() {
        var channel = channel();

        channel.writeInbound(head("/up", "Content-Length", "5000"));
        channel.writeInbound(new DefaultHttpContent(bytes(100)));
        channel.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);

        assertThat(passedOn).as("swallowed while an accept is pending").isEmpty();

        sink.succeedNext();
        channel.runPendingTasks();
        channel.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);

        assertThat(passedOn).as("passed on while the client is the one being waited for")
                            .containsExactly(IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
        channel.finishAndReleaseAll();
    }
}
