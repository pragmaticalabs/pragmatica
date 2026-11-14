// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.migration;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.PgSchemaName;

import static org.assertj.core.api.Assertions.assertThat;

/// #2100 E-B T1: the migration-set id grammar and the SPI value factories.
class MigrationSetSpiTest {
    @Test
    void migrationSetId_acceptsGrammar() {
        for (var raw : List.of("durable-entity-pg", "a", "x9-y", "a".repeat(63))) {
            var result = MigrationSetId.migrationSetId(raw);

            assertThat(result.isSuccess()).as("'%s' accepted", raw).isTrue();
            result.onSuccess(id -> assertThat(id.value()).isEqualTo(raw));
        }
    }

    @Test
    void migrationSetId_refusesOutsideGrammar() {
        var table = Arrays.asList(null, "", " ", "Upper", "9x", "-x", "a:b", "a@b", "a b", "a_b", "set:x@1", "a".repeat(64));

        for (var raw : table) {
            var result = MigrationSetId.migrationSetId(raw);

            assertThat(result.isFailure()).as("'%s' refused", raw).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(MigrationSetError.InvalidSetId.class));
        }
    }

    @Test
    void migrationTarget_acceptsSectionAndRefusesBlankOrSeparator() {
        var schema = PgSchemaName.pgSchemaName("entity_orders").unwrap();

        assertThat(MigrationTarget.migrationTarget("database.main", schema).isSuccess()).isTrue();
        assertThat(MigrationTarget.migrationTarget("", schema).isFailure()).isTrue();
        assertThat(MigrationTarget.migrationTarget(null, schema).isFailure()).isTrue();
        assertThat(MigrationTarget.migrationTarget("database.main@x", schema).isFailure()).isTrue();
    }

    @Test
    void renderedScript_acceptsPlainNameAndRefusesBlankOrPath() {
        assertThat(RenderedScript.renderedScript("V1__entity.sql", "CREATE TABLE t();").isSuccess()).isTrue();
        assertThat(RenderedScript.renderedScript("", "SELECT 1").isFailure()).isTrue();
        assertThat(RenderedScript.renderedScript(null, "SELECT 1").isFailure()).isTrue();
        assertThat(RenderedScript.renderedScript("../V1.sql", "SELECT 1").isFailure()).isTrue();
        assertThat(RenderedScript.renderedScript("a\\b.sql", "SELECT 1").isFailure()).isTrue();
        assertThat(RenderedScript.renderedScript("V1.sql", " ").isFailure()).isTrue();
        assertThat(RenderedScript.renderedScript("V1.sql", null).isFailure()).isTrue();
    }

    @Test
    void migrationSetId_refusalCarriesTheValue() {
        var refused = MigrationSetId.migrationSetId("Bad:id");

        assertThat(refused.isFailure()).isTrue();
        refused.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(MigrationSetError.InvalidSetId.class, refusal -> assertThat(refusal.value()).isEqualTo("Bad:id")));
    }

    @Test
    void migrationTarget_successCarriesSectionAndSchema() {
        var schema = PgSchemaName.pgSchemaName("entity_orders").unwrap();
        var target = MigrationTarget.migrationTarget("database.main", schema);

        assertThat(target.isSuccess()).isTrue();
        target.onSuccess(value -> assertThat(value.datasourceSection()).isEqualTo("database.main"));
        target.onSuccess(value -> assertThat(value.schema()).isSameAs(schema));
    }

    @Test
    void migrationTarget_refusalCarriesTheSection() {
        var schema = PgSchemaName.pgSchemaName("entity_orders").unwrap();
        var separator = MigrationTarget.migrationTarget("db@x", schema);
        var blank = MigrationTarget.migrationTarget(" ", schema);

        separator.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(MigrationSetError.InvalidTarget.class, refusal -> assertThat(refusal.section()).isEqualTo("db@x")));
        blank.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(MigrationSetError.InvalidTarget.class, refusal -> assertThat(refusal.section()).isEqualTo(" ")));
        assertThat(separator.isFailure()).isTrue();
        assertThat(blank.isFailure()).isTrue();
    }

    @Test
    void renderedScript_successCarriesFilenameAndSql() {
        var script = RenderedScript.renderedScript("V1__entity.sql", "CREATE TABLE t();");

        assertThat(script.isSuccess()).isTrue();
        script.onSuccess(value -> assertThat(value.filename()).isEqualTo("V1__entity.sql"));
        script.onSuccess(value -> assertThat(value.sql()).isEqualTo("CREATE TABLE t();"));
    }

    @Test
    void renderedScript_refusesInnerWhitespaceInTheFilename() {
        assertThat(RenderedScript.renderedScript("V1 entity.sql", "SELECT 1").isFailure()).isTrue();
        assertThat(RenderedScript.renderedScript("V1\tentity.sql", "SELECT 1").isFailure()).isTrue();
    }

    @Test
    void renderedScript_refusalCarriesTheFilename() {
        var badName = RenderedScript.renderedScript("a/b.sql", "SELECT 1");
        var blankSql = RenderedScript.renderedScript("V1.sql", " ");
        var blankName = RenderedScript.renderedScript(" ", "SELECT 1");

        assertThat(badName.isFailure()).isTrue();
        assertThat(blankSql.isFailure()).isTrue();
        assertThat(blankName.isFailure()).isTrue();
        badName.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(MigrationSetError.InvalidScript.class, refusal -> assertThat(refusal.filename()).isEqualTo("a/b.sql")));
        blankSql.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(MigrationSetError.InvalidScript.class, refusal -> assertThat(refusal.filename()).isEqualTo("V1.sql")));
        blankName.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(MigrationSetError.InvalidScript.class, refusal -> assertThat(refusal.filename()).isEqualTo(" ")));
    }
}
