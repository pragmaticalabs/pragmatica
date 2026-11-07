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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.IdGenerator;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// HTTP/1.1 side of request classification (the ingest gate). Sits between the codec and the aggregator, where a request
/// still arrives as its head followed by body chunks.
///
/// On the head it asks the [RequestClassifier] what to do, before a single body byte is read:
/// - [Disposition.Reject]: the final response goes out at once with `Connection: close`, and the rest of the request is
///   never read into memory. A client that sent `Expect: 100-continue` is never told to continue.
/// - [Disposition.Aggregate]: the body is read whole, bounded by the route's limit. A declared `Content-Length` over it
///   is answered 413 on the head; a chunked body that crosses it is answered 413 at the byte that crosses it. Either way
///   the connection is closed without reading the rest. The same [BodyGuard] holds the idle bound, the minimum rate and
///   the whole-body duration.
/// - [Disposition.Stream]: the body goes to the route's [BodySink] as it arrives (see [BodyStream]), under the same
///   bounds, and never reaches the aggregator. A declared `Content-Length` over the limit or a chunk that crosses it is
///   answered 413 and the sink is aborted ([BodySink.Ended#TOO_LARGE]); a broken time bound answers as on the aggregate
///   path and aborts it ([BodySink.Ended#TIMED_OUT]); a body the decoder cannot read, a malformed target or a second
///   `Host` line is answered 400 and aborts it ([BodySink.Ended#MALFORMED]); a connection lost first aborts it
///   ([BodySink.Ended#DISCONNECTED]). `Expect: 100-continue` is answered here, since the aggregator never sees the
///   request. Once the body ended and was accepted, the sink gets the request (head and trailers, empty body) and its
///   response; reading stays off until that response is written, and what the decoder had already delivered of a
///   pipelined next request is held and replayed then. A failed `accept` hands the sink a response that closes the
///   connection, which then lingers like a refused one. While the gate itself holds reading off, the reader-idle event is
///   not passed on: the client is not the one that is idle.
///
/// A refused request is answered and then closed after a short linger ([#LINGER_MILLIS]): the response is sent, the
/// output is shut down and reading stops, so the client's remaining bytes wait in the kernel's buffer and its sending
/// stalls. Closing at once would reset a connection that still has unread bytes, and the reset can destroy the response
/// before the client reads it. The linger is a bound, not a drain: the body is never read into memory.
///
/// Per channel, not sharable.
final class RequestGate extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(RequestGate.class);
    /// How long a refused connection discards what the client still sends before it is closed. A guess: long enough for
    /// a client to read the response and close on a loopback or LAN link, short enough that a client that keeps sending
    /// is cut within a second.
    static final long LINGER_MILLIS = 250;
    private static volatile long lingerMillis = LINGER_MILLIS;
    private static final TimeSpan NO_BOUND = TimeSpan.timeSpan(0).millis();

    private final HttpServerConfig config;
    private final RequestClassifier classifier;
    private Option<BodyGuard> guard = none();
    private boolean refused;
    /// The streamed request in progress: its body is read, or accepted, or the sink is answering it.
    private Option<Streamed> stream = none();
    /// The streamed request's body ended: messages the decoder still delivers wait here until its response is written.
    private boolean bodyEnded;
    private final ArrayDeque<Object> held = new ArrayDeque<>();

    /// A streamed request: its head, and its body on the way to the sink.
    private record Streamed(io.netty.handler.codec.http.HttpRequest head, BodySink sink, BodyStream body) {}

    RequestGate(HttpServerConfig config) {
        this.config = config;
        this.classifier = config.effectiveClassifier();
    }

    @Contract
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (refused) {
            ReferenceCountUtil.release(msg);

            return;
        }

        if (bodyEnded) {
            held.add(msg);

            return;
        }

        if (stream.isPresent() && msg instanceof HttpContent content) {
            streamed(ctx, content);

            return;
        }

        if (msg instanceof io.netty.handler.codec.http.HttpRequest head) {
            stopGuard();
            if (!head.decoderResult().isFailure() && !begin(ctx, head)) {
                ReferenceCountUtil.release(msg);

                return;
            }
        }

        if (msg instanceof HttpContent content && !count(ctx, content)) {
            ReferenceCountUtil.release(msg);

            return;
        }

        if (msg instanceof LastHttpContent) {
            stopGuard();
        }

        ctx.fireChannelRead(msg);
    }

    @Contract
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        stopGuard();
        disconnected();
        ctx.fireChannelInactive();
    }

    @Contract
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        stopGuard();
        disconnected();
    }

    /// The reader-idle event is not passed on while a streamed request holds reading off: the server is waiting for its
    /// sink, not for the client. The body's own bounds still apply to the client.
    @Contract
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (! (evt instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE && holdsReading(ctx))) {
            ctx.fireUserEventTriggered(evt);
        }
    }

    private boolean holdsReading(ChannelHandlerContext ctx) {
        return stream.isPresent() && !ctx.channel()
                                         .config()
                                         .isAutoRead();
    }

    /// Test seam: whether a received buffer is still held, by the streamed body or among the held messages.
    boolean holdsBodyBuffer() {
        return streamHoldsBuffer() || !held.isEmpty();
    }

    /// Whether the streamed body holds a chunk: it turns reading back on itself once that is accepted.
    private boolean streamHoldsBuffer() {
        return stream.map(streamed -> streamed.body()
                                              .holdsBuffer())
                     .or(false);
    }

    /// Classifies the head; returns false when the request was refused.
    private boolean begin(ChannelHandlerContext ctx, io.netty.handler.codec.http.HttpRequest head) {
        return switch (classifier.classify(headOf(head))) {
            case Disposition.Reject reject -> refuse(ctx, reject.respond());
            case Disposition.Aggregate aggregate -> admit(ctx, head, aggregate);
            case Disposition.Stream streamed -> beginStream(ctx, head, streamed);
        };
    }

    /// Starts streaming the body to the sink; returns false, since the request is never forwarded.
    private boolean beginStream(ChannelHandlerContext ctx,
                                io.netty.handler.codec.http.HttpRequest head,
                                Disposition.Stream streamed) {
        var sink = streamed.sink();

        if (isMalformed(head)) {
            sink.abort(BodySink.Ended.MALFORMED);

            return refuse(ctx, writer -> writer.error(HttpStatus.BAD_REQUEST, "Malformed request"));
        }

        var bounds = streamed.bounds();

        stream = some(new Streamed(head,
                                   sink,
                                   BodyStream.bodyStream(ctx.executor(),
                                                         ctx.channel(),
                                                         sink,
                                                         cause -> acceptFailed(ctx, sink, cause))));
        var started = BodyGuard.start(ctx.executor(),
                                      streamed.maxBytes(),
                                      bounds.map(BodyBounds::idle).or(NO_BOUND),
                                      bounds.map(BodyBounds::rate).or(config.bodyRateLimit()),
                                      bounds.map(BodyBounds::maxDuration).or(NO_BOUND),
                                      () -> {},
                                      reason -> onRefused(ctx, reason, bounds.isPresent()));

        guard = some(started);
        if (!started.refuseIfDeclaredTooLarge(HttpUtil.getContentLength(head, -1L)) && HttpUtil.is100ContinueExpected(head)) {
            HttpUtil.set100ContinueExpected(head, false);
            ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                                          HttpResponseStatus.CONTINUE,
                                                          Unpooled.EMPTY_BUFFER));
        }

        return false;
    }

    /// What the request handler answers 400 before any handler runs (#2419, #2386), checked here for a streamed request,
    /// which never reaches it.
    private static boolean isMalformed(io.netty.handler.codec.http.HttpRequest head) {
        return RequestTarget.requestTarget(head.uri()).isFailure() || head.headers()
                                                                          .getAll(HttpHeaderNames.HOST)
                                                                          .size() > 1;
    }

    /// A chunk of a streamed body: counted against the bounds, then handed to the body stream with its buffer.
    private void streamed(ChannelHandlerContext ctx, HttpContent content) {
        if (content.decoderResult().isFailure()) {
            content.release();
            endStream(BodySink.Ended.MALFORMED);
            refuse(ctx, writer -> writer.error(HttpStatus.BAD_REQUEST, "Malformed request body"));

            return;
        }

        if (!count(ctx, content)) {
            content.release();

            return;
        }

        stream.onPresent(streamed -> streamed.body()
                                             .offer(content.content()));
        if (content instanceof LastHttpContent last) {
            endBody(ctx, last.trailingHeaders());
        }
    }

    /// The body ended: once every chunk was accepted, the sink gets the request and its response.
    private void endBody(ChannelHandlerContext ctx, HttpHeaders trailers) {
        bodyEnded = true;
        stopGuard();
        ctx.channel().config().setAutoRead(false);
        stream.onPresent(streamed -> streamed.body()
                                             .end(() -> complete(ctx, streamed, trailers)));
    }

    private void complete(ChannelHandlerContext ctx, Streamed streamed, HttpHeaders trailers) {
        var request = requestOf(streamed.head(), Headers.trailers(fieldMap(trailers)));
        var keepAlive = HttpUtil.isKeepAlive(streamed.head());
        var writer = new NettyHttpServer.NettyResponseWriter(ctx,
                                                             request.requestId(),
                                                             keepAlive,
                                                             () -> responded(ctx, keepAlive));

        writer.guard();
        streamed.sink().complete(request, writer);
    }

    /// The sink's response was written: a keep-alive connection reads on, starting with what the decoder delivered
    /// meanwhile; any other one is closing, and what was held is dropped.
    private void responded(ChannelHandlerContext ctx, boolean keepAlive) {
        bodyEnded = false;
        stream = none();
        if (keepAlive) {
            replayHeld(ctx);
        } else {
            refused = true;
            releaseHeld();
        }
    }

    /// Passes the held messages on in order, until one of them starts a streamed request whose body ends among them;
    /// then reads on, unless a streamed body replayed here still holds a chunk.
    private void replayHeld(ChannelHandlerContext ctx) {
        while (!held.isEmpty() && !bodyEnded && !refused) {
            channelRead(ctx, held.poll());
        }

        if (!bodyEnded && !refused && !streamHoldsBuffer()) {
            ctx.channel().config().setAutoRead(true);
        }
    }

    /// An `accept` failed: the sink answers with a response that closes the connection, which then lingers; nothing more
    /// of the body is read.
    private void acceptFailed(ChannelHandlerContext ctx, BodySink sink, Cause cause) {
        stream = none();
        bodyEnded = false;
        answerAndClose(ctx, writer -> sink.fail(cause, writer), true);
    }

    /// The server ends the streamed request: the sink is aborted with `cause` (after its outstanding `accept`).
    private void endStream(Cause cause) {
        stream.onPresent(streamed -> streamed.body()
                                             .abort(cause));
    }

    private void disconnected() {
        endStream(BodySink.Ended.DISCONNECTED);
        releaseHeld();
    }

    private void releaseHeld() {
        held.forEach(ReferenceCountUtil::release);
        held.clear();
    }

    private boolean admit(ChannelHandlerContext ctx,
                          io.netty.handler.codec.http.HttpRequest head,
                          Disposition.Aggregate aggregate) {
        var bounds = aggregate.bounds();
        var started = BodyGuard.start(ctx.executor(),
                                      aggregate.maxBytes(),
                                      bounds.map(BodyBounds::idle).or(NO_BOUND),
                                      bounds.map(BodyBounds::rate).or(config.bodyRateLimit()),
                                      bounds.map(BodyBounds::maxDuration).or(NO_BOUND),
                                      aggregate.release(),
                                      reason -> onRefused(ctx, reason, bounds.isPresent()));

        guard = some(started);

        return ! started.refuseIfDeclaredTooLarge(HttpUtil.getContentLength(head, -1L));
    }

    /// Counts the chunk against the limit; returns false when it crossed it.
    private boolean count(ChannelHandlerContext ctx, HttpContent content) {
        return guard.map(active -> active.onBytes(content.content().readableBytes()))
                    .or(true);
    }

    private void onRefused(ChannelHandlerContext ctx, BodyGuard.Refusal reason, boolean timedAnswer) {
        guard = none();
        endStream(reason == BodyGuard.Refusal.TOO_LARGE
                  ? BodySink.Ended.TOO_LARGE
                  : BodySink.Ended.TIMED_OUT);
        switch (reason) {
            case TOO_LARGE -> refuse(ctx,
                                     writer -> writer.error(HttpStatus.REQUEST_ENTITY_TOO_LARGE,
                                                            "Request body exceeds the limit"));
            case TOO_SLOW, IDLE, DURATION -> tooSlow(ctx, reason, timedAnswer);
        }
    }

    /// An upload route's bounds answer 408 before closing; the listener's general minimum rate keeps closing
    /// silently, as it did before request classification.
    private void tooSlow(ChannelHandlerContext ctx, BodyGuard.Refusal reason, boolean timedAnswer) {
        log.debug("Refusing HTTP request body on {}: {}",
                  ctx.channel().remoteAddress(),
                  reason);
        if (timedAnswer) {
            refuse(ctx, writer -> writer.error(HttpStatus.REQUEST_TIMEOUT, "Request body " + describe(reason)));
        } else {
            refused = true;
            ctx.close();
        }
    }

    private static String describe(BodyGuard.Refusal reason) {
        return switch (reason) {
            case IDLE -> "idle longer than the upload idle timeout";
            case DURATION -> "took longer than the route's max_duration";
            default -> "arrived below the minimum rate";
        };
    }

    /// Answers with `respond`, then closes the connection after the linger. Returns false: the request is not forwarded.
    private boolean refuse(ChannelHandlerContext ctx, Consumer<ResponseWriter> respond) {
        return answerAndClose(ctx, respond, false);
    }

    /// [#refuse], with the response's wait bounded by the response timeout when `guarded`: a sink answers in its own time,
    /// and with reading off nothing else would end a connection whose sink never answers.
    private boolean answerAndClose(ChannelHandlerContext ctx, Consumer<ResponseWriter> respond, boolean guarded) {
        refused = true;
        stopGuard();
        var writer = new NettyHttpServer.NettyResponseWriter(ctx,
                                                             IdGenerator.generate("req"),
                                                             false,
                                                             () -> {},
                                                             true);

        if (guarded) {
            writer.guard();
        }

        respond.accept(writer);

        return false;
    }

    private void stopGuard() {
        guard.onPresent(BodyGuard::stop);
        guard = none();
    }

    /// Test seam: a linger long enough that a client seeing end of stream before it can only have been sent one by the
    /// output shutdown, not by the close. Restore [#LINGER_MILLIS] when done.
    @Contract
    static void lingerMillisForTest(long millis) {
        lingerMillis = millis;
    }

    /// Closes after sending what is queued: the output is shut down (the client sees the response and then end of
    /// stream), reading stops (what the client still sends waits in the kernel's buffer, so its sending stalls instead of
    /// being read into memory), and the connection is closed when the linger ends.
    @Contract
    static void lingerClose(Channel channel) {
        if (channel instanceof SocketChannel socket && socket.isActive()) {
            socket.config().setAutoRead(false);
            socket.shutdownOutput();
        }

        var deadline = channel.eventLoop().schedule(() -> {
                                                        channel.close();
                                                    },
                                                    lingerMillis,
                                                    TimeUnit.MILLISECONDS);

        channel.closeFuture().addListener(_ -> deadline.cancel(false));
    }

    private static HttpRequest headOf(io.netty.handler.codec.http.HttpRequest head) {
        return requestOf(head, Headers.empty());
    }

    /// The request as a streamed body's sink gets it: the head and `trailers`, with an empty body.
    private static HttpRequest requestOf(io.netty.handler.codec.http.HttpRequest head, Headers trailers) {
        var target = RequestTarget.lenientTarget(head.uri());
        var method = HttpMethod.httpMethod(head.method().name()).or(HttpMethod.GET);

        return new HeadRequest(IdGenerator.generate("req"),
                               method,
                               target.path(),
                               Headers.headers(fieldMap(head.headers())),
                               target.queryParams(),
                               trailers);
    }

    private static LinkedHashMap<String, List<String>> fieldMap(HttpHeaders headers) {
        var fields = new LinkedHashMap<String, List<String>>();

        for (var entry : headers) {
            fields.computeIfAbsent(entry.getKey().toLowerCase(Locale.ROOT),
                                   _ -> new ArrayList<>())
                  .add(entry.getValue());
        }

        return fields;
    }

    /// The head of a request, and its trailers once they arrived: everything but a body, which is not held.
    private record HeadRequest(String requestId,
                               HttpMethod method,
                               String path,
                               Headers headers,
                               QueryParams queryParams,
                               Headers trailers) implements HttpRequest {
        @Override
        public byte[] body() {
            return new byte[0];
        }
    }
}
