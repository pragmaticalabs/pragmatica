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
package org.pragmatica.jooq.r2dbc;

import java.util.Arrays;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.r2dbc.R2dbcError;
import org.pragmatica.r2dbc.ReactiveOperations;

import io.r2dbc.spi.Connection;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.jooq.RowCountQuery;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;


/// Runs jOOQ queries on an R2DBC [Connection] through jOOQ's reactive API.
///
/// jOOQ refuses its blocking `fetch` / `execute` when it holds only an R2DBC connection (`DetachedException`): such a
/// query is a `Publisher` that runs once it is subscribed. Each call renders the caller's query to SQL and bind values
/// and runs a FRESH query built under a configuration over this call's connection; the caller's query object is never
/// attached or otherwise touched, so one query instance (a `static final` constant, say) can be used by concurrent
/// calls and each runs on the connection of its own call. The emitted rows are collected into the returned [Promise];
/// a driver failure is mapped to [R2dbcError] with the SQLSTATE jOOQ's wrapping exception carries (see
/// [R2dbcError#fromException]).
///
/// Bind values are re-bound by their runtime type, so a custom jOOQ `Converter` or `Binding` on a field is not applied
/// to them.
public interface JooqR2dbcQueries {
    /// Fetches every record of the query on the connection.
    ///
    /// A query that selects a field declared without a type (`DSL.field("name")`) fails with an [R2dbcError] naming the
    /// field: the driver cannot convert a value to `Object`.
    ///
    /// @param connection R2DBC connection to run on
    /// @param dialect SQL dialect
    /// @param query JOOQ ResultQuery
    /// @param <R> Record type
    ///
    /// @return Promise containing the records, or the mapped failure
    static <R extends Record> Promise<List<R>> fetchAll(Connection connection,
                                                        SQLDialect dialect,
                                                        ResultQuery<R> query) {
        return untypedField(query).map(field -> untypedFieldFailure(field).<List<R>> promise())
                           .or(() -> fetchCopy(connection, dialect, query));
    }

    /// Executes the query on the connection.
    ///
    /// Only a row-count query (INSERT, UPDATE, DELETE, plain SQL, DDL) can run; any other [Query], such as a
    /// `ResultQuery` or an `INSERT ... RETURNING`, fails with an [R2dbcError.QueryFailed] saying so (a typed connector
    /// error at the connector level). Use `fetch` for those.
    ///
    /// @param connection R2DBC connection to run on
    /// @param dialect SQL dialect
    /// @param query JOOQ Query (INSERT, UPDATE, DELETE)
    ///
    /// @return Promise containing the number of affected rows, or the mapped failure
    static Promise<Integer> execute(Connection connection, SQLDialect dialect, Query query) {
        return query instanceof RowCountQuery rowCountQuery
               ? executeCopy(connection, dialect, rowCountQuery)
               : notRowCount(query).<Integer> promise();
    }

    private static <R extends Record> Promise<List<R>> fetchCopy(Connection connection,
                                                                 SQLDialect dialect,
                                                                 ResultQuery<R> query) {
        return ReactiveOperations.<Record> collectFromPublisher(copyOf(DSL.using(connection, dialect),
                                                                       query),
                                                                e -> R2dbcError.fromException(e,
                                                                                              query.getSQL()))
                                 .map(rows -> asRecordType(rows,
                                                           query.getRecordType()));
    }

    private static Promise<Integer> executeCopy(Connection connection, SQLDialect dialect, RowCountQuery query) {
        var copy = DSL.using(connection, dialect).query(query.getSQL(),
                                                        query.getBindValues().toArray());

        return ReactiveOperations.<Integer> collectFromPublisher(copy,
                                                                 e -> R2dbcError.fromException(e,
                                                                                               query.getSQL()))
                                 .map(JooqR2dbcQueries::sum);
    }

    private static ResultQuery<Record> copyOf(DSLContext ctx, ResultQuery<?> query) {
        var copy = ctx.resultQuery(query.getSQL(),
                                   query.getBindValues().toArray());

        return query.fields().length == 0
               ? copy
               : copy.coerce(query.fields());
    }

    private static <R extends Record> List<R> asRecordType(List<Record> rows, Class<? extends R> type) {
        return rows.stream()
                   .<R> map(row -> type.isInstance(row)
                                   ? type.cast(row)
                                   : row.into(type))
                   .toList();
    }

    private static Option<Field<?>> untypedField(ResultQuery<?> query) {
        return Option.from(Arrays.stream(query.fields()).filter(field -> field.getType() == Object.class).findFirst());
    }

    private static R2dbcError untypedFieldFailure(Field<?> field) {
        return new R2dbcError.QueryFailed("Field '" + field.getName()
                                         + "' is untyped, so its value cannot be converted: declare it with a type, e.g. DSL.field(\"" + field.getName()
                                         + "\", String.class)");
    }

    private static R2dbcError notRowCount(Query query) {
        return new R2dbcError.QueryFailed("Not a row-count query, so it cannot run reactively: " + query.getSQL());
    }

    private static int sum(List<Integer> counts) {
        return counts.stream()
                     .mapToInt(Integer::intValue)
                     .sum();
    }
}
