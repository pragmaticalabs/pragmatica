// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.resource.db.jdbc.JdbcSqlConnectorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.oracle.OracleContainer;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;

/// FDB-2b fix round (#2591, review A1): the Oracle tier-2 fence (`DBMS_LOCK.REQUEST`, `release_on_commit => FALSE`)
/// against a live Oracle Free 23. Each test gets its own user, so the lock name (derived from the schema) is private to
/// it. The raw lock block below is written independently of the production text.
@Tag("heavy-db")
class MigrationFenceOracleTest extends LiveFenceContract {
    private static final AtomicInteger USERS = new AtomicInteger();
    private static final String PASSWORD = "fencepw";
    private static final String TRY_LOCK = "DECLARE h VARCHAR2(128); r INTEGER; BEGIN"
                                           + " DBMS_LOCK.ALLOCATE_UNIQUE('AETHER_MIG_' || SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'), h);"
                                           + " r := DBMS_LOCK.REQUEST(h, DBMS_LOCK.X_MODE, 0, FALSE); ? := r; END;";
    private static OracleContainer oracle;
    private String user;

    @BeforeAll
    static void startOracle() {
        assumeTrue(DockerRequirement.dockerAvailable(), "Docker is required for the live-Oracle migration fence");
        oracle = new OracleContainer("gvenzl/oracle-free:23-slim-faststart");
        oracle.start();
    }

    @AfterAll
    static void stopOracle() {
        if (oracle != null) {
            oracle.stop();
        }
    }

    @Override
    void freshSchema() throws SQLException {
        user = "FENCE_" + USERS.incrementAndGet();

        try (var system = system()) {
            execute(system, "CREATE USER " + user + " IDENTIFIED BY " + PASSWORD + " QUOTA UNLIMITED ON USERS");
            execute(system, "GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO " + user);
            execute(system, "GRANT EXECUTE ON SYS.DBMS_LOCK TO " + user);
        }
    }

    @Override
    SqlConnector newConnector(int poolSize) {
        var pool = PoolConfig.poolConfigBuilder().withMinConnections(0).withMaxConnections(poolSize).build().unwrap();
        var config = databaseConnectorConfigBuilder().withName(DATASOURCE)
                                                     .withType(DatabaseType.ORACLE)
                                                     .withJdbcUrl(oracle.getJdbcUrl())
                                                     .withUsername(user)
                                                     .withPassword(PASSWORD)
                                                     .withPoolConfig(pool)
                                                     .build()
                                                     .unwrap();

        return new JdbcSqlConnectorFactory().provision(config).await().unwrap();
    }

    @Override
    Connection rawConnection() throws SQLException {
        return DriverManager.getConnection(oracle.getJdbcUrl(), user, PASSWORD);
    }

    /// DBMS_LOCK.REQUEST answers 0 (granted) or 4 (this session already owns it); 1 is a timeout (held elsewhere).
    @Override
    boolean tryHoldFence(Connection session) throws SQLException {
        try (CallableStatement call = session.prepareCall(TRY_LOCK)) {
            call.registerOutParameter(1, Types.INTEGER);
            call.execute();

            var answer = call.getInt(1);

            return answer == 0 || answer == 4;
        }
    }

    @Override
    long blockedSessions() throws SQLException {
        try (var system = system(); var statement = system.createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM v$session WHERE blocking_session IS NOT NULL")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Connection system() throws SQLException {
        return DriverManager.getConnection(oracle.getJdbcUrl(), "system", oracle.getPassword());
    }
}
