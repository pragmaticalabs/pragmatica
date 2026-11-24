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

import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.postgres.PgProtocolStream;
import org.pragmatica.postgres.SqlError;
import org.pragmatica.postgres.message.Message;
import org.pragmatica.postgres.message.backend.SslHandshake;
import org.pragmatica.postgres.message.frontend.SSLRequest;
import org.pragmatica.postgres.message.frontend.StartupMessage;
import org.pragmatica.postgres.message.frontend.Terminate;
import org.pragmatica.postgres.net.SslConfig;
import org.pragmatica.postgres.sasl.ChannelBindingDigest;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.postgres.net.ReleaseChain.releaseAll;


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
    private volatile ChannelHandlerContext ctx;
    private volatile Channel channel;
    /// Runs exactly once, when this stream's channel is closed by any path (local close, remote close, failed connect).
    private final Runnable onChannelClosed;
    private final AtomicBoolean channelReleased = new AtomicBoolean();

    private final GenericFutureListener<Future<? super Object>> outboundErrorListener = written -> {
        if (!written.isSuccess()) {
            gotError(SqlError.fromThrowable(written.cause()));
        }
    };

    public NettyPgProtocolStream(SocketAddress address,
                                 String hostname,
                                 SslConfig sslConfig,
                                 Charset encoding,
                                 EventLoopGroup eventLoopGroup,
                                 Runnable onChannelClosed) {
        this(address, hostname, sslConfig, encoding, eventLoopGroup, onChannelClosed, NioSocketChannel::new);
    }

    /// `channelFactory` is the test seam for a channel that cannot be created (#3635).
    NettyPgProtocolStream(SocketAddress address,
                          String hostname,
                          SslConfig sslConfig,
                          Charset encoding,
                          EventLoopGroup eventLoopGroup,
                          Runnable onChannelClosed,
                          ChannelFactory<? extends Channel> channelFactory) {
        super(encoding);
        this.onChannelClosed = onChannelClosed;
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

        this.channelPipeline = new Bootstrap().group(eventLoopGroup)
                                              .channelFactory(channelFactory)
                                              .handler(newProtocolInitializer());
    }

    @Override
    public Promise<Message> connect(StartupMessage startup) {
        startupWith = startup;

        return offerRoundTrip(new ActiveQuery.SingleMessage(),
                              this::connectChannel,
                              false).flatMap(this::send)
                             .flatMap(message -> connectSslOrDirect(message, startup));
    }

    private io.netty.channel.ChannelFuture connectChannel() {
        var connecting = channelPipeline.connect(address);

        channel = connecting.channel();
        channel.closeFuture().addListener(_ -> releaseChannel());
        connecting.addListener(outboundErrorListener);
        connecting.addListener(this::releaseOnFailedConnect);

        return connecting;
    }

    /// A failed connect ends the channel, but its closeFuture may never complete then: Netty 4.2.18
    /// AbstractBootstrap.initAndRegister answers a failed newChannel() with a FailedChannel and a failed init() or
    /// register() with closeForcibly(), and neither completes closeFuture (only AbstractChannel.register's rejected-task
    /// path and register0 call closeFuture.setClosed()). A channel left open by a failed connect is still registered, so
    /// the group's shutdown closes it.
    private void releaseOnFailedConnect(Future<? super Void> connected) {
        if (!connected.isSuccess()) {
            releaseChannel();
        }
    }

    /// The first of the channel's terminal signals (its close, a failed connect) runs `onChannelClosed`; later ones do not.
    private void releaseChannel() {
        if (channelReleased.compareAndSet(false, true)) {
            onChannelClosed.run();
        }
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
        return ctx.channel()
                  .isOpen();
    }

    /// Terminate, then close the channel on either outcome of that write (ReleaseChain invariant, #3635); resolves with the
    /// first failure. A stream whose channel never became active has no session to terminate: only its channel is closed.
    @Override
    public Promise<Unit> close() {
        if (ctx == null) {
            return closeUnconnected();
        }

        return releaseAll(() -> settled(ctx.writeAndFlush(Terminate.INSTANCE)), () -> settled(ctx.close()));
    }

    private Promise<Unit> closeUnconnected() {
        var unconnected = channel;

        return unconnected == null
               ? Promise.success(unit())
               : settled(unconnected.close());
    }

    private static Promise<Unit> settled(Future<? super Void> operation) {
        var settled = Promise.<Unit> promise();

        operation.addListener(done -> settle(done, settled));

        return settled;
    }

    private static void settle(Future<? super Void> done, Promise<Unit> settled) {
        if (done.isSuccess()) {
            settled.succeed(unit());
        } else {
            settled.fail(SqlError.fromThrowable(done.cause()));
        }
    }

    /// The channel of this stream, or null before `connect` (test access).
    Channel channel() {
        return channel;
    }

    @Override
    protected void write(Message... messages) {
        for (Message message : messages) {
            ctx.write(message).addListener(outboundErrorListener);
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

                pipeline.addLast(new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 1, 4, -4, 0, true))
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

                    ctx.pipeline()
                       .remove(this);
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
                        ctx.fireExceptionCaught(new IllegalStateException("SSL required (SslMode." + sslConfig.mode()
                                                                         + ") but not supported by Postgres server"));
                    }
                }
            }
        };
    }

    private boolean allowPlainFallback() {
        return sslConfig.mode() == SslConfig.SslMode.PREFER || sslConfig.mode() == SslConfig.SslMode.ALLOW;
    }

    @Override
    protected SslConfig.ChannelBinding channelBindingPolicy() {
        return sslConfig.channelBinding();
    }

    @Override
    protected Option<byte[]> tlsServerEndPointDigest() {
        if (ctx == null) {
            return Option.none();
        }

        var sslHandler = ctx.pipeline().get(SslHandler.class);

        if (sslHandler == null) {
            return Option.none();
        }

        try {
            var peerCerts = sslHandler.engine().getSession().getPeerCertificates();

            if (peerCerts.length == 0 || !(peerCerts[0] instanceof X509Certificate x509)) {
                return Option.none();
            }

            return Option.some(ChannelBindingDigest.tlsServerEndPoint(x509));
        } catch (javax.net.ssl.SSLPeerUnverifiedException e) {
            log.debug("Cannot extract server certificate for channel binding: {}", e.getMessage());

            return Option.none();
        }
    }

    private void installSslHandler(ChannelHandlerContext ctx) throws Exception {
        var port = address instanceof InetSocketAddress isa
                   ? isa.getPort()
                   : -1;
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

        sslConfig.clientCertificate()
                 .onPresent(cc -> {
                                if (cc.password()
                                      .isPresent()) {
                                    builder.keyManager(cc.cert().toFile(),
                                                       cc.key().toFile(),
                                                       cc.password().or((String) null));
                                } else {
                                    builder.keyManager(cc.cert().toFile(),
                                                       cc.key().toFile());
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
