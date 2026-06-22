// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.pg.split.SplitError;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.blueprint.MigrationEntry;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.slice.blueprint.MigrationEntry.migrationEntry;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

class AetherSchemaManagerTest {
    private static final SchemaPolicy POLICY = SchemaPolicy.schemaPolicy();
    private static final String NODE_ID = "node-1";

    @Nested
    class PostgresFamily {

        @Test
        void migrate_keepsFunctionBodyIntact_forDollarQuotedBody() {
            var connector = new RecordingConnector(DatabaseType.POSTGRESQL);
            var sql = """
                      CREATE TABLE t (id INT);
                      CREATE FUNCTION f() RETURNS void AS $$
                      BEGIN
                        INSERT INTO t VALUES (1);
                        INSERT INTO t VALUES (2);
                      END;
                      $$ LANGUAGE plpgsql;
                      """;

            migrate(connector, "V1__fn.sql", sql);

            assertThat(connector.migrationStatements).hasSize(2);
            assertThat(connector.migrationStatements.get(1)).contains("$$").contains("INSERT INTO t VALUES (1)").contains("INSERT INTO t VALUES (2)");
            assertThat(connector.usedTransaction).isTrue();
        }

        @Test
        void migrate_usesAutocommit_forConcurrentIndex() {
            var connector = new RecordingConnector(DatabaseType.POSTGRESQL);
            var sql = """
                      CREATE TABLE t (id INT);
                      CREATE INDEX CONCURRENTLY idx_t ON t (id);
                      """;

            migrate(connector, "V2__idx.sql", sql);

            assertThat(connector.migrationStatements).hasSize(2);
            assertThat(connector.usedTransaction).isFalse();
        }

        @Test
        void migrate_usesTransaction_forOrdinaryDdl() {
            var connector = new RecordingConnector(DatabaseType.POSTGRESQL);
            var sql = """
                      CREATE TABLE a (id INT);
                      CREATE TABLE b (id INT);
                      """;

            migrate(connector, "V3__ddl.sql", sql);

            assertThat(connector.migrationStatements).hasSize(2);
            assertThat(connector.usedTransaction).isTrue();
        }

        @Test
        void migrate_fails_forUnterminatedDollarQuote() {
            var connector = new RecordingConnector(DatabaseType.POSTGRESQL);
            var sql = """
                      CREATE FUNCTION f() RETURNS void AS $$
                      BEGIN
                        INSERT INTO t VALUES (1);
                      """;

            schemaManager().migrate("ds", List.of(migrationEntry("V4__bad.sql", sql, 4L)), connector, NODE_ID)
                           .await()
                           .onSuccess(_ -> fail("Expected migration to fail with a SplitError"))
                           .onFailure(cause -> assertThat(cause).isInstanceOf(SplitError.UnterminatedDollarQuote.class));

            assertThat(connector.migrationStatements).isEmpty();
        }
    }

    @Nested
    class NonWiredFallback {

        /// H2 is still unmapped in [MigrationDialects#dialectFor], so it must take the legacy
        /// naive `split(";")` path: the PostgreSQL `$$` body is cut at every internal `;`,
        /// the whole file runs in a transaction, and behavior is unchanged from before MySQL
        /// was wired.
        @Test
        void migrate_splitsDollarBodyAtInternalSemicolon_forUnwiredH2() {
            var connector = new RecordingConnector(DatabaseType.H2);
            var sql = """
                      CREATE FUNCTION f() RETURNS void AS $$
                      BEGIN
                        INSERT INTO t VALUES (1);
                        INSERT INTO t VALUES (2);
                      END;
                      $$ LANGUAGE plpgsql;
                      """;

            migrate(connector, "V1__fn.sql", sql);

            assertThat(connector.migrationStatements).hasSize(4);
            assertThat(connector.migrationStatements.get(0)).contains("CREATE FUNCTION").contains("INSERT INTO t VALUES (1)");
            assertThat(connector.migrationStatements.get(1)).isEqualTo("INSERT INTO t VALUES (2)");
            assertThat(connector.migrationStatements.get(2)).isEqualTo("END");
            assertThat(connector.migrationStatements.get(3)).isEqualTo("$$ LANGUAGE plpgsql");
            assertThat(connector.usedTransaction).isTrue();
        }
    }

    @Nested
    class MysqlFamily {

        /// MySQL is wired to the dialect-aware splitter: the `DELIMITER`-wrapped stored
        /// procedure is emitted as ONE statement (directives stripped, internal `;` kept),
        /// and because MySQL DDL auto-commits the whole file runs in AUTOCOMMIT — no
        /// transactional wrap.
        @Test
        void migrate_keepsProcedureBodyIntactInAutocommit_forMysqlDelimiterProcedure() {
            var connector = new RecordingConnector(DatabaseType.MYSQL);
            var sql = """
                      DELIMITER $$
                      CREATE PROCEDURE p()
                      BEGIN
                        SELECT 1;
                        SELECT 2;
                      END$$
                      DELIMITER ;
                      CREATE TABLE t (id INT);
                      """;

            migrate(connector, "V1__proc.sql", sql);

            assertThat(connector.migrationStatements).hasSize(2);
            assertThat(connector.migrationStatements.get(0)).contains("CREATE PROCEDURE p()").contains("SELECT 1;").contains("SELECT 2;").contains("END").doesNotContain("DELIMITER");
            assertThat(connector.migrationStatements.get(1)).contains("CREATE TABLE t (id INT)");
            assertThat(connector.usedTransaction).isFalse();
        }
    }

    private AetherSchemaManager schemaManager() {
        return AetherSchemaManager.aetherSchemaManager(POLICY);
    }

    private void migrate(RecordingConnector connector, String filename, String sql) {
        schemaManager().migrate("ds", List.of(migrationEntry(filename, sql, filename.hashCode())), connector, NODE_ID)
                       .await()
                       .onFailure(cause -> fail("Migration failed: " + cause.message()));
    }

    private static final class RecordingConnector implements SqlConnector {
        private static final String HISTORY_TABLE = "aether_schema_history";

        private final DatabaseConnectorConfig config;
        private final List<String> migrationStatements = new ArrayList<>();
        private boolean usedTransaction;

        private RecordingConnector(DatabaseType type) {
            this.config = new DatabaseConnectorConfig(none(),
                                                      some(type),
                                                      some("localhost"),
                                                      none(),
                                                      some("test"),
                                                      none(),
                                                      none(),
                                                      PoolConfig.DEFAULT,
                                                      Map.of(),
                                                      none(),
                                                      none(),
                                                      none());
        }

        @Override
        public DatabaseConnectorConfig config() {
            return config;
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }

        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.success(null);
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.success(Option.none());
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.success(List.of());
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            recordStatement(sql);
            return Promise.success(1);
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            return Promise.success(new int[0]);
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            usedTransaction = true;
            return callback.execute(this);
        }

        /// Records only migration-body statements, filtering out the schema-history bootstrap
        /// and bookkeeping SQL the manager runs on the same connector.
        private void recordStatement(String sql) {
            if (!sql.contains(HISTORY_TABLE)) {
                migrationStatements.add(sql);
            }
        }
    }
}
