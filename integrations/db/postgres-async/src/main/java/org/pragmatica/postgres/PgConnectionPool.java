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

package org.pragmatica.postgres;

import org.pragmatica.postgres.net.ConnectibleBuilder;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.lang.Unit.unit;

/**
 * Resource pool for backend connections.
 *
 * @author Antti Laisi
 */
public class PgConnectionPool extends PgConnectible {
    private final int maxConnections;
    private final int maxStatements;

    private final Lock guard = new ReentrantLock();
    private int size;
    private final Queue<Promise<Connection>> pending = new ArrayDeque<>();
    private final Queue<PgConnection> connections = new ArrayDeque<>();
    private Promise<Unit> closing;

    public PgConnectionPool(ConnectibleBuilder.ConnectibleConfiguration properties,
                            Supplier<Promise<ProtocolStream>> obtainStream) {
        super(properties, obtainStream);
        this.maxConnections = properties.maxConnections();
        this.maxStatements = properties.maxStatements();
    }

    private <T> T locked(Supplier<T> action) {
        guard.lock();
        try {
            return action.get();
        } finally {
            guard.unlock();
        }
    }

    private void release(PgConnection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("'connection' should be not null");
        }
        Runnable lucky = locked(() -> {
            var nextUser = pending.poll();

            if (nextUser != null) {
                return () -> nextUser.succeed(connection);
            } else {
                connections.add(connection);
                return checkClosed();
            }
        });
        Promise.async(lucky);
    }

    private sealed interface PoolResult {
        record Closed() implements PoolResult {}

        record Available(Connection connection) implements PoolResult {}

        record Pending(Promise<Connection> deferred, boolean makeNew) implements PoolResult {}
    }

    private static final PoolResult CLOSED_RESULT = new PoolResult.Closed();

    @Override
    public Promise<Connection> getConnection() {
        var poolResult = locked(() -> {
            if (closing != null) {
                return CLOSED_RESULT;
            }

            var conn = firstAliveConnection();

            if (conn != null) {
                return new PoolResult.Available(conn);
            }

            var deferred = Promise.<Connection>promise();
            pending.add(deferred);
            boolean makeNew = size < maxConnections;

            if (makeNew) {
                size++;
            }

            return new PoolResult.Pending(deferred, makeNew);
        });

        return switch (poolResult) {
            case PoolResult.Closed _ -> Promise.failure(new SqlError.ConnectionPoolClosed("Connection pool is closed"));
            case PoolResult.Available available -> Promise.success(available.connection());
            case PoolResult.Pending pendingResult -> {
                if (pendingResult.makeNew()) {
                    obtainStream.get()
                                .flatMap(stream -> {
                                    var conn = new PgConnection(stream, dataConverter, maxStatements);
                                    conn.onRelease(() -> release(conn));
                                    return conn.connect(username, password, database);
                                })
                                .flatMap(this::validateConnection)
                                .withResult(result -> result.fold(
                                    cause -> {
                                        propagateFailure(cause);
                                        return null;
                                    },
                                    connected -> {
                                        release((PgConnection) connected);
                                        return null;
                                    }));
                }

                yield pendingResult.deferred();
            }
        };
    }

    private void propagateFailure(Cause cause) {
        var actions = locked(() -> {
            size--;
            var unlucky = Stream.concat(
                                    pending.stream()
                                           .map(item -> (Runnable) () -> item.fail(cause)),
                                    Stream.of(checkClosed()))
                                .toList();
            pending.clear();
            return unlucky;
        });
        actions.forEach(Promise::async);
    }

    private Promise<Connection> validateConnection(Connection connection) {
        if (validationQuery != null && !validationQuery.isBlank()) {
            return runValidationQuery(connection);
        } else {
            return Promise.success(connection);
        }
    }

    private Promise<Connection> runValidationQuery(Connection connection) {
        return connection.completeScript(validationQuery)
                         .fold(result ->
                                   result.fold(
                                       cause -> ((PgConnection) connection).shutdown()
                                           .flatMap(_ -> Promise.failure(cause)),
                                       _ -> Promise.success(connection)
                                   ));
    }

    @Override
    public Promise<Unit> close() {
        return locked(() -> {
            if (closing == null) {
                closing = allOf(connections.stream()
                                           .map(PgConnection::shutdown));
                return closing;
            } else {
                return Promise.failure(SqlError.fromThrowable(new IllegalStateException("PG pool is already shutting down")));
            }
        });
    }

    private static final Runnable NO_OP = () -> {};

    private Runnable checkClosed() {
        if (closing != null && size <= connections.size()) {
            assert pending.isEmpty();
            return () -> closing.succeed(unit());
        } else {
            return NO_OP;
        }
    }

    private Connection firstAliveConnection() {
        PgConnection connection = connections.poll();

        while (connection != null && !connection.isConnected()) {
            size--;
            connection = connections.poll();
        }
        return connection;
    }

    private static Promise<Unit> allOf(Stream<? extends Promise<?>> promises) {
        var list = promises.toList();
        if (list.isEmpty()) {
            return Promise.success(unit());
        }
        var result = Promise.<Unit>promise();
        var remaining = new AtomicInteger(list.size());
        for (var p : list) {
            p.onResult(r -> r.fold(
                cause -> {
                    result.fail(cause);
                    return null;
                },
                _ -> {
                    if (remaining.decrementAndGet() == 0) {
                        result.succeed(unit());
                    }
                    return null;
                }
            ));
        }
        return result;
    }
}
