// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.PgSchemaName;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2100 E-B L1 survivor tests (v3244b S14, S16, S18, M01, M02), without Docker: how a failed `CREATE SCHEMA` on a
/// scripted PostgreSQL session is classified, what the role lookup falls back to, and the operator-facing wording
/// pinned as literal strings (never through the record's own `message()`).
class SchemaCreateRefusalTest {
    private static final Option<PgSchemaName> SCHEMA = some(PgSchemaName.pgSchemaName("not_made").unwrap());
    private static final String ROLE_SQL = "SELECT current_user AS role";

    @Test
    void createSchemaFailingWithAnotherSqlState_isReportedAsThatFailureNotAsDenied() {
        var failure = DatabaseConnectorError.queryFailed("CREATE SCHEMA", "disk full", none(), some("53100"));

        assertThat(migrateWithCreateFailure(failure, "app_role")).isSameAs(failure);
    }

    @Test
    void createSchemaFailingWithoutSqlStateAndWithAnotherMessage_isReportedAsThatFailureNotAsDenied() {
        var failure = DatabaseConnectorError.queryFailed("CREATE SCHEMA", "connection reset", none(), none());

        assertThat(migrateWithCreateFailure(failure, "app_role")).isSameAs(failure);
    }

    @Test
    void createSchemaFailingWithAPlainCause_isReportedAsThatFailureNotAsDenied() {
        var failure = Causes.cause("permission denied but not a connector error");

        assertThat(migrateWithCreateFailure(failure, "app_role")).isSameAs(failure);
    }

    @Test
    void createSchemaRefusedBySqlStateAlone_isDenied() {
        var failure = DatabaseConnectorError.queryFailed("CREATE SCHEMA", "unreadable", none(), some("42501"));

        assertThat(migrateWithCreateFailure(failure, "app_role").message()).isEqualTo(DENIED_APP_ROLE);
    }

    @Test
    void createSchemaRefusedByMessageAlone_isDenied() {
        var failure = DatabaseConnectorError.queryFailed("CREATE SCHEMA", "ERROR: permission denied for database db", none(), none());

        assertThat(migrateWithCreateFailure(failure, "app_role").message()).isEqualTo(DENIED_APP_ROLE);
    }

    @Test
    void deniedWhenTheRoleLookupFails_namesAnUnknownRoleAndStillRefuses() {
        var failure = DatabaseConnectorError.queryFailed("CREATE SCHEMA", "denied", none(), some("42501"));

        assertThat(migrateWithCreateFailure(failure, null).message()).isEqualTo(DENIED_APP_ROLE.replace("'app_role'", "'<unknown role>'"));
    }

    @Test
    void schemaCreateDeniedMessage_isPinnedAsALiteral() {
        assertThat(MigrationError.SchemaCreateDenied.schemaCreateDenied("not_made", "app_role").message()).isEqualTo(DENIED_APP_ROLE);
    }

    @Test
    void schemaQualifierUnsupportedMessage_isPinnedAsALiteral() {
        assertThat(MigrationError.SchemaQualifierUnsupported.schemaQualifierUnsupported("entity_orders", "ORACLE").message())
            .isEqualTo("Migration scoped to schema entity_orders refused: ORACLE is not supported for schema-scoped migration"
                       + " (PostgreSQL only). No statement was run."
                       + " To recover: point the datasource at a PostgreSQL database, or remove the schema setting.");
    }

    private static final String DENIED_APP_ROLE = "Schema not_made does not exist and role 'app_role' may not create it. No statement was run."
                                                  + " To recover: create the schema (CREATE SCHEMA not_made) or grant CREATE on the database to that role,"
                                                  + " then retry the migration ('aether schema retry')."
                                                  + " A schema created by hand also needs USAGE and CREATE for the role.";

    /// `role` null scripts a failing role lookup.
    private static Cause migrateWithCreateFailure(Cause createFailure, String role) {
        var session = new Session(createFailure, role);
        var outcome = SchemaMigrations.schemaMigrations(timeSpan(5).seconds(), SCHEMA)
                                      .migrate("ds", List.of(), session, "node-1", "owner")
                                      .await();

        return outcome.fold(cause -> cause, _ -> Causes.cause("unexpected success"));
    }

    /// A PostgreSQL session that holds the fence, reports the schema absent, and fails `CREATE SCHEMA` as scripted.
    private static final class Session implements SqlConnector {
        private static final Map<String, Map<String, Object>> ANSWERS = Map.of("pg_try_advisory_lock", Map.of("granted", 1),
                                                                               "AS n FROM pg_locks", Map.of("n", 1),
                                                                               "version()", Map.of("v", "PostgreSQL 16.4"),
                                                                               "set_config", Map.of("v", "set"));
        private final DatabaseConnectorConfig config = new DatabaseConnectorConfig(none(), some(DatabaseType.POSTGRESQL), some("localhost"), none(),
                                                                                   some("test"), none(), none(), PoolConfig.DEFAULT, Map.of(),
                                                                                   none(), none(), none());
        private final Cause createFailure;
        private final String role;

        Session(Cause createFailure, String role) {
            this.createFailure = createFailure;
            this.role = role;
        }

        @Override
        public <T> Promise<T> pinned(Duration statementTimeout, TransactionCallback<T> body, TransactionCallback<Unit> release) {
            return body.execute(this);
        }

        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return ROLE_SQL.equals(sql)
                   ? roleOrFailure(mapper)
                   : queryOptional(sql, mapper, params).flatMap(row -> row.async(Causes.cause("no row for " + sql)));
        }

        private <T> Promise<T> roleOrFailure(RowMapper<T> mapper) {
            return role == null
                   ? Causes.cause("role lookup unavailable").promise()
                   : mapper.map(new Row(Map.of("role", role))).async();
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.success(ANSWERS.entrySet()
                                          .stream()
                                          .filter(entry -> sql.contains(entry.getKey()))
                                          .findFirst()
                                          .map(entry -> mapper.map(new Row(entry.getValue())).option())
                                          .orElse(none()));
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            return sql.startsWith("CREATE SCHEMA")
                   ? createFailure.promise()
                   : Promise.success(0);
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.success(List.of());
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            return Promise.success(new int[0]);
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return callback.execute(this);
        }

        @Override
        public DatabaseConnectorConfig config() {
            return config;
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }
    }

    private record Row(Map<String, Object> values) implements RowMapper.RowAccessor {
        @Override
        public Result<String> getString(String column) {
            return value(column, String.class);
        }

        @Override
        public Result<Integer> getInt(String column) {
            return value(column, Integer.class);
        }

        @Override
        public Result<Long> getLong(String column) {
            return value(column, Integer.class).map(Integer::longValue);
        }

        @Override
        public Result<Double> getDouble(String column) {
            return value(column, Double.class);
        }

        @Override
        public Result<Boolean> getBoolean(String column) {
            return value(column, Boolean.class);
        }

        @Override
        public Result<byte[]> getBytes(String column) {
            return value(column, byte[].class);
        }

        @Override
        public <V> Result<V> getObject(String column, Class<V> type) {
            return value(column, type);
        }

        private <V> Result<V> value(String column, Class<V> type) {
            return Option.option(values.get(column)).filter(type::isInstance).map(type::cast).toResult(Causes.cause("no " + column));
        }
    }
}
