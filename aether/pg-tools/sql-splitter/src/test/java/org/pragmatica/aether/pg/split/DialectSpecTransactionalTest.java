// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.split;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DialectSpecTransactionalTest {
    private static final DialectSpec PG = Dialects.POSTGRESQL;

    @ParameterizedTest
    @ValueSource(strings = {
        "CREATE INDEX CONCURRENTLY idx ON t (c)",
        "CREATE UNIQUE INDEX CONCURRENTLY idx ON t (c)",
        "create index concurrently idx on t (c)",
        "DROP INDEX CONCURRENTLY idx",
        "REINDEX INDEX CONCURRENTLY idx",
        "VACUUM",
        "VACUUM ANALYZE t",
        "ALTER TYPE mood ADD VALUE 'happy'",
        "alter type mood add value 'sad'",
        "CREATE DATABASE app",
        "DROP DATABASE app"
    })
    void isTransactional_returnsFalse_forNonTransactionalCommands(String sql) {
        assertThat(PG.isTransactional(sql)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "CREATE TABLE t (id int)",
        "INSERT INTO t VALUES (1)",
        "CREATE INDEX idx ON t (c)",
        "UPDATE t SET c = 1",
        "ALTER TABLE t ADD COLUMN c int",
        "ALTER TYPE mood RENAME TO feeling",
        "SELECT 1"
    })
    void isTransactional_returnsTrue_forTransactionalCommands(String sql) {
        assertThat(PG.isTransactional(sql)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "  \n VACUUM",
        "-- maintenance\nVACUUM",
        "/* run nightly */ VACUUM",
        "/* lead */\n-- trail\n  CREATE INDEX CONCURRENTLY idx ON t (c)"
    })
    void isTransactional_toleratesLeadingNoise_beforeNonTransactionalCommand(String sql) {
        assertThat(PG.isTransactional(sql)).isFalse();
    }
}
