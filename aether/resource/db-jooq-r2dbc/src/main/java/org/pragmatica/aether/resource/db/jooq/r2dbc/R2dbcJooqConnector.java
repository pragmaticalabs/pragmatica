package org.pragmatica.aether.resource.db.jooq.r2dbc;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.jooq.JooqConnector;
import org.pragmatica.jooq.r2dbc.JooqR2dbcOperations;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.r2dbc.R2dbcError;
import org.pragmatica.r2dbc.ReactiveOperations;

import java.util.List;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/// R2DBC implementation of JooqConnector for reactive type-safe SQL queries.
///
/// Combines jOOQ's type-safe query building with R2DBC's reactive execution.
/// Uses JooqR2dbcOperations from integrations/db/jooq-r2dbc for Promise-based execution.
public final class R2dbcJooqConnector implements JooqConnector {
    private final DatabaseConnectorConfig config;
    private final ConnectionFactory connectionFactory;
    private final SQLDialect dialect;
    private final JooqR2dbcOperations operations;

    private R2dbcJooqConnector(DatabaseConnectorConfig config,
                               ConnectionFactory connectionFactory,
                               SQLDialect dialect) {
        this.config = config;
        this.connectionFactory = connectionFactory;
        this.dialect = dialect;
        this.operations = JooqR2dbcOperations.jooqR2dbcOperations(connectionFactory, dialect);
    }

    /// Creates an R2DBC jOOQ connector with the given configuration and connection factory.
    ///
    /// @param config            Connector configuration
    /// @param connectionFactory R2DBC ConnectionFactory
    /// @return New R2dbcJooqConnector instance
    public static R2dbcJooqConnector r2dbcJooqConnector(DatabaseConnectorConfig config,
                                                        ConnectionFactory connectionFactory) {
        var dialect = JooqConnector.mapDialect(config.type());
        return new R2dbcJooqConnector(config, connectionFactory, dialect);
    }

    @Override
    public DSLContext dsl() {
        return operations.dsl();
    }

    @Override
    public <R extends Record> Promise<R> fetchOne(ResultQuery<R> query) {
        return operations.fetchOne(query)
                         .mapError(R2dbcJooqConnector::toConnectorError);
    }

    @Override
    public <R extends Record> Promise<Option<R>> fetchOptional(ResultQuery<R> query) {
        return operations.fetchOptional(query)
                         .mapError(R2dbcJooqConnector::toConnectorError);
    }

    @Override
    public <R extends Record> Promise<List<R>> fetch(ResultQuery<R> query) {
        return operations.fetch(query)
                         .mapError(R2dbcJooqConnector::toConnectorError);
    }

    @Override
    public Promise<Integer> execute(Query query) {
        return operations.execute(query)
                         .mapError(R2dbcJooqConnector::toConnectorError);
    }

    @Override
    public <T> Promise<T> transactional(JooqConnector.TransactionCallback<T> callback) {
        return withConnection(conn -> beginAndExecute(conn, callback).fold(result -> resolveTransaction(conn, result)));
    }

    private <T> Promise<T> beginAndExecute(Connection conn, JooqConnector.TransactionCallback<T> callback) {
        return ReactiveOperations.fromVoidPublisher(conn.beginTransaction())
                                 .flatMap(_ -> callback.execute(new TransactionalR2dbcJooqConnector(config,
                                                                                                    conn,
                                                                                                    dialect)))
                                 .flatMap(result -> commitAndResolve(conn, result));
    }

    private static <T> Promise<T> commitAndResolve(Connection conn, T result) {
        return ReactiveOperations.fromVoidPublisher(conn.commitTransaction())
                                 .map(_ -> result);
    }

    private static <T> Promise<T> resolveTransaction(Connection conn, Result<T> result) {
        return result.fold(cause -> rollbackAndFail(conn, cause), Promise::success);
    }

    private static <T> Promise<T> rollbackAndFail(Connection conn, Cause cause) {
        return ReactiveOperations.fromVoidPublisher(conn.rollbackTransaction())
                                 .fold(_ -> cause.promise());
    }

    @Override
    public DatabaseConnectorConfig config() {
        return config;
    }

    @Override
    public Promise<Boolean> isHealthy() {
        return operations.fetchOne(dsl().selectOne())
                         .map(_ -> true)
                         .recover(_ -> false);
    }

    @Override
    public Promise<Unit> stop() {
        if (connectionFactory instanceof AutoCloseable closeable) {
            return Promise.lift(DatabaseConnectorError::databaseFailure, closeable::close)
                          .mapToUnit();
        }
        return Promise.success(Unit.unit());
    }

    private <T> Promise<T> withConnection(java.util.function.Function<Connection, Promise<T>> operation) {
        return ReactiveOperations.<Connection> fromPublisher(connectionFactory.create())
                                 .flatMap(conn -> executeAndClose(conn, operation));
    }

    private <T> Promise<T> executeAndClose(Connection conn,
                                           java.util.function.Function<Connection, Promise<T>> operation) {
        return operation.apply(conn)
                        .fold(result -> closeAndResolve(conn, result));
    }

    private static <T> Promise<T> closeAndResolve(Connection conn, Result<T> result) {
        return ReactiveOperations.fromVoidPublisher(conn.close())
                                 .fold(_ -> Promise.resolved(result));
    }

    static DatabaseConnectorError toConnectorError(Cause cause) {
        if (cause instanceof R2dbcError r2dbcError) {
            return switch (r2dbcError) {
                case R2dbcError.NoResult _ -> DatabaseConnectorError.ResultNotFound.INSTANCE;
                case R2dbcError.MultipleResults m -> DatabaseConnectorError.multipleResults(m.count());
                case R2dbcError.ConnectionFailed c -> DatabaseConnectorError.connectionFailed(c.message());
                case R2dbcError.ConstraintViolation v -> DatabaseConnectorError.constraintViolation(v.constraint());
                case R2dbcError.Timeout t -> DatabaseConnectorError.timeout(t.operation());
                case R2dbcError.QueryFailed q -> DatabaseConnectorError.queryFailed("", q.message());
                case R2dbcError.DatabaseFailure d -> DatabaseConnectorError.databaseFailure(d.cause());
            };
        }
        return DatabaseConnectorError.databaseFailure(new RuntimeException(cause.message()));
    }

    /// A JooqConnector bound to a specific R2DBC Connection for transactional operations.
    private record TransactionalR2dbcJooqConnector(DatabaseConnectorConfig config,
                                                   Connection connection,
                                                   SQLDialect dialect)
    implements JooqConnector {
        @Override
        public DSLContext dsl() {
            return DSL.using(dialect);
        }

        @Override
        public <R extends Record> Promise<R> fetchOne(ResultQuery<R> query) {
            return Promise.lift(e -> mapException(e,
                                                  query.getSQL()),
                                () -> JooqConnector.extractSingleResult(DSL.using(connection, dialect)
                                                                           .fetch(query)))
                          .mapError(R2dbcJooqConnector::toConnectorError);
        }

        @Override
        public <R extends Record> Promise<Option<R>> fetchOptional(ResultQuery<R> query) {
            return Promise.lift(e -> mapException(e,
                                                  query.getSQL()),
                                () -> JooqConnector.extractOptionalResult(DSL.using(connection, dialect)
                                                                             .fetch(query)))
                          .mapError(R2dbcJooqConnector::toConnectorError);
        }

        @Override
        public <R extends Record> Promise<List<R>> fetch(ResultQuery<R> query) {
            return Promise.lift(e -> mapException(e,
                                                  query.getSQL()),
                                () -> List.copyOf(DSL.using(connection, dialect)
                                                     .fetch(query)))
                          .mapError(R2dbcJooqConnector::toConnectorError);
        }

        @Override
        public Promise<Integer> execute(Query query) {
            return Promise.lift(e -> mapException(e,
                                                  query.getSQL()),
                                () -> DSL.using(connection, dialect)
                                         .execute(query))
                          .mapError(R2dbcJooqConnector::toConnectorError);
        }

        @Override
        public <T> Promise<T> transactional(JooqConnector.TransactionCallback<T> callback) {
            return callback.execute(this);
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }

        private static R2dbcError mapException(Throwable e, String sql) {
            return R2dbcError.fromException(e, sql);
        }
    }
}
