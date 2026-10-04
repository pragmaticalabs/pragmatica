package org.pragmatica.aether.resource.db.r2dbc;

import io.r2dbc.spi.ConnectionFactoryOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;

/// #784 — the R2DBC connection factory is built from the R2DBC transport's OWN values: a datasource with an R2DBC URL
/// and a JDBC URL, each with its own credentials, must give R2DBC the R2DBC URL's user and password. A wrong
/// `Transport` in this factory turns this red.
class R2dbcSqlConnectorFactoryTransportTest {
    @Test
    void r2dbcOptions_useTheR2dbcUrlsCredentials_notTheJdbcUrls() {
        var config = databaseConnectorConfigBuilder().withName("db")
                                                     .withR2dbcUrl("r2dbc:postgresql://r2-user:r2-pw@r2-host:5432/r2-db")
                                                     .withJdbcUrl("jdbc:postgresql://jdbc-user:jdbc-pw@jdbc-host:5433/jdbc-db")
                                                     .build()
                                                     .unwrap();

        var options = R2dbcSqlConnectorFactory.connectionFactoryOptions(config);

        assertThat(options.getValue(ConnectionFactoryOptions.USER)).isEqualTo("r2-user");
        assertThat(options.getValue(ConnectionFactoryOptions.PASSWORD)).isEqualTo("r2-pw");
        assertThat(options.getValue(ConnectionFactoryOptions.HOST)).isEqualTo("r2-host");
    }
}
