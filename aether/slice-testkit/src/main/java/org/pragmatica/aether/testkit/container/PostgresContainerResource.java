// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.container;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.resource.db.async.PgSqlConnectorFactory;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.testcontainers.containers.PostgreSQLContainer;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Testcontainer-backed `@PgSql`/`@Sql` resource (spec §5.2). Starts a real PostgreSQL container,
/// applies the slice's `schema/` migrations, and provisions the connector via the real
/// `PgSqlConnectorFactory.provision(config)` — no global `ConfigService` involved.
///
/// Optional Testcontainers + `resource-db-async` classes must be on the test classpath; a
/// fakes-only consumer that never calls `Containers.postgres()` never loads this class.
public final class PostgresContainerResource implements ContainerResource<PgSqlConnector> {
    private static final String DEFAULT_IMAGE = "postgres:15-alpine";

    private String image = DEFAULT_IMAGE;
    private Option<String> schemaLocation = none();
    private Option<PostgreSQLContainer<?>> container = none();

    private PostgresContainerResource() {}

    public static PostgresContainerResource postgres() {
        return new PostgresContainerResource();
    }

    /// Use a specific Postgres image (default `postgres:15-alpine`).
    public PostgresContainerResource withImage(String image) {
        this.image = image;

        return this;
    }

    /// Apply the slice's migrations from this classpath directory (e.g. `"schema/"`) after startup.
    public PostgresContainerResource withSchemaFrom(String classpathDirectory) {
        this.schemaLocation = some(classpathDirectory);

        return this;
    }

    @Override
    public Class<PgSqlConnector> resourceType() {
        return PgSqlConnector.class;
    }

    @Override
    public Promise<PgSqlConnector> provision() {
        return Promise.lift(Causes::fromThrowable, this::startContainer).flatMap(this::provisionConnector);
    }

    /// Container lifecycle stop — fire-and-forget void mutation on an external API.
    @Override
    @Contract
    public void stop() {
        container.onPresent(running -> running.stop());
    }

    private PostgreSQLContainer<?> startContainer() {
        var postgres = new PostgreSQLContainer<>(image);

        postgres.start();
        this.container = some(postgres);

        return postgres;
    }

    private Promise<PgSqlConnector> provisionConnector(PostgreSQLContainer<?> postgres) {
        return connectorConfig(postgres).async()
                              .flatMap(config -> new PgSqlConnectorFactory().provision(config))
                              .flatMap(this::applySchema);
    }

    private static Result<DatabaseConnectorConfig> connectorConfig(PostgreSQLContainer<?> postgres) {
        var host = postgres.getHost();
        var port = postgres.getMappedPort(5432);
        var database = postgres.getDatabaseName();

        return DatabaseConnectorConfig.databaseConnectorConfigBuilder()
                                      .withType(DatabaseType.POSTGRESQL)
                                      .withHost(host)
                                      .withPort(port)
                                      .withDatabase(database)
                                      .withUsername(postgres.getUsername())
                                      .withPassword(postgres.getPassword())
                                      .withAsyncUrl("postgresql://" + host + ":" + port + "/" + database)
                                      .build();
    }

    private Promise<PgSqlConnector> applySchema(PgSqlConnector connector) {
        return schemaLocation.map(location -> SchemaMigrations.apply(connector, location).map(_ -> connector))
                             .or(Promise.success(connector));
    }
}
