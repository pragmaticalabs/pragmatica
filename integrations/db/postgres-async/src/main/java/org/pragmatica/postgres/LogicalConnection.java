package org.pragmatica.postgres;

import org.pragmatica.postgres.conversion.DataConverter;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.Listening;
import org.pragmatica.postgres.net.PreparedStatement;
import org.pragmatica.postgres.net.Transaction;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.pragmatica.lang.Unit.unit;

/**
 * Logical connection for transaction-mode pooling. Borrows a physical connection per query/transaction
 * and returns it to the pool on completion. Supports prepared statement migration across physical backends.
 */
public class LogicalConnection implements Connection {
    private static final AtomicInteger LOGICAL_ID_COUNTER = new AtomicInteger();

    private enum State { IDLE, ACTIVE, IN_TX, PINNED, CLOSED }

    private final PgTransactionPool pool;
    private final DataConverter dataConverter;
    private final int logicalId;
    private final AtomicInteger nameCounter = new AtomicInteger();
    private final LinkedHashMap<String, StatementEntry> statementRegistry;

    private State state = State.IDLE;
    private PgConnection physical;
    private int lastPhysicalId;
    private int subscriptionCount;

    private record StatementEntry(String sql, String name, Oid[] types, int physicalId) {
        StatementEntry withName(String newName, int newPhysicalId) {
            return new StatementEntry(sql, newName, types, newPhysicalId);
        }
    }

    LogicalConnection(PgTransactionPool pool, DataConverter dataConverter, int maxStatements) {
        this.pool = pool;
        this.dataConverter = dataConverter;
        this.logicalId = LOGICAL_ID_COUNTER.incrementAndGet();
        this.statementRegistry = maxStatements > 0
            ? new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, StatementEntry> eldest) {
                    return size() > maxStatements;
                }
            }
            : null;
    }

    @Override
    public Promise<Unit> script(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                Consumer<PgRow> onRow,
                                Consumer<Integer> onAffected,
                                String sql) {
        if (state == State.CLOSED) {
            return Promise.failure(new SqlError.LogicalConnectionClosed("Logical connection is closed"));
        }
        return pool.borrowConnection()
                   .flatMap(conn -> {
                       physical = conn;
                       state = State.ACTIVE;
                       lastPhysicalId = System.identityHashCode(conn);
                       return conn.script(onColumns, onRow, onAffected, sql)
                                  .withResult(_ -> returnPhysical());
                   });
    }

    @Override
    public Promise<Integer> query(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                  Consumer<PgRow> onRow,
                                  String sql,
                                  Object... params) {
        if (state == State.CLOSED) {
            return Promise.failure(new SqlError.LogicalConnectionClosed("Logical connection is closed"));
        }
        if (state == State.PINNED) {
            return physical.query(onColumns, onRow, sql, params);
        }
        return pool.borrowConnection()
                   .flatMap(conn -> {
                       physical = conn;
                       state = State.ACTIVE;
                       lastPhysicalId = System.identityHashCode(conn);
                       return conn.query(onColumns, onRow, sql, params)
                                  .withResult(_ -> returnPhysical());
                   });
    }

    @Override
    public Promise<? extends PreparedStatement> prepareStatement(String sql, Oid... parametersTypes) {
        if (state == State.CLOSED) {
            return Promise.failure(new SqlError.LogicalConnectionClosed("Logical connection is closed"));
        }

        if (statementRegistry != null) {
            var entry = statementRegistry.get(sql);
            if (entry != null) {
                return pool.borrowConnection()
                           .flatMap(conn -> {
                               physical = conn;
                               state = State.ACTIVE;
                               int currentPhysicalId = System.identityHashCode(conn);
                               if (entry.physicalId() == currentPhysicalId) {
                                   lastPhysicalId = currentPhysicalId;
                                   return Promise.success(wrapPreparedStatement(sql, entry.name(), conn));
                               }
                               var newName = nextStatementName();
                               return conn.preparedStatementOf(sql, parametersTypes)
                                          .map(pgPs -> {
                                              lastPhysicalId = currentPhysicalId;
                                              statementRegistry.put(sql, entry.withName(newName, currentPhysicalId));
                                              return wrapPreparedStatement(sql, pgPs.sname(), conn);
                                          });
                           });
            }
        }

        return pool.borrowConnection()
                   .flatMap(conn -> {
                       physical = conn;
                       state = State.ACTIVE;
                       int currentPhysicalId = System.identityHashCode(conn);
                       lastPhysicalId = currentPhysicalId;
                       return conn.preparedStatementOf(sql, parametersTypes)
                                  .map(pgPs -> {
                                      if (statementRegistry != null) {
                                          var name = pgPs.sname();
                                          statementRegistry.put(sql, new StatementEntry(sql, name, parametersTypes, currentPhysicalId));
                                      }
                                      return wrapPreparedStatement(sql, pgPs.sname(), conn);
                                  });
                   });
    }

    private PreparedStatement wrapPreparedStatement(String sql, String sname, PgConnection conn) {
        return new LogicalPreparedStatement(sql, sname, conn);
    }

    private String nextStatementName() {
        return "l" + logicalId + "_s" + nameCounter.incrementAndGet();
    }

    private class LogicalPreparedStatement implements PreparedStatement {
        private final String sql;
        private final String sname;
        private final PgConnection boundPhysical;

        LogicalPreparedStatement(String sql, String sname, PgConnection boundPhysical) {
            this.sql = sql;
            this.sname = sname;
            this.boundPhysical = boundPhysical;
        }

        @Override
        public Promise<PgResultSet> query(Object... params) {
            return boundPhysical.prepareStatement(sql, dataConverter.assumeTypes(params))
                                .flatMap(ps -> ps.query(params));
        }

        @Override
        public Promise<Integer> fetch(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                      Consumer<PgRow> processor,
                                      Object... params) {
            return boundPhysical.prepareStatement(sql, dataConverter.assumeTypes(params))
                                .flatMap(ps -> ps.fetch(onColumns, processor, params));
        }

        @Override
        public Promise<Unit> close() {
            returnPhysical();
            return Promise.success(unit());
        }
    }

    @Override
    public Promise<Transaction> begin() {
        if (state == State.CLOSED) {
            return Promise.failure(new SqlError.LogicalConnectionClosed("Logical connection is closed"));
        }
        return pool.borrowConnection()
                   .flatMap(conn -> {
                       physical = conn;
                       state = State.IN_TX;
                       lastPhysicalId = System.identityHashCode(conn);
                       return conn.completeScript("BEGIN")
                                  .map(_ -> new LogicalTransaction(0));
                   });
    }

    @Override
    public Promise<Listening> subscribe(String channel, Consumer<String> onNotification) {
        if (state == State.CLOSED) {
            return Promise.failure(new SqlError.LogicalConnectionClosed("Logical connection is closed"));
        }
        if (state == State.PINNED && physical != null) {
            subscriptionCount++;
            return physical.subscribe(channel, onNotification)
                           .map(listening -> wrapListening(listening));
        }
        return pool.borrowConnection()
                   .flatMap(conn -> {
                       physical = conn;
                       state = State.PINNED;
                       lastPhysicalId = System.identityHashCode(conn);
                       subscriptionCount = 1;
                       return conn.subscribe(channel, onNotification)
                                  .map(listening -> wrapListening(listening));
                   });
    }

    private Listening wrapListening(Listening inner) {
        return () -> inner.unlisten()
                          .withSuccess(_ -> {
                              subscriptionCount--;
                              if (subscriptionCount <= 0 && state == State.PINNED) {
                                  returnPhysical();
                              }
                          })
                          .mapToUnit();
    }

    @Override
    public Promise<Unit> close() {
        if (state == State.CLOSED) {
            return Promise.success(unit());
        }
        return switch (state) {
            case IDLE -> {
                state = State.CLOSED;
                yield Promise.success(unit());
            }
            case IN_TX -> {
                var conn = physical;
                state = State.CLOSED;
                yield conn.completeScript("ROLLBACK")
                          .withResult(_ -> {
                              pool.returnConnection(conn);
                              physical = null;
                          })
                          .mapToUnit();
            }
            case PINNED -> {
                var conn = physical;
                state = State.CLOSED;
                pool.returnConnection(conn);
                physical = null;
                yield Promise.success(unit());
            }
            case ACTIVE -> {
                state = State.CLOSED;
                yield Promise.success(unit());
            }
            default -> Promise.success(unit());
        };
    }

    @Override
    public boolean isConnected() {
        return state != State.CLOSED;
    }

    private void returnPhysical() {
        if (physical != null && state != State.IN_TX && state != State.PINNED && state != State.CLOSED) {
            var conn = physical;
            physical = null;
            state = State.IDLE;
            pool.returnConnection(conn);
        }
    }

    class LogicalTransaction implements Transaction {
        private final int depth;

        LogicalTransaction(int depth) {
            this.depth = depth;
        }

        @Override
        public Promise<Transaction> begin() {
            int next = depth + 1;
            return physical.completeScript("SAVEPOINT sp_" + next)
                           .map(_ -> new LogicalTransaction(next));
        }

        @Override
        public Promise<Unit> commit() {
            if (depth == 0) {
                return physical.completeScript("COMMIT")
                               .withResult(_ -> returnFromTx())
                               .mapToUnit();
            }
            return physical.completeScript("RELEASE SAVEPOINT sp_" + depth).mapToUnit();
        }

        @Override
        public Promise<Unit> rollback() {
            if (depth == 0) {
                return physical.completeScript("ROLLBACK")
                               .withResult(_ -> returnFromTx())
                               .mapToUnit();
            }
            return physical.completeScript("ROLLBACK TO SAVEPOINT sp_" + depth).mapToUnit();
        }

        @Override
        public Promise<Unit> close() {
            return commit()
                .fold(this::handleException);
        }

        @Override
        public Connection getConnection() {
            return LogicalConnection.this;
        }

        @Override
        public Promise<Unit> script(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                    Consumer<PgRow> onRow,
                                    Consumer<Integer> onAffected,
                                    String sql) {
            return physical.script(onColumns, onRow, onAffected, sql)
                           .fold(this::handleException);
        }

        @Override
        public Promise<Integer> query(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                      Consumer<PgRow> onRow,
                                      String sql,
                                      Object... params) {
            return physical.query(onColumns, onRow, sql, params)
                           .fold(this::handleException);
        }

        private <T> Promise<T> handleException(Result<T> result) {
            return result.fold(cause -> rollback().fold(_ -> Promise.failure(cause)),
                               Promise::success);
        }

        private void returnFromTx() {
            var conn = physical;
            physical = null;
            state = State.IDLE;
            pool.returnConnection(conn);
        }
    }
}
