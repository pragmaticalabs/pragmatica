/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pragmatica.postgres.net.netty;

import org.pragmatica.postgres.PgProtocolStream;
import org.pragmatica.postgres.SqlError;
import org.pragmatica.postgres.message.Message;
import org.pragmatica.postgres.message.backend.SslHandshake;
import org.pragmatica.postgres.message.frontend.SSLRequest;
import org.pragmatica.postgres.message.frontend.StartupMessage;
import org.pragmatica.postgres.message.frontend.Terminate;
import org.pragmatica.postgres.net.SslConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.util.List;

import static org.pragmatica.lang.Unit.unit;

/**
 * Netty messages stream to Postgres backend.
 *
 * @author Antti Laisi
 */
public class NettyPgProtocolStream extends PgProtocolStream {
    private static final Logger log = LoggerFactory.getLogger(NettyPgProtocolStream.class);

    protected final SslConfig sslConfig;
    protected final boolean useSsl;
    private final String hostname;
    private final SocketAddress address;
    private final Bootstrap channelPipeline;
    private StartupMessage startupWith;
    private ChannelHandlerContext ctx;

    private final GenericFutureListener<Future<? super Object>> outboundErrorListener = written -> {
        if (!written.isSuccess()) {
            gotError(SqlError.fromThrowable(written.cause()));
        }
    };

    public NettyPgProtocolStream(SocketAddress address, String hostname, SslConfig sslConfig,
                                  Charset encoding, EventLoopGroup eventLoopGroup) {
        super(encoding);
        this.address = address;
        this.hostname = hostname;
        this.sslConfig = sslConfig;
        this.useSsl = sslConfig.mode() != SslConfig.SslMode.DISABLE;
        if (sslConfig.mode() == SslConfig.SslMode.ALLOW) {
            // libpq's ALLOW is "plain first, retry with TLS on failure" — a connection-level
            // retry. Pragmatic interpretation here: treat as PREFER (TLS first, fall back to
            // plain). Both produce a working connection against either server config; the
            // strict ALLOW semantics matter only if a network observer can be reasoned about.
            log.warn("SslMode.ALLOW currently behaves as PREFER (TLS first, plain fallback); "
                      + "true ALLOW (plain first, TLS retry) is tracked as follow-up");
        }
        this.channelPipeline = new Bootstrap()
            .group(eventLoopGroup)
            .channel(NioSocketChannel.class)
            .handler(newProtocolInitializer());
    }

    @Override
    public Promise<Message> connect(StartupMessage startup) {
        startupWith = startup;
        return offerRoundTrip(new ActiveQuery.SingleMessage(),
                              () -> channelPipeline.connect(address).addListener(outboundErrorListener), false)
            .flatMap(this::send)
            .flatMap(message -> connectSslOrDirect(message, startup));
    }

    private Promise<Message> connectSslOrDirect(Message message, StartupMessage startup) {
        if (message == SslHandshake.INSTANCE) {
            return send(startup);
        } else {
            return Promise.success(message);
        }
    }

    @Override
    public boolean isConnected() {
        return ctx.channel().isOpen();
    }

    @Override
    public Promise<Unit> close() {
        var uponClose = Promise.<Unit>promise();

        ctx.writeAndFlush(Terminate.INSTANCE)
           .addListener(written -> handleWriteResult(written, uponClose));
        return uponClose;
    }

    private void handleWriteResult(io.netty.util.concurrent.Future<? super Void> written, Promise<Unit> uponClose) {
        if (written.isSuccess()) {
            ctx.close()
               .addListener(closed -> handleCloseResult(closed, uponClose));
        } else {
            uponClose.fail(SqlError.fromThrowable(written.cause()));
        }
    }

    private static void handleCloseResult(io.netty.util.concurrent.Future<? super Void> closed, Promise<Unit> uponClose) {
        if (closed.isSuccess()) {
            uponClose.succeed(unit());
        } else {
            uponClose.fail(SqlError.fromThrowable(closed.cause()));
        }
    }

    @Override
    protected void write(Message... messages) {
        for (Message message : messages) {
            ctx.write(message)
               .addListener(outboundErrorListener);
        }
        ctx.flush();
    }

    private ChannelInitializer<Channel> newProtocolInitializer() {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel channel) {
                var pipeline = channel.pipeline();

                if (useSsl) {
                    pipeline.addLast(newSslInitiator());
                }
                pipeline
                    .addLast(new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 1, 4, -4, 0, true))
                    .addLast(new NettyMessageDecoder(encoding))
                    .addLast(new NettyMessageEncoder(encoding))
                    .addLast(newProtocolHandler());
            }
        };
    }

    private ChannelHandler newSslInitiator() {
        return new ByteToMessageDecoder() {
            @Override
            protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
                if (in.readableBytes() >= 1) {
                    var response = in.readByte();
                    ctx.pipeline().remove(this);
                    if ('S' == response) {
                        installSslHandler(ctx);
                    } else if (allowPlainFallback()) {
                        // PREFER/ALLOW: server doesn't support TLS — fall back to plain text.
                        // Reuse the SslHandshake sentinel to signal "negotiation done, proceed
                        // to send StartupMessage" via connectSslOrDirect.
                        log.warn("Postgres server does not support TLS; falling back to plain text per SslMode.{}",
                                  sslConfig.mode());
                        gotMessage(SslHandshake.INSTANCE);
                    } else {
                        ctx.fireExceptionCaught(new IllegalStateException(
                            "SSL required (SslMode." + sslConfig.mode() + ") but not supported by Postgres server"));
                    }
                }
            }
        };
    }

    private boolean allowPlainFallback() {
        return sslConfig.mode() == SslConfig.SslMode.PREFER
               || sslConfig.mode() == SslConfig.SslMode.ALLOW;
    }

    private void installSslHandler(ChannelHandlerContext ctx) throws Exception {
        var port = address instanceof InetSocketAddress isa ? isa.getPort() : -1;
        var sslHandler = buildSslContext().newHandler(ctx.alloc(), hostname, port);
        if (sslConfig.mode() == SslConfig.SslMode.VERIFY_FULL) {
            // Enable JDK hostname verification — without this, Netty's default
            // SslHandler does not verify the hostname even when given hostname/port.
            var sslEngine = sslHandler.engine();
            var params = sslEngine.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            sslEngine.setSSLParameters(params);
        }
        ctx.pipeline().addFirst(sslHandler);
    }

    private SslContext buildSslContext() throws Exception {
        var builder = SslContextBuilder.forClient();

        if (sslConfig.allowAllCertificates()) {
            log.warn("*** INSECURE TLS: PostgreSQL client trusts ALL certificates (allowAllCertificates=true) ***");
            builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
        } else {
            sslConfig.rootCertPem().onPresent(path -> builder.trustManager(path.toFile()));
        }

        sslConfig.clientCertificate().onPresent(cc -> {
            if (cc.password().isPresent()) {
                builder.keyManager(cc.cert().toFile(), cc.key().toFile(), cc.password().or((String) null));
            } else {
                builder.keyManager(cc.cert().toFile(), cc.key().toFile());
            }
        });

        sslConfig.sslContextCustomizer().onPresent(c -> c.accept(builder));

        return builder.build();
    }

    private ChannelHandler newProtocolHandler() {
        return new ChannelInboundHandlerAdapter() {

            @Override
            public void channelActive(ChannelHandlerContext context) {
                NettyPgProtocolStream.this.ctx = context;
                if (useSsl) {
                    gotMessage(SSLRequest.INSTANCE);
                } else {
                    gotMessage(startupWith);
                }
            }

            @Override
            public void userEventTriggered(ChannelHandlerContext context, Object evt) {
                if (evt instanceof SslHandshakeCompletionEvent handshake && handshake.isSuccess()) {
                    gotMessage(SslHandshake.INSTANCE);
                }
            }

            @Override
            public void channelRead(ChannelHandlerContext context, Object message) {
                if (message instanceof Message msg) {
                    gotMessage(msg);
                }
            }

            @Override
            public void channelInactive(ChannelHandlerContext context) {
                gotError(SqlError.fromThrowable(new IOException("Channel state changed to inactive")));
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
                gotError(SqlError.fromThrowable(cause));
            }
        };
    }
}
