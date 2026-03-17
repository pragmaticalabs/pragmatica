package org.pragmatica.postgres;

import org.pragmatica.postgres.net.ConnectibleBuilder;
import org.pragmatica.postgres.net.Connection;
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
 * Transaction-mode connection pool. Multiplexes N logical connections over M physical connections.
 * Physical connections are borrowed per-query/transaction and returned on completion.
 */
public class PgTransactionPool extends PgConnectible {
    private final int maxConnections;
    private final int maxStatements;

    private final Lock guard = new ReentrantLock();
    private int size;
    private final Queue<Promise<PgConnection>> pendingBorrows = new ArrayDeque<>();
    private final Queue<PgConnection> idle = new ArrayDeque<>();
    private Promise<Unit> closing;

    public PgTransactionPool(ConnectibleBuilder.ConnectibleConfiguration properties,
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

    @Override
    public Promise<Connection> getConnection() {
        return Promise.success(new LogicalConnection(this, dataConverter, maxStatements));
    }

    private sealed interface BorrowResult {
        record Closed() implements BorrowResult {}
        record Available(PgConnection connection) implements BorrowResult {}
        record Pending(Promise<PgConnection> deferred, boolean makeNew) implements BorrowResult {}
    }

    private static final BorrowResult CLOSED_BORROW = new BorrowResult.Closed();

    Promise<PgConnection> borrowConnection() {
        var result = locked(() -> {
            if (closing != null) {
                return CLOSED_BORROW;
            }

            var conn = firstAliveConnection();
            if (conn != null) {
                return (BorrowResult) new BorrowResult.Available(conn);
            }

            var deferred = Promise.<PgConnection>promise();
            pendingBorrows.add(deferred);
            boolean makeNew = size < maxConnections;
            if (makeNew) {
                size++;
            }
            return (BorrowResult) new BorrowResult.Pending(deferred, makeNew);
        });

        return switch (result) {
            case BorrowResult.Closed _ -> Promise.failure(new SqlError.ConnectionPoolClosed("Transaction pool is closed"));
            case BorrowResult.Available available -> Promise.success(available.connection());
            case BorrowResult.Pending pending -> {
                if (pending.makeNew()) {
                    createPhysicalConnection();
                }
                yield pending.deferred();
            }
        };
    }

    private void createPhysicalConnection() {
        obtainStream.get()
                    .flatMap(stream -> {
                        var conn = new PgConnection(stream, dataConverter, 0);
                        return conn.connect(username, password, database);
                    })
                    .flatMap(this::validateConnection)
                    .withResult(result -> result.fold(
                        cause -> {
                            propagateFailure(cause);
                            return null;
                        },
                        connected -> {
                            returnConnection((PgConnection) connected);
                            return null;
                        }));
    }

    private Promise<Connection> validateConnection(Connection connection) {
        if (validationQuery != null && !validationQuery.isBlank()) {
            return connection.completeScript(validationQuery)
                             .map(_ -> connection);
        }
        return Promise.success(connection);
    }

    void returnConnection(PgConnection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("'connection' should be not null");
        }
        Runnable action = locked(() -> {
            var nextUser = pendingBorrows.poll();
            if (nextUser != null) {
                return () -> nextUser.succeed(connection);
            } else {
                idle.add(connection);
                return checkClosed();
            }
        });
        Promise.async(action);
    }

    private void propagateFailure(org.pragmatica.lang.Cause cause) {
        var actions = locked(() -> {
            size--;
            var unlucky = Stream.concat(
                                    pendingBorrows.stream()
                                                  .map(item -> (Runnable) () -> item.fail(cause)),
                                    Stream.of(checkClosed()))
                                .toList();
            pendingBorrows.clear();
            return unlucky;
        });
        actions.forEach(Promise::async);
    }

    @Override
    public Promise<Unit> close() {
        return locked(() -> {
            if (closing == null) {
                closing = allOf(idle.stream().map(PgConnection::shutdown));
                return closing;
            } else {
                return Promise.failure(SqlError.fromThrowable(new IllegalStateException("Transaction pool is already shutting down")));
            }
        });
    }

    private static final Runnable NO_OP = () -> {};

    private Runnable checkClosed() {
        if (closing != null && size <= idle.size()) {
            assert pendingBorrows.isEmpty();
            return () -> closing.succeed(unit());
        }
        return NO_OP;
    }

    private PgConnection firstAliveConnection() {
        PgConnection connection = idle.poll();
        while (connection != null && !connection.isConnected()) {
            size--;
            connection = idle.poll();
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
