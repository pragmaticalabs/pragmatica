// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaLoaderTest {

    @Nested
    class DirectoryConvention {
        @Test
        void defaultDatabase_mapsToSchemaDir() {
            assertThat(SchemaLoader.configToSchemaPath("database")).isEqualTo("schema/");
        }

        @Test
        void namedDatasource_mapsToSubdirectory() {
            assertThat(SchemaLoader.configToSchemaPath("database.analytics"))
                .isEqualTo("schema/analytics/");
        }

        @Test
        void deeplyNested_mapsCorrectly() {
            assertThat(SchemaLoader.configToSchemaPath("database.tenant.primary"))
                .isEqualTo("schema/tenant.primary/");
        }

        @Test
        void unknownPrefix_defaultsToSchema() {
            assertThat(SchemaLoader.configToSchemaPath("other")).isEqualTo("schema/");
        }
    }

    @Nested
    class SchemaFromScripts {
        @Test
        void loadFromScripts_singleTable_success() {
            var result = SchemaLoader.loadFromScripts(List.of(
                """
                CREATE TABLE users (
                    id bigint NOT NULL,
                    name text NOT NULL,
                    email text NOT NULL,
                    PRIMARY KEY (id)
                )"""
            ));
            assertThat(result.isSuccess()).isTrue();
            var schema = result.unwrap();
            assertThat(schema.table("users").isPresent()).isTrue();

            var table = schema.table("users").unwrap();
            assertThat(table.columns()).hasSize(3);
            assertThat(table.column("id").isPresent()).isTrue();
            assertThat(table.column("name").isPresent()).isTrue();
            assertThat(table.column("email").isPresent()).isTrue();
        }

        @Test
        void loadFromScripts_multipleMigrations_appliedInOrder() {
            var result = SchemaLoader.loadFromScripts(List.of(
                "CREATE TABLE orders (id bigint NOT NULL, status text NOT NULL, PRIMARY KEY (id))",
                "ALTER TABLE orders ADD COLUMN total numeric(10,2) NOT NULL DEFAULT 0"
            ));
            assertThat(result.isSuccess()).isTrue();
            var table = result.unwrap().table("orders").unwrap();
            assertThat(table.columns()).hasSize(3);
            assertThat(table.column("total").isPresent()).isTrue();
        }

        @Test
        void loadFromScripts_invalidSql_failure() {
            var result = SchemaLoader.loadFromScripts(List.of(
                "NOT VALID SQL AT ALL @@@@"
            ));
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void loadFromScripts_schemaWithConstraints_preservesPk() {
            var result = SchemaLoader.loadFromScripts(List.of(
                """
                CREATE TABLE users (
                    id bigint NOT NULL,
                    email text NOT NULL,
                    CONSTRAINT users_pkey PRIMARY KEY (id),
                    CONSTRAINT users_email_unique UNIQUE (email)
                )"""
            ));
            assertThat(result.isSuccess()).isTrue();
            var table = result.unwrap().table("users").unwrap();
            assertThat(table.constraints()).hasSize(2);
        }
    }

    @Nested
    class SchemaCache {
        @Test
        void loadFromScripts_emptyList_returnsEmptySchema() {
            var result = SchemaLoader.loadFromScripts(List.of());
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap().tables()).isEmpty();
        }
    }

    @Nested
    class MigrationOrdering {
        @Test
        void orderMigrations_ordersByNumericVersion_notLexicographically() {
            var result = SchemaLoader.orderMigrations(List.of(
                "V10__add_total.sql", "V2__create_orders.sql", "V1__init.sql"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap())
                .containsExactly("V1__init.sql", "V2__create_orders.sql", "V10__add_total.sql");
        }

        @Test
        void orderMigrations_discoversArbitrarilyNamedFile() {
            var result = SchemaLoader.orderMigrations(List.of("V002__eventmanagement.sql"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()).containsExactly("V002__eventmanagement.sql");
        }

        @Test
        void orderMigrations_ignoresNonMigrationFiles() {
            var result = SchemaLoader.orderMigrations(List.of(
                "migrations.list", "README.md", "schema.sql", "V001__init.sql"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()).containsExactly("V001__init.sql");
        }

        @Test
        void orderMigrations_dedupesSameFileFromMultipleRoots() {
            var result = SchemaLoader.orderMigrations(List.of(
                "V001__init.sql", "V001__init.sql"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()).containsExactly("V001__init.sql");
        }

        @Test
        void orderMigrations_duplicateVersion_fails() {
            var result = SchemaLoader.orderMigrations(List.of(
                "V001__alpha.sql", "V001__beta.sql"));

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void orderMigrations_malformedVersion_fails() {
            var result = SchemaLoader.orderMigrations(List.of("V0x__broken.sql"));

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void orderMigrations_emptyInput_returnsEmpty() {
            var result = SchemaLoader.orderMigrations(List.of());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()).isEmpty();
        }
    }
}
