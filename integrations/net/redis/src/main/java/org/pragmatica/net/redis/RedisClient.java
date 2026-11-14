package org.pragmatica.net.redis;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
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
///    connection was closed and the event loop group was shut down. A command issued after close fails
///    with [RedisError.General#CLOSED] without being sent. Earned by the `closing` flag, read before a
///    connection is chosen.
///  - There is no ordering between commands: each may take a different connection.
public final class RedisClient implements RedisCommands {
    private final RedisConfig config;
    private final Option<SslContext> tls;
    private final EventLoopGroup group;
    private final Object lock = new Object();
    private final List<RedisConnection> ready = new ArrayList<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Promise<Unit> closed = Promise.promise();
    private int opening;
    private Promise<RedisConnection> lastOpening = RedisError.General.CLOSED.promise();

    private RedisClient(RedisConfig config, Option<SslContext> tls) {
        this.config = config;
        this.tls = tls;
        this.group = new MultiThreadIoEventLoopGroup(Math.min(config.poolSize(), 4),
                                                     NioIoHandler.newFactory());
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
        var client = new RedisClient(config, tls);

        return client.acquire()
                     .map(_ -> client)
                     .onFailure(_ -> client.close());
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

        return lastOpening;
    }

    /// Pool bookkeeping runs before any caller continuation of the returned promise, so a caller that
    /// reacts to the new connection already finds it in `ready` (a plain `onResult` callback gives no such order).
    private Promise<RedisConnection> opened(Result<RedisConnection> result) {
        synchronized (lock) {
            opening--;
            result.onSuccess(this::register);
        }

        return result.async();
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
            closes = ready.stream().map(RedisConnection::close).toList();
            ready.clear();
        }

        Promise.allOf(closes).onResultRun(this::release);
    }

    @Contract
    private void release() {
        group.shutdownGracefully();
        closed.succeed(unit());
    }
}
