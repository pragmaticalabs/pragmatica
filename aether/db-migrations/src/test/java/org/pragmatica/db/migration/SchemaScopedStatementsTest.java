// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.util.List;

import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.PgSchemaName;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2100 E-B L1, without Docker: the statements a schema-scoped repository emits, the `none` overloads, and the refusal
/// of an engine that has no schemas. The byte-identity of the unscoped form is pinned separately by
/// `SchemaHistoryStatementsFixtureTest`; the live behaviour by `SchemaScopedMigrationPgTest`.
class SchemaScopedStatementsTest {
    private static final String QUALIFIER = "\"entity_orders\".aether_schema_";
    private static final Option<PgSchemaName> SCHEMA = Option.some(PgSchemaName.pgSchemaName("entity_orders").unwrap());

    @Test
    void scopedRepository_qualifiesEveryHistoryMetaAndOwnerTable() {
        var operations = HistoryOperations.record(DatabaseType.POSTGRESQL, SchemaHistoryRepository.schemaHistoryRepository(SCHEMA));
        var statements = operations.values().stream().flatMap(List::stream).toList();

        assertThat(statements).as("positive control: statements were recorded").hasSizeGreaterThan(20);

        for (var entry : operations.entrySet()) {
            assertThat(entry.getValue()).as("operation " + entry.getKey()).isNotEmpty();

            for (var statement : entry.getValue()) {
                assertThat(statement.replace(QUALIFIER, "")).as(entry.getKey() + ": " + statement).doesNotContain("aether_schema_");
            }
        }

        assertThat(statements).as("the qualifier is present, not merely the names absent").anyMatch(statement -> statement.contains(QUALIFIER));
    }

    @Test
    void scopedEvolution_createsHistoryMetaAndOwnerInTheSchema() {
        assertThat(SchemaHistoryEvolution.createMetaTableSql(DatabaseType.POSTGRESQL, SCHEMA))
            .isEqualTo("CREATE TABLE IF NOT EXISTS \"entity_orders\".aether_schema_history_meta (schema_version INTEGER NOT NULL)");
        assertThat(SchemaHistoryEvolution.createOwnerTableSql(DatabaseType.POSTGRESQL, SCHEMA))
            .isEqualTo("CREATE TABLE IF NOT EXISTS \"entity_orders\".aether_schema_owner (blueprint_base VARCHAR(256) NOT NULL)");
        assertThat(SchemaHistoryEvolution.STEP_V1.ddl().render(DatabaseType.POSTGRESQL, SCHEMA).getFirst())
            .startsWith("CREATE TABLE IF NOT EXISTS \"entity_orders\".aether_schema_history (");
        assertThat(SchemaHistoryEvolution.STEP_V2.ddl().render(DatabaseType.POSTGRESQL, SCHEMA))
            .allMatch(statement -> statement.startsWith("ALTER TABLE \"entity_orders\".aether_schema_history ADD COLUMN"));
    }

    @ParameterizedTest
    @EnumSource(DatabaseType.class)
    void explicitNoneSchema_issuesTheSameStatementsAsTheUnscopedRepository(DatabaseType type) {
        assertThat(HistoryOperations.record(type, SchemaHistoryRepository.schemaHistoryRepository(Option.none())))
            .isEqualTo(HistoryOperations.record(type, SchemaHistoryRepository.schemaHistoryRepository()));
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseType.class, mode = EnumSource.Mode.EXCLUDE, names = "POSTGRESQL")
    void migrate_withASchemaOnAnEngineWithoutSchemas_isRefusedBeforeAnyStatement(DatabaseType type) {
        var connector = new HistoryOperations.ScriptedConnector(type, 0);
        var outcome = SchemaMigrations.schemaMigrations(timeSpan(5).seconds(), SCHEMA)
                                      .migrate("ds", List.of(), connector, "node-1", "owner")
                                      .await();

        String refusal = outcome.fold(cause -> cause.message(), value -> "unexpected success: " + value);

        assertThat(refusal).isEqualTo(MigrationError.SchemaQualifierUnsupported.schemaQualifierUnsupported("entity_orders", type.name()).message());
        assertThat(connector.statements).as("nothing ran, not even the fence").isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseType.class, mode = EnumSource.Mode.EXCLUDE, names = "POSTGRESQL")
    void migrate_withoutASchemaOnTheSameEngine_isNotRefused(DatabaseType type) {
        var connector = new HistoryOperations.ScriptedConnector(type, 0);
        var outcome = SchemaMigrations.schemaMigrations(timeSpan(5).seconds())
                                      .migrate("ds", List.of(), connector, "node-1", "owner")
                                      .await();
        String message = outcome.fold(cause -> cause.message(), value -> "");

        assertThat(message).as("without a schema the engine is never refused")
                           .doesNotContain("Migration scoped to schema");
    }
}
