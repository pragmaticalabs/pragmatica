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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.IdGenerator;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
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
    private static final TimeSpan NO_BOUND = TimeSpan.timeSpan(0).millis();

    private final HttpServerConfig config;
    private final RequestClassifier classifier;
    private Option<BodyGuard> guard = none();
    private boolean refused;

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
        ctx.fireChannelInactive();
    }

    @Contract
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        stopGuard();
    }

    /// Classifies the head; returns false when the request was refused.
    private boolean begin(ChannelHandlerContext ctx, io.netty.handler.codec.http.HttpRequest head) {
        return switch (classifier.classify(headOf(head))) {
            case Disposition.Reject reject -> refuse(ctx, reject.respond());
            case Disposition.Aggregate aggregate -> admit(ctx, head, aggregate);
        };
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
    private boolean refuse(ChannelHandlerContext ctx, java.util.function.Consumer<ResponseWriter> respond) {
        refused = true;
        stopGuard();
        respond.accept(new NettyHttpServer.NettyResponseWriter(ctx,
                                                               IdGenerator.generate("req"),
                                                               false,
                                                               () -> {},
                                                               true));

        return false;
    }

    private void stopGuard() {
        guard.onPresent(BodyGuard::stop);
        guard = none();
    }

    /// Closes after sending what is queued: the output is shut down (the client sees the response and then end of
    /// stream), reading stops (what the client still sends waits in the kernel's buffer, so its sending stalls instead of
    /// being read into memory), and the connection is closed when the linger ends.
    static void lingerClose(Channel channel) {
        if (channel instanceof SocketChannel socket && socket.isActive()) {
            socket.config().setAutoRead(false);
            socket.shutdownOutput();
        }

        var deadline = channel.eventLoop().schedule(() -> {
                                                        channel.close();
                                                    },
                                                    LINGER_MILLIS,
                                                    TimeUnit.MILLISECONDS);

        channel.closeFuture().addListener(_ -> deadline.cancel(false));
    }

    private static HttpRequest headOf(io.netty.handler.codec.http.HttpRequest head) {
        var decoder = new QueryStringDecoder(head.uri());
        var method = HttpMethod.httpMethod(head.method().name()).or(HttpMethod.GET);
        var fields = new LinkedHashMap<String, List<String>>();

        for (var entry : head.headers()) {
            fields.computeIfAbsent(entry.getKey().toLowerCase(Locale.ROOT),
                                   _ -> new ArrayList<>())
                  .add(entry.getValue());
        }

        return new HeadRequest(IdGenerator.generate("req"),
                               method,
                               decoder.path(),
                               Headers.headers(fields),
                               QueryParams.queryParams(decoder.parameters()));
    }

    /// The head of a request: everything but a body, which has not arrived.
    private record HeadRequest(String requestId,
                               HttpMethod method,
                               String path,
                               Headers headers,
                               QueryParams queryParams) implements HttpRequest {
        @Override
        public byte[] body() {
            return new byte[0];
        }
    }
}
