package org.pragmatica.aether.infra.db.jooq.r2dbc;

import org.pragmatica.aether.infra.db.DatabaseConnector;
import org.pragmatica.aether.infra.db.DatabaseConnectorConfig;
import org.pragmatica.aether.infra.db.DatabaseConnectorError;
import org.pragmatica.aether.infra.db.DatabaseType;
import org.pragmatica.aether.infra.db.RowMapper;
import org.pragmatica.aether.infra.db.TransactionCallback;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.jooq.r2dbc.JooqR2dbcOperations;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.r2dbc.R2dbcError;
import org.pragmatica.r2dbc.ReactiveOperations;

import java.util.List;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.jooq.SQLDialect;

/// jOOQ R2DBC implementation of DatabaseConnector for reactive type-safe SQL queries.
///
/// Combines jOOQ's type-safe query building with R2DBC's reactive execution.
///
/// Uses JooqR2dbcOperations from integrations/db/jooq-r2dbc for Promise-based execution.
@SuppressWarnings("JBCT-RET-01") // Reactive Streams Subscriber callbacks — void required by specification
public final class JooqR2dbcDatabaseConnector implements DatabaseConnector {
    private final DatabaseConnectorConfig config;
    private final ConnectionFactory connectionFactory;
    private final SQLDialect dialect;
    private final JooqR2dbcOperations operations;

    private JooqR2dbcDatabaseConnector(DatabaseConnectorConfig config,
                                       ConnectionFactory connectionFactory,
                                       SQLDialect dialect) {
        this.config = config;
        this.connectionFactory = connectionFactory;
        this.dialect = dialect;
        this.operations = JooqR2dbcOperations.jooqR2dbcOperations(connectionFactory, dialect);
    }

    /// Creates a jOOQ R2DBC connector with the given configuration and connection factory.
    ///
    /// @param config            Connector configuration
    /// @param connectionFactory R2DBC ConnectionFactory
    /// @return New JooqR2dbcDatabaseConnector instance
    public static JooqR2dbcDatabaseConnector jooqR2dbcDatabaseConnector(DatabaseConnectorConfig config,
                                                                        ConnectionFactory connectionFactory) {
        var dialect = mapDialect(config.type());
        return new JooqR2dbcDatabaseConnector(config, connectionFactory, dialect);
    }

    /// Creates a jOOQ R2DBC connector with explicit SQL dialect.
    ///
    /// @param config            Connector configuration
    /// @param connectionFactory R2DBC ConnectionFactory
    /// @param dialect           SQL dialect
    /// @return New JooqR2dbcDatabaseConnector instance
    public static JooqR2dbcDatabaseConnector jooqR2dbcDatabaseConnector(DatabaseConnectorConfig config,
                                                                        ConnectionFactory connectionFactory,
                                                                        SQLDialect dialect) {
        return new JooqR2dbcDatabaseConnector(config, connectionFactory, dialect);
    }

    /// Returns the underlying ConnectionFactory.
    ///
    /// @return R2DBC ConnectionFactory
    public ConnectionFactory connectionFactory() {
        return connectionFactory;
    }

    /// Returns the DSLContext for type-safe query building.
    ///
    /// @return DSLContext instance
    public DSLContext dsl() {
        return operations.dsl();
    }

    /// Returns the SQL dialect.
    ///
    /// @return SQL dialect
    public SQLDialect dialect() {
        return dialect;
    }

    /// Returns the underlying JooqR2dbcOperations for advanced use.
    ///
    /// @return JooqR2dbcOperations instance
    public JooqR2dbcOperations operations() {
        return operations;
    }

    /// Fetches a single record from the query.
    ///
    /// @param query jOOQ ResultQuery
    /// @param <R>   Record type
    /// @return Promise with single record or failure
    public <R extends Record> Promise<R> fetchOne(ResultQuery<R> query) {
        return operations.fetchOne(query)
                         .mapError(JooqR2dbcDatabaseConnector::toConnectorError);
    }

    /// Fetches an optional record from the query.
    ///
    /// @param query jOOQ ResultQuery
    /// @param <R>   Record type
    /// @return Promise with Option containing record
    public <R extends Record> Promise<Option<R>> fetchOptional(ResultQuery<R> query) {
        return operations.fetchOptional(query)
                         .mapError(JooqR2dbcDatabaseConnector::toConnectorError);
    }

    /// Fetches all records from the query.
    ///
    /// @param query jOOQ ResultQuery
    /// @param <R>   Record type
    /// @return Promise with list of records
    public <R extends Record> Promise<List<R>> fetch(ResultQuery<R> query) {
        return operations.fetch(query)
                         .mapError(JooqR2dbcDatabaseConnector::toConnectorError);
    }

    /// Executes a jOOQ query.
    ///
    /// @param query jOOQ Query
    /// @return Promise with number of affected rows
    public Promise<Integer> execute(Query query) {
        return operations.execute(query)
                         .mapError(JooqR2dbcDatabaseConnector::toConnectorError);
    }

    // ========== DatabaseConnector Implementation ==========
    @Override
    public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return withConnection(conn -> {
                                  var stmt = conn.createStatement(sql);
                                  for (int i = 0; i < params.length; i++) {
                                      stmt.bind(i, params[i]);
                                  }
                                  return ReactiveOperations.<Result<T>> fromPublisher(flatMapResult(stmt.execute(),
                                                                                                    (row, meta) -> mapper.map(new R2dbcRowAccessor(row))),
                                                                                      e -> R2dbcError.fromException(e,
                                                                                                                    sql))
                                                           .flatMap(result -> result.mapError(JooqR2dbcDatabaseConnector::toConnectorError)
                                                                                    .async());
                              });
    }

    @Override
    public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
        return withConnection(conn -> {
                                  var stmt = conn.createStatement(sql);
                                  for (int i = 0; i < params.length; i++) {
                                      stmt.bind(i, params[i]);
                                  }
                                  return ReactiveOperations.<Result<T>> firstFromPublisher(flatMapResult(stmt.execute(),
                                                                                                         (row, meta) -> mapper.map(new R2dbcRowAccessor(row))),
                                                                                           e -> R2dbcError.fromException(e,
                                                                                                                         sql))
                                                           .flatMap(opt -> opt.fold(() -> Promise.success(Option.none()),
                                                                                    result -> result.mapError(JooqR2dbcDatabaseConnector::toConnectorError)
                                                                                                    .async()
                                                                                                    .map(Option::some)));
                              });
    }

    @Override
    public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return withConnection(conn -> {
                                  var stmt = conn.createStatement(sql);
                                  for (int i = 0; i < params.length; i++) {
                                      stmt.bind(i, params[i]);
                                  }
                                  return ReactiveOperations.<Result<T>> collectFromPublisher(flatMapResult(stmt.execute(),
                                                                                                           (row, meta) -> mapper.map(new R2dbcRowAccessor(row))),
                                                                                             e -> R2dbcError.fromException(e,
                                                                                                                           sql))
                                                           .flatMap(JooqR2dbcDatabaseConnector::collectSuccessfulResults);
                              });
    }

    @Override
    public Promise<Integer> update(String sql, Object... params) {
        return withConnection(conn -> {
                                  var stmt = conn.createStatement(sql);
                                  for (int i = 0; i < params.length; i++) {
                                      stmt.bind(i, params[i]);
                                  }
                                  return ReactiveOperations.<io.r2dbc.spi.Result> fromPublisher(stmt.execute(),
                                                                                                e -> R2dbcError.fromException(e,
                                                                                                                              sql))
                                                           .flatMap(result -> ReactiveOperations.fromPublisher(result.getRowsUpdated(),
                                                                                                               e -> R2dbcError.fromException(e,
                                                                                                                                             sql)))
                                                           .map(Long::intValue);
                              });
    }

    @Override
    public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
        if (paramsList.isEmpty()) {
            return Promise.success(new int[0]);
        }
        return withConnection(conn -> {
                                  var stmt = conn.createStatement(sql);
                                  for (int i = 0; i < paramsList.size(); i++) {
                                      var params = paramsList.get(i);
                                      for (int j = 0; j < params.length; j++) {
                                          stmt.bind(j, params[j]);
                                      }
                                      if (i < paramsList.size() - 1) {
                                          stmt.add();
                                      }
                                  }
                                  return ReactiveOperations.<io.r2dbc.spi.Result> collectFromPublisher(stmt.execute(),
                                                                                                       e -> R2dbcError.fromException(e,
                                                                                                                                     sql))
                                                           .flatMap(results -> collectUpdateCounts(results,
                                                                                                   new int[results.size()],
                                                                                                   0));
                              });
    }

    private static Promise<int[]> collectUpdateCounts(List<io.r2dbc.spi.Result> results, int[] counts, int index) {
        if (index >= results.size()) {
            return Promise.success(counts);
        }
        return ReactiveOperations.<Long> fromPublisher(results.get(index)
                                                              .getRowsUpdated())
                                 .map(count -> {
                                     counts[index] = count.intValue();
                                     return counts;
                                 })
                                 .flatMap(_ -> collectUpdateCounts(results, counts, index + 1));
    }

    @Override
    public <T> Promise<T> transactional(TransactionCallback<T> callback) {
        return withConnection(conn -> ReactiveOperations.fromVoidPublisher(conn.beginTransaction())
                                                        .flatMap(_ -> callback.execute(new TransactionalJooqR2dbcConnector(config,
                                                                                                                           conn,
                                                                                                                           dialect)))
                                                        .flatMap(result -> ReactiveOperations.fromVoidPublisher(conn.commitTransaction())
                                                                                             .map(_ -> result))
                                                        .onFailure(_ -> ReactiveOperations.fromVoidPublisher(conn.rollbackTransaction())));
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
            return Promise.lift(DatabaseConnectorError::databaseFailure,
                                () -> {
                                    closeable.close();
                                    return Unit.unit();
                                });
        }
        return Promise.success(Unit.unit());
    }

    private <T> Promise<T> withConnection(java.util.function.Function<Connection, Promise<T>> operation) {
        return ReactiveOperations.<Connection> fromPublisher(connectionFactory.create())
                                 .flatMap(conn -> operation.apply(conn)
                                                           .onResult(_ -> ReactiveOperations.fromPublisher(conn.close())));
    }

    private static <T> Promise<List<T>> collectSuccessfulResults(List<Result<T>> results) {
        for (var result : results) {
            if (result.isFailure()) {
                return result.mapError(JooqR2dbcDatabaseConnector::toConnectorError)
                             .async()
                             .map(_ -> List.<T>of());
            }
        }
        return Promise.success(results.stream()
                                      .map(Result::unwrap)
                                      .toList());
    }

    private static DatabaseConnectorError toConnectorError(org.pragmatica.lang.Cause cause) {
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

    @SuppressWarnings("unchecked")
    private <T> org.reactivestreams.Publisher<T> flatMapResult(org.reactivestreams.Publisher<? extends io.r2dbc.spi.Result> resultPublisher,
                                                               java.util.function.BiFunction<Row, RowMetadata, T> mapper) {
        return subscriber -> resultPublisher.subscribe(new org.reactivestreams.Subscriber<io.r2dbc.spi.Result>() {
            @Override
            public void onSubscribe(org.reactivestreams.Subscription s) {
                                                           s.request(1);
                                                       }

            @Override
            public void onNext(io.r2dbc.spi.Result result) {
                                                           result.map(mapper)
                                                                 .subscribe((org.reactivestreams.Subscriber<? super Object>) subscriber);
                                                       }

            @Override
            public void onError(Throwable t) {
                                                           subscriber.onError(t);
                                                       }

            @Override
            public void onComplete() {}
        });
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

    /// RowAccessor implementation for R2DBC Row.
    private record R2dbcRowAccessor(Row row) implements RowMapper.RowAccessor {
        @Override
        public Result<String> getString(String column) {
            return Result.lift(DatabaseConnectorError::databaseFailure, () -> row.get(column, String.class));
        }

        @Override
        public Result<Integer> getInt(String column) {
            return Result.lift(DatabaseConnectorError::databaseFailure, () -> row.get(column, Integer.class));
        }

        @Override
        public Result<Long> getLong(String column) {
            return Result.lift(DatabaseConnectorError::databaseFailure, () -> row.get(column, Long.class));
        }

        @Override
        public Result<Double> getDouble(String column) {
            return Result.lift(DatabaseConnectorError::databaseFailure, () -> row.get(column, Double.class));
        }

        @Override
        public Result<Boolean> getBoolean(String column) {
            return Result.lift(DatabaseConnectorError::databaseFailure, () -> row.get(column, Boolean.class));
        }

        @Override
        public Result<byte[]> getBytes(String column) {
            return Result.lift(DatabaseConnectorError::databaseFailure, () -> row.get(column, byte[].class));
        }

        @Override
        public <V> Result<V> getObject(String column, Class<V> type) {
            return Result.lift(DatabaseConnectorError::databaseFailure, () -> row.get(column, type));
        }
    }

    /// A DatabaseConnector bound to a specific R2DBC Connection for transactional operations.
    private record TransactionalJooqR2dbcConnector(DatabaseConnectorConfig config,
                                                   Connection connection,
                                                   SQLDialect dialect)
    implements DatabaseConnector {
        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            var stmt = connection.createStatement(sql);
            for (int i = 0; i < params.length; i++) {
                stmt.bind(i, params[i]);
            }
            return ReactiveOperations.<Result<T>> fromPublisher(flatMapResult(stmt.execute(),
                                                                              (row, meta) -> mapper.map(new R2dbcRowAccessor(row))),
                                                                e -> R2dbcError.fromException(e, sql))
                                     .flatMap(result -> result.mapError(JooqR2dbcDatabaseConnector::toConnectorError)
                                                              .async());
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            var stmt = connection.createStatement(sql);
            for (int i = 0; i < params.length; i++) {
                stmt.bind(i, params[i]);
            }
            return ReactiveOperations.<Result<T>> firstFromPublisher(flatMapResult(stmt.execute(),
                                                                                   (row, meta) -> mapper.map(new R2dbcRowAccessor(row))),
                                                                     e -> R2dbcError.fromException(e, sql))
                                     .flatMap(opt -> opt.fold(() -> Promise.success(Option.none()),
                                                              result -> result.mapError(JooqR2dbcDatabaseConnector::toConnectorError)
                                                                              .async()
                                                                              .map(Option::some)));
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            var stmt = connection.createStatement(sql);
            for (int i = 0; i < params.length; i++) {
                stmt.bind(i, params[i]);
            }
            return ReactiveOperations.<Result<T>> collectFromPublisher(flatMapResult(stmt.execute(),
                                                                                     (row, meta) -> mapper.map(new R2dbcRowAccessor(row))),
                                                                       e -> R2dbcError.fromException(e, sql))
                                     .flatMap(JooqR2dbcDatabaseConnector::collectSuccessfulResults);
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            var stmt = connection.createStatement(sql);
            for (int i = 0; i < params.length; i++) {
                stmt.bind(i, params[i]);
            }
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
            var stmt = connection.createStatement(sql);
            for (int i = 0; i < paramsList.size(); i++) {
                var params = paramsList.get(i);
                for (int j = 0; j < params.length; j++) {
                    stmt.bind(j, params[j]);
                }
                if (i < paramsList.size() - 1) {
                    stmt.add();
                }
            }
            return ReactiveOperations.<io.r2dbc.spi.Result> collectFromPublisher(stmt.execute(),
                                                                                 e -> R2dbcError.fromException(e, sql))
                                     .flatMap(results -> collectUpdateCounts(results,
                                                                             new int[results.size()],
                                                                             0));
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
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

        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }

        @SuppressWarnings("unchecked")
        private <T> org.reactivestreams.Publisher<T> flatMapResult(org.reactivestreams.Publisher<? extends io.r2dbc.spi.Result> resultPublisher,
                                                                   java.util.function.BiFunction<Row, RowMetadata, T> mapper) {
            return subscriber -> resultPublisher.subscribe(new org.reactivestreams.Subscriber<io.r2dbc.spi.Result>() {
                @Override
                public void onSubscribe(org.reactivestreams.Subscription s) {
                                                               s.request(1);
                                                           }

                @Override
                public void onNext(io.r2dbc.spi.Result result) {
                                                               result.map(mapper)
                                                                     .subscribe((org.reactivestreams.Subscriber<? super Object>) subscriber);
                                                           }

                @Override
                public void onError(Throwable t) {
                                                               subscriber.onError(t);
                                                           }

                @Override
                public void onComplete() {}
            });
        }
    }
}
