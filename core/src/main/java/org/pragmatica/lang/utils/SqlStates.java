/*
 *  Copyright (c) 2026 Sergiy Yevtushenko.
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
package org.pragmatica.lang.utils;

import java.util.Set;


/// The one decision, shared by every database family, on what a server-reported SQLSTATE means for a caller: a
/// constraint violation, a rolled-back transaction, a failure a retry may outlive, or anything else.
///
/// Each connector family keeps its own error types (a JDBC or jOOQ connector returns a connector error, an R2DBC one
/// the R2DBC layer's error, postgres-async its driver's), but whether a SQLSTATE is worth a retry is decided here and
/// nowhere else, so a retry policy that reads transience from the error behaves the same on every path.
///
/// | Class | SQLSTATE | Kind |
/// |---|---|---|
/// | constraint | class `23` | [Kind#CONSTRAINT_VIOLATION], not retried |
/// | rollback | class `40` | [Kind#ROLLBACK], retried |
/// | connection | class `08` except `08007` and `08P01`; `57P01`, `57P02`, `57P03`; class `53` | [Kind#TRANSIENT_CONNECTION], retried |
/// | other | everything else, including `08007`, `08P01` and `57014` | [Kind#OTHER], not retried |
///
/// `08007` (transaction resolution unknown) is a COMMIT whose reply was lost, so a retry could re-run a committed
/// write; `08P01` is a protocol violation; `57014` is a cancel and should surface, not be re-driven.
public sealed interface SqlStates {
    /// What a SQLSTATE means for the caller.
    enum Kind {
        CONSTRAINT_VIOLATION,
        ROLLBACK,
        TRANSIENT_CONNECTION,
        OTHER
    }

    /// Connection-class SQLSTATEs a retry must not repeat.
    Set<String> UNRETRYABLE_CONNECTION_STATES = Set.of("08007", "08P01");

    /// Server-going-away SQLSTATEs a retry may outlive: admin shutdown, crash shutdown, cannot connect now.
    Set<String> SERVER_GONE_STATES = Set.of("57P01", "57P02", "57P03");

    /// Classifies a SQLSTATE.
    ///
    /// @param sqlState the SQLSTATE a server reported
    ///
    /// @return what it means for the caller
    static Kind classify(String sqlState) {
        if (sqlState.startsWith("23")) {
            return Kind.CONSTRAINT_VIOLATION;
        }

        if (sqlState.startsWith("40")) {
            return Kind.ROLLBACK;
        }

        return isTransientConnection(sqlState)
               ? Kind.TRANSIENT_CONNECTION
               : Kind.OTHER;
    }

    /// Whether a retry may outlive the failure this SQLSTATE reports: a rolled-back transaction or a transient
    /// connection failure.
    ///
    /// @param sqlState the SQLSTATE a server reported
    ///
    /// @return `true` when a retry may succeed
    static boolean isTransient(String sqlState) {
        return switch (classify(sqlState)) {
            case ROLLBACK, TRANSIENT_CONNECTION -> true;
            case CONSTRAINT_VIOLATION, OTHER -> false;
        };
    }

    private static boolean isTransientConnection(String sqlState) {
        return isConnectionClass(sqlState) || SERVER_GONE_STATES.contains(sqlState) || sqlState.startsWith("53");
    }

    private static boolean isConnectionClass(String sqlState) {
        return sqlState.startsWith("08") && !UNRETRYABLE_CONNECTION_STATES.contains(sqlState);
    }

    record unused() implements SqlStates {}
}
