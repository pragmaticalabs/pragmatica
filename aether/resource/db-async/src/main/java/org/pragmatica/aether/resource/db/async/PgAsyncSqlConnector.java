// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.PinnedSessionBound;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgConnection;
import org.pragmatica.postgres.PgResultSet;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.Listening;
import org.pragmatica.postgres.net.Transaction;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


final class PgAsyncSqlConnector implements AsyncSqlConnector {
    private final DatabaseConnectorConfig config;
    private final Connectible connectible;
    private final Connectible dedicated;

    private PgAsyncSqlConnector(DatabaseConnectorConfig config, Connectible connectible, Connectible dedicated) {
        this.config = config;
        this.connectible = connectible;
        this.dedicated = dedicated;
    }

    /// `dedicated` is an unpooled source (`ConnectibleBuilder.plain()`): `pinned` opens its connection there and never
    /// from `connectible`, whatever the pool mode.
    static PgAsyncSqlConnector pgAsyncSqlConnector(DatabaseConnectorConfig config,
                                                   Connectible connectible,
                                                   Connectible dedicated) {
        return new PgAsyncSqlConnector(config, connectible, dedicated);
    }

    @Override
    public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return connectible.completeQuery(convertPlaceholders(sql),
                                         params)
                          .flatMap(rs -> extractSingleRow(rs, mapper, sql));
    }

    @Override
    public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
        return connectible.completeQuery(convertPlaceholders(sql),
                                         params)
                          .flatMap(rs -> extractOptionalRow(rs, mapper));
    }

    @Override
    public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return connectible.completeQuery(convertPlaceholders(sql),
                                         params)
                          .flatMap(rs -> extractAllRows(rs, mapper));
    }

    @Override
    public Promise<Integer> update(String sql, Object... params) {
        return connectible.completeQuery(convertPlaceholders(sql),
                                         params)
                          .map(PgResultSet::affectedRows);
    }

    @Override
    public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
        if (paramsList.isEmpty()) {
            return Promise.success(new int[0]);
        }

        return borrowed(conn -> executeBatch(conn, convertPlaceholders(sql), paramsList));
    }

    @Override
    public <T> Promise<T> transactional(TransactionCallback<T> callback) {
        return borrowed(conn -> runTransaction(conn, config, callback));
    }

    /// postgres-async has no per-statement timeout, so `statementTimeout` is not enforced here: the bound is the
    /// caller's own promise timeout.
    @Override
    public <T> Promise<T> pinned(Duration statementTimeout,
                                 TransactionCallback<T> body,
                                 TransactionCallback<Unit> release) {
        return dedicated.getConnection()
                        .flatMap(conn -> runPinned(conn,
                                                   config,
                                                   PinnedSessionBound.serverBounded(statementTimeout, body),
                                                   release));
    }

    @Override
    public Promise<Listening> subscribe(String channel, Consumer<String> onNotification) {
        return connectible.getConnection()
                          .flatMap(conn -> subscribeOwning(conn, channel, onNotification));
    }

    @Override
    public DatabaseConnectorConfig config() {
        return config;
    }

    @Override
    public DatabaseType databaseType() {
        return config.effectiveType(DatabaseConnectorConfig.Transport.ASYNC);
    }

    @Override
    public Promise<Boolean> isHealthy() {
        return connectible.completeQuery("SELECT 1")
                          .map(_ -> true)
                          .recover(_ -> false);
    }

    @Override
    public Promise<Unit> stop() {
        return connectible.close();
    }

    private static <T> Promise<T> extractSingleRow(PgResultSet rs, RowMapper<T> mapper, String sql) {
        if (rs.size() == 0) {
            return DatabaseConnectorError.ResultNotFound.INSTANCE.promise();
        }

        if (rs.size() > 1) {
            return DatabaseConnectorError.multipleResults(rs.size()).promise();
        }

        return mapRow(rs.index(0), mapper);
    }

    private static <T> Promise<Option<T>> extractOptionalRow(PgResultSet rs, RowMapper<T> mapper) {
        if (rs.size() == 0) {
            return Promise.success(none());
        }

        return mapRow(rs.index(0), mapper).map(v -> some(v));
    }

    private static <T> Promise<List<T>> extractAllRows(PgResultSet rs, RowMapper<T> mapper) {
        var results = new ArrayList<Result<T>>(rs.size());

        for (var row : rs) {
            results.add(mapper.map(new PgRowAccessor(row)));
        }

        return Result.firstFailureOf(results).async();
    }

    private static <T> Promise<T> mapRow(PgRow row, RowMapper<T> mapper) {
        return mapper.map(new PgRowAccessor(row))
                     .async();
    }

    private static Promise<int[]> executeBatch(Connection conn, String sql, List<Object[]> paramsList) {
        return conn.begin()
                   .flatMap(tx -> executeBatchInTransaction(tx,
                                                            sql,
                                                            paramsList,
                                                            new int[paramsList.size()],
                                                            0));
    }

    private static Promise<int[]> executeBatchInTransaction(Transaction tx,
                                                            String sql,
                                                            List<Object[]> paramsList,
                                                            int[] counts,
                                                            int index) {
        if (index >= paramsList.size()) {
            return tx.commit()
                     .map(_ -> counts);
        }

        return tx.completeQuery(sql,
                                paramsList.get(index))
                 .map(rs -> recordAffectedRows(counts, index, rs))
                 .flatMap(_ -> executeBatchInTransaction(tx, sql, paramsList, counts, index + 1))
                 .fold(result -> rollbackOnFailure(tx, result));
    }

    private static int recordAffectedRows(int[] counts, int index, PgResultSet rs) {
        counts[index] = rs.affectedRows();

        return rs.affectedRows();
    }

    private static <T> Promise<T> rollbackOnFailure(Transaction tx, Result<T> result) {
        return result.fold(cause -> tx.rollback()
                                      .flatMap(_ -> cause.promise()),
                           Promise::success);
    }

    private static <T> Promise<T> runTransaction(Connection conn,
                                                 DatabaseConnectorConfig config,
                                                 TransactionCallback<T> callback) {
        return conn.begin()
                   .flatMap(tx -> executeInTransaction(tx, config, callback));
    }

    private static <T> Promise<T> runPinned(Connection conn,
                                            DatabaseConnectorConfig config,
                                            TransactionCallback<T> body,
                                            TransactionCallback<Unit> release) {
        return runPinnedSession(conn, new PinnedSqlConnector(config, conn), body, release);
    }

    private static <T> Promise<T> runPinnedSession(Connection conn,
                                                   SqlConnector session,
                                                   TransactionCallback<T> body,
                                                   TransactionCallback<Unit> release) {
        return invoke(body, session).fold(bodyResult -> closeAfterRelease(conn, session, release, bodyResult));
    }

    private static <R> Promise<R> invoke(TransactionCallback<R> callback, SqlConnector session) {
        return Promise.lift(DatabaseConnectorError::databaseFailure,
                            () -> callback.execute(session))
                      .flatMap(promise -> promise);
    }

    // Close is the release (design 2.1 rule 2): the dedicated connection comes from `PgDatabase`, which has no pool, so
    // close() ends the socket and the server session with it and drops every session-scoped lock. The explicit
    // `release` step is a courtesy that ran just before; its outcome changes nothing, so a failure of it is absorbed
    // (design-out: no pool exists to carry the session on).
    private static <T> Promise<T> closeAfterRelease(Connection conn,
                                                    SqlConnector session,
                                                    TransactionCallback<Unit> release,
                                                    Result<T> bodyResult) {
        return invoke(release, session).fold(_ -> closeAndResolve(conn, bodyResult));
    }

    /// The pooled connection goes back on EVERY exit of `body` (success or failure): `Connection.close()` of a pooled
    /// connection is the release (`PgConnection.close` runs the pool's `onRelease`, PgConnectionPool wires it at
    /// connection creation), and neither commit nor rollback returns it. A release that fails is absorbed (design-out: the
    /// pool's release cannot fail), so the body's own result or cause is what the caller sees.
    private <T> Promise<T> borrowed(Fn1<Promise<T>, Connection> body) {
        return connectible.getConnection()
                          .flatMap(conn -> runAndRelease(conn, body));
    }

    private static <T> Promise<T> runAndRelease(Connection conn, Fn1<Promise<T>, Connection> body) {
        return body.apply(conn)
                   .fold(result -> closeAndResolve(conn, result));
    }

    /// A subscription pins its pooled connection for as long as it listens (LISTEN is session state), so the connection
    /// goes back when the subscription ends: on a failed subscribe at once, otherwise on the first `unlisten` (a repeated
    /// `unlisten` is a no-op success, so the pool never receives the same connection twice). A failed `unlisten` DISCARDS
    /// the connection (closes its socket, which ends its LISTEN registrations, then hands the dead connection to the pool,
    /// which drops it and frees the slot): a connection that may still be listening must not be handed to another
    /// borrower, and a dead one must not hold a slot. Limit: a subscription dropped without `unlisten`, or a `stop()` with a
    /// live subscription, still holds its connection (`PgConnectionPool.close` only shuts down idle connections).
    private static Promise<Listening> subscribeOwning(Connection conn,
                                                      String channel,
                                                      Consumer<String> onNotification) {
        return conn.subscribe(channel, onNotification)
                   .map(listening -> releasingOnUnlisten(conn, listening))
                   .fold(result -> releaseOnFailure(conn, result));
    }

    private static Promise<Listening> releaseOnFailure(Connection conn, Result<Listening> result) {
        return result.fold(_ -> closeAndResolve(conn, result), Promise::success);
    }

    private static Listening releasingOnUnlisten(Connection conn, Listening listening) {
        var released = new AtomicBoolean(false);

        return () -> unlistenOnce(released, conn, listening);
    }

    private static Promise<Unit> unlistenOnce(AtomicBoolean released, Connection conn, Listening listening) {
        if (!released.compareAndSet(false, true)) {
            return Promise.unitPromise();
        }

        return listening.unlisten()
                        .fold(result -> releaseAfterUnlisten(conn, result));
    }

    private static Promise<Unit> releaseAfterUnlisten(Connection conn, Result<Unit> result) {
        return result.fold(_ -> discardKeeping(conn, result), _ -> closeAndResolve(conn, result));
    }

    private static Promise<Unit> discardKeeping(Connection conn, Result<Unit> result) {
        return discard(conn).fold(_ -> Promise.resolved(result));
    }

    /// Really closes the socket (a pooled `close()` is only the release), then releases the dead connection so the pool
    /// drops it and frees its slot (`PgConnectionPool.firstAliveConnection`).
    private static Promise<Unit> discard(Connection conn) {
        return (conn instanceof PgConnection pg
                ? pg.shutdown()
                : Promise.unitPromise()).fold(_ -> conn.close());
    }

    private static <T> Promise<T> closeAndResolve(Connection conn, Result<T> bodyResult) {
        return conn.close()
                   .fold(_ -> Promise.resolved(bodyResult));
    }

    private static <T> Promise<T> executeInTransaction(Transaction tx,
                                                       DatabaseConnectorConfig config,
                                                       TransactionCallback<T> callback) {
        var txConnector = new TransactionalSqlConnector(config, tx);

        return invoke(callback, txConnector).fold(result -> resolveTransaction(tx, result));
    }

    private static <T> Promise<T> resolveTransaction(Transaction tx, Result<T> result) {
        return result.fold(cause -> tx.rollback()
                                      .flatMap(_ -> cause.promise()),
                           value -> tx.commit()
                                      .map(_ -> value));
    }

    static String convertPlaceholders(String sql) {
        if (!sql.contains("?")) {
            return sql;
        }

        var sb = new StringBuilder(sql.length() + 16);
        int index = 1;

        for (int i = 0; i < sql.length(); i++) {
            var ch = sql.charAt(i);

            if (ch == '?') {
                sb.append('$').append(index++);
            } else {
                sb.append(ch);
            }
        }

        return sb.toString();
    }

    /// The pinned session: statements run directly on the one physical connection (autocommit), and `transactional`
    /// begins and commits on that SAME connection instead of borrowing another.
    private record PinnedSqlConnector(DatabaseConnectorConfig config, Connection connection) implements SqlConnector {
        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return connection.completeQuery(convertPlaceholders(sql),
                                            params)
                             .flatMap(rs -> extractSingleRow(rs, mapper, sql));
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return connection.completeQuery(convertPlaceholders(sql),
                                            params)
                             .flatMap(rs -> extractOptionalRow(rs, mapper));
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return connection.completeQuery(convertPlaceholders(sql),
                                            params)
                             .flatMap(rs -> extractAllRows(rs, mapper));
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            return connection.completeQuery(convertPlaceholders(sql),
                                            params)
                             .map(PgResultSet::affectedRows);
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            if (paramsList.isEmpty()) {
                return Promise.success(new int[0]);
            }

            return executeBatch(connection, convertPlaceholders(sql), paramsList);
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return runTransaction(connection, config, callback);
        }

        @Override
        public DatabaseType databaseType() {
            return config.effectiveType(DatabaseConnectorConfig.Transport.ASYNC);
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }
    }

    private record TransactionalSqlConnector(DatabaseConnectorConfig config, Transaction transaction) implements SqlConnector {
        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return transaction.completeQuery(convertPlaceholders(sql),
                                             params)
                              .flatMap(rs -> extractSingleRow(rs, mapper, sql));
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return transaction.completeQuery(convertPlaceholders(sql),
                                             params)
                              .flatMap(rs -> extractOptionalRow(rs, mapper));
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return transaction.completeQuery(convertPlaceholders(sql),
                                             params)
                              .flatMap(rs -> extractAllRows(rs, mapper));
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            return transaction.completeQuery(convertPlaceholders(sql),
                                             params)
                              .map(PgResultSet::affectedRows);
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            if (paramsList.isEmpty()) {
                return Promise.success(new int[0]);
            }

            return executeBatchInline(convertPlaceholders(sql), paramsList, new int[paramsList.size()], 0);
        }

        private Promise<int[]> executeBatchInline(String sql, List<Object[]> paramsList, int[] counts, int index) {
            if (index >= paramsList.size()) {
                return Promise.success(counts);
            }

            return transaction.completeQuery(sql,
                                             paramsList.get(index))
                              .map(rs -> recordAffectedRows(counts, index, rs))
                              .flatMap(_ -> executeBatchInline(sql, paramsList, counts, index + 1));
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return callback.execute(this);
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(unit());
        }
    }
}
