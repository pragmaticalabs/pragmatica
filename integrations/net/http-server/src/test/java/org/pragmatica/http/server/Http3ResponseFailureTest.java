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
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.http3.Http3Frame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamResetException;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.ContentCategory;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.QuicSslContextFactory;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2310: a response the HTTP/3 writer cannot complete never leaves the stream to the 5 s idle timeout. A failure
/// before the HEADERS frame is committed is answered `500`; a failed frame write after it resets the stream with
/// `H3_INTERNAL_ERROR`. Both must reach a real HTTP/3 client well inside the idle timeout.
class Http3ResponseFailureTest {
    /// Well under the 5 s QUIC idle timeout, so an outcome that only the timeout could produce fails the assertion.
    private static final long PROMPT_MS = 2_000;
    private static final byte[] BODY = "hello".getBytes(StandardCharsets.UTF_8);

    private Http3Server server;
    private EventLoopGroup clientGroup;
    private int port;

    @BeforeEach
    void setUp() {
        clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    }

    @AfterEach
    void tearDown() {
        clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        server.stop().await(timeSpan(10).seconds());
    }

    /// Control: the same request and handler, with nothing failing, ends with a clean 200 and `FIN`.
    @Test
    void response_withoutFailure_endsWith200AndFin() throws Exception {
        start((_, writer) -> writer.write(HttpStatus.OK, BODY, CommonContentType.TEXT_PLAIN), Option.none());

        var outcome = exchange();

        assertThat(outcome.status()).isEqualTo("200");
        assertThat(outcome.end()).isEqualTo("fin");
    }

    @Test
    void failureBeforeTheHeadersAreCommitted_isAnswered500_promptly() throws Exception {
        start((_, writer) -> writer.write(HttpStatus.OK, BODY, THROWING_CONTENT_TYPE), Option.none());

        var outcome = exchange();

        assertThat(outcome.status()).as("the uncommitted response is replaced by a 500").isEqualTo("500");
        assertThat(outcome.end()).isEqualTo("fin");
        assertThat(outcome.elapsedMs()).as("answered promptly, not at the idle timeout").isLessThan(PROMPT_MS);
    }

    @Test
    void failureAfterTheHeadersAreCommitted_resetsTheStream_promptly() throws Exception {
        start((_, writer) -> writer.write(HttpStatus.OK, BODY, CommonContentType.TEXT_PLAIN),
              Option.some(() -> new FailingWrites(Http3DataFrame.class)));

        var outcome = exchange();

        assertThat(outcome.end()).as("a committed response that cannot finish is reset")
                                 .isEqualTo("reset:" + Http3ErrorCode.H3_INTERNAL_ERROR.code());
        assertThat(outcome.elapsedMs()).as("reset promptly, not at the idle timeout").isLessThan(PROMPT_MS);
    }

    /// A failed HEADERS write (QPACK encoding, or the transport) commits nothing the client can use, and the body must
    /// not follow it: the stream is reset.
    @Test
    void failedHeadersWrite_resetsTheStream_promptly() throws Exception {
        start((_, writer) -> writer.write(HttpStatus.OK, BODY, CommonContentType.TEXT_PLAIN),
              Option.some(() -> new FailingWrites(Http3HeadersFrame.class)));

        var outcome = exchange();

        assertThat(outcome.end()).isEqualTo("reset:" + Http3ErrorCode.H3_INTERNAL_ERROR.code());
        assertThat(outcome.elapsedMs()).isLessThan(PROMPT_MS);
    }

    /// The promise `writeAsync` returns tells the handler what the client saw: a response replaced by the 500 fails it
    /// with the build failure, while the client still gets the 500.
    @Test
    void writeAsyncPromise_failsWhenTheResponseIsAnswered500() throws Exception {
        var failed = new CompletableFuture<Boolean>();

        start((_, writer) -> writer.writeAsync(HttpStatus.OK, BODY, THROWING_CONTENT_TYPE)
                                   .onResult(result -> failed.complete(result.isFailure())),
              Option.none());

        var outcome = exchange();

        assertThat(outcome.status()).isEqualTo("500");
        assertThat(failed.get(5, TimeUnit.SECONDS)).as("the write promise failed").isTrue();
    }

    /// Control for the two promise tests: a clean response resolves the promise successfully, once the `FIN` is sent.
    @Test
    void writeAsyncPromise_succeeds_whenTheResponseIsSentCleanly() throws Exception {
        var succeeded = new CompletableFuture<Boolean>();

        start((_, writer) -> writer.writeAsync(HttpStatus.OK, BODY, CommonContentType.TEXT_PLAIN)
                                   .onResult(result -> succeeded.complete(result.isSuccess())),
              Option.none());

        var outcome = exchange();

        assertThat(outcome.end()).isEqualTo("fin");
        assertThat(succeeded.get(5, TimeUnit.SECONDS)).as("the write promise succeeded").isTrue();
    }

    @Test
    void writeAsyncPromise_fails_whenTheStreamIsReset() throws Exception {
        var failed = new CompletableFuture<Boolean>();

        start((_, writer) -> writer.writeAsync(HttpStatus.OK, BODY, CommonContentType.TEXT_PLAIN)
                                   .onResult(result -> failed.complete(result.isFailure())),
              Option.some(() -> new FailingWrites(Http3DataFrame.class)));

        var outcome = exchange();

        assertThat(outcome.end()).isEqualTo("reset:" + Http3ErrorCode.H3_INTERNAL_ERROR.code());
        assertThat(failed.get(5, TimeUnit.SECONDS)).as("the write promise failed").isTrue();
    }

    /// One response per request: a second write, and an error thrown after the handler has responded, add no frames.
    @Test
    void secondWriteAndALateHandlerError_sendNoSecondResponse() throws Exception {
        start((_, writer) -> respondTwiceThenFail(writer), Option.none());

        var outcome = exchange();

        assertThat(outcome.status()).isEqualTo("200");
        assertThat(outcome.headersFrames()).as("exactly one HEADERS frame").isEqualTo(1);
        assertThat(outcome.end()).isEqualTo("fin");
    }

    private static void respondTwiceThenFail(ResponseWriter writer) {
        writer.write(HttpStatus.OK, BODY, CommonContentType.TEXT_PLAIN);
        writer.write(HttpStatus.CREATED, BODY, CommonContentType.TEXT_PLAIN);
        throw new IllegalStateException("handler fails after responding");
    }

    /// The 500 that replaces an unbuildable response carries the server's own headers only: a handler header the codec
    /// refuses (a null value) would fail the 500's own build too, and the stream would be left unanswered.
    @Test
    void failureBuildingAHandlerHeader_isAnswered500WithoutThatHeader() throws Exception {
        start((_, writer) -> writer.header("x-null-valued", null)
                                   .write(HttpStatus.OK, BODY, CommonContentType.TEXT_PLAIN),
              Option.none());

        var outcome = exchange();

        assertThat(outcome.status()).isEqualTo("500");
        assertThat(outcome.end()).isEqualTo("fin");
        assertThat(outcome.elapsedMs()).isLessThan(PROMPT_MS);
    }

    /// When the HEADERS write and the DATA write both fail, the stream is reset, and the reset is logged, once.
    @Test
    void resetOfAStreamWhoseEveryWriteFailed_isLoggedOnce() throws Exception {
        try (var capture = LogCapture.capture(Http3Server.class, Level.WARN, false)) {
            start((_, writer) -> writer.write(HttpStatus.OK, BODY, CommonContentType.TEXT_PLAIN),
                  Option.some(() -> new FailingWrites(Http3Frame.class)));

            var outcome = exchange();

            assertThat(outcome.end()).isEqualTo("reset:" + Http3ErrorCode.H3_INTERNAL_ERROR.code());
            assertThat(capture.messages(Level.WARN)).filteredOn(message -> message.contains("resetting the stream"))
                                                    .hasSize(1);
        }
    }

    private static final ContentType THROWING_CONTENT_TYPE = new ContentType() {
        @Override
        public String headerText() {
            throw new IllegalStateException("content type cannot be rendered");
        }

        @Override
        public ContentCategory category() {
            return ContentCategory.TEXT;
        }
    };

    /// Fails every write of one frame type the way a transport failure would; other frames pass.
    private static final class FailingWrites extends ChannelOutboundHandlerAdapter {
        private final Class<?> frameType;

        FailingWrites(Class<?> frameType) {
            this.frameType = frameType;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (frameType.isInstance(msg)) {
                ReferenceCountUtil.release(msg);
                promise.setFailure(new IOException("injected " + frameType.getSimpleName() + " write failure"));
            } else {
                super.write(ctx, msg, promise);
            }
        }
    }

    private void start(BiConsumer<HttpRequest, ResponseWriter> handler, Option<Supplier<ChannelHandler>> interceptor) {
        port = freeUdpPort();

        var group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

        server = Http3Server.bind(HttpServerConfig.httpServerConfig("h3-failure", port),
                                  QuicSslContextFactory.createSelfSignedServer(Http3.supportedApplicationProtocols())
                                                       .unwrap(),
                                  handler,
                                  group,
                                  true,
                                  interceptor)
                            .await(timeSpan(10).seconds())
                            .unwrap();
    }

    private record Outcome(String status, String end, long elapsedMs, int headersFrames) {}

    private Outcome exchange() throws Exception {
        var ssl = QuicSslContextBuilder.forClient()
                                       .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                       .applicationProtocols(Http3.supportedApplicationProtocols())
                                       .build();
        var codec = Http3.newQuicClientCodecBuilder()
                         .sslContext(ssl)
                         .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                         .initialMaxData(10_000_000)
                         .initialMaxStreamDataBidirectionalLocal(1_000_000)
                         .build();
        var channel = new Bootstrap().group(clientGroup)
                                     .channel(NioDatagramChannel.class)
                                     .handler(codec)
                                     .bind(0)
                                     .sync()
                                     .channel();

        try {
            var quic = QuicChannel.newBootstrap(channel)
                                  .handler(new Http3ClientConnectionHandler())
                                  .remoteAddress(new InetSocketAddress("127.0.0.1", port))
                                  .connect()
                                  .get(10, TimeUnit.SECONDS);
            var reader = new OutcomeReader();
            var stream = Http3.newRequestStream(quic, reader).sync().getNow();
            var request = new DefaultHttp3HeadersFrame();

            request.headers().method("GET").path("/").authority("127.0.0.1:" + port).scheme("https");

            var sent = System.nanoTime();

            stream.writeAndFlush(request).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);

            var end = reader.end.get(10, TimeUnit.SECONDS);
            var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent);

            quic.close().sync();

            return new Outcome(reader.status.get(), end, elapsedMs, reader.headersFrames.get());
        } finally {
            channel.close().sync();
        }
    }

    /// Records the first response status, the number of HEADERS frames and how the stream ended: `fin`, `reset:<code>`,
    /// or `inactive`.
    private static final class OutcomeReader extends Http3RequestStreamInboundHandler {
        private final AtomicReference<String> status = new AtomicReference<>("none");
        private final AtomicInteger headersFrames = new AtomicInteger();
        private final CompletableFuture<String> end = new CompletableFuture<>();

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
            headersFrames.incrementAndGet();
            status.compareAndSet("none", String.valueOf(frame.headers().status()));
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
            ReferenceCountUtil.release(frame);
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
            end.complete("fin");
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            end.complete(cause instanceof QuicStreamResetException reset
                         ? "reset:" + reset.applicationProtocolCode()
                         : "error:" + cause.getClass().getSimpleName());
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            end.complete("inactive");
            ctx.fireChannelInactive();
        }
    }

    private static int freeUdpPort() {
        try (var socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
