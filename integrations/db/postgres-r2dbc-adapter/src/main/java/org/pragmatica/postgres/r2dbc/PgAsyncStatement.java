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
package org.pragmatica.postgres.r2dbc;

import java.util.ArrayList;
import java.util.List;

import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import org.reactivestreams.Publisher;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


/// R2DBC [Statement] backed by a postgres-async extended query protocol.
///
/// Supports positional parameter binding with `$N` placeholders, which is what the PostgreSQL server
/// accepts; the SQL is sent unchanged.
public final class PgAsyncStatement implements Statement {
    private final PgAsyncConnection connection;
    private final String sql;
    private final List<Object[]> bindings = new ArrayList<>();
    private List<Object> currentBinding = new ArrayList<>();

    PgAsyncStatement(PgAsyncConnection connection, String sql) {
        this.connection = connection;
        this.sql = QuestionMarkEscape.unescape(sql);
    }

    @Override
    public Statement add() {
        bindings.add(currentBinding.toArray());
        currentBinding = new ArrayList<>();

        return this;
    }

    @Override
    public Statement bind(int index, Object value) {
        ensureCapacity(index);
        currentBinding.set(index, value);

        return this;
    }

    @Override
    public Statement bind(String name, Object value) {
        throw new UnsupportedOperationException("Named parameters not supported");
    }

    @Override
    public Statement bindNull(int index, Class<?> type) {
        ensureCapacity(index);
        currentBinding.set(index, null);

        return this;
    }

    @Override
    public Statement bindNull(String name, Class<?> type) {
        throw new UnsupportedOperationException("Named parameters not supported");
    }

    @Override
    public Publisher<? extends Result> execute() {
        flushCurrentBinding();
        if (bindings.isEmpty()) {
            return executeSimpleQuery();
        }

        if (bindings.size() == 1) {
            return executeSingleBinding();
        }

        return executeMultipleBindings();
    }

    private void flushCurrentBinding() {
        if (!currentBinding.isEmpty()) {
            bindings.add(currentBinding.toArray());
            currentBinding = new ArrayList<>();
        }
    }

    private Mono<Result> executeSimpleQuery() {
        return Mono.create(sink -> connection.pgConnection()
                                             .completeQuery(sql)
                                             .onSuccess(rs -> sink.success(new PgAsyncResult(rs)))
                                             .onFailure(cause -> sink.error(R2dbcAdapterException.from(cause))));
    }

    private Mono<Result> executeSingleBinding() {
        var params = bindings.getFirst();

        bindings.clear();

        return Mono.create(sink -> connection.pgConnection()
                                             .completeQuery(sql, params)
                                             .onSuccess(rs -> sink.success(new PgAsyncResult(rs)))
                                             .onFailure(cause -> sink.error(R2dbcAdapterException.from(cause))));
    }

    private Flux<Result> executeMultipleBindings() {
        var allParams = new ArrayList<>(bindings);

        bindings.clear();

        return Flux.fromIterable(allParams).concatMap(this::executeWithParams);
    }

    private Mono<Result> executeWithParams(Object[] params) {
        return Mono.create(sink -> connection.pgConnection()
                                             .completeQuery(sql, params)
                                             .onSuccess(rs -> sink.success(new PgAsyncResult(rs)))
                                             .onFailure(cause -> sink.error(R2dbcAdapterException.from(cause))));
    }

    private void ensureCapacity(int index) {
        while (currentBinding.size() <= index) {
            currentBinding.add(null);
        }
    }
}
