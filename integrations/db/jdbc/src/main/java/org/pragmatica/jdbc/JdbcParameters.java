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

import java.sql.Timestamp;
import java.time.Instant;


/// Normalize Java values not covered by JDBC's setObject type mapping.
/// Timestamp binding retains the driver's existing timezone/column semantics.
public final class JdbcParameters {
    private JdbcParameters() {}

    public static Object jdbcValue(Object value) {
        return value instanceof Instant instant
               ? Timestamp.from(instant)
               : value;
    }
}
