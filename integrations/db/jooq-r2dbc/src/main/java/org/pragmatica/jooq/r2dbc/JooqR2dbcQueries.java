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

import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.r2dbc.R2dbcError;
import org.pragmatica.r2dbc.ReactiveOperations;

import io.r2dbc.spi.Connection;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.jooq.RowCountQuery;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;


/// Runs jOOQ queries on an R2DBC [Connection] through jOOQ's reactive API.
///
/// jOOQ refuses its blocking `fetch` / `execute` when it holds only an R2DBC connection (`DetachedException`): such a
/// query is a `Publisher` that runs once it is subscribed. The query is attached to a configuration over the given
/// connection and subscribed to; the emitted rows are collected into the returned [Promise]. A driver failure is mapped
/// to [R2dbcError] with the SQLSTATE jOOQ's wrapping exception carries (see [R2dbcError#fromException]).
///
/// The query is attached for the call, so one query instance must not run on two connections at the same time.
public interface JooqR2dbcQueries {
    /// Fetches every record of the query on the connection.
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
        query.attach(DSL.using(connection, dialect).configuration());

        return ReactiveOperations.collectFromPublisher(query, e -> R2dbcError.fromException(e, query.getSQL()));
    }

    /// Executes the query on the connection.
    ///
    /// @param connection R2DBC connection to run on
    /// @param dialect SQL dialect
    /// @param query JOOQ Query (INSERT, UPDATE, DELETE)
    ///
    /// @return Promise containing the number of affected rows, or the mapped failure
    static Promise<Integer> execute(Connection connection, SQLDialect dialect, Query query) {
        return query instanceof RowCountQuery rowCountQuery
               ? executeRowCount(connection, dialect, rowCountQuery)
               : Causes.cause("Not a row-count query, so it cannot run reactively: " + query.getSQL()).promise();
    }

    private static Promise<Integer> executeRowCount(Connection connection, SQLDialect dialect, RowCountQuery query) {
        query.attach(DSL.using(connection, dialect).configuration());

        return ReactiveOperations.<Integer> collectFromPublisher(query, e -> R2dbcError.fromException(e, query.getSQL()))
                                 .map(JooqR2dbcQueries::sum);
    }

    private static int sum(List<Integer> counts) {
        return counts.stream().mapToInt(Integer::intValue).sum();
    }
}
