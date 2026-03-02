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

package com.github.pgasync;

import com.github.pgasync.net.*;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.lang.Functions.Fn1;
import static org.pragmatica.lang.Unit.unit;

/**
 * Resource pool for backend connections.
 *
 * @author Antti Laisi
 */
public class PgConnectionPool extends PgConnectible {
    private class PooledPgConnection implements Connection {
        private class PooledPgTransaction implements Transaction {

            private final Transaction delegate;

            PooledPgTransaction(Transaction delegate) {
                this.delegate = delegate;
            }

            public Promise<Unit> commit() {
                return delegate.commit();
            }

            public Promise<Unit> rollback() {
                return delegate.rollback();
            }

            public Promise<Unit> close() {
                return delegate.close();
            }

            public Promise<Transaction> begin() {
                return delegate.begin().map(PooledPgTransaction::new);
            }

            @Override
            public Promise<Integer> query(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                          Consumer<PgRow> onRow,
                                          String sql,
                                          Object... params) {
                return delegate.query(onColumns, onRow, sql, params);
            }

            @Override
            public Promise<Unit> script(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                        Consumer<PgRow> onRow,
                                        Consumer<Integer> onAffected,
                                        String sql) {
                return delegate.script(onColumns, onRow, onAffected, sql);
            }

            public Connection getConnection() {
                return PooledPgConnection.this;
            }
        }

        private final PgConnection delegate;
        private PooledPgPreparedStatement evicted;
        private final LinkedHashMap<String, PooledPgPreparedStatement> statements = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PooledPgPreparedStatement> eldest) {
                if (size() > maxStatements) {
                    evicted = eldest.getValue();
                    return true;
                } else {
                    return false;
                }
            }
        };

        PooledPgConnection(PgConnection delegate) {
            this.delegate = delegate;
        }

        Promise<PooledPgConnection> connect(String username, String password, String database) {
            return delegate.connect(username, password, database).map(_ -> PooledPgConnection.this);
        }

        public boolean isConnected() {
            return delegate.isConnected();
        }

        Promise<Unit> shutdown() {
            return allOf(statements.values().stream()
                             .map(stmt -> stmt.delegate.close()))
                .withResult(_ -> statements.clear())
                .fold(_ -> delegate.close());
        }

        @Override
        public Promise<Unit> close() {
            release(this);
            return Promise.success(unit());
        }

        @Override
        public Promise<Listening> subscribe(String channel, Consumer<String> onNotification) {
            return delegate.subscribe(channel, onNotification);
        }

        @Override
        public Promise<Transaction> begin() {
            return delegate.begin().map(PooledPgTransaction::new);
        }

        @Override
        public Promise<Unit> script(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                    Consumer<PgRow> onRow,
                                    Consumer<Integer> onAffected,
                                    String sql) {
            return delegate.script(onColumns, onRow, onAffected, sql);
        }

        @Override
        public Promise<Integer> query(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                      Consumer<PgRow> onRow,
                                      String sql,
                                      Object... params) {
            return prepareStatement(sql, dataConverter.assumeTypes(params))
                .flatMap(stmt ->
                             stmt.fetch(onColumns, onRow, params)
                                 .fold(result ->
                                           stmt.close()
                                               .flatMap(_ -> Promise.resolved(result))));
        }

        @Override
        public Promise<PreparedStatement> prepareStatement(String sql, Oid... parametersTypes) {
            PooledPgPreparedStatement statement = statements.remove(sql);
            if (statement != null) {
                return Promise.success(statement);
            } else {
                return delegate.preparedStatementOf(sql, parametersTypes)
                               .map(stmt -> new PooledPgPreparedStatement(sql, stmt));
            }
        }

        private class PooledPgPreparedStatement implements PreparedStatement {

            private static final String
                DUPLICATED_PREPARED_STATEMENT_DETECTED =
                "Duplicated prepared statement detected. Closing extra instance. \n{}";
            private final String sql;
            private final PgConnection.PgPreparedStatement delegate;

            private PooledPgPreparedStatement(String sql, PgConnection.PgPreparedStatement delegate) {
                this.sql = sql;
                this.delegate = delegate;
            }

            @Override
            public Promise<Unit> close() {
                PooledPgPreparedStatement already = statements.put(sql, this);
                if (evicted != null) {
                    try {
                        if (already != null && already != evicted) {
                            log.warn(DUPLICATED_PREPARED_STATEMENT_DETECTED, already.sql);
                            return evicted.delegate.close()
                                                   .flatMap(_ -> already.delegate.close());
                        } else {
                            return evicted.delegate.close();
                        }
                    } finally {
                        evicted = null;
                    }
                } else {
                    if (already != null) {
                        log.warn(DUPLICATED_PREPARED_STATEMENT_DETECTED, already.sql);
                        return already.delegate.close();
                    } else {
                        return Promise.success(unit());
                    }
                }
            }

            @Override
            public Promise<PgResultSet> query(Object... params) {
                return delegate.query(params);
            }

            @Override
            public Promise<Integer> fetch(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                          Consumer<PgRow> processor,
                                          Object... params) {
                return delegate.fetch(onColumns, processor, params);
            }
        }
    }

    private final int maxConnections;
    private final int maxStatements;

    private final Lock guard = new ReentrantLock();
    private int size;
    private final Queue<Promise<Connection>> pending = new LinkedList<>();
    private final Queue<PooledPgConnection> connections = new LinkedList<>();
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

    private void release(PooledPgConnection connection) {
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

    @Override
    public Promise<Connection> getConnection() {
        if (locked(() -> closing != null)) {
            return Promise.failure(new SqlError.ConnectionPoolClosed("Connection pool is closed"));
        } else {
            var cached = locked(this::firstAliveConnection);

            if (cached != null) {
                return Promise.success(cached);
            } else {
                var deferred = Promise.<Connection>promise();
                boolean makeNewConnection = locked(() -> {
                    pending.add(deferred);
                    if (size < maxConnections) {
                        size++;
                        return true;
                    } else {
                        return false;
                    }
                });
                if (makeNewConnection) {
                    obtainStream.get()
                                .flatMap(stream -> new PooledPgConnection(new PgConnection(stream, dataConverter))
                                    .connect(username, password, database))
                                .flatMap(this::validatePooledConnection)
                                .withResult(result -> result.fold(
                                    cause -> {
                                        propagateFailure(cause);
                                        return null;
                                    },
                                    connected -> {
                                        release(connected);
                                        return null;
                                    }));
                }
                return deferred;
            }
        }
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

    private Promise<PooledPgConnection> validatePooledConnection(PooledPgConnection pooledConnection) {
        if (validationQuery != null && !validationQuery.isBlank()) {
            return runValidationQuery(pooledConnection);
        } else {
            return Promise.success(pooledConnection);
        }
    }

    private Promise<PooledPgConnection> runValidationQuery(PooledPgConnection pooledConnection) {
        return pooledConnection.completeScript(validationQuery)
                               .fold(result ->
                                         result.fold(
                                             cause -> pooledConnection.delegate
                                                 .close()
                                                 .flatMap(_ -> Promise.failure(cause)),
                                             _ -> Promise.success(pooledConnection)
                                         ));
    }

    @Override
    public Promise<Unit> close() {
        return locked(() -> {
            if (closing == null) {
                closing = allOf(connections.stream()
                                           .map(PooledPgConnection::shutdown));
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
        Connection connection = connections.poll();

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
