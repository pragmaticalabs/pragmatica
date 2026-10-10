package org.pragmatica.aether.resource.db.jooq.jdbc;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;

/// #784 — the JDBC pool is built from the JDBC transport's OWN values. A datasource carrying a JDBC URL and an async
/// URL, each with its own credentials, must give the JDBC pool the JDBC URL's: reading another transport's would
/// hand it the async user and password. A wrong `Transport` in this factory turns this red.
class JdbcJooqConnectorFactoryTransportTest {
    @Test
    void jdbcPool_usesTheJdbcUrlsCredentials_notTheAsyncUrls() {
        var config = databaseConnectorConfigBuilder().withName("db")
                                                     .withJdbcUrl("jdbc:postgresql://jdbc-user:jdbc-pw@jdbc-host:5432/jdbc-db")
                                                     .withAsyncUrl("postgresql://async-user:async-pw@async-host:5433/async-db")
                                                     .build()
                                                     .unwrap();

        var hikari = JdbcJooqConnectorFactory.hikariConfig(config);

        assertThat(hikari.getJdbcUrl()).isEqualTo(config.jdbcUrl().unwrap());
        assertThat(hikari.getUsername()).isEqualTo("jdbc-user");
        assertThat(hikari.getPassword()).isEqualTo("jdbc-pw");
    }

    @Test
    void jdbcPool_withOnlyAnAsyncUrl_stillGetsItsCredentials() {
        var config = databaseConnectorConfigBuilder().withName("db")
                                                     .withAsyncUrl("postgresql://async-user:async-pw@async-host:5433/async-db")
                                                     .build()
                                                     .unwrap();

        var hikari = JdbcJooqConnectorFactory.hikariConfig(config);

        assertThat(hikari.getUsername()).as("the single-URL fall-through is unchanged").isEqualTo("async-user");
        assertThat(hikari.getJdbcUrl()).contains("async-host");
    }
}
