// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.sql.SQLException;

import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// FDB-2b fix round (#2591, review B survivors F14-F21): [MigrationFence#refusedAtConnect] reads H2's exclusive-mode
/// refusal (SQLSTATE 90135) from the connector error AND from the driver exception chain the pool wraps it in. Every
/// other failure passes through unchanged (same instance).
class MigrationFenceConnectRefusalTest {
    private static final String EXCLUSIVE = "90135";

    @Test
    void refusedAtConnect_sqlStateOnTheError_isFencedInDatabase() {
        var error = DatabaseConnectorError.queryFailed("connect", "refused", Option.none(), Option.some(EXCLUSIVE));

        assertThat(refused(error)).isInstanceOf(MigrationError.MigrationFencedInDatabase.class);
    }

    @Test
    void refusedAtConnect_driverExceptionTwoDeepInAQueryFailure_isFencedInDatabase() {
        var driver = new SQLException("exclusive", EXCLUSIVE);
        var error = DatabaseConnectorError.queryFailed("connect", "wrapped", Option.some(new RuntimeException(new IllegalStateException(driver))), Option.none());

        assertThat(refused(error)).isInstanceOf(MigrationError.MigrationFencedInDatabase.class);
    }

    @Test
    void refusedAtConnect_driverExceptionTwoDeepInAConnectionFailure_isFencedInDatabase() {
        var driver = new SQLException("exclusive", EXCLUSIVE);
        var error = DatabaseConnectorError.connectionFailed("wrapped", Option.some(new RuntimeException(new IllegalStateException(driver))), Option.none());

        assertThat(refused(error)).isInstanceOf(MigrationError.MigrationFencedInDatabase.class);
    }

    @Test
    void refusedAtConnect_driverExceptionWithAnotherSqlState_passesThroughUnchanged() {
        var error = DatabaseConnectorError.queryFailed("connect", "wrapped", Option.some(new RuntimeException(new SQLException("other", "08001"))), Option.none());

        assertThat(refused(error)).isSameAs(error);
    }

    @Test
    void refusedAtConnect_chainWithoutAnySqlException_passesThroughUnchanged() {
        var error = DatabaseConnectorError.queryFailed("connect", "wrapped", Option.some(new RuntimeException(new IllegalStateException("x"))), Option.none());

        assertThat(refused(error)).isSameAs(error);
    }

    @Test
    void refusedAtConnect_noCauseAndNoSqlState_passesThroughUnchanged() {
        var error = DatabaseConnectorError.queryFailed("connect", "plain");

        assertThat(refused(error)).isSameAs(error);
    }

    @Test
    void refusedAtConnect_aCauseThatIsNotAConnectorError_passesThroughUnchanged() {
        var cause = Causes.cause("not a connector error");

        assertThat(MigrationFence.refusedAtConnect("ds", DatabaseType.H2, cause)).isSameAs(cause);
    }

    @Test
    void refusedAtConnect_otherEngineWithTheSameSqlState_passesThroughUnchanged() {
        var error = DatabaseConnectorError.queryFailed("connect", "refused", Option.none(), Option.some(EXCLUSIVE));

        assertThat(MigrationFence.refusedAtConnect("ds", DatabaseType.POSTGRESQL, error)).isSameAs(error);
    }

    private static Cause refused(Cause cause) {
        return MigrationFence.refusedAtConnect("ds", DatabaseType.H2, cause);
    }
}
