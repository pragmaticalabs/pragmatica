package org.pragmatica.aether.infra.db.jdbc;

import org.pragmatica.aether.infra.db.DatabaseConnector;
import org.pragmatica.aether.infra.db.DatabaseConnectorConfig;
import org.pragmatica.aether.infra.db.DatabaseConnectorError;
import org.pragmatica.aether.infra.db.RowMapper;
import org.pragmatica.aether.infra.db.TransactionCallback;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC implementation of DatabaseConnector.
 * <p>
 * Uses a DataSource for connection pooling (typically HikariCP).
 */
public final class JdbcDatabaseConnector implements DatabaseConnector {
    private final DatabaseConnectorConfig config;
    private final DataSource dataSource;

    private JdbcDatabaseConnector(DatabaseConnectorConfig config, DataSource dataSource) {
        this.config = config;
        this.dataSource = dataSource;
    }

    /**
     * Creates a JDBC connector with the given configuration and data source.
     *
     * @param config     Connector configuration
     * @param dataSource JDBC DataSource (typically from HikariCP)
     * @return New JdbcDatabaseConnector instance
     */
    public static JdbcDatabaseConnector jdbcDatabaseConnector(DatabaseConnectorConfig config, DataSource dataSource) {
        return new JdbcDatabaseConnector(config, dataSource);
    }

    @Override
    public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return Promise.lift(
            e -> mapException(e, sql),
            () -> executeQuery(sql, params, rs -> {
                if (!rs.next()) {
                    throw new NoResultException("Query returned no results");
                }
                var accessor = new JdbcRowAccessor(rs);
                var result = mapper.map(accessor);
                if (rs.next()) {
                    throw new MultipleResultsException("Query returned multiple results", countRemaining(rs) + 2);
                }
                return result.unwrap();
            })
        );
    }

    @Override
    public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
        return Promise.lift(
            e -> mapException(e, sql),
            () -> executeQuery(sql, params, rs -> {
                if (!rs.next()) {
                    return Option.none();
                }
                var accessor = new JdbcRowAccessor(rs);
                return mapper.map(accessor).map(Option::some).or(Option::none);
            })
        );
    }

    @Override
    public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return Promise.lift(
            e -> mapException(e, sql),
            () -> executeQuery(sql, params, rs -> {
                var results = new ArrayList<T>();
                var accessor = new JdbcRowAccessor(rs);
                while (rs.next()) {
                    mapper.map(accessor).onSuccess(results::add);
                }
                return results;
            })
        );
    }

    @Override
    public Promise<Integer> update(String sql, Object... params) {
        return Promise.lift(
            e -> mapException(e, sql),
            () -> {
                try (var conn = dataSource.getConnection();
                     var stmt = prepareStatement(conn, sql, params)) {
                    return stmt.executeUpdate();
                }
            }
        );
    }

    @Override
    public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
        return Promise.lift(
            e -> mapException(e, sql),
            () -> {
                try (var conn = dataSource.getConnection();
                     var stmt = conn.prepareStatement(sql)) {
                    for (var params : paramsList) {
                        setParameters(stmt, params);
                        stmt.addBatch();
                    }
                    return stmt.executeBatch();
                }
            }
        );
    }

    @Override
    public <T> Promise<T> transactional(TransactionCallback<T> callback) {
        return Promise.lift(
            DatabaseConnectorError::databaseFailure,
            () -> {
                try (var conn = dataSource.getConnection()) {
                    conn.setAutoCommit(false);
                    try {
                        var transactionalConnector = new TransactionalJdbcConnector(config, conn);
                        var result = callback.execute(transactionalConnector).await();
                        return result.fold(
                            cause -> {
                                rollbackSilently(conn);
                                throw new TransactionFailedException(cause.message());
                            },
                            value -> {
                                commitConnection(conn);
                                return value;
                            }
                        );
                    } catch (Exception e) {
                        rollbackSilently(conn);
                        throw e;
                    }
                }
            }
        );
    }

    @Override
    public DatabaseConnectorConfig config() {
        return config;
    }

    @Override
    public Promise<Boolean> isHealthy() {
        return Promise.lift(
            DatabaseConnectorError::databaseFailure,
            () -> {
                try (var conn = dataSource.getConnection()) {
                    return conn.isValid(5);
                }
            }
        ).replaceResult(result -> result.fold(_ -> Result.success(false), Result::success));
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.lift(
            DatabaseConnectorError::databaseFailure,
            () -> {
                if (dataSource instanceof AutoCloseable closeable) {
                    closeable.close();
                }
                return Unit.unit();
            }
        );
    }

    private <T> T executeQuery(String sql, Object[] params, ResultSetHandler<T> handler) throws Exception {
        try (var conn = dataSource.getConnection();
             var stmt = prepareStatement(conn, sql, params);
             var rs = stmt.executeQuery()) {
            return handler.handle(rs);
        }
    }

    private PreparedStatement prepareStatement(Connection conn, String sql, Object[] params) throws SQLException {
        var stmt = conn.prepareStatement(sql);
        setParameters(stmt, params);
        return stmt;
    }

    private void setParameters(PreparedStatement stmt, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            stmt.setObject(i + 1, params[i]);
        }
    }

    private int countRemaining(ResultSet rs) throws SQLException {
        int count = 0;
        while (rs.next()) {
            count++;
        }
        return count;
    }

    private void rollbackSilently(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // Best effort rollback
        }
    }

    private void commitConnection(Connection conn) {
        try {
            conn.commit();
        } catch (SQLException e) {
            throw new TransactionFailedException("Failed to commit: " + e.getMessage());
        }
    }

    private static DatabaseConnectorError mapException(Throwable throwable, String sql) {
        return switch (throwable) {
            case SQLTimeoutException _ -> DatabaseConnectorError.timeout(sql);
            case SQLIntegrityConstraintViolationException e -> DatabaseConnectorError.constraintViolation(e.getMessage());
            case SQLTransactionRollbackException e -> DatabaseConnectorError.transactionRollback(e.getMessage());
            case NoResultException _ -> DatabaseConnectorError.NoResult.INSTANCE;
            case MultipleResultsException e -> DatabaseConnectorError.multipleResults(e.count());
            case TransactionFailedException e -> DatabaseConnectorError.connectionFailed(e.getMessage());
            case SQLException e -> Option.option(e.getSQLState())
                                         .filter(s -> s.startsWith("08"))
                                         .map(_ -> (DatabaseConnectorError) DatabaseConnectorError.connectionFailed(e.getMessage(), e))
                                         .or(() -> DatabaseConnectorError.queryFailed(sql, e));
            default -> DatabaseConnectorError.databaseFailure(throwable);
        };
    }

    @FunctionalInterface
    private interface ResultSetHandler<T> {
        T handle(ResultSet rs) throws Exception;
    }

    private static final class NoResultException extends RuntimeException {
        NoResultException(String message) {
            super(message);
        }
    }

    private static final class MultipleResultsException extends RuntimeException {
        private final int count;

        MultipleResultsException(String message, int count) {
            super(message);
            this.count = count;
        }

        int count() {
            return count;
        }
    }

    private static final class TransactionFailedException extends RuntimeException {
        TransactionFailedException(String message) {
            super(message);
        }
    }

    /**
     * Transaction-bound connector that uses a single connection.
     */
    private record TransactionalJdbcConnector(DatabaseConnectorConfig config, Connection conn) implements DatabaseConnector {

        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.lift(
                e -> mapException(e, sql),
                () -> {
                    try (var stmt = prepareStatement(sql, params);
                         var rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            throw new NoResultException("Query returned no results");
                        }
                        var accessor = new JdbcRowAccessor(rs);
                        var result = mapper.map(accessor);
                        if (rs.next()) {
                            throw new MultipleResultsException("Query returned multiple results", 2);
                        }
                        return result.unwrap();
                    }
                }
            );
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.lift(
                e -> mapException(e, sql),
                () -> {
                    try (var stmt = prepareStatement(sql, params);
                         var rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            return Option.none();
                        }
                        var accessor = new JdbcRowAccessor(rs);
                        return mapper.map(accessor).map(Option::some).or(Option::none);
                    }
                }
            );
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.lift(
                e -> mapException(e, sql),
                () -> {
                    try (var stmt = prepareStatement(sql, params);
                         var rs = stmt.executeQuery()) {
                        var results = new ArrayList<T>();
                        var accessor = new JdbcRowAccessor(rs);
                        while (rs.next()) {
                            mapper.map(accessor).onSuccess(results::add);
                        }
                        return results;
                    }
                }
            );
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            return Promise.lift(
                e -> mapException(e, sql),
                () -> {
                    try (var stmt = prepareStatement(sql, params)) {
                        return stmt.executeUpdate();
                    }
                }
            );
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            return Promise.lift(
                e -> mapException(e, sql),
                () -> {
                    try (var stmt = conn.prepareStatement(sql)) {
                        for (var params : paramsList) {
                            setParameters(stmt, params);
                            stmt.addBatch();
                        }
                        return stmt.executeBatch();
                    }
                }
            );
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            // Already in a transaction, just execute directly
            return callback.execute(this);
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.lift(
                DatabaseConnectorError::databaseFailure,
                () -> conn.isValid(5)
            ).replaceResult(result -> result.fold(_ -> Result.success(false), Result::success));
        }

        private PreparedStatement prepareStatement(String sql, Object[] params) throws SQLException {
            var stmt = conn.prepareStatement(sql);
            setParameters(stmt, params);
            return stmt;
        }

        private void setParameters(PreparedStatement stmt, Object[] params) throws SQLException {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
        }
    }
}
