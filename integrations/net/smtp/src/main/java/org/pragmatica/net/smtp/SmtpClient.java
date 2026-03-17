/*
 *  Copyright (c) 2022-2025 Sergiy Yevtushenko.
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

package org.pragmatica.net.smtp;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.AsyncCloseable;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;

/// Asynchronous SMTP client using Netty TCP pipeline.
///
/// Each [#send(SmtpMessage)] call creates a new TCP connection, runs the full SMTP session
/// (EHLO, optional STARTTLS, optional AUTH, MAIL FROM, RCPT TO, DATA, QUIT), and closes.
public interface SmtpClient extends AsyncCloseable {
    Logger log = LoggerFactory.getLogger(SmtpClient.class);

    /// Send an email message, returning a promise that completes with the server response on success.
    Promise<String> send(SmtpMessage message);

    /// Create an SMTP client with its own event loop group.
    /// The event loop will be shut down when the client is closed.
    static SmtpClient smtpClient(SmtpConfig config) {
        var eventLoop = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        return new SmtpClientImpl(config, eventLoop, true);
    }

    /// Create an SMTP client sharing the given event loop group.
    /// The event loop will NOT be shut down when the client is closed.
    static SmtpClient smtpClient(SmtpConfig config, EventLoopGroup eventLoopGroup) {
        return new SmtpClientImpl(config, eventLoopGroup, false);
    }
}

record SmtpClientImpl(SmtpConfig config,
                      EventLoopGroup eventLoopGroup,
                      boolean ownsEventLoop) implements SmtpClient {
    private static final int MAX_LINE_LENGTH = 512;

    @Override
    public Promise<String> send(SmtpMessage message) {
        return Promise.promise(promise -> initiateSend(message, promise));
    }

    @Override
    public Promise<Unit> close() {
        if (!ownsEventLoop) {
            return Promise.success(unit());
        }
        return Promise.promise(promise -> eventLoopGroup.shutdownGracefully()
                                                        .addListener(_ -> promise.succeed(unit())));
    }

    private void initiateSend(SmtpMessage message, Promise<String> promise) {
        var sslContext = buildSslContext();
        var session = new SmtpSession(config, message, promise, sslContext);

        var bootstrap = new Bootstrap().group(eventLoopGroup)
                                       .channel(NioSocketChannel.class)
                                       .handler(SmtpChannelInitializer.forSession(session, config, sslContext));

        connectWithTimeout(bootstrap, session, promise);
    }

    private void connectWithTimeout(Bootstrap bootstrap, SmtpSession session, Promise<String> promise) {
        var address = new InetSocketAddress(config.host(), config.port());
        bootstrap.connect(address)
                 .addListener((ChannelFuture future) -> handleConnect(future, session));

        promise.async(config.commandTimeout(),
                      pending -> pending.fail(new SmtpError.Timeout("SMTP session timed out after " + config.commandTimeout().millis() + "ms")));
    }

    private static void handleConnect(ChannelFuture future, SmtpSession session) {
        if (future.isSuccess()) {
            session.setChannel(future.channel());
            log.debug("Connected to SMTP server");
            return;
        }
        session.onException(future.cause());
    }

    private Option<SslContext> buildSslContext() {
        if (config.tlsMode() == SmtpTlsMode.NONE) {
            return none();
        }
        return buildInsecureSslContext();
    }

    private static Option<SslContext> buildInsecureSslContext() {
        return Result.lift(e -> new SmtpError.TlsFailed(e.getMessage()),
                           () -> SslContextBuilder.forClient()
                                                  .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                                  .build())
                     .onFailure(cause -> log.warn("Failed to build SSL context: {}", cause.message()))
                     .option();
    }
}

class SmtpChannelInitializer extends ChannelInitializer<SocketChannel> {
    private static final int MAX_LINE_LENGTH = 512;

    private final SmtpSession session;
    private final SmtpConfig config;
    private final Option<SslContext> sslContext;

    private SmtpChannelInitializer(SmtpSession session, SmtpConfig config, Option<SslContext> sslContext) {
        this.session = session;
        this.config = config;
        this.sslContext = sslContext;
    }

    static SmtpChannelInitializer forSession(SmtpSession session, SmtpConfig config, Option<SslContext> sslContext) {
        return new SmtpChannelInitializer(session, config, sslContext);
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        var pipeline = ch.pipeline();

        // For IMPLICIT TLS, add SSL handler first
        if (config.tlsMode() == SmtpTlsMode.IMPLICIT) {
            sslContext.onPresent(ctx -> pipeline.addLast("ssl", ctx.newHandler(ch.alloc(), config.host(), config.port())));
        }

        pipeline.addLast("framer", new LineBasedFrameDecoder(MAX_LINE_LENGTH))
                .addLast("decoder", new StringDecoder(StandardCharsets.US_ASCII))
                .addLast("encoder", new StringEncoder(StandardCharsets.US_ASCII))
                .addLast("handler", new SmtpResponseHandler(session));
    }
}
