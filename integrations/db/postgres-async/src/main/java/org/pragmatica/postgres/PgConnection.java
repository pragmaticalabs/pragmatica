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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.postgres.conversion.DataConverter;
import org.pragmatica.postgres.message.Message;
import org.pragmatica.postgres.message.backend.Authentication;
import org.pragmatica.postgres.message.backend.DataRow;
import org.pragmatica.postgres.message.frontend.*;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.Listening;
import org.pragmatica.postgres.net.NotificationHandler;
import org.pragmatica.postgres.net.PreparedStatement;
import org.pragmatica.postgres.net.Transaction;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.postgres.message.backend.RowDescription.ColumnDescription;
import static org.pragmatica.lang.Unit.unit;


/**
 * A connection to Postgres backend. The postmaster forks a backend process for each connection. A connection can process only a single query at a
 * time.
 *
 * @author Antti Laisi
 */
public class PgConnection implements Connection {
    /**
     * Uses named server side prepared statement and named portal.
     */
    public class PgPreparedStatement implements PreparedStatement {
        private final String sname;
        private final String sql;
        private Columns columns;

        PgPreparedStatement(String sname, String sql) {
            this.sname = sname;
            this.sql = sql;
        }

        String sname() {
            return sname;
        }

        @Override
        public Promise<PgResultSet> query(Object... params) {
            var rows = new ArrayList<PgRow>(16);

            return fetch((_, _) -> {},
                         rows::add,
                         params).map(_ -> new PgResultSet(columns.byName, columns.ordered, rows, 0));
        }

        //TODO: consider conversion to "reducer" style to eliminate externally stored state
        @Override
        public Promise<Integer> fetch(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                      Consumer<PgRow> processor,
                                      Object... params) {
            var bind = new Bind(sname, dataConverter.fromParameters(params));
            Consumer<DataRow> rowProcessor = dataRow -> processor.accept(new PgRow(dataRow,
                                                                                   columns.byName,
                                                                                   columns.ordered,
                                                                                   dataConverter));

            if (columns != null) {
                short[] resultFmtCodes = DataConverter.resultFormatCodes(columns.ordered);

                columns = updateFormatCodes(columns, resultFmtCodes);
                onColumns.accept(columns.byName, columns.ordered);
                var types = dataConverter.assumeTypes(params);
                var encoded = dataConverter.fromParametersBinary(params, types);
                var binaryBind = new Bind(sname, encoded.values(), encoded.formatCodes(), resultFmtCodes);

                return stream.send(binaryBind, rowProcessor);
            } else {
                return stream.send(bind,
                                   Describe.portal(),
                                   columnDescriptions -> {
                                       columns = calcColumns(columnDescriptions);
                                       onColumns.accept(columns.byName, columns.ordered);
                                   },
                                   rowProcessor);
            }
        }

        @Override
        public Promise<Unit> close() {
            if (statementCache != null) {
                return closeWithCache();
            }

            return closeOnServer();
        }

        private Promise<Unit> closeWithCache() {
            if (statementCache.get(sql) == this) {
                return Promise.success(unit());
            }

            PgPreparedStatement already = statementCache.put(sql, this);

            if (evicted != null) {
                try {
                    if (already != null && already != evicted) {
                        log.warn(DUPLICATED_PREPARED_STATEMENT_DETECTED, already.sql);

                        return evicted.closeOnServer()
                                      .flatMap(_ -> already.closeOnServer());
                    } else {
                        return evicted.closeOnServer();
                    }
                } finally {
                    evicted = null;
                }
            } else {
                if (already != null) {
                    log.warn(DUPLICATED_PREPARED_STATEMENT_DETECTED, already.sql);

                    return already.closeOnServer();
                } else {
                    return Promise.success(unit());
                }
            }
        }

        private Promise<Unit> closeOnServer() {
            return stream.send(Close.statement(sname))
                         .mapToUnit();
        }
    }

    private static final String DUPLICATED_PREPARED_STATEMENT_DETECTED = "Duplicated prepared statement detected. Closing extra instance. \n{}";

    private static final Pattern VALID_CHANNEL_NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    public record Columns(Map<String, PgColumn> byName, PgColumn[] ordered) {}

    private static class NameSequence {
        private long counter;
        private String prefix;

        NameSequence(final String prefix) {
            this.prefix = prefix;
        }

        private String next() {
            if (counter == Long.MAX_VALUE) {
                counter = 0;
                prefix = "_" + prefix;
            }

            return prefix + ++counter;
        }
    }

    private final NameSequence preparedStatementNames = new NameSequence("s-");
    private final ProtocolStream stream;
    private final DataConverter dataConverter;
    private final LinkedHashMap<String, PgPreparedStatement> statementCache;
    private PgPreparedStatement evicted;
    private Runnable onRelease;
    private Columns currentColumns;
    /// Outer-transaction depth: 0 = no tx, 1 = inside outer tx, >1 = nested savepoints.
    /// PostgreSQL connections are single-threaded — no synchronization needed.
    private int txDepth = 0;
    /// Hooks fired once when the outer transaction commits (depth transitions 1 → 0 via COMMIT).
    private final ArrayList<Runnable> onCommitHooks = new ArrayList<>();
    /// Hooks fired once when the outer transaction rolls back (depth 1 → 0 via ROLLBACK).
    private final ArrayList<Runnable> onRollbackHooks = new ArrayList<>();

    boolean inTransaction() {
        return txDepth > 0;
    }

    void enterTx() {
        txDepth++;
    }

    /// Decrement tx depth. When the outer tx ends (depth reaches 0), fire either the
    /// commit or rollback hooks (and discard the other side). Savepoint release/rollback
    /// just decrements — hooks remain pending for the outer outcome. This means
    /// subscribe/unlisten inside a rolled-back savepoint may leave inconsistent state;
    /// see the javadoc on [#subscribe(String, Consumer)].
    void exitTx(boolean committed) {
        txDepth--;
        if (txDepth > 0) {
            return;
        }

        var fire = committed
                   ? new ArrayList<>(onCommitHooks)
                   : new ArrayList<>(onRollbackHooks);

        onCommitHooks.clear();
        onRollbackHooks.clear();
        for (var hook : fire) {
            try {
                hook.run();
            } catch (Throwable t) {
                log.warn("Transaction {} hook threw",
                         committed
                         ? "commit"
                         : "rollback",
                         t);
            }
        }
    }

    void registerOnCommit(Runnable hook) {
        onCommitHooks.add(hook);
    }

    void registerOnRollback(Runnable hook) {
        onRollbackHooks.add(hook);
    }

    PgConnection(ProtocolStream stream, DataConverter dataConverter, int maxStatements) {
        this.stream = stream;
        this.dataConverter = dataConverter;
        this.statementCache = maxStatements > 0
                              ? new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PgPreparedStatement> eldest) {
                if (size() > maxStatements) {
                    evicted = eldest.getValue();

                    return true;
                }

                return false;
            }
        }
                              : null;
    }

    void onRelease(Runnable onRelease) {
        this.onRelease = onRelease;
    }

    Promise<Connection> connect(String username, String password, String database) {
        return stream.connect(new StartupMessage(username, database))
                     .flatMap(authentication -> authenticate(username, password, authentication))
                     .map(_ -> PgConnection.this);
    }

    private Promise<? extends Message> authenticate(String username, String password, Message message) {
        return message instanceof Authentication authentication && !authentication.authenticationOk()
               ? stream.authenticate(username, password, authentication)
               : Promise.success(message);
    }

    public boolean isConnected() {
        return stream.isConnected();
    }

    @Override
    public Promise<? extends PreparedStatement> prepareStatement(String sql, Oid... parametersTypes) {
        if (statementCache != null) {
            var cached = statementCache.get(sql);

            if (cached != null) {
                return Promise.success(cached);
            }
        }

        return preparedStatementOf(sql, parametersTypes);
    }

    Promise<PgPreparedStatement> preparedStatementOf(String sql, Oid... parametersTypes) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("'sql' shouldn't be null or empty or blank string");
        }

        if (parametersTypes == null) {
            throw new IllegalArgumentException("'parametersTypes' shouldn't be null, at least it should be empty");
        }

        var statementName = preparedStatementNames.next();

        return stream.send(new Parse(sql, statementName, parametersTypes))
                     .map(_ -> new PgPreparedStatement(statementName, sql));
    }

    @Override
    public Promise<Unit> script(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                Consumer<PgRow> onRow,
                                Consumer<Integer> onAffected,
                                String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("'sql' shouldn't be null or empty or blank string");
        }

        return stream.send(new Query(sql),
                           columnDescriptions -> {
                               currentColumns = calcColumns(columnDescriptions);
                               onColumns.accept(currentColumns.byName, currentColumns.ordered);
                           },
                           message -> onRow.accept(new PgRow(message,
                                                             currentColumns.byName,
                                                             currentColumns.ordered,
                                                             dataConverter)),
                           message -> {
                               currentColumns = null;
                               onAffected.accept(message.affectedRows());
                           });
    }

    @Override
    public Promise<Integer> query(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                  Consumer<PgRow> onRow,
                                  String sql,
                                  Object... params) {
        return prepareStatement(sql, dataConverter.assumeTypes(params)).flatMap(ps -> ps.fetch(onColumns, onRow, params)
                                                                                        .fold(result -> result.fold(cause -> handleQueryFailure(cause,
                                                                                                                                                onColumns,
                                                                                                                                                onRow,
                                                                                                                                                sql,
                                                                                                                                                params),
                                                                                                                    value -> isStatementCached(sql)
                                                                                                                             ? Promise.success(value)
                                                                                                                             : closePreparedStatement(Result.success(value),
                                                                                                                                                      ps))));
    }

    private Promise<Integer> handleQueryFailure(Cause cause,
                                                BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                                Consumer<PgRow> onRow,
                                                String sql,
                                                Object... params) {
        if (isStaleStatementError(cause) && statementCache != null) {
            var evicted = statementCache.remove(sql);

            if (evicted != null) {
                return evicted.closeOnServer()
                              .flatMap(_ -> retryQuery(onColumns, onRow, sql, params));
            }
        }

        return cause.promise();
    }

    private Promise<Integer> retryQuery(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                        Consumer<PgRow> onRow,
                                        String sql,
                                        Object... params) {
        return preparedStatementOf(sql, dataConverter.assumeTypes(params)).flatMap(ps -> ps.fetch(onColumns,
                                                                                                  onRow,
                                                                                                  params)
                                                                                           .fold(result -> isStatementCached(sql)
                                                                                                           ? Promise.resolved(result)
                                                                                                           : closePreparedStatement(result,
                                                                                                                                    ps)));
    }

    private static boolean isStaleStatementError(Cause cause) {
        return cause instanceof SqlError.ServerError serverError && ("42P05".equals(serverError.response().code()) || "26000".equals(serverError.response()
                                                                                                                                                .code()));
    }

    private boolean isStatementCached(String sql) {
        return statementCache != null && statementCache.containsKey(sql);
    }

    private static Promise<Integer> closePreparedStatement(Result<Integer> result, PreparedStatement ps) {
        return ps.close()
                 .flatMap(_ -> Promise.resolved(result));
    }

    @Override
    public Promise<Transaction> begin() {
        return completeScript("BEGIN").withSuccess(_ -> enterTx())
                             .map(_ -> new PgConnectionTransaction());
    }

    /// Subscribe to NOTIFY messages on a channel.
    ///
    /// **Auto-commit (no active transaction):** LISTEN takes effect immediately.
    /// The returned [Listening] removes the local handler and issues UNLISTEN.
    ///
    /// **Inside an explicit transaction:** PostgreSQL defers LISTEN until COMMIT
    /// (and discards it on ROLLBACK). The local handler is registered immediately,
    /// but a rollback hook is also registered: if the outer transaction rolls back,
    /// the handler is removed (mirrors the server-side discard). Likewise, when
    /// the returned [Listening] is invoked inside a transaction, UNLISTEN is queued
    /// and the local handler is only removed if/when the outer transaction commits.
    ///
    /// **Limitation:** subscribe/unlisten inside a SAVEPOINT that is later rolled
    /// back via `ROLLBACK TO SAVEPOINT` may leave the client and server states
    /// inconsistent until the outer transaction completes. Outer COMMIT/ROLLBACK
    /// always fires the registered hooks.
    public Promise<Listening> subscribe(String channel, Consumer<String> onNotification) {
        return validateChannelName(channel).flatMap(_ -> completeScript("LISTEN " + channel))
                                  .map(_ -> registerSubscription(channel,
                                                                 stream.subscribe(channel, onNotification)));
    }

    @Override
    public Promise<Listening> subscribe(String channel, NotificationHandler onNotification) {
        return validateChannelName(channel).flatMap(_ -> completeScript("LISTEN " + channel))
                                  .map(_ -> registerSubscription(channel,
                                                                 stream.subscribeWithDetails(channel, onNotification)));
    }

    private Listening registerSubscription(String channel, Runnable unsubscribe) {
        if (inTransaction()) {
            registerOnRollback(unsubscribe);
        }

        return () -> doUnlisten(channel, unsubscribe);
    }

    private Promise<Unit> doUnlisten(String channel, Runnable unsubscribe) {
        if (inTransaction()) {
            return completeScript("UNLISTEN " + channel).withSuccess(_ -> registerOnCommit(unsubscribe))
                                 .mapToUnit();
        }

        return completeScript("UNLISTEN " + channel).withSuccess(_ -> unsubscribe.run())
                             .mapToUnit();
    }

    private static Promise<Unit> validateChannelName(String channel) {
        if (channel != null && VALID_CHANNEL_NAME.matcher(channel).matches()) {
            return Promise.success(unit());
        }

        return new SqlError.InvalidChannelName("Invalid channel name: " + channel).promise();
    }

    @Override
    public Promise<Unit> close() {
        if (onRelease != null) {
            onRelease.run();

            return Promise.success(unit());
        }

        return stream.close();
    }

    Promise<Unit> shutdown() {
        if (statementCache != null) {
            return allOf(statementCache.values().stream().map(PgPreparedStatement::closeOnServer)).withResult(_ -> statementCache.clear())
                        .fold(_ -> stream.close());
        }

        return stream.close();
    }

    private static Columns updateFormatCodes(Columns existing, short[] formatCodes) {
        var ordered = existing.ordered;
        var byName = new HashMap<String, PgColumn>(ordered.length * 2);
        var updated = new PgColumn[ordered.length];

        for (int i = 0; i < ordered.length; i++) {
            updated[i] = ordered[i].withFormatCode(formatCodes[i]);
            byName.put(updated[i].name(), updated[i]);
        }

        return new Columns(Map.copyOf(byName), updated);
    }

    private static Columns calcColumns(ColumnDescription[] descriptions) {
        var byName = new HashMap<String, PgColumn>(descriptions.length * 2);
        var ordered = new PgColumn[descriptions.length];

        for (int i = 0; i < descriptions.length; i++) {
            var column = new PgColumn(i,
                                      descriptions[i].getName(),
                                      descriptions[i].getType(),
                                      descriptions[i].getFormatCode());

            byName.put(descriptions[i].getName(), column);
            ordered[i] = column;
        }

        return new Columns(Map.copyOf(byName), ordered);
    }

    /**
     * Transaction that rollbacks the tx on backend error and closes the connection on COMMIT/ROLLBACK failure.
     */
    class PgConnectionTransaction implements Transaction {
        private final int depth;

        PgConnectionTransaction() {
            this(0);
        }

        private PgConnectionTransaction(int depth) {
            this.depth = depth;
        }

        @Override
        public Promise<Transaction> begin() {
            int next = depth + 1;

            return completeScript("SAVEPOINT sp_" + next).withSuccess(_ -> enterTx())
                                 .map(_ -> new PgConnectionTransaction(next));
        }

        @Override
        public Promise<Unit> commit() {
            if (depth == 0) {
                // COMMIT: on server failure, the tx is automatically rolled back per PG semantics —
                // fire rollback hooks. On wire/connection failure, also fire rollback hooks (best effort).
                return PgConnection.this.completeScript("COMMIT")
                                   .onResult(result -> exitTx(result.isSuccess()))
                                   .map(Unit::toUnit);
            }

            return PgConnection.this.completeScript("RELEASE SAVEPOINT sp_" + depth)
                               .withSuccess(_ -> exitTx(true))
                               .map(Unit::toUnit);
        }

        @Override
        public Promise<Unit> rollback() {
            if (depth == 0) {
                // ROLLBACK: fire rollback hooks regardless — on wire failure the connection is broken
                // anyway, so unsubscribing local handlers is the right cleanup.
                return PgConnection.this.completeScript("ROLLBACK")
                                   .onResultRun(() -> exitTx(false))
                                   .map(Unit::toUnit);
            }

            return PgConnection.this.completeScript("ROLLBACK TO SAVEPOINT sp_" + depth)
                               .withSuccess(_ -> exitTx(false))
                               .map(Unit::toUnit);
        }

        @Override
        public Promise<Unit> close() {
            return commit().fold(this::handleException);
        }

        @Override
        public Connection getConnection() {
            return PgConnection.this;
        }

        @Override
        public Promise<Unit> script(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                    Consumer<PgRow> onRow,
                                    Consumer<Integer> onAffected,
                                    String sql) {
            return PgConnection.this.script(onColumns, onRow, onAffected, sql).fold(this::handleException);
        }

        public Promise<Integer> query(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                      Consumer<PgRow> onRow,
                                      String sql,
                                      Object... params) {
            return PgConnection.this.query(onColumns, onRow, sql, params).fold(this::handleException);
        }

        private <T> Promise<T> handleException(Result<T> result) {
            return result.fold(cause -> rollback().fold(_ -> Promise.failure(cause)),
                               Promise::success);
        }
    }

    private static Promise<Unit> allOf(Stream<? extends Promise<?>> promises) {
        var list = promises.toList();

        if (list.isEmpty()) {
            return Promise.success(unit());
        }

        var result = Promise.<Unit> promise();
        var remaining = new AtomicInteger(list.size());

        for (var p : list) {
            p.onResult(r -> r.fold(cause -> {
                                       result.fail(cause);

                                       return null;
                                   },
                                   _ -> {
                                       if (remaining.decrementAndGet() == 0) {
                                       result.succeed(unit());
                                   }

                                       return null;
                                   }));
        }

        return result;
    }
}
