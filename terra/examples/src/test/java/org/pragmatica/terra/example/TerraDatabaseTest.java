// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.example.inventory.InventoryService;
import org.pragmatica.aether.example.shared.LineItem;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.terra.TerraApplication;
import org.pragmatica.terra.example.database.SchemaProbe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Opt-in local PostgreSQL proof. Creates and removes only a unique test schema.
@EnabledIfSystemProperty(named = "terra.test.jdbcUrl", matches = ".+")
class TerraDatabaseTest {
    @TempDir Path directory;
    private String baseUrl;
    private String schema;
    private String jdbcUrl;
    private ConfigurationProvider config;

    @BeforeEach void prepare() throws Exception {
        baseUrl = System.getProperty("terra.test.jdbcUrl");
        schema = "terra_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(baseUrl); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        jdbcUrl = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        config = ConfigurationProvider.builder().withDefaults(Map.of("database.jdbc_url", jdbcUrl)).build();
        writeBlueprint("example:inventory:1");
        Files.createDirectory(directory.resolve("schema"));
        try (var source = getClass().getResourceAsStream("/inventory/schema/V001__create_tables.sql")) {
            Files.copy(source, directory.resolve("schema/V001__create_tables.sql"));
        }
        Files.writeString(directory.resolve("schema/V002__seed.sql"), "INSERT INTO products(product_id, stock) VALUES ('SKU-1', 100);");
    }

    @AfterEach void cleanup() throws Exception {
        if (schema == null) return;
        try (var connection = DriverManager.getConnection(baseUrl); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test void start_inventoryExample_migratesBeforeConstructionAndReusesHistory() throws Exception {
        var app = start().unwrap();
        try {
            assertThat(app.migrations()).hasSize(1);
            assertThat(app.migrations().getFirst().result().appliedCount()).isEqualTo(2);
            assertThat(app.slice(SchemaProbe.class).unwrap().count().await(timeSpan(5).seconds()).unwrap()).isEqualTo(1);
            var stock = app.slice(InventoryService.class).unwrap()
                .checkStock(new InventoryService.CheckStockRequest(List.of(LineItem.lineItem("SKU-1", 5).unwrap())))
                .await(timeSpan(5).seconds()).unwrap();
            assertThat(stock.isFullyAvailable()).isTrue();
            assertThat(stock.availableStock().values().iterator().next().value()).isEqualTo(100);
        } finally { app.close().await(timeSpan(5).seconds()).unwrap(); }
        writeBlueprint("example:inventory:2");
        var second = start().unwrap();
        try {
            assertThat(second.migrations().getFirst().result().appliedCount()).isZero();
            assertThat(second.migrations().getFirst().result().currentVersion()).isEqualTo(2);
        } finally { second.close().await(timeSpan(5).seconds()).unwrap(); }
        assertThat(queryString("SELECT blueprint_base FROM aether_schema_owner")).isEqualTo("example:inventory");
        assertThat(queryString("SELECT COUNT(*) FROM aether_schema_history WHERE status = 'SUCCESS'")).isEqualTo("2");
    }

    @Test void start_changedAppliedScript_refusesChecksumMismatch() throws Exception {
        start().unwrap().close().await(timeSpan(5).seconds()).unwrap();
        Files.writeString(directory.resolve("schema/V002__seed.sql"), "INSERT INTO products(product_id, stock) VALUES ('SKU-2', 10);");
        var result = start();
        assertThat(result.isFailure()).isTrue();
        assertThat(result.<String>fold(c -> c.message(), _ -> "unexpected success")).contains("Checksum mismatch");
        assertThat(queryString("SELECT COUNT(*) FROM products")).isEqualTo("1");
    }

    @Test void start_otherBlueprint_refusesPhysicalOwnershipConflict() throws Exception {
        start().unwrap().close().await(timeSpan(5).seconds()).unwrap();
        writeBlueprint("example:unrelated:1");
        var result = start();
        assertThat(result.isFailure()).isTrue();
        assertThat(result.<String>fold(c -> c.message(), _ -> "unexpected success")).contains("already migrated by blueprint");
        assertThat(queryString("SELECT blueprint_base FROM aether_schema_owner")).isEqualTo("example:inventory");
    }

    @Test void start_scriptFails_rollsBackDdlAndHistoryThenAllowsCorrectedRestart() throws Exception {
        Files.writeString(directory.resolve("schema/V003__failure.sql"), "CREATE TABLE should_rollback(id INT); INSERT INTO absent_table VALUES (1);");
        var result = start();
        assertThat(result.isFailure()).isTrue();
        assertThat(queryString("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + schema + "' AND table_name = 'should_rollback'")).isEqualTo("0");
        assertThat(queryString("SELECT COUNT(*) FROM aether_schema_history WHERE version = 3")).isEqualTo("0");
        Files.writeString(directory.resolve("schema/V003__failure.sql"), "CREATE TABLE should_rollback(id INT);");
        var app = start().unwrap();
        try { assertThat(app.migrations().getFirst().result().appliedCount()).isEqualTo(1); }
        finally { app.close().await(timeSpan(5).seconds()).unwrap(); }
    }

    private org.pragmatica.lang.Result<TerraApplication> start() {
        return TerraApplication.start(directory.resolve("blueprint.toml"), _ -> config, config).await(timeSpan(15).seconds());
    }

    private void writeBlueprint(String id) throws Exception {
        Files.writeString(directory.resolve("blueprint.toml"), "id='" + id + "'\n" + """
            [[slices]]
            artifact='org.pragmatica.example:terra-inventory-service:1.0.0-rc4'
            [[slices]]
            artifact='org.pragmatica.example:terra-schema-probe:1.0.0-rc4'
            """);
    }

    private String queryString(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
