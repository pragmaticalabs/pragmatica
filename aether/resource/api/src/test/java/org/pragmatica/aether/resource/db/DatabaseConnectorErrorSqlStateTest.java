// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #2408: a server-reported SQLSTATE is classified once, for every connector, and stays readable on the result. Whether a
/// retry may help is read from the type (`Cause.Transient`), never from the message. `08007` and `57014` are the rows that
/// pin the deliberate non-retry: a COMMIT whose reply was lost, and a cancel.
class DatabaseConnectorErrorSqlStateTest {
    private static DatabaseConnectorError classify(String sqlState) {
        return DatabaseConnectorError.fromSqlState(some(sqlState), "SELECT 1", "driver message", none());
    }

    @Test
    void fromSqlState_class23_isAConstraintViolationKeepingItsSqlState() {
        var error = classify("23505");

        assertThat(error).isInstanceOf(DatabaseConnectorError.ConstraintViolation.class);
        assertThat(error.sqlState()).isEqualTo(some("23505"));
        assertThat(error.isTransient()).isFalse();
    }

    @Test
    void fromSqlState_class40_isATransientRollbackKeepingItsSqlState() {
        var error = classify("40001");

        assertThat(error).isInstanceOf(DatabaseConnectorError.TransactionRolledBack.class);
        assertThat(error.sqlState()).isEqualTo(some("40001"));
        assertThat(error.isTransient()).isTrue();
    }

    @Test
    void fromSqlState_connectionAndServerGoneStates_areTransientConnectionFailures() {
        for (var state : new String[]{"08001", "08003", "08004", "08006", "57P01", "57P02", "57P03", "53300"}) {
            var error = classify(state);

            assertThat(error).as(state).isInstanceOf(DatabaseConnectorError.ConnectionFailed.class);
            assertThat(error.sqlState()).as(state).isEqualTo(some(state));
            assertThat(error.isTransient()).as(state).isTrue();
        }
    }

    @Test
    void fromSqlState_unretryableStates_areNotTransient() {
        for (var state : new String[]{"08007", "08P01", "57014", "57P04", "42601", "22012"}) {
            var error = classify(state);

            assertThat(error).as(state).isInstanceOf(DatabaseConnectorError.QueryFailed.class);
            assertThat(error.sqlState()).as(state).isEqualTo(some(state));
            assertThat(error.isTransient()).as(state).isFalse();
        }
    }

    @Test
    void fromSqlState_noSqlState_isANonTransientQueryFailure() {
        var error = DatabaseConnectorError.fromSqlState(none(), "SELECT 1", "driver message", none());

        assertThat(error).isInstanceOf(DatabaseConnectorError.QueryFailed.class);
        assertThat(error.sqlState()).isEqualTo(none());
        assertThat(error.isTransient()).isFalse();
    }

    @Test
    void sqlState_ofVariantsWithoutOne_isEmpty() {
        assertThat(DatabaseConnectorError.ResultNotFound.INSTANCE.sqlState()).isEqualTo(none());
        assertThat(DatabaseConnectorError.timeout("q").sqlState()).isEqualTo(none());
        assertThat(DatabaseConnectorError.connectionFailed("refused").sqlState()).isEqualTo(none());
    }
}
