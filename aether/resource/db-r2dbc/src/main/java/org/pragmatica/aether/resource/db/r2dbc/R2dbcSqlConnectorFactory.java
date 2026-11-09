// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.r2dbc;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.db.SqlConnectorView;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.aether.resource.db.SharedPoolSupport;
import org.pragmatica.aether.resource.db.DbShareKey;
import org.pragmatica.aether.resource.NodeShareable;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Promise;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;


/// A [NodeShareable] factory (#2973): slices on one node whose datasource resolves to the same connection settings share
/// one pool, and each receives its own view of it.
public final class R2dbcSqlConnectorFactory implements ResourceFactory<SqlConnector, DatabaseConnectorConfig>, NodeShareable<SqlConnector, DatabaseConnectorConfig> {
    @Override
    public Class<SqlConnector> resourceType() {
        return SqlConnector.class;
    }

    @Override
    public Class<DatabaseConnectorConfig> configType() {
        return DatabaseConnectorConfig.class;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean supports(DatabaseConnectorConfig config) {
        return config.r2dbcUrl()
                     .isPresent();
    }

    @Override
    public Promise<SqlConnector> provision(DatabaseConnectorConfig config) {
        // D2.8 (#2973): the driver is found through this factory's class loader, never a slice's, because the pool may be
        // shared by every slice on the node.
        return Promise.lift(DatabaseConnectorError::databaseFailure,
                            () -> SharedPoolSupport.underClassLoaderOf(getClass(), () -> connector(config)));
    }

    /// #2973: the effective endpoint, credentials, pool settings, properties and application name of the R2DBC transport (this connector sends no application name; the configured value is keyed all the same).
    @Override
    public Option<Object> shareKey(DatabaseConnectorConfig config) {
        return DbShareKey.dbShareKey(getClass(), SqlConnector.class, config, DatabaseConnectorConfig.Transport.R2DBC);
    }

    @Override
    public SqlConnector view(SqlConnector pool, Fn0<Promise<Unit>> release) {
        return SqlConnectorView.sqlConnectorView(pool, release);
    }

    /// The warm-up is the transport's real one: the pool opens `min_connections` at provisioning.
    @Override
    public PoolDemand demand(DatabaseConnectorConfig config) {
        return SharedPoolSupport.poolDemand(config, DatabaseConnectorConfig.Transport.R2DBC, config.poolConfig().minConnections());
    }

    @Override
    public Option<Promise<ServerProbe>> probe(SqlConnector pool) {
        return SharedPoolSupport.probe(pool);
    }

    /// Package-visible so the URL and credentials the pool is built with are checkable without connecting (#784): the
    /// R2DBC connector reads ITS OWN transport's values.
    static ConnectionFactoryOptions connectionFactoryOptions(DatabaseConnectorConfig config) {
        var options = ConnectionFactoryOptions.parse(config.effectiveR2dbcUrl());
        var optionsBuilder = ConnectionFactoryOptions.builder().from(options);

        config.effectiveUsername(DatabaseConnectorConfig.Transport.R2DBC)
              .onPresent(u -> optionsBuilder.option(ConnectionFactoryOptions.USER, u));
        config.effectivePassword(DatabaseConnectorConfig.Transport.R2DBC)
              .onPresent(p -> optionsBuilder.option(ConnectionFactoryOptions.PASSWORD, p));

        return optionsBuilder.build();
    }

    private static SqlConnector connector(DatabaseConnectorConfig config) {
        var connectionFactory = ConnectionFactories.get(connectionFactoryOptions(config));
        var poolConfig = ConnectionPoolConfiguration.builder(connectionFactory)
                                                    .maxSize(config.poolConfig().maxConnections())
                                                    .initialSize(config.poolConfig().minConnections())
                                                    .maxIdleTime(config.poolConfig().idleTimeout().duration())
                                                    .maxLifeTime(config.poolConfig().maxLifetime().duration())
                                                    .build();
        var pool = new ConnectionPool(poolConfig);

        return R2dbcSqlConnector.r2dbcSqlConnector(config, pool, connectionFactory);
    }
}
