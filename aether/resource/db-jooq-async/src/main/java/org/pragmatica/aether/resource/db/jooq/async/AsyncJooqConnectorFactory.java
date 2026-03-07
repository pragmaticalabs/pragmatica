package org.pragmatica.aether.resource.db.jooq.async;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.jooq.JooqConnector;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;
import org.pragmatica.postgres.r2dbc.PgAsyncConnectionFactory;
import org.pragmatica.lang.Promise;

/// SPI factory for creating postgres-async JooqConnector instances via R2DBC adapter.
///
/// Priority 20 — preferred over both JDBC and R2DBC when asyncUrl is configured.
/// Uses postgres-async through the R2DBC SPI adapter for full jOOQ compatibility.
public final class AsyncJooqConnectorFactory implements ResourceFactory<JooqConnector, DatabaseConnectorConfig> {
    @Override
    public Class<JooqConnector> resourceType() {
        return JooqConnector.class;
    }

    @Override
    public Class<DatabaseConnectorConfig> configType() {
        return DatabaseConnectorConfig.class;
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public boolean supports(DatabaseConnectorConfig config) {
        return config.asyncUrl()
                     .isPresent();
    }

    @Override
    public Promise<JooqConnector> provision(DatabaseConnectorConfig config) {
        return Promise.lift(DatabaseConnectorError::databaseFailure, () -> connector(config));
    }

    private static JooqConnector connector(DatabaseConnectorConfig config) {
        var builder = new NettyConnectibleBuilder();
        builder.hostname(config.effectiveHost())
               .port(config.effectivePort())
               .database(config.effectiveDatabase());
        config.effectiveUsername()
              .onPresent(builder::username);
        config.effectivePassword()
              .onPresent(builder::password);
        builder.maxConnections(config.poolConfig()
                                     .maxConnections());
        config.poolConfig()
              .validationQuery()
              .onPresent(builder::validationQuery);
        var connectible = builder.pool();
        var connectionFactory = PgAsyncConnectionFactory.pgAsyncConnectionFactory(connectible);
        return AsyncJooqConnector.asyncJooqConnector(config, connectionFactory);
    }
}
