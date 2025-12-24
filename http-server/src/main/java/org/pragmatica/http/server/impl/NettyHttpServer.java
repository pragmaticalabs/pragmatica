package org.pragmatica.http.server.impl;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.http.server.HttpServerError;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;

import static org.pragmatica.lang.Unit.unit;

/**
 * Netty-based HTTP server implementation.
 */
public final class NettyHttpServer implements HttpServer {
    private static final Logger LOG = LoggerFactory.getLogger(NettyHttpServer.class);

    private final HttpServerConfig config;
    private final BiConsumer<RequestContext, ResponseWriter> handler;
    private final Option<SslContext> sslContext;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    private NettyHttpServer(
            HttpServerConfig config,
            BiConsumer<RequestContext, ResponseWriter> handler,
            Option<SslContext> sslContext
    ) {
        this.config = config;
        this.handler = handler;
        this.sslContext = sslContext;
    }

    /**
     * Create a new Netty HTTP server.
     *
     * @param config  server configuration
     * @param handler request handler
     * @return server instance or error
     */
    public static Promise<HttpServer> create(
            HttpServerConfig config,
            BiConsumer<RequestContext, ResponseWriter> handler
    ) {
        Result<Option<SslContext>> sslResult = config.tls()
                .map(TlsContextFactory::create)
                .map(result -> result.map(Option::some))
                .fold(
                        () -> Result.success(Option.empty()),
                        result -> result
                );

        return sslResult
                .map(ssl -> (HttpServer) new NettyHttpServer(config, handler, ssl))
                .async();
    }

    @Override
    public Promise<Unit> start() {
        return Promise.promise(promise -> {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();

            try {
                var bootstrap = new ServerBootstrap()
                        .group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                var pipeline = ch.pipeline();

                                sslContext.onPresent(ssl -> pipeline.addLast(ssl.newHandler(ch.alloc())));

                                pipeline.addLast(new HttpServerCodec());
                                pipeline.addLast(new HttpObjectAggregator(config.maxContentLength()));
                                pipeline.addLast(new HttpRequestHandler(handler));
                            }
                        })
                        .option(ChannelOption.SO_BACKLOG, 128)
                        .childOption(ChannelOption.SO_KEEPALIVE, true);

                var channelFuture = bootstrap.bind(config.port());

                channelFuture.addListener(future -> {
                    if (future.isSuccess()) {
                        serverChannel = channelFuture.channel();
                        var protocol = sslContext.isPresent() ? "HTTPS" : "HTTP";
                        LOG.info("{} server started on port {}", protocol, config.port());
                        promise.succeed(unit());
                    } else {
                        cleanup();
                        promise.fail(new HttpServerError.BindFailed(config.port(), future.cause()));
                    }
                });
            } catch (Exception e) {
                cleanup();
                promise.fail(new HttpServerError.BindFailed(config.port(), e));
            }
        });
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.promise(promise -> {
            LOG.info("Stopping HTTP server on port {}", config.port());

            if (serverChannel != null) {
                serverChannel.close().addListener(future -> {
                    cleanup();
                    promise.succeed(unit());
                });
            } else {
                cleanup();
                promise.succeed(unit());
            }
        });
    }

    @Override
    public int port() {
        return config.port();
    }

    private void cleanup() {
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }
}
