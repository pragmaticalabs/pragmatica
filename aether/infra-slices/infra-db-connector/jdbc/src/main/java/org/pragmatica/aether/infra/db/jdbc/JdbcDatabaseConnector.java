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

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// JDBC implementation of DatabaseConnector.
///
/// Uses a DataSource for connection pooling (typically HikariCP).
@SuppressWarnings("JBCT-EX-01") // Private helpers throw checked exceptions consumed by Promise.lift()
public final class JdbcDatabaseConnector implements DatabaseConnector {
    private final DatabaseConnectorConfig config;
    private final DataSource dataSource;

    private JdbcDatabaseConnector(DatabaseConnectorConfig config, DataSource dataSource) {
        this.config = config;
        this.dataSource = dataSource;
    }

    /// Creates a JDBC connector with the given configuration and data source.
    ///
    /// @param config     Connector configuration
    /// @param dataSource JDBC DataSource (typically from HikariCP)
    /// @return New JdbcDatabaseConnector instance
    public static JdbcDatabaseConnector jdbcDatabaseConnector(DatabaseConnectorConfig config, DataSource dataSource) {
        return new JdbcDatabaseConnector(config, dataSource);
    }

    @Override
    public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return Promise.lift(e -> mapException(e, sql), () -> runQueryOne(sql, params, mapper));
    }

    @Override
    public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
        return Promise.lift(e -> mapException(e, sql), () -> runQueryOptional(sql, params, mapper));
    }

    @Override
    public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return Promise.lift(e -> mapException(e, sql), () -> runQueryList(sql, params, mapper));
    }

    @Override
    public Promise<Integer> update(String sql, Object... params) {
        return Promise.lift(e -> mapException(e, sql), () -> runUpdate(sql, params));
    }

    @Override
    public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
        return Promise.lift(e -> mapException(e, sql), () -> runBatch(sql, paramsList));
    }

    @Override
    public <T> Promise<T> transactional(TransactionCallback<T> callback) {
        return Promise.lift(DatabaseConnectorError::databaseFailure, () -> runTransaction(callback));
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
                      .replaceResult(result -> result.fold(_ -> success(false),
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

    private <T> T runQueryOne(String sql, Object[] params, RowMapper<T> mapper) throws Exception {
        try (var conn = dataSource.getConnection();
             var stmt = buildStatement(conn, sql, params);
             var rs = stmt.executeQuery()) {
            if (!rs.next()) {
                return DatabaseConnectorError.ResultNotFound.INSTANCE.<T> result()
                                             .unwrap();
            }
            var accessor = new JdbcRowAccessor(rs);
            var result = mapper.map(accessor);
            if (rs.next()) {
                return DatabaseConnectorError.multipleResults(countRemaining(rs) + 2)
                                             .<T> result()
                                             .unwrap();
            }
            return result.unwrap();
        }
    }

    private <T> Option<T> runQueryOptional(String sql, Object[] params, RowMapper<T> mapper) throws Exception {
        try (var conn = dataSource.getConnection();
             var stmt = buildStatement(conn, sql, params);
             var rs = stmt.executeQuery()) {
            if (!rs.next()) {
                return none();
            }
            var accessor = new JdbcRowAccessor(rs);
            return mapper.map(accessor)
                         .map(Option::some)
                         .or(Option::none);
        }
    }

    private <T> List<T> runQueryList(String sql, Object[] params, RowMapper<T> mapper) throws Exception {
        try (var conn = dataSource.getConnection();
             var stmt = buildStatement(conn, sql, params);
             var rs = stmt.executeQuery()) {
            var results = new ArrayList<T>();
            var accessor = new JdbcRowAccessor(rs);
            while (rs.next()) {
                mapper.map(accessor)
                      .onSuccess(results::add);
            }
            return results;
        }
    }

    private int runUpdate(String sql, Object[] params) throws Exception {
        try (var conn = dataSource.getConnection();
             var stmt = buildStatement(conn, sql, params)) {
            return stmt.executeUpdate();
        }
    }

    private int[] runBatch(String sql, List<Object[]> paramsList) throws Exception {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            for (var params : paramsList) {
                applyParameters(stmt, params);
                stmt.addBatch();
            }
            return stmt.executeBatch();
        }
    }

    private <T> T runTransaction(TransactionCallback<T> callback) throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try{
                var transactionalConnector = new TransactionalJdbcConnector(config, conn);
                var result = callback.execute(transactionalConnector)
                                     .await();
                return result.fold(cause -> rollbackAndFail(conn, cause), value -> commitAndReturn(conn, value));
            } catch (Exception e) {
                rollbackSilently(conn);
                throw e;
            }
        }
    }

    private <T> T rollbackAndFail(Connection conn, org.pragmatica.lang.Cause cause) {
        rollbackSilently(conn);
        return DatabaseConnectorError.connectionFailed(cause.message())
                                     .<T> result()
                                     .unwrap();
    }

    private <T> T commitAndReturn(Connection conn, T value) {
        commitConnection(conn);
        return value;
    }

    private PreparedStatement buildStatement(Connection conn, String sql, Object[] params) throws SQLException {
        var stmt = conn.prepareStatement(sql);
        stmt.setQueryTimeout((int) config.poolConfig()
                                        .connectionTimeout()
                                        .toSeconds());
        applyParameters(stmt, params);
        return stmt;
    }

    private void applyParameters(PreparedStatement stmt, Object[] params) throws SQLException {
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
        try{
            conn.rollback();
        } catch (SQLException ignored) {}
    }

    private void commitConnection(Connection conn) {
        Result.lift(DatabaseConnectorError::databaseFailure, conn::commit)
              .unwrap();
    }

    private static DatabaseConnectorError mapException(Throwable throwable, String sql) {
        return switch (throwable) {
            case SQLTimeoutException _ -> DatabaseConnectorError.timeout(sql);
            case SQLIntegrityConstraintViolationException e -> DatabaseConnectorError.constraintViolation(e.getMessage());
            case SQLTransactionRollbackException e -> DatabaseConnectorError.transactionRollback(e.getMessage());
            case SQLException e -> option(e.getSQLState()).filter(s -> s.startsWith("08"))
                                         .map(_ -> (DatabaseConnectorError) DatabaseConnectorError.connectionFailed(e.getMessage(),
                                                                                                                    e))
                                         .or(() -> DatabaseConnectorError.queryFailed(sql, e));
            default -> DatabaseConnectorError.databaseFailure(throwable);
        };
    }

    /// Transaction-bound connector that uses a single connection.
    private record TransactionalJdbcConnector(DatabaseConnectorConfig config, Connection conn) implements DatabaseConnector {
        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.lift(e -> mapException(e, sql), () -> txQueryOne(sql, params, mapper));
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.lift(e -> mapException(e, sql), () -> txQueryOptional(sql, params, mapper));
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.lift(e -> mapException(e, sql), () -> txQueryList(sql, params, mapper));
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            return Promise.lift(e -> mapException(e, sql), () -> txUpdate(sql, params));
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            return Promise.lift(e -> mapException(e, sql), () -> txBatch(sql, paramsList));
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            // Already in a transaction, just execute directly
            return callback.execute(this);
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.lift(DatabaseConnectorError::databaseFailure,
                                () -> conn.isValid(5))
                          .replaceResult(result -> result.fold(_ -> success(false),
                                                               Result::success));
        }

        private <T> T txQueryOne(String sql, Object[] params, RowMapper<T> mapper) throws Exception {
            try (var stmt = txBuildStatement(sql, params);
                 var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return DatabaseConnectorError.ResultNotFound.INSTANCE.<T> result()
                                                 .unwrap();
                }
                var accessor = new JdbcRowAccessor(rs);
                var result = mapper.map(accessor);
                if (rs.next()) {
                    return DatabaseConnectorError.multipleResults(2)
                                                 .<T> result()
                                                 .unwrap();
                }
                return result.unwrap();
            }
        }

        private <T> Option<T> txQueryOptional(String sql, Object[] params, RowMapper<T> mapper) throws Exception {
            try (var stmt = txBuildStatement(sql, params);
                 var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return none();
                }
                var accessor = new JdbcRowAccessor(rs);
                return mapper.map(accessor)
                             .map(Option::some)
                             .or(Option::none);
            }
        }

        private <T> List<T> txQueryList(String sql, Object[] params, RowMapper<T> mapper) throws Exception {
            try (var stmt = txBuildStatement(sql, params);
                 var rs = stmt.executeQuery()) {
                var results = new ArrayList<T>();
                var accessor = new JdbcRowAccessor(rs);
                while (rs.next()) {
                    mapper.map(accessor)
                          .onSuccess(results::add);
                }
                return results;
            }
        }

        private int txUpdate(String sql, Object[] params) throws Exception {
            try (var stmt = txBuildStatement(sql, params)) {
                return stmt.executeUpdate();
            }
        }

        private int[] txBatch(String sql, List<Object[]> paramsList) throws Exception {
            try (var stmt = conn.prepareStatement(sql)) {
                for (var params : paramsList) {
                    txApplyParameters(stmt, params);
                    stmt.addBatch();
                }
                return stmt.executeBatch();
            }
        }

        private PreparedStatement txBuildStatement(String sql, Object[] params) throws SQLException {
            var stmt = conn.prepareStatement(sql);
            stmt.setQueryTimeout((int) config.poolConfig()
                                            .connectionTimeout()
                                            .toSeconds());
            txApplyParameters(stmt, params);
            return stmt;
        }

        private void txApplyParameters(PreparedStatement stmt, Object[] params) throws SQLException {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
        }
    }
}
