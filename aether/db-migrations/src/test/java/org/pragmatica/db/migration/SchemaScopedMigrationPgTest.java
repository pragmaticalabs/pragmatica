// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.resource.db.PgSchemaName;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.resource.db.jdbc.JdbcSqlConnectorFactory;
import org.pragmatica.db.migration.SchemaMigrations.SchemaResult;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.testing.DockerRequirement;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2100 E-B L1 (design §d T5, T6), against a LIVE PostgreSQL 16 through the production JDBC transport. Self-skips
/// without Docker unless `-Daether.requireDocker=true`.
///
/// - T5: the user path (no schema) and an entity schema each keep their own history, meta and owner tables, so the two
///   version sequences and the two ownership claims are independent.
/// - T6: least privilege. A role that cannot create schemas migrates into a pre-created one; with the schema absent it
///   is refused as `SchemaCreateDenied` having written nothing; once the grant exists a retry completes.
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class SchemaScopedMigrationPgTest {
    private static final String MIGRATOR = "migrator";
    private static final String LIMITED = "limited";
    private static final String PASSWORD = "secret";
    private static final String DATASOURCE = "scoped";
    private static final TimeSpan GENEROUS = timeSpan(60).seconds();
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static PostgreSQLContainer<?> postgres;

    private final List<SqlConnector> opened = new ArrayList<>();
    private String database;

    @BeforeAll
    static void startPostgres() throws SQLException {
        assumeTrue(DockerRequirement.dockerAvailable(), "Docker is required for the live-PostgreSQL schema-scoped migration");
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();

        try (var admin = superuser(postgres.getDatabaseName())) {
            execute(admin, "CREATE ROLE " + MIGRATOR + " LOGIN PASSWORD '" + PASSWORD + "'");
            execute(admin, "CREATE ROLE " + LIMITED + " LOGIN PASSWORD '" + PASSWORD + "'");
        }
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @AfterEach
    void cleanUp() throws SQLException {
        opened.forEach(connector -> connector.stop().await());
        opened.clear();

        if (database != null) {
            try (var admin = superuser(postgres.getDatabaseName())) {
                execute(admin, "DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    /// T5: V1 of the user path and V1 of an entity schema both apply, each in its own history table; the second
    /// entity version advances only the entity sequence; the two owner tables hold the two different blueprints.
    @Test
    void migrate_userPathAndEntitySchema_keepIndependentSequences() throws SQLException {
        freshDatabase(MIGRATOR);
        var connector = connector(MIGRATOR);
        var entity = Option.some(schemaName("entity_orders"));

        assertThat(migrate(SchemaMigrations.schemaMigrations(GENEROUS), connector, "user-app",
                           script(1, "users", "CREATE TABLE users (id BIGINT PRIMARY KEY)")).isSuccess()).isTrue();
        assertThat(migrate(SchemaMigrations.schemaMigrations(GENEROUS, entity), connector, "entity-app",
                           script(1, "orders", "CREATE TABLE entity_orders.orders (id BIGINT PRIMARY KEY)")).isSuccess()).isTrue();
        assertThat(migrate(SchemaMigrations.schemaMigrations(GENEROUS, entity), connector, "entity-app",
                           script(1, "orders", "CREATE TABLE entity_orders.orders (id BIGINT PRIMARY KEY)"),
                           script(2, "orders_note", "ALTER TABLE entity_orders.orders ADD COLUMN note TEXT")).isSuccess()).isTrue();

        assertThat(scalar("SELECT count(*) FROM public.aether_schema_history")).as("user history: V1 only").isEqualTo(1);
        assertThat(scalar("SELECT max(version) FROM public.aether_schema_history")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM entity_orders.aether_schema_history")).as("entity history: V1 and V2").isEqualTo(2);
        assertThat(scalar("SELECT max(version) FROM entity_orders.aether_schema_history")).isEqualTo(2);
        assertThat(text("SELECT blueprint_base FROM public.aether_schema_owner")).isEqualTo("user-app");
        assertThat(text("SELECT blueprint_base FROM entity_orders.aether_schema_owner")).isEqualTo("entity-app");
        assertThat(scalar("SELECT count(*) FROM pg_tables WHERE tablename LIKE 'aether_schema_%' AND schemaname NOT IN ('public', 'entity_orders')"))
            .as("no history table anywhere else").isZero();
    }

    /// T6: pre-created schema and no database CREATE completes; absent schema is refused as `SchemaCreateDenied` and
    /// leaves nothing behind; after the grant a retry completes and creates it.
    @Test
    void migrate_withoutDatabaseCreate_usesAPreCreatedSchemaAndRefusesAnAbsentOne() throws SQLException {
        freshDatabase(postgres.getUsername());
        execute("CREATE SCHEMA pre_made AUTHORIZATION " + LIMITED);
        var connector = connector(LIMITED);

        var preMade = migrate(SchemaMigrations.schemaMigrations(GENEROUS, Option.some(schemaName("pre_made"))), connector, "entity-app",
                              script(1, "t", "CREATE TABLE pre_made.t (id BIGINT)"));

        assertThat(preMade.isSuccess()).as("pre-created schema, no database CREATE").isTrue();
        assertThat(scalar("SELECT count(*) FROM pre_made.aether_schema_history")).isEqualTo(1);

        var absent = migrate(SchemaMigrations.schemaMigrations(GENEROUS, Option.some(schemaName("not_made"))), connector, "entity-app",
                             script(1, "t", "CREATE TABLE not_made.t (id BIGINT)"));

        String refusal = absent.fold(cause -> cause.message(), value -> "unexpected success: " + value);

        assertThat(refusal).isEqualTo(MigrationError.SchemaCreateDenied.schemaCreateDenied("not_made", LIMITED).message());
        assertThat(scalar("SELECT count(*) FROM pg_namespace WHERE nspname = 'not_made'")).as("nothing was created").isZero();

        execute("GRANT CREATE ON DATABASE " + database + " TO " + LIMITED);

        var retried = migrate(SchemaMigrations.schemaMigrations(GENEROUS, Option.some(schemaName("not_made"))), connector, "entity-app",
                              script(1, "t", "CREATE TABLE not_made.t (id BIGINT)"));

        assertThat(retried.isSuccess()).as("after the grant").isTrue();
        assertThat(scalar("SELECT count(*) FROM not_made.aether_schema_history")).isEqualTo(1);
    }

    private static PgSchemaName schemaName(String raw) {
        return PgSchemaName.pgSchemaName(raw).unwrap();
    }

    private static MigrationScript script(int version, String description, String sql) {
        return MigrationScript.migrationScript("V" + version + "__" + description + ".sql", sql, sql.hashCode());
    }

    private static Result<SchemaResult> migrate(SchemaMigrations migrations, SqlConnector connector, String owner, MigrationScript... scripts) {
        return migrations.migrate(DATASOURCE, List.of(scripts), connector, "node-1", owner).await();
    }

    /// A fresh database owned by `owner`; the container superuser owns it, so `LIMITED` has no CREATE on it.
    private void freshDatabase(String owner) throws SQLException {
        database = "scoped_" + DATABASES.incrementAndGet();

        try (var admin = superuser(postgres.getDatabaseName())) {
            execute(admin, "CREATE DATABASE " + database + " OWNER " + owner);
            execute(admin, "GRANT CONNECT ON DATABASE " + database + " TO " + LIMITED);
        }
    }

    private SqlConnector connector(String role) {
        var pool = PoolConfig.poolConfigBuilder().withMinConnections(0).withMaxConnections(4).build().unwrap();
        var config = databaseConnectorConfigBuilder().withName(DATASOURCE)
                                                     .withUsername(role)
                                                     .withPassword(PASSWORD)
                                                     .withPoolConfig(pool)
                                                     .withJdbcUrl(jdbcUrl(database))
                                                     .build()
                                                     .unwrap();
        var connector = new JdbcSqlConnectorFactory().provision(config).await().unwrap();

        opened.add(connector);
        return connector;
    }

    private void execute(String sql) throws SQLException {
        try (var admin = superuser(database)) {
            execute(admin, sql);
        }
    }

    private long scalar(String sql) throws SQLException {
        try (var conn = superuser(database); var statement = conn.createStatement(); var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String text(String sql) throws SQLException {
        try (var conn = superuser(database); var statement = conn.createStatement(); var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Connection superuser(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(db), postgres.getUsername(), postgres.getPassword());
    }

    private static String jdbcUrl(String db) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + db;
    }

    private static void execute(Connection conn, String sql) throws SQLException {
        try (var statement = conn.createStatement()) {
            statement.execute(sql);
        }
    }
}
