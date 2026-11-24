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
package org.pragmatica.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;

import org.pragmatica.lang.Option;


/// Reads result-set columns that JDBC's `getObject` type mapping does not cover; the read-side mirror of
/// [JdbcParameters].
///
/// An `Instant` is read through `getTimestamp` WITH A UTC CALENDAR, because pgjdbc's `getObject(col, Instant.class)`
/// is unsupported. A `timestamptz` column yields its absolute instant, and a `timestamp` (without time zone) column
/// is interpreted as a UTC wall clock, the inverse of [JdbcParameters#bind], so a bound `Instant` reads back equal on
/// every JVM zone, for instants from 1582-10-15 onward (`Timestamp` is hybrid Julian/Gregorian, PostgreSQL proleptic
/// Gregorian). Column values written by other means as a non-UTC wall clock are read as UTC too.
public final class JdbcResults {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private JdbcResults() {}

    /// Reads a `timestamptz` or `timestamp` column as an `Instant`; SQL `NULL` is `Option.none()`.
    ///
    /// JDBC boundary: mirrors `ResultSet.getTimestamp`, so it is meant for the `ThrowingFn1` row mappers of [JdbcOperations].
    ///
    /// @param rows   result set positioned on the row to read
    /// @param column 1-based column index
    ///
    /// @return the column instant, or none for SQL `NULL`
    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
    public static Option<Instant> getInstant(ResultSet rows, int column) throws SQLException {
        return Option.option(rows.getTimestamp(column, Calendar.getInstance(UTC))).map(Timestamp::toInstant);
    }

    /// Reads a `timestamptz` or `timestamp` column as an `Instant`; SQL `NULL` is `Option.none()`.
    ///
    /// JDBC boundary: mirrors `ResultSet.getTimestamp`, so it is meant for the `ThrowingFn1` row mappers of [JdbcOperations].
    ///
    /// @param rows   result set positioned on the row to read
    /// @param column column label
    ///
    /// @return the column instant, or none for SQL `NULL`
    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
    public static Option<Instant> getInstant(ResultSet rows, String column) throws SQLException {
        return Option.option(rows.getTimestamp(column, Calendar.getInstance(UTC))).map(Timestamp::toInstant);
    }
}
