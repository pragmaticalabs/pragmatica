package org.pragmatica.aether.resource.db.async;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgResultSet;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.Listening;
import org.pragmatica.postgres.net.Transaction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;

/// postgres-async implementation of SqlConnector for native async database access.
///
/// Uses the postgres-async Connectible directly for zero-overhead database operations.
/// Converts `?` placeholders (SqlConnector convention) to `$1`, `$2`, etc. (PostgreSQL wire protocol).
final class PgAsyncSqlConnector implements AsyncSqlConnector {
    private final DatabaseConnectorConfig config;
    private final Connectible connectible;

    private PgAsyncSqlConnector(DatabaseConnectorConfig config, Connectible connectible) {
        this.config = config;
        this.connectible = connectible;
    }

    static PgAsyncSqlConnector pgAsyncSqlConnector(DatabaseConnectorConfig config, Connectible connectible) {
        return new PgAsyncSqlConnector(config, connectible);
    }

    @Override public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return connectible.completeQuery(convertPlaceholders(sql), params)
        .flatMap(rs -> extractSingleRow(rs, mapper, sql));
    }

    @Override public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
        return connectible.completeQuery(convertPlaceholders(sql), params)
        .flatMap(rs -> extractOptionalRow(rs, mapper));
    }

    @Override public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return connectible.completeQuery(convertPlaceholders(sql), params).flatMap(rs -> extractAllRows(rs, mapper));
    }

    @Override public Promise<Integer> update(String sql, Object... params) {
        return connectible.completeQuery(convertPlaceholders(sql), params).map(PgResultSet::affectedRows);
    }

    @Override public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
        if ( paramsList.isEmpty()) {
        return Promise.success(new int[0]);}
        return connectible.getConnection().flatMap(conn -> executeBatch(conn, convertPlaceholders(sql), paramsList));
    }

    @Override public <T> Promise<T> transactional(TransactionCallback<T> callback) {
        return connectible.getConnection().flatMap(conn -> runTransaction(conn, callback));
    }

    @Override public Promise<Listening> subscribe(String channel, Consumer<String> onNotification) {
        return connectible.getConnection().flatMap(conn -> conn.subscribe(channel, onNotification));
    }

    @Override public DatabaseConnectorConfig config() {
        return config;
    }

    @Override public Promise<Boolean> isHealthy() {
        return connectible.completeQuery("SELECT 1").map(_ -> true)
                                        .recover(_ -> false);
    }

    @Override public Promise<Unit> stop() {
        return connectible.close();
    }

    // --- Row extraction helpers ---
    private static <T> Promise<T> extractSingleRow(PgResultSet rs, RowMapper<T> mapper, String sql) {
        if ( rs.size() == 0) {
        return DatabaseConnectorError.ResultNotFound.INSTANCE.promise();}
        if ( rs.size() > 1) {
        return DatabaseConnectorError.multipleResults(rs.size()).promise();}
        return mapRow(rs.index(0), mapper);
    }

    private static <T> Promise<Option<T>> extractOptionalRow(PgResultSet rs, RowMapper<T> mapper) {
        if ( rs.size() == 0) {
        return Promise.success(none());}
        return mapRow(rs.index(0), mapper).map(v -> some(v));
    }

    private static <T> Promise<List<T>> extractAllRows(PgResultSet rs, RowMapper<T> mapper) {
        var results = new ArrayList<Result<T>>(rs.size());
        for ( var row : rs) {
        results.add(mapper.map(new PgRowAccessor(row)));}
        return Result.allOf(results).mapError(PgAsyncSqlConnector::toConnectorError)
                           .async();
    }

    private static <T> Promise<T> mapRow(PgRow row, RowMapper<T> mapper) {
        return mapper.map(new PgRowAccessor(row)).mapError(PgAsyncSqlConnector::toConnectorError)
                         .async();
    }

    // --- Batch execution ---
    private static Promise<int[]> executeBatch(Connection conn, String sql, List<Object[]> paramsList) {
        return conn.begin()
        .flatMap(tx -> executeBatchInTransaction(tx, sql, paramsList, new int[paramsList.size()], 0));
    }

    private static Promise<int[]> executeBatchInTransaction(Transaction tx,
                                                            String sql,
                                                            List<Object[]> paramsList,
                                                            int[] counts,
                                                            int index) {
        if ( index >= paramsList.size()) {
        return tx.commit().map(_ -> counts);}
        return tx.completeQuery(sql,
                                paramsList.get(index)).map(rs -> recordAffectedRows(counts, index, rs))
                               .flatMap(_ -> executeBatchInTransaction(tx, sql, paramsList, counts, index + 1))
                               .fold(result -> rollbackOnFailure(tx, result));
    }

    private static int recordAffectedRows(int[] counts, int index, PgResultSet rs) {
        counts[index] = rs.affectedRows();
        return rs.affectedRows();
    }

    private static <T> Promise<T> rollbackOnFailure(Transaction tx, Result<T> result) {
        return result.fold(cause -> tx.rollback().flatMap(_ -> cause.promise()),
                           Promise::success);
    }

    // --- Transaction execution ---
    private <T> Promise<T> runTransaction(Connection conn, TransactionCallback<T> callback) {
        return conn.begin().flatMap(tx -> executeInTransaction(tx, callback));
    }

    private <T> Promise<T> executeInTransaction(Transaction tx, TransactionCallback<T> callback) {
        var txConnector = new TransactionalSqlConnector(config, tx);
        return callback.execute(txConnector).fold(result -> resolveTransaction(tx, result));
    }

    private static <T> Promise<T> resolveTransaction(Transaction tx, Result<T> result) {
        return result.fold(cause -> tx.rollback().flatMap(_ -> cause.promise()),
                           value -> tx.commit().map(_ -> value));
    }

    // --- Placeholder conversion ---
    /// Converts `?` placeholders to PostgreSQL `$1`, `$2`, etc.
    /// If the SQL already uses `$N` placeholders, it is returned unchanged.
    static String convertPlaceholders(String sql) {
        if ( !sql.contains("?")) {
        return sql;}
        var sb = new StringBuilder(sql.length() + 16);
        int index = 1;
        for ( int i = 0; i < sql.length(); i++) {
            var ch = sql.charAt(i);
            if ( ch == '?') {
            sb.append('$').append(index++);} else
            {
            sb.append(ch);}
        }
        return sb.toString();
    }

    // --- Error mapping ---
    private static DatabaseConnectorError toConnectorError(Cause cause) {
        if ( cause instanceof DatabaseConnectorError dce) {
        return dce;}
        return DatabaseConnectorError.databaseFailure(new RuntimeException(cause.message()));
    }

    // --- Transactional connector ---
    private record TransactionalSqlConnector(DatabaseConnectorConfig config,
                                             Transaction transaction) implements SqlConnector {
        @Override public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return transaction.completeQuery(convertPlaceholders(sql), params)
            .flatMap(rs -> extractSingleRow(rs, mapper, sql));
        }

        @Override public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return transaction.completeQuery(convertPlaceholders(sql), params)
            .flatMap(rs -> extractOptionalRow(rs, mapper));
        }

        @Override public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return transaction.completeQuery(convertPlaceholders(sql), params)
            .flatMap(rs -> extractAllRows(rs, mapper));
        }

        @Override public Promise<Integer> update(String sql, Object... params) {
            return transaction.completeQuery(convertPlaceholders(sql), params).map(PgResultSet::affectedRows);
        }

        @Override public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            if ( paramsList.isEmpty()) {
            return Promise.success(new int[0]);}
            return executeBatchInline(convertPlaceholders(sql), paramsList, new int[paramsList.size()], 0);
        }

        private Promise<int[]> executeBatchInline(String sql, List<Object[]> paramsList, int[] counts, int index) {
            if ( index >= paramsList.size()) {
            return Promise.success(counts);}
            return transaction.completeQuery(sql,
                                             paramsList.get(index)).map(rs -> recordAffectedRows(counts, index, rs))
                                            .flatMap(_ -> executeBatchInline(sql, paramsList, counts, index + 1));
        }

        @Override public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return callback.execute(this);
        }

        @Override public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }

        @Override public Promise<Unit> stop() {
            return Promise.success(unit());
        }
    }
}
