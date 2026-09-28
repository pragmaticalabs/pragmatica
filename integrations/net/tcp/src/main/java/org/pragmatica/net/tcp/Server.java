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
package org.pragmatica.net.tcp;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Promise.promise;
import static org.pragmatica.lang.Promise.resolved;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Convenient wrapper for Netty server setup boilerplate.
/// Supports TLS, socket options, client connections, and optional UDP binding reusing the same worker group.
public interface Server {
    Logger log = LoggerFactory.getLogger(Server.class);
    String name();
    int port();
    /// Get the boss EventLoopGroup for metrics collection.
    EventLoopGroup bossGroup();
    /// Get the worker EventLoopGroup for metrics collection.
    EventLoopGroup workerGroup();
    /// Get the optional UDP channel, if UDP was configured and bound.
    Option<Channel> udpChannel();

    Promise<Channel> connectTo(NodeAddress peerLocation);

    /// Shutdown the server.
    ///
    /// @param intermediateOperation Operation to run between server channel shutdown and event loop groups shutdown.
    ///                              Enables graceful shutdown: stop accepting new connections, finish existing work, then shutdown.
    ///
    /// @return resolves once both event loop groups have terminated, with the intermediate operation's outcome. It may
    ///         FAIL with the group shutdown's cause (a failed or timed-out termination); a caller in a shutdown sequence
    ///         must not abort on it (#1610).
    Promise<Unit> stop(Supplier<Promise<Unit>> intermediateOperation);

    /// Create a server with the given configuration (TCP only).
    ///
    /// @param config          server configuration
    /// @param channelHandlers supplier for channel handlers (called for each new connection)
    ///
    /// @return promise of the running server
    static Promise<Server> server(ServerConfig config, Supplier<List<ChannelHandler>> channelHandlers) {
        return server(config, channelHandlers, Option.empty());
    }

    /// Create a server with TCP and UDP support.
    ///
    /// @param config          server configuration
    /// @param channelHandlers supplier for TCP channel handlers (called for each new connection)
    /// @param udpHandlers     supplier for UDP channel handlers
    ///
    /// @return promise of the running server
    static Promise<Server> server(ServerConfig config,
                                  Supplier<List<ChannelHandler>> channelHandlers,
                                  Supplier<List<ChannelHandler>> udpHandlers) {
        return server(config, channelHandlers, Option.some(udpHandlers));
    }

    private static Promise<Server> server(ServerConfig config,
                                          Supplier<List<ChannelHandler>> channelHandlers,
                                          Option<Supplier<List<ChannelHandler>>> udpHandlers) {
        record server(String name,
                      int port,
                      EventLoopGroup bossGroup,
                      EventLoopGroup workerGroup,
                      Channel serverChannel,
                      Option<Channel> udpChannel,
                      Supplier<List<ChannelHandler>> channelHandlers,
                      Option<SslContext> clientSslContext) implements Server {
            /// #1610: every step is a DEPENDENT continuation of the one before it, so the returned promise
            /// resolves only once both event loop groups have TERMINATED — and with them every channel,
            /// including the UDP one — rather than when their shutdown was merely requested. Before, the
            /// group shutdown and the caller's resolution were two independent `onResult` actions with no
            /// mutual order, and `shutdownGracefully()` only initiates, so `stop()` could resolve while the
            /// ports were still bound. The intermediate operation's outcome is what `stop()` reports; the
            /// groups are shut down whatever it was.
            @Override
            public Promise<Unit> stop(Supplier<Promise<Unit>> intermediate) {
                log.trace("Stopping {}: closing server channel", name());

                return Promise.all(completion(serverChannel.close()),
                                   udpClosed())
                              .id()
                              .fold(_ -> intermediate.get())
                              .fold(this::shutdownGroupsThen);
            }

            private Promise<Unit> udpClosed() {
                return udpChannel.map(channel -> completion(channel.close()))
                                 .or(Promise.unitPromise());
            }

            private Promise<Unit> shutdownGroupsThen(Result<Unit> intermediateOutcome) {
                return shutdownGroups().fold(groupsOutcome -> resolved(intermediateOutcome.flatMap(_ -> groupsOutcome)));
            }

            private Promise<Unit> shutdownGroups() {
                log.debug("Stopping {}: shutting down boss and worker groups", name());

                return Promise.all(terminated(bossGroup),
                                   terminated(workerGroup))
                              .id()
                              .mapToUnit()
                              .onSuccessRun(() -> log.info("Server {} stopped",
                                                           name()))
                              .onFailure(cause -> log.warn("Server {} did not stop cleanly: {}",
                                                           name(),
                                                           cause.message()));
            }

            @Override
            public Promise<Channel> connectTo(NodeAddress address) {
                var bootstrap = new Bootstrap().group(workerGroup)
                                               .channel(NioSocketChannel.class)
                                               .option(ChannelOption.TCP_NODELAY, true)
                                               .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                                               .handler(createTcpChildHandler(channelHandlers, clientSslContext));
                var promise = Promise.<Channel> promise();

                bootstrap.connect(address.host(),
                                  address.port())
                         .addListener((ChannelFutureListener) future -> {
                                          if (future.isSuccess()) {
                                          promise.succeed(future.channel());
                                      } else {
                                          promise.fail(Causes.fromThrowable(future.cause()));
                                      }
                                      });

                return promise;
            }
        }
        // Handle TLS configuration for server (incoming connections)
        var sslContext = config.tls().await().flatMap(TlsContextFactory::createServer).option();
        // Handle TLS configuration for client (outgoing connections)
        var clientSslContext = config.clientTls().await().flatMap(TlsContextFactory::createClient).option();
        var bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        var workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        var socketOptions = config.socketOptions();
        var bootstrap = new ServerBootstrap().group(bossGroup, workerGroup)
                                             .channel(NioServerSocketChannel.class)
                                             .handler(new LoggingHandler(LogLevel.TRACE))
                                             .childHandler(createTcpChildHandler(channelHandlers, sslContext))
                                             .option(ChannelOption.SO_BACKLOG,
                                                     socketOptions.soBacklog())
                                             .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                                             .childOption(ChannelOption.SO_KEEPALIVE,
                                                          socketOptions.soKeepalive())
                                             .childOption(ChannelOption.TCP_NODELAY,
                                                          socketOptions.tcpNoDelay())
                                             .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        var promise = Promise.<Server> promise();

        bootstrap.bind(config.port())
                 .addListener((ChannelFutureListener) future -> {
                                  if (future.isSuccess()) {
                                  logTcpStarted(config, sslContext);
                                  var tcpChannel = future.channel();

                                  bindUdpIfConfigured(config,
                                                      workerGroup,
                                                      udpHandlers,
                                                      udpChannel -> promise.succeed(new server(config.name(),
                                                                                               config.port(),
                                                                                               bossGroup,
                                                                                               workerGroup,
                                                                                               tcpChannel,
                                                                                               udpChannel,
                                                                                               channelHandlers,
                                                                                               clientSslContext)));
                              } else {
                                  bossGroup.shutdownGracefully();
                                  workerGroup.shutdownGracefully();
                                  promise.fail(Causes.fromThrowable(future.cause()));
                              }
                              });

        return promise;
    }

    /// No quiet period (#1610, as #929 did for SWIM): the caller's intermediate operation is the drain, and a
    /// server with live peers may never go quiet, so Netty's default 2 s quiet period would stretch every stop
    /// towards its timeout. The timeout is enforced on the event loop itself, so a wedged loop cannot enforce
    /// it; the caller-side [Promise#timeout] bounds the wait regardless.
    long SHUTDOWN_TIMEOUT_MS = 5_000L;

    private static Promise<Unit> terminated(EventLoopGroup group) {
        return completion(group.shutdownGracefully(0, SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)).timeout(timeSpan(SHUTDOWN_TIMEOUT_MS + 1_000L).millis());
    }

    /// A Netty future as a promise: success, or its failure cause.
    private static Promise<Unit> completion(Future<?> future) {
        return promise(settled -> future.addListener(done -> settled.resolve(outcomeOf(done))));
    }

    private static Result<Unit> outcomeOf(Future<?> done) {
        return done.isSuccess()
               ? Result.unitResult()
               : Causes.fromThrowable(done.cause()).result();
    }

    private static void logTcpStarted(ServerConfig config, Option<SslContext> sslContext) {
        var protocol = sslContext.map(_ -> "TLS").or("TCP");

        log.info("Server {} started on port {} ({})", config.name(), config.port(), protocol);
    }

    private static void bindUdpIfConfigured(ServerConfig config,
                                            EventLoopGroup workerGroup,
                                            Option<Supplier<List<ChannelHandler>>> udpHandlers,
                                            Consumer<Option<Channel>> callback) {
        config.udpPort()
              .flatMap(port -> udpHandlers.map(handlers -> new UdpBindParams(port, handlers)))
              .apply(() -> callback.accept(Option.empty()),
                     params -> bindUdp(config, workerGroup, params, callback));
    }

    private static void bindUdp(ServerConfig config,
                                EventLoopGroup workerGroup,
                                UdpBindParams params,
                                Consumer<Option<Channel>> callback) {
        var udpBootstrap = new Bootstrap().group(workerGroup)
                                          .channel(NioDatagramChannel.class)
                                          .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                                          .handler(createUdpHandler(params.handlers()));

        udpBootstrap.bind(params.port())
                    .addListener((ChannelFutureListener) udpFuture -> handleUdpBindResult(config,
                                                                                          params,
                                                                                          udpFuture,
                                                                                          callback));
    }

    private static void handleUdpBindResult(ServerConfig config,
                                            UdpBindParams params,
                                            ChannelFuture udpFuture,
                                            Consumer<Option<Channel>> callback) {
        if (udpFuture.isSuccess()) {
            log.info("Server {} UDP bound on port {}", config.name(), params.port());
            callback.accept(Option.some(udpFuture.channel()));
        } else {
            log.warn("Server {} failed to bind UDP port {}: {}",
                     config.name(),
                     params.port(),
                     udpFuture.cause().getMessage());
            callback.accept(Option.empty());
        }
    }

    private static ChannelInitializer<SocketChannel> createTcpChildHandler(Supplier<List<ChannelHandler>> channelHandlers,
                                                                           Option<SslContext> sslContext) {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                var pipeline = ch.pipeline();

                sslContext.onPresent(ctx -> pipeline.addLast(ctx.newHandler(ch.alloc())));
                for (var handler : channelHandlers.get()) {
                    pipeline.addLast(handler);
                }
            }
        };
    }

    private static ChannelInitializer<DatagramChannel> createUdpHandler(Supplier<List<ChannelHandler>> udpHandlers) {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(DatagramChannel ch) {
                var pipeline = ch.pipeline();

                for (var handler : udpHandlers.get()) {
                    pipeline.addLast(handler);
                }
            }
        };
    }

    record UdpBindParams(int port, Supplier<List<ChannelHandler>> handlers) {}

    /// Create a server with the simple configuration.
    ///
    /// @param name            server name for logging
    /// @param port            port to bind to
    /// @param channelHandlers supplier for channel handlers
    ///
    /// @return promise of the running server
    static Promise<Server> server(String name, int port, Supplier<List<ChannelHandler>> channelHandlers) {
        return server(ServerConfig.serverConfig(name, port), channelHandlers);
    }
}
