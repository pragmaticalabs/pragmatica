// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.resource.db.jdbc.JdbcSqlConnectorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.pragmatica.testing.DockerRequirement;
import org.testcontainers.containers.MSSQLServerContainer;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;

/// FDB-2b fix round (#2591, review A1): the SQL Server tier-2 fence (`sp_getapplock`, `@LockOwner = 'Session'`) against a
/// live SQL Server 2022. The raw lock statement below is written independently of the production text.
@Tag("heavy-db")
class MigrationFenceSqlServerTest extends LiveFenceContract {
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final String TRY_LOCK = "SET NOCOUNT ON; DECLARE @r INT; EXEC @r = sp_getapplock @Resource = 'aether_schema_migration',"
                                           + " @LockMode = 'Exclusive', @LockOwner = 'Session', @LockTimeout = 0; SELECT @r";
    private static MSSQLServerContainer<?> sqlServer;
    private String database;

    @BeforeAll
    static void startSqlServer() {
        assumeTrue(DockerRequirement.dockerAvailable(), "Docker is required for the live-SQL-Server migration fence");
        sqlServer = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();
        sqlServer.start();
    }

    @AfterAll
    static void stopSqlServer() {
        if (sqlServer != null) {
            sqlServer.stop();
        }
    }

    @Override
    void freshSchema() throws SQLException {
        database = "fence_" + DATABASES.incrementAndGet();

        try (var admin = open("master")) {
            execute(admin, "CREATE DATABASE " + database);
        }
    }

    @Override
    SqlConnector newConnector(int poolSize) {
        var pool = PoolConfig.poolConfigBuilder().withMinConnections(0).withMaxConnections(poolSize).build().unwrap();
        var config = databaseConnectorConfigBuilder().withName(DATASOURCE)
                                                     .withType(DatabaseType.SQLSERVER)
                                                     .withJdbcUrl(url(database))
                                                     .withUsername(sqlServer.getUsername())
                                                     .withPassword(sqlServer.getPassword())
                                                     .withPoolConfig(pool)
                                                     .build()
                                                     .unwrap();

        return new JdbcSqlConnectorFactory().provision(config).await().unwrap();
    }

    @Override
    Connection rawConnection() throws SQLException {
        return open(database);
    }

    @Override
    boolean tryHoldFence(Connection session) throws SQLException {
        try (var statement = session.createStatement(); var rs = statement.executeQuery(TRY_LOCK)) {
            rs.next();
            return rs.getInt(1) >= 0;
        }
    }

    @Override
    long blockedSessions() throws SQLException {
        try (var admin = open("master"); var statement = admin.createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM sys.dm_exec_requests WHERE blocking_session_id <> 0")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Connection open(String db) throws SQLException {
        return DriverManager.getConnection(url(db), sqlServer.getUsername(), sqlServer.getPassword());
    }

    private static String url(String db) {
        return sqlServer.getJdbcUrl() + ";databaseName=" + db;
    }
}
