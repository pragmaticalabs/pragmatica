// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2100 E-B T1: the schema-name grammar and reserved names. `public` is refused (OPEN 2, a CTO guess pending the owner).
class PgSchemaNameTest {
    private static final String SIXTY_THREE = "a".repeat(63);
    private static final String SIXTY_FOUR = "a".repeat(64);

    @Test
    void pgSchemaName_acceptsGrammar() {
        for (var raw : List.of("entity_orders", "_x", "a", "x9", SIXTY_THREE)) {
            var result = PgSchemaName.pgSchemaName(raw);

            assertThat(result.isSuccess()).as("'%s' accepted", raw).isTrue();
            result.onSuccess(name -> assertThat(name.value()).isEqualTo(raw));
        }
    }

    @Test
    void pgSchemaName_refusesOutsideGrammar() {
        var table = Arrays.asList(null, "", " ", "Upper", "9lives", "a b", "x;", "x\";drop table t;--", "x\"y", "a-b", "x'y", "a\n", "x$", "é", SIXTY_FOUR);

        for (var raw : table) {
            var result = PgSchemaName.pgSchemaName(raw);

            assertThat(result.isFailure()).as("'%s' refused", raw).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(PgSchemaNameError.Malformed.class));
        }
    }

    @Test
    void pgSchemaName_refusesReserved() {
        for (var raw : List.of("pg_x", "pg_catalog", "pg_", "information_schema", "public")) {
            var result = PgSchemaName.pgSchemaName(raw);

            assertThat(result.isFailure()).as("'%s' refused", raw).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(PgSchemaNameError.Reserved.class));
        }
    }

    @Test
    void quoted_alwaysDoubleQuotes() {
        var name = PgSchemaName.pgSchemaName("entity_orders");

        assertThat(name.isSuccess()).isTrue();
        name.onSuccess(schema -> assertThat(schema.quoted()).isEqualTo("\"entity_orders\""));
    }

    @Test
    void pgSchemaName_acceptsTheReservedPrefixBoundary() {
        assertThat(PgSchemaName.pgSchemaName("pgx").isSuccess()).isTrue();
        assertThat(PgSchemaName.pgSchemaName("publicx").isSuccess()).isTrue();
    }

    @Test
    void pgSchemaName_malformedRefusalCarriesTheValue() {
        var refused = PgSchemaName.pgSchemaName("Bad;");
        var nullRefused = PgSchemaName.pgSchemaName(null);

        assertThat(refused.isFailure()).isTrue();
        assertThat(nullRefused.isFailure()).isTrue();
        refused.onFailure(cause -> assertThat(cause).isEqualTo(PgSchemaNameError.malformed("Bad;")));
        nullRefused.onFailure(cause -> assertThat(cause).isEqualTo(PgSchemaNameError.malformed(null)));
    }

    @Test
    void pgSchemaName_reservedRefusalCarriesTheValue() {
        var refused = PgSchemaName.pgSchemaName("pg_temp");

        assertThat(refused.isFailure()).isTrue();
        refused.onFailure(cause -> assertThat(cause).isEqualTo(PgSchemaNameError.reserved("pg_temp")));
    }
}
