/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.pragmatica.r2dbc;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.function.BiFunction;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import io.r2dbc.spi.Statement;
import org.reactivestreams.Publisher;

/// Promise-based R2DBC operations interface.
/// Provides a functional wrapper for reactive database access with typed error handling.
public interface R2dbcOperations {
    /// Executes a query and maps the single result.
    ///
    /// @param sql SQL query
    /// @param mapper Function to map Row to domain object
    /// @param params Query parameters
    /// @param <T> Result type
    ///
    /// @return Promise containing the mapped result or failure
    <T> Promise<T> queryOne(String sql, BiFunction<Row, RowMetadata, T> mapper, Object... params);

    /// Executes a query and returns optional result.
    ///
    /// @param sql SQL query
    /// @param mapper Function to map Row to domain object
    /// @param params Query parameters
    /// @param <T> Result type
    ///
    /// @return Promise containing Option with the mapped result
    <T> Promise<Option<T>> queryOptional(String sql, BiFunction<Row, RowMetadata, T> mapper, Object... params);

    /// Executes a query and maps all results to a list.
    ///
    /// @param sql SQL query
    /// @param rowMapper Function to map each row to domain object
    /// @param params Query parameters
    /// @param <T> Result element type
    ///
    /// @return Promise containing list of mapped results
    <T> Promise<List<T>> queryList(String sql, BiFunction<Row, RowMetadata, T> rowMapper, Object... params);

    /// Executes an update statement (INSERT, UPDATE, DELETE).
    ///
    /// @param sql SQL statement
    /// @param params Statement parameters
    ///
    /// @return Promise containing number of affected rows
    Promise<Long> update(String sql, Object... params);

    /// Creates R2dbcOperations from a ConnectionFactory.
    ///
    /// @param connectionFactory R2DBC ConnectionFactory
    ///
    /// @return R2dbcOperations instance
    static R2dbcOperations r2dbcOperations(ConnectionFactory connectionFactory) {
        return new ConnectionFactoryR2dbcOperations(connectionFactory);
    }
}

/// ConnectionFactory-based implementation of R2dbcOperations.
final class ConnectionFactoryR2dbcOperations implements R2dbcOperations {
    private final ConnectionFactory connectionFactory;

    ConnectionFactoryR2dbcOperations(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public <T> Promise<T> queryOne(String sql, BiFunction<Row, RowMetadata, T> mapper, Object... params) {
        return withConnection(conn -> executeQueryOne(conn, sql, mapper, params));
    }

    @Override
    public <T> Promise<Option<T>> queryOptional(String sql, BiFunction<Row, RowMetadata, T> mapper, Object... params) {
        return withConnection(conn -> executeQueryOptional(conn, sql, mapper, params));
    }

    @Override
    public <T> Promise<List<T>> queryList(String sql, BiFunction<Row, RowMetadata, T> mapper, Object... params) {
        return withConnection(conn -> executeQueryList(conn, sql, mapper, params));
    }

    @Override
    public Promise<Long> update(String sql, Object... params) {
        return withConnection(conn -> executeUpdate(conn, sql, params));
    }

    private <T> Promise<T> executeQueryOne(Connection conn, String sql, BiFunction<Row, RowMetadata, T> mapper,
                                           Object... params) {
        var publisher = flatMapResult(createStatement(conn, sql, params).execute(), mapper);
        return ReactiveOperations.fromPublisher(publisher, e -> R2dbcError.fromException(e, sql));
    }

    private <T> Promise<Option<T>> executeQueryOptional(Connection conn, String sql,
                                                        BiFunction<Row, RowMetadata, T> mapper, Object... params) {
        var publisher = flatMapResult(createStatement(conn, sql, params).execute(), mapper);
        return ReactiveOperations.firstFromPublisher(publisher, e -> R2dbcError.fromException(e, sql));
    }

    private <T> Promise<List<T>> executeQueryList(Connection conn, String sql,
                                                  BiFunction<Row, RowMetadata, T> mapper, Object... params) {
        var publisher = flatMapResult(createStatement(conn, sql, params).execute(), mapper);
        return ReactiveOperations.collectFromPublisher(publisher, e -> R2dbcError.fromException(e, sql));
    }

    private Promise<Long> executeUpdate(Connection conn, String sql, Object... params) {
        return ReactiveOperations.<io.r2dbc.spi.Result>fromPublisher(createStatement(conn, sql, params).execute(),
                                                                     e -> R2dbcError.fromException(e, sql))
                                 .flatMap(result -> ReactiveOperations.fromPublisher(result.getRowsUpdated(),
                                                                                     e -> R2dbcError.fromException(e, sql)));
    }

    private <T> Promise<T> withConnection(Fn1<Promise<T>, Connection> operation) {
        return ReactiveOperations.<Connection>fromPublisher(connectionFactory.create())
                                 .flatMap(conn -> executeAndClose(conn, operation));
    }

    private <T> Promise<T> executeAndClose(Connection conn, Fn1<Promise<T>, Connection> operation) {
        return operation.apply(conn)
                        .fold(result -> closeAndResolve(conn, result));
    }

    private static <T> Promise<T> closeAndResolve(Connection conn, Result<T> result) {
        return ReactiveOperations.fromVoidPublisher(conn.close())
                                 .fold(_ -> Promise.resolved(result));
    }

    private Statement createStatement(Connection conn, String sql, Object[] params) {
        var stmt = conn.createStatement(sql);
        for (int i = 0; i < params.length; i++) {
            stmt.bind(i, params[i]);
        }
        return stmt;
    }

    private <T> Publisher<T> flatMapResult(Publisher<? extends io.r2dbc.spi.Result> resultPublisher,
                                           BiFunction<Row, RowMetadata, T> mapper) {
        return subscriber -> resultPublisher.subscribe(new ResultFlatMapSubscriber<>(subscriber, mapper));
    }

    /// Subscriber that flat-maps R2DBC Result into mapped row values.
    private static final class ResultFlatMapSubscriber<T> implements org.reactivestreams.Subscriber<io.r2dbc.spi.Result> {
        private final org.reactivestreams.Subscriber<? super T> downstream;
        private final BiFunction<Row, RowMetadata, T> mapper;

        ResultFlatMapSubscriber(org.reactivestreams.Subscriber<? super T> downstream,
                                BiFunction<Row, RowMetadata, T> mapper) {
            this.downstream = downstream;
            this.mapper = mapper;
        }

        @Override
        public void onSubscribe(org.reactivestreams.Subscription s) {
            s.request(1);
        }

        @Override
        public void onNext(io.r2dbc.spi.Result result) {
            result.map(mapper)
                  .subscribe(downstream);
        }

        @Override
        public void onError(Throwable t) {
            downstream.onError(t);
        }

        @Override
        public void onComplete() {}
    }
}
