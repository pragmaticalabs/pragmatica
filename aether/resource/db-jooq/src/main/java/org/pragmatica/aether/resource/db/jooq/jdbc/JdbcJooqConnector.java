package org.pragmatica.aether.resource.db.jooq.jdbc;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.jooq.JooqConnector;
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
import java.util.List;

import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/// JDBC implementation of JooqConnector for type-safe jOOQ queries.
///
/// Provides jOOQ-specific operations backed by JDBC DataSource with
/// HikariCP connection pooling.
@SuppressWarnings("JBCT-EX-01") // JDBC/jOOQ adapter -- exceptions are caught at Promise.lift() boundary
public final class JdbcJooqConnector implements JooqConnector {
    private static final System.Logger LOG = System.getLogger(JdbcJooqConnector.class.getName());

    private final DatabaseConnectorConfig config;
    private final DataSource dataSource;
    private final SQLDialect dialect;
    private final DSLContext dsl;

    private JdbcJooqConnector(DatabaseConnectorConfig config, DataSource dataSource, SQLDialect dialect) {
        this.config = config;
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.dsl = DSL.using(dialect);
    }

    /// Creates a JDBC jOOQ connector with the given configuration and data source.
    ///
    /// @param config     Connector configuration
    /// @param dataSource JDBC DataSource
    /// @return New JdbcJooqConnector instance
    public static JdbcJooqConnector jdbcJooqConnector(DatabaseConnectorConfig config, DataSource dataSource) {
        var dialect = JooqConnector.mapDialect(config.type());
        return new JdbcJooqConnector(config, dataSource, dialect);
    }

    /// Creates a JDBC jOOQ connector with explicit SQL dialect.
    ///
    /// @param config     Connector configuration
    /// @param dataSource JDBC DataSource
    /// @param dialect    SQL dialect
    /// @return New JdbcJooqConnector instance
    public static JdbcJooqConnector jdbcJooqConnector(DatabaseConnectorConfig config,
                                                      DataSource dataSource,
                                                      SQLDialect dialect) {
        return new JdbcJooqConnector(config, dataSource, dialect);
    }

    @Override
    public DSLContext dsl() {
        return dsl;
    }

    @Override
    public <R extends Record> Promise<R> fetchOne(ResultQuery<R> query) {
        return Promise.lift(e -> mapException(e, query.getSQL()), () -> doFetchOne(query));
    }

    @Override
    public <R extends Record> Promise<Option<R>> fetchOptional(ResultQuery<R> query) {
        return Promise.lift(e -> mapException(e, query.getSQL()), () -> doFetchOptional(query));
    }

    @Override
    public <R extends Record> Promise<List<R>> fetch(ResultQuery<R> query) {
        return Promise.lift(e -> mapException(e, query.getSQL()), () -> doFetch(query));
    }

    @Override
    public Promise<Integer> execute(Query query) {
        return Promise.lift(e -> mapException(e, query.getSQL()), () -> doExecute(query));
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
                            () -> checkHealth(dataSource))
                      .recover(_ -> false);
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.lift(DatabaseConnectorError::databaseFailure, () -> closeDataSource(dataSource));
    }

    // --- Private helpers: named methods extracted from lambdas ---
    private <R extends Record> R doFetchOne(ResultQuery<R> query) throws Exception {
        try (var conn = dataSource.getConnection()) {
            return JooqConnector.extractSingleResult(DSL.using(conn, dialect)
                                                        .fetch(query));
        }
    }

    private <R extends Record> Option<R> doFetchOptional(ResultQuery<R> query) throws Exception {
        try (var conn = dataSource.getConnection()) {
            return JooqConnector.extractOptionalResult(DSL.using(conn, dialect)
                                                          .fetch(query));
        }
    }

    private <R extends Record> List<R> doFetch(ResultQuery<R> query) throws Exception {
        try (var conn = dataSource.getConnection()) {
            return DSL.using(conn, dialect)
                      .fetch(query);
        }
    }

    private int doExecute(Query query) throws Exception {
        try (var conn = dataSource.getConnection()) {
            return DSL.using(conn, dialect)
                      .execute(query);
        }
    }

    private <T> T runTransaction(TransactionCallback<T> callback) throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try{
                var transactionalConnector = new TransactionalJooqConnector(config, conn, dialect);
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
        return cause.<T> result()
                    .unwrap();
    }

    private <T> T commitAndReturn(Connection conn, T value) {
        commitConnection(conn);
        return value;
    }

    private Unit rollbackSilently(Connection conn) {
        try{
            conn.rollback();
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.DEBUG, "Rollback failed", e);
        }
        return unit();
    }

    private Unit commitConnection(Connection conn) {
        Result.lift(DatabaseConnectorError::databaseFailure, conn::commit)
              .unwrap();
        return unit();
    }

    private static boolean checkHealth(DataSource dataSource) throws Exception {
        try (var conn = dataSource.getConnection()) {
            return conn.isValid(5);
        }
    }

    private static Unit closeDataSource(DataSource dataSource) throws Exception {
        if (dataSource instanceof AutoCloseable closeable) {
            closeable.close();
        }
        return unit();
    }

    static DatabaseConnectorError mapException(Throwable throwable, String sql) {
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

    /// Transaction-bound jOOQ connector.
    private record TransactionalJooqConnector(DatabaseConnectorConfig config,
                                              Connection conn,
                                              SQLDialect dialect) implements JooqConnector {
        @Override
        public DSLContext dsl() {
            return DSL.using(conn, dialect);
        }

        @Override
        public <R extends Record> Promise<R> fetchOne(ResultQuery<R> query) {
            return Promise.lift(e -> mapException(e, query.getSQL()), () -> txFetchOne(query));
        }

        @Override
        public <R extends Record> Promise<Option<R>> fetchOptional(ResultQuery<R> query) {
            return Promise.lift(e -> mapException(e, query.getSQL()), () -> txFetchOptional(query));
        }

        @Override
        public <R extends Record> Promise<List<R>> fetch(ResultQuery<R> query) {
            return Promise.lift(e -> mapException(e, query.getSQL()),
                                () -> DSL.using(conn, dialect)
                                         .fetch(query));
        }

        @Override
        public Promise<Integer> execute(Query query) {
            return Promise.lift(e -> mapException(e, query.getSQL()),
                                () -> DSL.using(conn, dialect)
                                         .execute(query));
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return callback.execute(this);
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.lift(DatabaseConnectorError::databaseFailure,
                                () -> conn.isValid(5))
                          .recover(_ -> false);
        }

        private <R extends Record> R txFetchOne(ResultQuery<R> query) {
            return JooqConnector.extractSingleResult(DSL.using(conn, dialect)
                                                        .fetch(query));
        }

        private <R extends Record> Option<R> txFetchOptional(ResultQuery<R> query) {
            return JooqConnector.extractOptionalResult(DSL.using(conn, dialect)
                                                          .fetch(query));
        }
    }
}
