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
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.io.IOException;
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
    protected final boolean useSsl;
    private final SocketAddress address;
    private final Bootstrap channelPipeline;
    private StartupMessage startupWith;
    private ChannelHandlerContext ctx;

    private final GenericFutureListener<Future<? super Object>> outboundErrorListener = written -> {
        if (!written.isSuccess()) {
            gotError(SqlError.fromThrowable(written.cause()));
        }
    };

    public NettyPgProtocolStream(SocketAddress address, boolean useSsl, Charset encoding, EventLoopGroup eventLoopGroup) {
        super(encoding);
        this.address = address;
        this.useSsl = useSsl; // TODO: refactor into SSLConfig with trust parameters
        this.channelPipeline = new Bootstrap()
            .group(eventLoopGroup)
            .channel(NioSocketChannel.class)
            .handler(newProtocolInitializer());
    }

    @Override
    public Promise<Message> connect(StartupMessage startup) {
        startupWith = startup;
        return offerRoundTrip(() -> channelPipeline.connect(address).addListener(outboundErrorListener), false)
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
                    if ('S' == in.readByte()) { // SSL supported response
                        //TODO: take SSL configuration from config
                        ctx.pipeline().remove(this);
                        ctx.pipeline().addFirst(
                            SslContextBuilder
                                .forClient()
                                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                .build()
                                .newHandler(ctx.alloc()));
                    } else {
                        ctx.fireExceptionCaught(new IllegalStateException("SSL required but not supported by Postgres"));
                    }
                }
            }
        };
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
