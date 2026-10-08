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

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;


/// Binds statement parameters that JDBC's `setObject` type mapping does not cover.
///
/// An `Instant` is bound as a `Timestamp` WITH A UTC CALENDAR. pgjdbc refuses `setObject(Instant)`, and a plain
/// `Timestamp` (or `OffsetDateTime`) binding stores the JVM-zone wall clock into a `timestamp` (without time zone)
/// column, so the stored value moves with `-Duser.timezone`. With the UTC calendar both a `timestamptz` and a
/// `timestamp` column receive the same UTC instant on every JVM zone, for instants from 1582-10-15 onward (`Timestamp` is
/// hybrid Julian/Gregorian, PostgreSQL proleptic Gregorian, so earlier instants shift by days). Reading a `timestamp` column back with the
/// default-calendar `getTimestamp` is still JVM-zone dependent: read it with a UTC calendar.
public final class JdbcParameters {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private JdbcParameters() {}

    /// JDBC boundary: mirrors `PreparedStatement.setObject`, whose callers already run inside the driver's `SQLException` handling.
    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
    public static void bind(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value instanceof Instant instant) {
            statement.setTimestamp(index, Timestamp.from(instant), Calendar.getInstance(UTC));
        } else {
            statement.setObject(index, value);
        }
    }
}
