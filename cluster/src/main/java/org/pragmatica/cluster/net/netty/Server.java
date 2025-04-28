package org.pragmatica.cluster.net.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.pragmatica.cluster.net.NodeAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * Convenient wrapper for Netty server setup boilerplate. It also enables connecting to other servers as client,
 * reusing the same channel handlers.
 */
public interface Server {
    int port();

    String name();

    Channel connectTo(NodeAddress peerLocation);

    /**
     * Shutdown the server
     *
     * @param intermediateOperation Operation to run between server channel shutdown and event loop groups shutdown
     */
    void stop(InterruptibleRunnable intermediateOperation);

    static Server create(String name, int port, Supplier<List<ChannelHandler>> channelHandlers) {
        record server(String name, int port, EventLoopGroup bossGroup, EventLoopGroup workerGroup,
                      Channel serverChannel,
                      Supplier<List<ChannelHandler>> channelHandlers) implements Server {
            private static final Logger logger = LoggerFactory.getLogger(Server.class);

            @Override
            public void stop(InterruptibleRunnable intermediateOperation) {
                try {
                    logger.info("Stopping {}: closing server channel", name());
                    serverChannel.close().sync();

                    intermediateOperation.run();

                    logger.info("Stopping {}: shutting down boss group", name());
                    bossGroup.shutdownGracefully();

                    logger.info("Stopping {}: shutting down worker group", name());
                    workerGroup.shutdownGracefully();
                } catch (InterruptedException e) {
                    logger.info("Stopping {}: exception caught:", name(), e);
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Failed to stop " + name(), e);
                }
            }

            @Override
            public Channel connectTo(NodeAddress address) {
                try {
                    var bootstrap = new Bootstrap()
                            .group(workerGroup)
                            .channel(NioSocketChannel.class)
                            .handler(server.createChildHandler(channelHandlers));

                    var channel = bootstrap.connect(address.host(),
                                                    address.port())
                                           .sync()
                                           .channel();
                    logger.info("Connected to peer {}", address);
                    return channel;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Failed to connect to " + address, e);
                }
            }

            private static ChannelInitializer<SocketChannel> createChildHandler(Supplier<List<ChannelHandler>> channelHandlers) {
                return new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        var pipeline = ch.pipeline();
                        for (var handler : channelHandlers.get()) {
                            pipeline.addLast(handler);
                        }
                    }
                };
            }
        }

        try {
            var bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
            var workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
            var bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.TRACE))
                    .childHandler(server.createChildHandler(channelHandlers));
            var serverChannel = bootstrap.bind(port).sync().channel();

            server.logger.info("{} server started on port {}", name, port);
            return new server(name, port, bossGroup, workerGroup, serverChannel, channelHandlers);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to start server " + name + " at port " + port, e);
        }
    }

    @FunctionalInterface
    interface InterruptibleRunnable {
        void run() throws InterruptedException;
    }
}
