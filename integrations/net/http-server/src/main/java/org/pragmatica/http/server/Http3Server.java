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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.IdGenerator;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.http3.Http3Headers;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.http3.Http3ServerConnectionHandler;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Promise.resolved;


/// HTTP/3 server implementation using Netty QUIC transport.
///
/// Binds a UDP socket and handles HTTP/3 requests over QUIC,
/// converting them to the same [HttpRequest]/[ResponseWriter]
/// interface used by the HTTP/1.1 server.
final class Http3Server {
    private static final Logger log = LoggerFactory.getLogger(Http3Server.class);

    private final int port;
    private final Option<EventLoopGroup> workerGroup;
    private final Option<Channel> serverChannel;
    private final boolean ownsGroup;

    private Http3Server(int port,
                        Option<EventLoopGroup> workerGroup,
                        Option<Channel> serverChannel,
                        boolean ownsGroup) {
        this.port = port;
        this.workerGroup = workerGroup;
        this.serverChannel = serverChannel;
        this.ownsGroup = ownsGroup;
    }

    int port() {
        return port;
    }

    /// #1612: the same dependent, bounded chain as [NettyHttpServer#stop()] — the datagram channel close is
    /// bounded, then an owned group is shut down with no quiet period (see [ServerShutdown]) and awaited,
    /// bounded, and the first failure is reported typed. A shared group belongs to its owner and is left
    /// running. Before #1612 this already waited for termination, but unbounded, with the default quiet
    /// period, and reporting success whatever the outcome.
    Promise<Unit> stop() {
        log.info("Stopping HTTP/3 server on port {}", port);

        return serverChannel.map(ServerShutdown::closed)
                            .or(Promise.unitPromise())
                            .fold(this::shutdownGroupThen);
    }

    /// Package-private for the #1612 stop tests: the owned group and the datagram channel, to wedge the channel's
    /// loop or confirm termination.
    Option<EventLoopGroup> workerGroup() {
        return workerGroup;
    }

    Option<Channel> serverChannel() {
        return serverChannel;
    }

    private Promise<Unit> shutdownGroupThen(Result<Unit> closeOutcome) {
        return shutdownOwnedGroup().fold(groupOutcome -> resolved(closeOutcome.flatMap(_ -> groupOutcome)))
                                 .onSuccessRun(() -> log.info("HTTP/3 server on port {} stopped", port))
                                 .onFailure(cause -> log.warn("HTTP/3 server on port {} did not stop cleanly: {}",
                                                              port,
                                                              cause.message()));
    }

    private Promise<Unit> shutdownOwnedGroup() {
        return ownsGroup
               ? workerGroup.map(ServerShutdown::terminated)
                            .or(Promise.unitPromise())
               : Promise.unitPromise();
    }

    /// Create and start an HTTP/3 server with its own event loop group.
    static Promise<Http3Server> create(HttpServerConfig config,
                                       QuicSslContext quicSslContext,
                                       BiConsumer<HttpRequest, ResponseWriter> handler) {
        var group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

        return bind(config, quicSslContext, handler, group, true);
    }

    /// Create and start an HTTP/3 server using an externally managed event loop group.
    static Promise<Http3Server> createShared(HttpServerConfig config,
                                             QuicSslContext quicSslContext,
                                             BiConsumer<HttpRequest, ResponseWriter> handler,
                                             EventLoopGroup workerGroup) {
        return bind(config, quicSslContext, handler, workerGroup, false);
    }

    /// Package-private so the #1612 tests can pass an owned group and check its termination at the moment a
    /// failed create reports.
    static Promise<Http3Server> bind(HttpServerConfig config,
                                     QuicSslContext quicSslContext,
                                     BiConsumer<HttpRequest, ResponseWriter> handler,
                                     EventLoopGroup group,
                                     boolean ownsGroup) {
        return bind(config, quicSslContext, handler, group, ownsGroup, none());
    }

    /// #2310 test seam: `streamWriteInterceptor` supplies, per request stream, a handler installed between the HTTP/3
    /// frame codec and the request handler, so a test can fail a response frame write the way a transport failure
    /// would. Production passes none.
    @SuppressWarnings("JBCT-UTIL-01")
    static Promise<Http3Server> bind(HttpServerConfig config,
                                     QuicSslContext quicSslContext,
                                     BiConsumer<HttpRequest, ResponseWriter> handler,
                                     EventLoopGroup group,
                                     boolean ownsGroup,
                                     Option<Supplier<ChannelHandler>> streamWriteInterceptor) {
        var codec = Http3.newQuicServerCodecBuilder()
                         .sslContext(quicSslContext)
                         .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                         .initialMaxData(10_000_000)
                         .initialMaxStreamDataBidirectionalLocal(1_000_000)
                         .initialMaxStreamDataBidirectionalRemote(1_000_000)
                         .initialMaxStreamsBidirectional(100)
                         .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                         .handler(new QuicConnectionInitializer(handler, config, streamWriteInterceptor))
                         .build();
        var bootstrap = new Bootstrap().group(group).channel(NioDatagramChannel.class).handler(codec);

        return Promise.promise(promise -> bootstrap.bind(new InetSocketAddress(config.port()))
                                                   .addListener(future -> onBind(config,
                                                                                 promise,
                                                                                 future,
                                                                                 group,
                                                                                 ownsGroup)));
    }

    @SuppressWarnings("JBCT-PAT-01")  // Netty future callback
    private static void onBind(HttpServerConfig config,
                               Promise<Http3Server> promise,
                               io.netty.util.concurrent.Future<? super Void> future,
                               EventLoopGroup group,
                               boolean ownsGroup) {
        if (future.isSuccess()) {
            var channel = ((io.netty.channel.ChannelFuture) future).channel();

            log.info("HTTP/3 QUIC server '{}' started on port {}", config.name(), config.port());
            promise.succeed(new Http3Server(config.port(), Option.option(group), Option.option(channel), ownsGroup));
        } else {
            // #1612: the group this create made is terminated (bounded) BEFORE the failure is reported; the bind
            // failure is what the create reports, and a termination failure is only logged.
            var bindFailed = new HttpServerError.BindFailed(config.port(), future.cause());

            releaseGroupOnBindFailure(ownsGroup, group).onResultRun(() -> promise.fail(bindFailed));
        }
    }

    private static Promise<Unit> releaseGroupOnBindFailure(boolean ownsGroup, EventLoopGroup group) {
        return ownsGroup
               ? ServerShutdown.terminated(group).onFailure(cause -> log.warn("HTTP/3 server event loop did not terminate after a failed bind: {}",
                                                                              cause.message()))
               : Promise.unitPromise();
    }

    /// Initializer for each new QUIC connection.
    /// Adds [Http3ServerConnectionHandler] which manages HTTP/3 streams.
    private static class QuicConnectionInitializer extends ChannelInitializer<QuicChannel> {
        private final BiConsumer<HttpRequest, ResponseWriter> handler;
        private final HttpServerConfig config;
        private final Option<Supplier<ChannelHandler>> streamWriteInterceptor;

        QuicConnectionInitializer(BiConsumer<HttpRequest, ResponseWriter> handler,
                                  HttpServerConfig config,
                                  Option<Supplier<ChannelHandler>> streamWriteInterceptor) {
            this.handler = handler;
            this.config = config;
            this.streamWriteInterceptor = streamWriteInterceptor;
        }

        @Override
        protected void initChannel(QuicChannel ch) {
            ch.pipeline()
              .addLast(new Http3ServerConnectionHandler(new Http3StreamInitializer(handler,
                                                                                   config,
                                                                                   streamWriteInterceptor)));
        }
    }

    /// Initializer for each new HTTP/3 request stream.
    /// Creates a fresh [Http3RequestHandler] per stream (no shared state). Typed on [Channel], not [QuicStreamChannel],
    /// because it needs nothing QUIC-specific; package-private so a test can install it on an `EmbeddedChannel` and pin
    /// that the server's config reaches each stream.
    static class Http3StreamInitializer extends ChannelInitializer<Channel> {
        private final BiConsumer<HttpRequest, ResponseWriter> handler;
        private final HttpServerConfig config;
        private final Option<Supplier<ChannelHandler>> streamWriteInterceptor;

        Http3StreamInitializer(BiConsumer<HttpRequest, ResponseWriter> handler,
                               HttpServerConfig config,
                               Option<Supplier<ChannelHandler>> streamWriteInterceptor) {
            this.handler = handler;
            this.config = config;
            this.streamWriteInterceptor = streamWriteInterceptor;
        }

        /// #2386: the frame-boundary guard goes first, ahead of Netty's frame codec, so it sees the stream's bytes.
        @Override
        protected void initChannel(Channel ch) {
            ch.pipeline().addFirst(new Http3FrameBoundaryGuard());
            streamWriteInterceptor.onPresent(interceptor -> ch.pipeline()
                                                              .addLast(interceptor.get()));
            ch.pipeline().addLast(Http3RequestHandler.http3RequestHandler(config, handler));
        }
    }

    /// Per-stream HTTP/3 request handler.
    ///
    /// Accumulates headers and data frames, then dispatches
    /// to the shared request handler when input closes.
    /// Each stream gets its own instance — no @Sharable needed.
    ///
    /// A stream that breaks HTTP/3 framing is never dispatched, not even its valid prefix (#2386): see
    /// [#exceptionCaught] and [#userEventTriggered].
    ///
    /// A body over `maxContentLength` is answered 413 as soon as it crosses the limit and is never dispatched
    /// (I1: it was cut to the limit and dispatched as if complete). The trailer section, when the client sends one, is
    /// delivered as [HttpRequest#trailers()] (#2310).
    /// The body buffer is released on every exit: dispatch,
    /// rejection, a stream that closes without headers, and a stream torn down before its input closed.
    ///
    /// I3/F6: the same two bounds as HTTP/1.1. IDLE: a body that delivers no frame for `readTimeout` (QUIC's own 5 s
    /// idle covers only a silent CONNECTION, not one silent stream among busy ones). RATE: a body arriving below
    /// `bodyRateLimit` once its grace has passed, which is what bounds a client trickling a byte every few seconds.
    /// Either answers 408, resets the stream's input and never dispatches. A slow upload above the minimum rate
    /// completes however long it takes.
    ///
    /// Package-private so the server-level tests can drive it in an `EmbeddedChannel`.
    static class Http3RequestHandler extends Http3RequestStreamInboundHandler {
        private static final Logger log = LoggerFactory.getLogger(Http3RequestHandler.class);

        private final BiConsumer<HttpRequest, ResponseWriter> handler;
        private final int maxContentLength;
        private final TimeSpan readTimeout;
        private final BodyRateLimit bodyRateLimit;
        private Http3HeadersFrame headersFrame;
        private Option<Http3HeadersFrame> trailersFrame = Option.none();
        private ByteBuf bodyData;
        private boolean rejected;
        private Option<ScheduledFuture<?>> idleTimer = none();
        private Option<BodyRateGuard> rateGuard = none();

        Http3RequestHandler(BiConsumer<HttpRequest, ResponseWriter> handler,
                            int maxContentLength,
                            TimeSpan readTimeout,
                            BodyRateLimit bodyRateLimit) {
            this.handler = handler;
            this.maxContentLength = maxContentLength;
            this.readTimeout = readTimeout;
            this.bodyRateLimit = bodyRateLimit;
        }

        /// The handler every request stream gets: the server's configured body limit, read timeout and minimum body
        /// rate. Package-private so a test can pin that the configured values, not defaults, reach the stream.
        static Http3RequestHandler http3RequestHandler(HttpServerConfig config,
                                                       BiConsumer<HttpRequest, ResponseWriter> handler) {
            return new Http3RequestHandler(handler,
                                           config.maxContentLength(),
                                           config.readTimeout(),
                                           config.bodyRateLimit());
        }

        /// #2310: the first HEADERS frame is the header section; one after it is the trailer section (RFC 9114
        /// §4.1). Netty's request-stream validation refuses any HEADERS frame after the trailers, and its trailer
        /// decoding refuses pseudo-header fields and a section over SETTINGS_MAX_FIELD_SECTION_SIZE.
        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
            if (headersFrame == null) {
                headersFrame = frame;
                armIdleTimer(ctx);
                rateGuard = some(BodyRateGuard.start(ctx.executor(), bodyRateLimit, () -> rejectTooSlow(ctx)));
            } else {
                trailersFrame = some(frame);
            }
        }

        /// Re-armed on every body frame, so it fires only after `readTimeout` without one.
        private void armIdleTimer(ChannelHandlerContext ctx) {
            idleTimer.onPresent(timer -> timer.cancel(false));
            idleTimer = readTimeout.nanos() > 0
                        ? some(ctx.executor().schedule(() -> rejectIdle(ctx), readTimeout.nanos(), TimeUnit.NANOSECONDS))
                        : none();
        }

        private void stopTimers() {
            idleTimer.onPresent(timer -> timer.cancel(false));
            idleTimer = none();
            rateGuard.onPresent(BodyRateGuard::stop);
            rateGuard = none();
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
            if (!rejected) {
                rateGuard.onPresent(guard -> guard.onBytes(dataFrame.content().readableBytes()));
                armIdleTimer(ctx);
                appendData(ctx, dataFrame.content());
            }

            ReferenceCountUtil.release(dataFrame);
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
            stopTimers();
        }

        /// #2386: dispatch waits for [ChannelInputShutdownReadComplete], the event after the input-shutdown one. Netty's
        /// request-stream validation checks the body against `content-length` only when the input ends, and on a
        /// mismatch raises the error and withholds this event; dispatching on input shutdown came before that check.
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt == ChannelInputShutdownReadComplete.INSTANCE) {
                inputComplete(ctx);
            }
            super.userEventTriggered(ctx, evt);
        }

        private void inputComplete(ChannelHandlerContext ctx) {
            if (headersFrame == null || rejected) {
                releaseBody();

                return;
            }

            dispatch(ctx);
        }

        @Contract
        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            stopTimers();
            releaseBody();
        }

        @SuppressWarnings({"JBCT-PAT-01", "JBCT-EX-01"})  // Netty handler boundary: must catch exceptions to send error response
        private void dispatch(ChannelHandlerContext ctx) {
            var requestId = IdGenerator.generate("req");
            var responseWriter = new Http3ResponseWriter(ctx, requestId);
            // #2310: building the request (headers, request trailers, body) is inside the guard too, so a failure there
            // is answered 500 instead of closing the stream from exceptionCaught
            try {
                handler.accept(buildRequestContext(requestId), responseWriter);
            } catch (Exception e) {
                log.error("Error handling HTTP/3 request {}", requestId, e);
                responseWriter.error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
            }
        }

        private void appendData(ChannelHandlerContext ctx, ByteBuf content) {
            var buffered = bodyData == null
                           ? 0
                           : bodyData.readableBytes();

            if ((long) buffered + content.readableBytes() > maxContentLength) {
                rejectTooLarge(ctx);

                return;
            }

            if (bodyData == null) {
                bodyData = Unpooled.buffer(content.readableBytes());
            }

            bodyData.writeBytes(content);
        }

        private void rejectTooLarge(ChannelHandlerContext ctx) {
            log.debug("HTTP/3 request body exceeds {} bytes, answering 413", maxContentLength);
            reject(ctx,
                   HttpStatus.REQUEST_ENTITY_TOO_LARGE,
                   "Request body exceeds the " + maxContentLength + "-byte limit");
        }

        private void rejectIdle(ChannelHandlerContext ctx) {
            if (!rejected) {
                log.debug("HTTP/3 request body idle for {}, answering 408", readTimeout);
                reject(ctx, HttpStatus.REQUEST_TIMEOUT, "Request body idle longer than the read timeout");
            }
        }

        private void rejectTooSlow(ChannelHandlerContext ctx) {
            if (!rejected) {
                log.debug("HTTP/3 request body below {} B/s, answering 408", bodyRateLimit.bytesPerSecond());
                reject(ctx, HttpStatus.REQUEST_TIMEOUT, "Request body arrived below the minimum rate");
            }
        }

        /// Answers without dispatching, then asks the client to stop sending (RFC 9114 §4.1.1: a server that does
        /// not need the rest of the request may abort reading it, with H3_NO_ERROR, after a complete response).
        private void reject(ChannelHandlerContext ctx, HttpStatus status, String message) {
            rejected = true;
            stopTimers();
            releaseBody();
            new Http3ResponseWriter(ctx, IdGenerator.generate("req")).error(status, message);
            if (ctx.channel() instanceof QuicStreamChannel stream) {
                stream.shutdownInput(Http3ErrorCode.H3_NO_ERROR.code());
            }
        }

        /// Package-private test seam: whether a body buffer is still held, so a test can assert every exit released it.
        boolean holdsBodyBuffer() {
            return bodyData != null;
        }

        private void releaseBody() {
            if (bodyData != null) {
                bodyData.release();
                bodyData = null;
            }
        }

        private HttpRequest buildRequestContext(String requestId) {
            var h3Headers = headersFrame.headers();
            var methodStr = Option.option(h3Headers.method()).map(CharSequence::toString).or("GET");
            var pathStr = Option.option(h3Headers.path()).map(CharSequence::toString).or("/");
            var method = HttpMethod.httpMethod(methodStr).or(HttpMethod.GET);
            var pathAndQuery = pathStr.split("\\?", 2);
            var path = pathAndQuery[0];
            var queryParams = parseQueryParams(pathAndQuery.length > 1
                                               ? pathAndQuery[1]
                                               : "");
            var headerMap = extractHeaders(h3Headers);
            var trailers = Headers.trailers(trailersFrame.map(frame -> extractHeaders(frame.headers())).or(Map.of()));
            var body = extractBody();

            return new Http3RequestContext(requestId,
                                           method,
                                           path,
                                           Headers.headers(headerMap),
                                           queryParams,
                                           body,
                                           trailers);
        }

        private byte[] extractBody() {
            if (bodyData == null) {
                return new byte[0];
            }

            var bytes = new byte[bodyData.readableBytes()];

            bodyData.readBytes(bytes);
            releaseBody();

            return bytes;
        }

        private static Map<String, List<String>> extractHeaders(Http3Headers h3Headers) {
            var headerMap = new HashMap<String, List<String>>();

            for (var entry : h3Headers) {
                headerMap.computeIfAbsent(entry.getKey().toString().toLowerCase(Locale.ROOT),
                                          _ -> new ArrayList<>())
                         .add(entry.getValue().toString());
            }

            return headerMap;
        }

        private static QueryParams parseQueryParams(String queryString) {
            if (queryString.isEmpty()) {
                return QueryParams.empty();
            }

            var params = new HashMap<String, List<String>>();

            for (var param : queryString.split("&")) {
                var kv = param.split("=", 2);

                if (kv.length == 2) {
                    params.computeIfAbsent(kv[0], _ -> new ArrayList<>()).add(kv[1]);
                } else if (kv.length == 1) {
                    params.computeIfAbsent(kv[0], _ -> new ArrayList<>()).add("");
                }
            }

            return QueryParams.queryParams(params);
        }

        /// #2386: Netty raises each framing or message error of the stream here, synchronously, before it drops the
        /// offending frame and so before the stream's input can end. Marking the stream rejected is what keeps its
        /// valid prefix from being dispatched: closing it is not enough, because when the bad bytes and the `FIN`
        /// arrive in one read, the transport still delivers the input-shutdown events after the close.
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            rejected = true;
            log.error("Error in HTTP/3 handler", cause);
            ctx.close();
        }
    }

    private record Http3RequestContext(String requestId,
                                       HttpMethod method,
                                       String path,
                                       Headers headers,
                                       QueryParams queryParams,
                                       byte[] body,
                                       Headers trailers) implements HttpRequest {}

    /// HTTP/3 response writer that sends response as HTTP/3 frames.
    private static class Http3ResponseWriter implements ResponseWriter {
        /// #2386: RFC 9114 §4.2 — connection-specific fields an HTTP/3 message must not carry (`te` is allowed only in a
        /// request), lowercase.
        private static final Set<String> CONNECTION_SPECIFIC = Set.of("connection",
                                                                      "keep-alive",
                                                                      "proxy-connection",
                                                                      "transfer-encoding",
                                                                      "upgrade",
                                                                      "te");

        private final ChannelHandlerContext ctx;
        private final String requestId;
        // Thread-safe: each Http3ResponseWriter is created per-stream and accessed only from the Netty event loop thread
        private final Map<String, String> pendingHeaders = new HashMap<>();
        private final AtomicBoolean written = new AtomicBoolean(false);
        private final AtomicBoolean reset = new AtomicBoolean(false);
        private final Promise<Unit> flushed = Promise.promise();

        Http3ResponseWriter(ChannelHandlerContext ctx, String requestId) {
            this.ctx = ctx;
            this.requestId = requestId;
        }

        /// #2310: HTTP/3 field names are lowercase (RFC 9114 §4.2), and Netty refuses a HEADERS frame with an
        /// uppercase one. A handler's `X-Request-Id` used to fail the whole response, leaving the stream unanswered.
        ///
        /// #2386: a connection-specific field (`Connection`, `Keep-Alive`, `Proxy-Connection`, `Transfer-Encoding`,
        /// `Upgrade`, `TE`, in any case) or a name starting with `:` is dropped (RFC 9114 §4.2): a client refuses a
        /// response carrying `connection` or an unknown pseudo-header, and the pseudo-header fields are the writer's
        /// own, so an application `:status` does not replace the real one.
        @Override
        public ResponseWriter header(String name, String value) {
            var fieldName = name.toLowerCase(Locale.ROOT);

            if (CONNECTION_SPECIFIC.contains(fieldName) || fieldName.startsWith(":")) {
                log.debug("Dropping response field '{}' for request {}: not allowed in HTTP/3", name, requestId);
            } else {
                pendingHeaders.put(fieldName, value);
            }

            return this;
        }

        @Override
        public void write(HttpStatus status, byte[] body, ContentType contentType) {
            writeAsync(status, body, contentType);
        }

        /// #2310: a response that fails never leaves the stream to the idle timeout. Until the HEADERS frame is handed
        /// to the channel nothing is committed: a failure building it (a content type that throws, a header the codec
        /// refuses when the headers are built, such as a null value) is answered `500` with the server's own headers
        /// only. From the HEADERS write on, the response is committed: a failed frame write resets the stream with
        /// `H3_INTERNAL_ERROR`. Each failure is logged once, and the returned promise fails with it, as
        /// [NettyHttpServer]'s writer fails its flush promise. A field HTTP/3 forbids never gets this far: [#header]
        /// drops it (#2386).
        @Override
        public Promise<Unit> writeAsync(HttpStatus status, byte[] body, ContentType contentType) {
            if (!written.compareAndSet(false, true)) {
                return flushed;
            }

            return Result.lift(Causes::fromThrowable,
                               () -> responseHeaders(status, body.length, contentType, pendingHeaders)).fold(this::answerInternalError,
                                                                                                             headers -> commit(headers,
                                                                                                                               body));
        }

        private Http3Headers responseHeaders(HttpStatus status,
                                             int contentLength,
                                             ContentType contentType,
                                             Map<String, String> extraHeaders) {
            var headers = new io.netty.handler.codec.http3.DefaultHttp3Headers();

            headers.status(String.valueOf(status.code()));
            headers.set("content-type", contentType.headerText());
            headers.setInt("content-length", contentLength);
            headers.set("x-request-id", requestId);
            extraHeaders.forEach(headers::set);

            return headers;
        }

        private Promise<Unit> answerInternalError(Cause cause) {
            log.error("Failed to build HTTP/3 response for request {}, answering 500: {}", requestId, cause.message());
            var body = "{\"error\":\"Internal Server Error\"}".getBytes(StandardCharsets.UTF_8);
            // the promise fails first: the 500's own writes may complete it synchronously on the event loop
            flushed.fail(cause);

            return commit(responseHeaders(HttpStatus.INTERNAL_SERVER_ERROR,
                                          body.length,
                                          CommonContentType.APPLICATION_JSON,
                                          Map.of()),
                          body);
        }

        /// The HEADERS frame and the body, then a clean `FIN` once the last write succeeds. Any failed write resets the
        /// stream instead, once.
        private Promise<Unit> commit(Http3Headers headers, byte[] body) {
            ctx.write(new DefaultHttp3HeadersFrame(headers)).addListener(this::resetOnFailure);
            var last = body.length > 0
                       ? ctx.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(body)))
                       : ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);

            last.addListener(this::finishOrReset);

            return flushed;
        }

        private void resetOnFailure(Future<? super Void> future) {
            if (!future.isSuccess()) {
                reset(future.cause());
            }
        }

        private void finishOrReset(Future<? super Void> future) {
            if (!future.isSuccess()) {
                reset(future.cause());
            } else if (!reset.get()) {
                ((QuicStreamChannel) ctx.channel()).shutdownOutput();
                flushed.succeed(Unit.unit());
            }
        }

        private void reset(Throwable cause) {
            if (reset.compareAndSet(false, true)) {
                // WARN without a stack trace: after the commit the failure is the transport's (often a peer that
                // already went away), not the handler's; H1 does not log it at all and only fails the flush promise
                log.warn("HTTP/3 response for request {} failed after its headers were committed, resetting the stream: {}",
                         requestId,
                         cause.toString());
                ((QuicStreamChannel) ctx.channel()).shutdownOutput(Http3ErrorCode.H3_INTERNAL_ERROR.code());
                flushed.fail(Causes.fromThrowable(cause));
            }
        }
    }
}
