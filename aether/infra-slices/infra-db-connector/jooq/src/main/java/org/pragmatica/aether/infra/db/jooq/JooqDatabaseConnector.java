package org.pragmatica.aether.infra.db.jooq;

import org.pragmatica.aether.infra.db.DatabaseConnector;
import org.pragmatica.aether.infra.db.DatabaseConnectorConfig;
import org.pragmatica.aether.infra.db.DatabaseConnectorError;
import org.pragmatica.aether.infra.db.DatabaseType;
import org.pragmatica.aether.infra.db.RowMapper;
import org.pragmatica.aether.infra.db.TransactionCallback;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.util.ArrayList;
import java.util.List;

import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/// jOOQ implementation of DatabaseConnector for type-safe SQL queries.
///
/// Provides both standard DatabaseConnector operations and jOOQ-specific
/// operations for type-safe query building.
@SuppressWarnings("JBCT-EX-01") // JDBC/jOOQ adapter — exceptions are caught at Promise.lift() boundary
public final class JooqDatabaseConnector implements DatabaseConnector {
    private final DatabaseConnectorConfig config;
    private final DataSource dataSource;
    private final SQLDialect dialect;
    private final DSLContext dsl;

    private JooqDatabaseConnector(DatabaseConnectorConfig config, DataSource dataSource, SQLDialect dialect) {
        this.config = config;
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.dsl = DSL.using(dialect);
    }

    /// Creates a jOOQ connector with the given configuration and data source.
    ///
    /// @param config     Connector configuration
    /// @param dataSource JDBC DataSource
    /// @return New JooqDatabaseConnector instance
    public static JooqDatabaseConnector jooqDatabaseConnector(DatabaseConnectorConfig config, DataSource dataSource) {
        var dialect = mapDialect(config.type());
        return new JooqDatabaseConnector(config, dataSource, dialect);
    }

    /// Creates a jOOQ connector with explicit SQL dialect.
    ///
    /// @param config     Connector configuration
    /// @param dataSource JDBC DataSource
    /// @param dialect    SQL dialect
    /// @return New JooqDatabaseConnector instance
    public static JooqDatabaseConnector jooqDatabaseConnector(DatabaseConnectorConfig config,
                                                              DataSource dataSource,
                                                              SQLDialect dialect) {
        return new JooqDatabaseConnector(config, dataSource, dialect);
    }

    /// Returns the DSLContext for type-safe query building.
    ///
    /// @return DSLContext instance
    public DSLContext dsl() {
        return dsl;
    }

    /// Fetches a single record from the query.
    ///
    /// @param query jOOQ ResultQuery
    /// @param <R>   Record type
    /// @return Promise with single record or failure
    public <R extends Record> Promise<R> fetchOne(ResultQuery<R> query) {
        return Promise.lift(e -> mapException(e, query.getSQL()),
                            () -> {
                                try (var conn = dataSource.getConnection()) {
                                    var result = DSL.using(conn, dialect)
                                                    .fetch(query);
                                    if (result.isEmpty()) {
                                        throw new NoResultException("Query returned no results");
                                    }
                                    if (result.size() > 1) {
                                        throw new MultipleResultsException("Query returned multiple results",
                                                                           result.size());
                                    }
                                    return result.get(0);
                                }
                            });
    }

    /// Fetches an optional record from the query.
    ///
    /// @param query jOOQ ResultQuery
    /// @param <R>   Record type
    /// @return Promise with Option containing record
    public <R extends Record> Promise<Option<R>> fetchOptional(ResultQuery<R> query) {
        return Promise.lift(e -> mapException(e, query.getSQL()),
                            () -> {
                                try (var conn = dataSource.getConnection()) {
                                    var result = DSL.using(conn, dialect)
                                                    .fetch(query);
                                    return result.isEmpty()
                                           ? Option.none()
                                           : Option.some(result.get(0));
                                }
                            });
    }

    /// Fetches all records from the query.
    ///
    /// @param query jOOQ ResultQuery
    /// @param <R>   Record type
    /// @return Promise with list of records
    public <R extends Record> Promise<List<R>> fetch(ResultQuery<R> query) {
        return Promise.lift(e -> mapException(e, query.getSQL()),
                            () -> {
                                try (var conn = dataSource.getConnection()) {
                                    return DSL.using(conn, dialect)
                                              .fetch(query);
                                }
                            });
    }

    /// Executes a jOOQ query.
    ///
    /// @param query jOOQ Query
    /// @return Promise with number of affected rows
    public Promise<Integer> execute(Query query) {
        return Promise.lift(e -> mapException(e, query.getSQL()),
                            () -> {
                                try (var conn = dataSource.getConnection()) {
                                    return DSL.using(conn, dialect)
                                              .execute(query);
                                }
                            });
    }

    // ========== DatabaseConnector Implementation ==========
    @Override
    public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return Promise.lift(e -> mapException(e, sql),
                            () -> {
                                try (var conn = dataSource.getConnection();
                                     var stmt = conn.prepareStatement(sql)) {
                                    setParameters(stmt, params);
                                    try (var rs = stmt.executeQuery()) {
                                        if (!rs.next()) {
                                            throw new NoResultException("Query returned no results");
                                        }
                                        var accessor = new JooqRowAccessor(rs);
                                        var result = mapper.map(accessor);
                                        if (rs.next()) {
                                            throw new MultipleResultsException("Query returned multiple results", 2);
                                        }
                                        return result.unwrap();
                                    }
                                }
                            });
    }

    @Override
    public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
        return Promise.lift(e -> mapException(e, sql),
                            () -> {
                                try (var conn = dataSource.getConnection();
                                     var stmt = conn.prepareStatement(sql)) {
                                    setParameters(stmt, params);
                                    try (var rs = stmt.executeQuery()) {
                                        if (!rs.next()) {
                                            return Option.none();
                                        }
                                        var accessor = new JooqRowAccessor(rs);
                                        return mapper.map(accessor)
                                                     .map(Option::some)
                                                     .or(Option::none);
                                    }
                                }
                            });
    }

    @Override
    public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return Promise.lift(e -> mapException(e, sql),
                            () -> {
                                try (var conn = dataSource.getConnection();
                                     var stmt = conn.prepareStatement(sql)) {
                                    setParameters(stmt, params);
                                    try (var rs = stmt.executeQuery()) {
                                        var results = new ArrayList<T>();
                                        var accessor = new JooqRowAccessor(rs);
                                        while (rs.next()) {
                                            mapper.map(accessor)
                                                  .onSuccess(results::add);
                                        }
                                        return results;
                                    }
                                }
                            });
    }

    @Override
    public Promise<Integer> update(String sql, Object... params) {
        return Promise.lift(e -> mapException(e, sql),
                            () -> {
                                try (var conn = dataSource.getConnection();
                                     var stmt = conn.prepareStatement(sql)) {
                                    setParameters(stmt, params);
                                    return stmt.executeUpdate();
                                }
                            });
    }

    @Override
    public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
        return Promise.lift(e -> mapException(e, sql),
                            () -> {
                                try (var conn = dataSource.getConnection();
                                     var stmt = conn.prepareStatement(sql)) {
                                    for (var params : paramsList) {
                                        setParameters(stmt, params);
                                        stmt.addBatch();
                                    }
                                    return stmt.executeBatch();
                                }
                            });
    }

    @Override
    public <T> Promise<T> transactional(TransactionCallback<T> callback) {
        return Promise.lift(DatabaseConnectorError::databaseFailure,
                            () -> {
                                try (var conn = dataSource.getConnection()) {
                                    conn.setAutoCommit(false);
                                    try{
                                        var transactionalConnector = new TransactionalJooqConnector(config,
                                                                                                    conn,
                                                                                                    dialect);
                                        var result = callback.execute(transactionalConnector)
                                                             .await();
                                        return result.fold(cause -> handleTransactionFailure(conn, cause),
                                                           value -> handleTransactionSuccess(conn, value));
                                    } catch (Exception e) {
                                        rollbackSilently(conn);
                                        throw e;
                                    }
                                }
                            });
    }

    private <T> T handleTransactionFailure(Connection conn, org.pragmatica.lang.Cause cause) {
        rollbackSilently(conn);
        throw new TransactionFailedException(cause.message());
    }

    private <T> T handleTransactionSuccess(Connection conn, T value) {
        commitConnection(conn);
        return value;
    }

    @Override
    public DatabaseConnectorConfig config() {
        return config;
    }

    @Override
    public Promise<Boolean> isHealthy() {
        return Promise.lift(DatabaseConnectorError::databaseFailure,
                            () -> {
                                try (var conn = dataSource.getConnection()) {
                                    return conn.isValid(5);
                                }
                            })
                      .replaceResult(result -> result.fold(_ -> Result.success(false),
                                                           Result::success));
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.lift(DatabaseConnectorError::databaseFailure,
                            () -> {
                                if (dataSource instanceof AutoCloseable closeable) {
                                    closeable.close();
                                }
                                return Unit.unit();
                            });
    }

    private void setParameters(java.sql.PreparedStatement stmt, Object[] params) throws SQLException {
        stmt.setQueryTimeout((int) config.poolConfig()
                                        .connectionTimeout()
                                        .toSeconds());
        for (int i = 0; i < params.length; i++) {
            stmt.setObject(i + 1, params[i]);
        }
    }

    private void rollbackSilently(Connection conn) {
        try{
            conn.rollback();
        } catch (SQLException ignored) {}
    }

    private void commitConnection(Connection conn) {
        try{
            conn.commit();
        } catch (SQLException e) {
            throw new TransactionFailedException("Failed to commit: " + e.getMessage());
        }
    }

    private static SQLDialect mapDialect(DatabaseType type) {
        return switch (type) {
            case POSTGRESQL, COCKROACHDB -> SQLDialect.POSTGRES;
            case MYSQL -> SQLDialect.MYSQL;
            case MARIADB -> SQLDialect.MARIADB;
            case H2 -> SQLDialect.H2;
            case SQLITE -> SQLDialect.SQLITE;
            // ORACLE and SQLSERVER require commercial jOOQ license
            case ORACLE, SQLSERVER, DB2 -> SQLDialect.DEFAULT;
        };
    }

    private static DatabaseConnectorError mapException(Throwable throwable, String sql) {
        return switch (throwable) {
            case SQLTimeoutException _ -> DatabaseConnectorError.timeout(sql);
            case SQLIntegrityConstraintViolationException e -> DatabaseConnectorError.constraintViolation(e.getMessage());
            case SQLTransactionRollbackException e -> DatabaseConnectorError.transactionRollback(e.getMessage());
            case NoResultException _ -> DatabaseConnectorError.ResultNotFound.INSTANCE;
            case MultipleResultsException e -> DatabaseConnectorError.multipleResults(e.count());
            case TransactionFailedException e -> DatabaseConnectorError.connectionFailed(e.getMessage());
            case SQLException e -> Option.option(e.getSQLState())
                                         .filter(s -> s.startsWith("08"))
                                         .map(_ -> (DatabaseConnectorError) DatabaseConnectorError.connectionFailed(e.getMessage(),
                                                                                                                    e))
                                         .or(() -> DatabaseConnectorError.queryFailed(sql, e));
            default -> DatabaseConnectorError.databaseFailure(throwable);
        };
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

    /// Transaction-bound jOOQ connector.
    private record TransactionalJooqConnector(DatabaseConnectorConfig config, Connection conn, SQLDialect dialect) implements DatabaseConnector {
        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.lift(e -> mapException(e, sql),
                                () -> {
                                    try (var stmt = conn.prepareStatement(sql)) {
                                        setParameters(stmt, params);
                                        try (var rs = stmt.executeQuery()) {
                                            if (!rs.next()) {
                                                throw new NoResultException("Query returned no results");
                                            }
                                            var accessor = new JooqRowAccessor(rs);
                                            var result = mapper.map(accessor);
                                            if (rs.next()) {
                                                throw new MultipleResultsException("Query returned multiple results", 2);
                                            }
                                            return result.unwrap();
                                        }
                                    }
                                });
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.lift(e -> mapException(e, sql),
                                () -> {
                                    try (var stmt = conn.prepareStatement(sql)) {
                                        setParameters(stmt, params);
                                        try (var rs = stmt.executeQuery()) {
                                            if (!rs.next()) {
                                                return Option.none();
                                            }
                                            var accessor = new JooqRowAccessor(rs);
                                            return mapper.map(accessor)
                                                         .map(Option::some)
                                                         .or(Option::none);
                                        }
                                    }
                                });
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.lift(e -> mapException(e, sql),
                                () -> {
                                    try (var stmt = conn.prepareStatement(sql)) {
                                        setParameters(stmt, params);
                                        try (var rs = stmt.executeQuery()) {
                                            var results = new ArrayList<T>();
                                            var accessor = new JooqRowAccessor(rs);
                                            while (rs.next()) {
                                                mapper.map(accessor)
                                                      .onSuccess(results::add);
                                            }
                                            return results;
                                        }
                                    }
                                });
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            return Promise.lift(e -> mapException(e, sql),
                                () -> {
                                    try (var stmt = conn.prepareStatement(sql)) {
                                        setParameters(stmt, params);
                                        return stmt.executeUpdate();
                                    }
                                });
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            return Promise.lift(e -> mapException(e, sql),
                                () -> {
                                    try (var stmt = conn.prepareStatement(sql)) {
                                        for (var params : paramsList) {
                                            setParameters(stmt, params);
                                            stmt.addBatch();
                                        }
                                        return stmt.executeBatch();
                                    }
                                });
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return callback.execute(this);
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.lift(DatabaseConnectorError::databaseFailure,
                                () -> conn.isValid(5))
                          .replaceResult(result -> result.fold(_ -> Result.success(false),
                                                               Result::success));
        }

        private void setParameters(java.sql.PreparedStatement stmt, Object[] params) throws SQLException {
            stmt.setQueryTimeout((int) config.poolConfig()
                                            .connectionTimeout()
                                            .toSeconds());
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
        }
    }
}
