package org.pragmatica.aether.resource.db.r2dbc;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.r2dbc.R2dbcError;
import org.pragmatica.r2dbc.R2dbcOperations;
import org.pragmatica.r2dbc.ReactiveOperations;

import java.util.List;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import io.r2dbc.spi.Statement;

/// R2DBC implementation of SqlConnector for reactive database access.
///
/// Uses R2dbcOperations from integrations/db/r2dbc for Promise-based reactive execution.
public final class R2dbcSqlConnector implements SqlConnector {
    private final DatabaseConnectorConfig config;
    private final ConnectionFactory connectionFactory;
    private final R2dbcOperations operations;

    private R2dbcSqlConnector(DatabaseConnectorConfig config, ConnectionFactory connectionFactory) {
        this.config = config;
        this.connectionFactory = connectionFactory;
        this.operations = R2dbcOperations.r2dbcOperations(connectionFactory);
    }

    /// Creates an R2DBC connector with the given configuration and connection factory.
    ///
    /// @param config            Connector configuration
    /// @param connectionFactory R2DBC ConnectionFactory
    /// @return New R2dbcSqlConnector instance
    public static R2dbcSqlConnector r2dbcSqlConnector(DatabaseConnectorConfig config,
                                                      ConnectionFactory connectionFactory) {
        return new R2dbcSqlConnector(config, connectionFactory);
    }

    /// Returns the underlying ConnectionFactory.
    ///
    /// @return R2DBC ConnectionFactory
    public ConnectionFactory connectionFactory() {
        return connectionFactory;
    }

    /// Returns the underlying R2dbcOperations for advanced use.
    ///
    /// @return R2dbcOperations instance
    public R2dbcOperations operations() {
        return operations;
    }

    @Override
    public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return operations.queryOne(sql,
                                   (row, meta) -> mapRow(row, mapper),
                                   params)
                         .flatMap(R2dbcSqlConnector::unwrapMappedResult);
    }

    @Override
    public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
        return operations.queryOptional(sql,
                                        (row, meta) -> mapRow(row, mapper),
                                        params)
                         .flatMap(R2dbcSqlConnector::unwrapOptionalResult);
    }

    @Override
    public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return operations.queryList(sql,
                                    (row, meta) -> mapRow(row, mapper),
                                    params)
                         .flatMap(R2dbcSqlConnector::collectSuccessfulResults);
    }

    @Override
    public Promise<Integer> update(String sql, Object... params) {
        return operations.update(sql, params)
                         .map(Long::intValue);
    }

    @Override
    public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
        if (paramsList.isEmpty()) {
            return Promise.success(new int[0]);
        }
        return withConnection(conn -> executeBatch(conn, sql, paramsList));
    }

    @Override
    public <T> Promise<T> transactional(SqlConnector.TransactionCallback<T> callback) {
        return withConnection(conn -> beginAndExecute(conn, callback).fold(result -> resolveTransaction(conn, result)));
    }

    @Override
    public DatabaseConnectorConfig config() {
        return config;
    }

    @Override
    public Promise<Boolean> isHealthy() {
        return operations.queryOne("SELECT 1",
                                   (row, meta) -> 1)
                         .map(_ -> true)
                         .recover(_ -> false);
    }

    @Override
    public Promise<Unit> stop() {
        if (connectionFactory instanceof AutoCloseable closeable) {
            return Promise.lift(DatabaseConnectorError::databaseFailure, () -> closeable.close());
        }
        return Promise.success(Unit.unit());
    }

    // --- Leaf: Row mapping ---
    private static <T> Result<T> mapRow(Row row, RowMapper<T> mapper) {
        return mapper.map(new R2dbcRowAccessor(row));
    }

    // --- Leaf: Unwrap single mapped result ---
    private static <T> Promise<T> unwrapMappedResult(Result<T> result) {
        return result.mapError(R2dbcSqlConnector::toConnectorError)
                     .async();
    }

    // --- Leaf: Unwrap optional mapped result ---
    private static <T> Promise<Option<T>> unwrapOptionalResult(Option<Result<T>> opt) {
        return opt.map(result -> unwrapMappedResult(result).map(Option::some))
                  .or(() -> Promise.success(Option.none()));
    }

    // --- Leaf: Collect results, fail-fast on first failure ---
    private static <T> Promise<List<T>> collectSuccessfulResults(List<Result<T>> results) {
        return Result.allOf(results)
                     .mapError(R2dbcSqlConnector::toConnectorError)
                     .async();
    }

    // --- Sequencer: Connection lifecycle ---
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

    // --- Sequencer: Transaction lifecycle ---
    private <T> Promise<T> beginAndExecute(Connection conn, SqlConnector.TransactionCallback<T> callback) {
        return ReactiveOperations.fromVoidPublisher(conn.beginTransaction())
                                 .flatMap(_ -> callback.execute(new TransactionalR2dbcConnector(config, conn)))
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

    // --- Sequencer: Batch execution ---
    private static Promise<int[]> executeBatch(Connection conn, String sql, List<Object[]> paramsList) {
        var stmt = buildBatchStatement(conn, sql, paramsList);
        return ReactiveOperations.<io.r2dbc.spi.Result> collectFromPublisher(stmt.execute(),
                                                                             e -> R2dbcError.fromException(e, sql))
                                 .flatMap(results -> collectUpdateCounts(results,
                                                                         new int[results.size()],
                                                                         0));
    }

    // --- Leaf: Build batch statement ---
    private static Statement buildBatchStatement(Connection conn, String sql, List<Object[]> paramsList) {
        var stmt = conn.createStatement(sql);
        for (int i = 0; i < paramsList.size(); i++) {
            bindParameters(stmt, paramsList.get(i));
            if (i < paramsList.size() - 1) {
                stmt.add();
            }
        }
        return stmt;
    }

    private static void bindParameters(Statement stmt, Object[] params) {
        for (int j = 0; j < params.length; j++) {
            stmt.bind(j, params[j]);
        }
    }

    // --- Iteration: Collect update counts recursively ---
    private static Promise<int[]> collectUpdateCounts(List<io.r2dbc.spi.Result> results, int[] counts, int index) {
        if (index >= results.size()) {
            return Promise.success(counts);
        }
        return ReactiveOperations.<Long> fromPublisher(results.get(index)
                                                              .getRowsUpdated())
                                 .map(count -> recordCount(counts, index, count))
                                 .flatMap(_ -> collectUpdateCounts(results, counts, index + 1));
    }

    private static int[] recordCount(int[] counts, int index, Long count) {
        counts[index] = count.intValue();
        return counts;
    }

    // --- Error mapping ---
    static DatabaseConnectorError toConnectorError(Cause cause) {
        if (cause instanceof R2dbcError r2dbcError) {
            return mapR2dbcError(r2dbcError);
        }
        return DatabaseConnectorError.databaseFailure(new RuntimeException(cause.message()));
    }

    private static DatabaseConnectorError mapR2dbcError(R2dbcError r2dbcError) {
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

    /// A SqlConnector bound to a specific R2DBC Connection for transactional operations.
    private record TransactionalR2dbcConnector(DatabaseConnectorConfig config, Connection connection)
    implements SqlConnector {
        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            var stmt = createStatement(connection, sql, params);
            return ReactiveOperations.<Result<T>> fromPublisher(flatMapResult(stmt.execute(),
                                                                              (row, meta) -> mapRow(row, mapper)),
                                                                e -> R2dbcError.fromException(e, sql))
                                     .flatMap(R2dbcSqlConnector::unwrapMappedResult);
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            var stmt = createStatement(connection, sql, params);
            return ReactiveOperations.<Result<T>> firstFromPublisher(flatMapResult(stmt.execute(),
                                                                                   (row, meta) -> mapRow(row, mapper)),
                                                                     e -> R2dbcError.fromException(e, sql))
                                     .flatMap(R2dbcSqlConnector::unwrapOptionalResult);
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            var stmt = createStatement(connection, sql, params);
            return ReactiveOperations.<Result<T>> collectFromPublisher(flatMapResult(stmt.execute(),
                                                                                     (row, meta) -> mapRow(row, mapper)),
                                                                       e -> R2dbcError.fromException(e, sql))
                                     .flatMap(R2dbcSqlConnector::collectSuccessfulResults);
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            var stmt = createStatement(connection, sql, params);
            return ReactiveOperations.<io.r2dbc.spi.Result> fromPublisher(stmt.execute(),
                                                                          e -> R2dbcError.fromException(e, sql))
                                     .flatMap(result -> ReactiveOperations.fromPublisher(result.getRowsUpdated(),
                                                                                         e -> R2dbcError.fromException(e,
                                                                                                                       sql)))
                                     .map(Long::intValue);
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            if (paramsList.isEmpty()) {
                return Promise.success(new int[0]);
            }
            var stmt = buildBatchStatement(connection, sql, paramsList);
            return ReactiveOperations.<io.r2dbc.spi.Result> collectFromPublisher(stmt.execute(),
                                                                                 e -> R2dbcError.fromException(e, sql))
                                     .flatMap(results -> collectUpdateCounts(results,
                                                                             new int[results.size()],
                                                                             0));
        }

        @Override
        public <T> Promise<T> transactional(SqlConnector.TransactionCallback<T> callback) {
            // Already in a transaction, just execute the callback with this connector
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

        private static Statement createStatement(Connection conn, String sql, Object[] params) {
            var stmt = conn.createStatement(sql);
            bindParameters(stmt, params);
            return stmt;
        }

        @SuppressWarnings({"unchecked", "JBCT-RET-01"})
        private <T> org.reactivestreams.Publisher<T> flatMapResult(org.reactivestreams.Publisher<? extends io.r2dbc.spi.Result> resultPublisher,
                                                                   java.util.function.BiFunction<Row, RowMetadata, T> mapper) {
            return subscriber -> resultPublisher.subscribe(new ResultFlatMapSubscriber<>(subscriber, mapper));
        }

        /// Subscriber that flat-maps R2DBC Result into mapped row values.
        @SuppressWarnings("JBCT-RET-01") // Reactive Streams Subscriber interface requires void methods
        private static final class ResultFlatMapSubscriber<T> implements org.reactivestreams.Subscriber<io.r2dbc.spi.Result> {
            private final org.reactivestreams.Subscriber<? super T> downstream;
            private final java.util.function.BiFunction<Row, RowMetadata, T> mapper;

            ResultFlatMapSubscriber(org.reactivestreams.Subscriber<? super T> downstream,
                                    java.util.function.BiFunction<Row, RowMetadata, T> mapper) {
                this.downstream = downstream;
                this.mapper = mapper;
            }

            @Override
            public void onSubscribe(org.reactivestreams.Subscription s) {
                s.request(1);
            }

            @Override
            @SuppressWarnings("unchecked")
            public void onNext(io.r2dbc.spi.Result result) {
                result.map(mapper)
                      .subscribe((org.reactivestreams.Subscriber<? super Object>) downstream);
            }

            @Override
            public void onError(Throwable t) {
                downstream.onError(t);
            }

            @Override
            public void onComplete() {}
        }
    }
}
