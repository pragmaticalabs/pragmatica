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
package org.pragmatica.net.redis;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.utils.Causes;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.net.redis.RedisCommands.command;
import static org.pragmatica.net.redis.RedisCommands.utf8;
import static org.pragmatica.net.redis.RedisError.AuthFailed.authFailed;
import static org.pragmatica.net.redis.RedisError.ConnectionFailed.connectionFailed;
import static org.pragmatica.net.redis.RedisError.ConnectionLost.connectionLost;
import static org.pragmatica.net.redis.RedisError.PoolSaturated.poolSaturated;
import static org.pragmatica.net.redis.RedisError.ProtocolViolation.protocolViolation;
import static org.pragmatica.net.redis.RedisError.Timeout.timeout;


/// One TCP (optionally TLS) connection with pipelining: commands are written as they are issued, and
/// replies are matched to them first-in-first-out.
///
/// A connection is single-use: once it is lost, failed or closed it never reconnects. Reconnection is
/// the pool's job ([RedisClient]), and it happens for the NEXT command: every command in flight when the
/// connection ended fails with a typed [RedisError] and is not retried here or there.
///
/// Guarantees (see also [RedisCommands]):
///
///  - Every accepted command is resolved exactly once: with its reply, or, when the connection ends
///    first, with the connection's terminal failure. Earned by the FIFO queue, which is drained on
///    the channel's `closeFuture`; the queue is touched only on the channel's event loop.
///  - [#close] is idempotent and returns the same promise every time; it resolves after every pending
///    command was failed with [RedisError.General#CLOSED] and the socket was closed.
///  - At most `maxInFlight` commands are unresolved at once; the next one fails with
///    [RedisError.PoolSaturated] without being sent. Earned by an atomic counter taken before the write.
public final class RedisConnection implements RedisCommands {
    private final RedisConfig config;
    private final Option<EventLoopGroup> ownedGroup;
    /// Touched only on the event loop.
    private final Queue<Promise<RespValue>> pending = new ArrayDeque<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicReference<Cause> terminal = new AtomicReference<>();
    private final Promise<Unit> closed = Promise.promise();
    /// Assigned by the channel initializer, which runs on the event loop before the first read.
    private volatile Channel channel;

    private RedisConnection(RedisConfig config, Option<EventLoopGroup> ownedGroup) {
        this.config = config;
        this.ownedGroup = ownedGroup;
    }

    /// Open a connection on a private single-thread event loop that is shut down with the connection.
    /// TLS, when configured, uses the JVM trust store.
    public static Promise<RedisConnection> redisConnection(RedisConfig config) {
        return RedisClient.tlsContext(config)
                          .async()
                          .flatMap(tls -> connectOwning(config, tls));
    }

    /// As [#redisConnection(RedisConfig)] with a caller supplied TLS context (private CA, mutual TLS).
    public static Promise<RedisConnection> redisConnection(RedisConfig config, SslContext tls) {
        return connectOwning(config, some(tls));
    }

    private static Promise<RedisConnection> connectOwning(RedisConfig config, Option<SslContext> tls) {
        var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        return connect(config, group, tls, some(group));
    }

    /// Open a connection on a shared event loop that the caller keeps owning.
    static Promise<RedisConnection> connect(RedisConfig config,
                                            EventLoopGroup group,
                                            Option<SslContext> tls,
                                            Option<EventLoopGroup> ownedGroup) {
        var connection = new RedisConnection(config, ownedGroup);

        return connection.open(group, tls)
                         .flatMap(_ -> connection.handshake()
                                                 .mapError(RedisConnection::asConnectFailure))
                         .onFailure(_ -> connection.close());
    }

    private Promise<Channel> open(EventLoopGroup group, Option<SslContext> tls) {
        var bootstrap = new Bootstrap().group(group)
                                       .channel(NioSocketChannel.class)
                                       .option(ChannelOption.TCP_NODELAY, true)
                                       .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                                               (int) config.connectTimeout().millis())
                                       .handler(new Initializer(tls));

        return Promise.<Channel> promise(promise -> bootstrap.connect(config.host(),
                                                                      config.port())
                                                             .addListener((ChannelFuture future) -> settleConnect(future,
                                                                                                                  promise))).flatMap(ch -> tls.fold(() -> Promise.success(ch),
                                                                                                                                                    _ -> handshaken(ch)));
    }

    /// With TLS, the connection is handed out only after the handshake succeeded, so a refused
    /// certificate or a plaintext server is a typed [RedisError.ConnectionFailed] here, not a failure of
    /// the first command.
    private static Promise<Channel> handshaken(Channel ch) {
        return Promise.promise(promise -> ch.pipeline()
                                            .get(SslHandler.class)
                                            .handshakeFuture()
                                            .addListener(future -> settleHandshake(future, ch, promise)));
    }

    @Contract
    private static void settleHandshake(Future<?> future, Channel ch, Promise<Channel> promise) {
        promise.resolve(future.isSuccess()
                        ? success(ch)
                        : connectionFailed(Causes.fromThrowable(future.cause())).result());
    }

    @Contract
    private static void settleConnect(ChannelFuture future, Promise<Channel> promise) {
        promise.resolve(future.isSuccess()
                        ? success(future.channel())
                        : connectionFailed(Causes.fromThrowable(future.cause())).result());
    }

    /// AUTH then SELECT, each only when configured. They are ordinary commands, so a failure of either
    /// closes the connection and is reported before the connection is handed out.
    private Promise<RedisConnection> handshake() {
        return authenticate().flatMap(_ -> selectDatabase())
                           .map(_ -> this);
    }

    private static Cause asConnectFailure(Cause cause) {
        return cause instanceof RedisError.AuthFailed
               ? cause
               : connectionFailed(cause);
    }

    private Promise<Unit> authenticate() {
        return config.password()
                     .fold(() -> Promise.success(unit()),
                           secret -> execute(authCommand(secret)).mapError(RedisConnection::asAuthFailure)
                                            .flatMap(reply -> reply.asOk()
                                                                   .async()));
    }

    private List<byte[]> authCommand(String secret) {
        return config.username()
                     .fold(() -> command("AUTH",
                                         utf8(secret)),
                           user -> command("AUTH",
                                           utf8(user),
                                           utf8(secret)));
    }

    private static Cause asAuthFailure(Cause cause) {
        return cause instanceof RedisError.ServerError error
               ? authFailed(error.message())
               : cause;
    }

    private Promise<Unit> selectDatabase() {
        return config.database() == 0
               ? Promise.success(unit())
               : execute(command("SELECT",
                                 utf8(Integer.toString(config.database())))).flatMap(reply -> reply.asOk()
                                                                                                   .async());
    }

    @Override
    public Promise<RespValue> execute(List<byte[]> command) {
        return reserveSlot().async()
                          .flatMap(_ -> enqueue(command));
    }

    /// Take one of the `maxInFlight` slots, or fail without sending.
    private Result<Unit> reserveSlot() {
        if (inFlight.incrementAndGet() > config.maxInFlight()) {
            inFlight.decrementAndGet();

            return poolSaturated(config.maxInFlight()).result();
        }

        return success(unit());
    }

    int inFlight() {
        return inFlight.get();
    }

    boolean isActive() {
        return channel != null
               && channel.isActive()
               && terminal.get() == null;
    }

    private Promise<RespValue> enqueue(List<byte[]> command) {
        var reply = Promise.<RespValue> promise();

        runOnEventLoop(() -> send(command, reply)).onFailure(cause -> refuse(reply, cause));

        return reply.timeout(config.commandTimeout())
                    .onFailure(this::closeOnTimeout)
                    .mapError(this::describeTimeout);
    }

    /// Runs on the event loop, which orders the queue entry with the write.
    @Contract
    private void send(List<byte[]> command, Promise<RespValue> reply) {
        if (terminal.get() != null || !channel.isActive()) {
            refuse(reply, terminalCause());

            return;
        }

        pending.add(reply);
        channel.writeAndFlush(RespCodec.encode(channel.alloc(),
                                               command))
               .addListener((ChannelFuture write) -> onWritten(write));
    }

    @Contract
    private void onWritten(ChannelFuture write) {
        if (!write.isSuccess()) {
            fail(connectionLost(Causes.fromThrowable(write.cause())));
        }
    }

    @Contract
    private void refuse(Promise<RespValue> reply, Cause cause) {
        inFlight.decrementAndGet();
        reply.resolve(cause.result());
    }

    private Result<Unit> runOnEventLoop(Runnable task) {
        var loop = channel.eventLoop();

        return Result.lift(thrown -> connectionLost(Causes.fromThrowable(thrown)), () -> executeOn(loop, task));
    }

    private static void executeOn(EventLoop loop, Runnable task) {
        if (loop.inEventLoop()) {
            task.run();
        } else {
            loop.execute(task);
        }
    }

    /// FIFO correlation cannot skip a reply, so a command that timed out ends the connection.
    @Contract
    private void closeOnTimeout(Cause cause) {
        if (cause instanceof CoreError.Timeout) {
            fail(connectionLost(Causes.cause("connection closed after a command timed out")));
        }
    }

    private Cause describeTimeout(Cause cause) {
        return cause instanceof CoreError.Timeout
               ? timeout(config.commandTimeout().millis())
               : cause;
    }

    /// Fail the connection: remember the first cause, then close the socket, which drains the queue.
    @Contract
    private void fail(Cause cause) {
        terminal.compareAndSet(null, cause);
        channel.close();
    }

    private Cause terminalCause() {
        return Option.option(terminal.get()).or(RedisError.General.CLOSED);
    }

    /// Close the connection. Idempotent: every call returns the same promise.
    public Promise<Unit> close() {
        terminal.compareAndSet(null, RedisError.General.CLOSED);
        Option.option(channel).onPresent(Channel::close);
        Option.option(channel).onEmpty(this::release);

        return closed;
    }

    /// The connection is over: stop the private event loop, if any, and resolve [#close]'s promise.
    @Contract
    private void release() {
        ownedGroup.onPresent(EventLoopGroup::shutdownGracefully);
        closed.succeed(unit());
    }

    /// Runs on the event loop once the channel is closed, whether or not it ever became active: every
    /// queued command gets the terminal cause.
    @Contract
    private void drain() {
        terminal.compareAndSet(null, connectionLost(Causes.cause("connection closed by the server")));
        var cause = terminalCause();

        while (!pending.isEmpty()) {
            refuse(pending.poll(), cause);
        }

        release();
    }

    private final class Initializer extends ChannelInitializer<SocketChannel> {
        private final Option<SslContext> tls;

        private Initializer(Option<SslContext> tls) {
            this.tls = tls;
        }

        @Override
        @Contract
        protected void initChannel(SocketChannel ch) {
            channel = ch;
            ch.closeFuture().addListener(_ -> drain());
            tls.onPresent(context -> ch.pipeline()
                                       .addLast("ssl",
                                                context.newHandler(ch.alloc(),
                                                                   config.host(),
                                                                   config.port())));
            ch.pipeline().addLast("decoder", new RespFrameDecoder()).addLast("replies", new ReplyHandler());
        }
    }

    private final class ReplyHandler extends SimpleChannelInboundHandler<RespValue> {
        @Override
        @Contract
        protected void channelRead0(ChannelHandlerContext ctx, RespValue value) {
            Option.option(pending.poll())
                  .onPresent(reply -> complete(reply, value))
                  .onEmpty(() -> fail(protocolViolation("reply without a command in flight")));
        }

        @Override
        @Contract
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
            if (event instanceof Cause cause) {
                terminal.compareAndSet(null, cause);
            }
        }

        @Override
        @Contract
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            fail(connectionLost(Causes.fromThrowable(cause)));
        }
    }

    @Contract
    private void complete(Promise<RespValue> reply, RespValue value) {
        inFlight.decrementAndGet();
        reply.resolve(value.asServerError().fold(() -> success(value), RedisError.ServerError::result));
    }
}
