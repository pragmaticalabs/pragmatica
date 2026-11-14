package org.pragmatica.net.redis;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.net.redis.RedisError.ConnectionFailed.connectionFailed;
import static org.pragmatica.net.redis.RedisError.PoolSaturated.poolSaturated;
import static org.pragmatica.net.redis.RedisError.TlsSetupFailed.tlsSetupFailed;


/// A bounded pool of [RedisConnection]s over one shared event loop group.
///
/// Connections are opened lazily, up to `poolSize`: a new one only when every open connection already
/// has a command in flight. A connection that was lost is dropped from the pool and replaced by the NEXT
/// command; the commands that were in flight on it are not retried (see [RedisCommands] for delivery).
///
/// Guarantees (see also [RedisCommands]):
///
///  - At most `poolSize` connections exist or are being opened at once. Earned by the pool lock, which
///    covers the count and the decision to open.
///  - [#close] is idempotent and returns the same promise every time; it resolves after every
///    connection was closed, including the ones that were still being opened when it was called, and the
///    event loop group terminated. A command issued after close fails with [RedisError.General#CLOSED]
///    without being sent, and no connection is opened after close began. Earned by the `closing` flag,
///    checked under the pool lock before a connection is chosen or opened, and by the list of connections
///    being opened, which close waits for.
///  - There is no ordering between commands: each may take a different connection.
public final class RedisClient implements RedisCommands {
    private final RedisConfig config;
    private final Option<SslContext> tls;
    private final EventLoopGroup group;
    private final Object lock = new Object();
    private final List<RedisConnection> ready = new ArrayList<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Promise<Unit> closed = Promise.promise();
    /// Under the lock: connections being opened right now, which close() must wait for.
    private final List<Promise<RedisConnection>> openings = new ArrayList<>();
    private int opening;
    private Promise<RedisConnection> lastOpening = RedisError.General.CLOSED.promise();

    /// The client owns `group` and shuts it down on close.
    RedisClient(RedisConfig config, Option<SslContext> tls, EventLoopGroup group) {
        this.config = config;
        this.tls = tls;
        this.group = group;
    }

    /// Create a client and prove it can reach the server by opening its first connection. TLS, when
    /// configured, uses the JVM trust store.
    public static Promise<RedisClient> redisClient(RedisConfig config) {
        return tlsContext(config).async()
                         .flatMap(tls -> start(config, tls));
    }

    /// As [#redisClient(RedisConfig)] with a caller supplied TLS context (private CA, mutual TLS).
    public static Promise<RedisClient> redisClient(RedisConfig config, SslContext tls) {
        return start(config, some(tls));
    }

    private static Promise<RedisClient> start(RedisConfig config, Option<SslContext> tls) {
        return Result.lift(thrown -> connectionFailed(Causes.fromThrowable(thrown)),
                           () -> new MultiThreadIoEventLoopGroup(Math.min(config.poolSize(), 4),
                                                                 NioIoHandler.newFactory()))
                     .async()
                     .flatMap(group -> new RedisClient(config, tls, group).open());
    }

    /// Prove the server is reachable by opening the first connection; on failure the client is closed.
    Promise<RedisClient> open() {
        return acquire().map(_ -> this)
                        .onFailure(_ -> close());
    }

    static Result<Option<SslContext>> tlsContext(RedisConfig config) {
        return config.tls()
               ? Result.lift(thrown -> tlsSetupFailed(String.valueOf(thrown.getMessage())),
                             () -> some(SslContextBuilder.forClient().build()))
               : Result.success(none());
    }

    @Override
    public Promise<RespValue> execute(List<byte[]> command) {
        return closing.get()
               ? RedisError.General.CLOSED.promise()
               : acquire().flatMap(connection -> connection.execute(command));
    }

    /// Number of connections that are open right now.
    int openConnections() {
        synchronized (lock) {
            return ready.size();
        }
    }

    private Promise<RedisConnection> acquire() {
        synchronized (lock) {
            if (closing.get()) {
                return RedisError.General.CLOSED.promise();
            }

            ready.removeIf(connection -> !connection.isActive());

            return ready.stream()
                        .min((a, b) -> Integer.compare(a.inFlight(),
                                                       b.inFlight()))
                        .filter(best -> best.inFlight() == 0 || !hasCapacity())
                        .map(Promise::success)
                        .orElseGet(this::openOrWait);
        }
    }

    private boolean hasCapacity() {
        return ready.size() + opening < config.poolSize();
    }

    /// Under the lock: open one more connection when allowed, else share the one being opened.
    private Promise<RedisConnection> openOrWait() {
        if (!hasCapacity()) {
            return ready.isEmpty()
                   ? lastOpening
                   : poolSaturated(config.maxInFlight()).promise();
        }

        opening++;
        lastOpening = RedisConnection.connect(config, group, tls, none()).fold(this::opened);
        openings.add(lastOpening);
        lastOpening.onResultRun(this::forgetSettledOpenings);

        return lastOpening;
    }

    /// Pool bookkeeping runs before any caller continuation of the returned promise, so a caller that
    /// reacts to the new connection already finds it in `ready` (a plain `onResult` callback gives no such order).
    private Promise<RedisConnection> opened(Result<RedisConnection> result) {
        synchronized (lock) {
            opening--;

            return result.onSuccess(this::register)
                         .async();
        }
    }

    @Contract
    private void forgetSettledOpenings() {
        synchronized (lock) {
            openings.removeIf(Promise::isResolved);
        }
    }

    /// Under the lock. A connection that finished opening after close is closed, not pooled.
    @Contract
    private void register(RedisConnection connection) {
        if (closing.get()) {
            connection.close();
        } else {
            ready.add(connection);
        }
    }

    /// Close the pool. Idempotent: every call returns the same promise.
    public Promise<Unit> close() {
        if (closing.compareAndSet(false, true)) {
            closeAll();
        }

        return closed;
    }

    @Contract
    private void closeAll() {
        List<Promise<Unit>> closes;

        synchronized (lock) {
            closes = new ArrayList<>(ready.stream().map(RedisConnection::close).toList());
            openings.forEach(opening -> closes.add(closeWhenOpened(opening)));
            ready.clear();
        }

        Promise.allOf(closes).onResultRun(this::release);
    }

    /// Resolves once an opening connection settled and, if it opened, was closed ([#register] closes it,
    /// and close is idempotent, so asking again returns the same promise).
    private static Promise<Unit> closeWhenOpened(Promise<RedisConnection> opening) {
        return opening.fold(result -> result.fold(_ -> Promise.success(unit()), RedisConnection::close));
    }

    @Contract
    private void release() {
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).addListener(_ -> closed.succeed(unit()));
    }
}
