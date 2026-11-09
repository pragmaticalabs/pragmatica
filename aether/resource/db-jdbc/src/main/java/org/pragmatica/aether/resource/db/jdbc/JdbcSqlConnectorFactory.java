// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jdbc;

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

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;


/// A [NodeShareable] factory (#2973): slices on one node whose datasource resolves to the same connection settings share
/// one pool, and each receives its own view of it.
public final class JdbcSqlConnectorFactory implements ResourceFactory<SqlConnector, DatabaseConnectorConfig>, NodeShareable<SqlConnector, DatabaseConnectorConfig> {
    @Override
    public Class<SqlConnector> resourceType() {
        return SqlConnector.class;
    }

    @Override
    public Class<DatabaseConnectorConfig> configType() {
        return DatabaseConnectorConfig.class;
    }

    @Override
    public Promise<SqlConnector> provision(DatabaseConnectorConfig config) {
        // D2.8 (#2973): the driver is found through this factory's class loader, never a slice's, because the pool may be
        // shared by every slice on the node.
        return Promise.lift(DatabaseConnectorError::databaseFailure,
                            () -> SharedPoolSupport.underClassLoaderOf(getClass(), () -> connector(config)));
    }

    /// #2973: the effective endpoint, credentials, pool settings, properties and application name of the JDBC transport (this connector sends no application name; the configured value is keyed all the same).
    @Override
    public Option<Object> shareKey(DatabaseConnectorConfig config) {
        return DbShareKey.dbShareKey(getClass(), SqlConnector.class, config, DatabaseConnectorConfig.Transport.JDBC);
    }

    @Override
    public SqlConnector view(SqlConnector pool, Fn0<Promise<Unit>> release) {
        return SqlConnectorView.sqlConnectorView(pool, release);
    }

    /// The warm-up is the transport's real one: the pool opens `min_connections` at provisioning.
    @Override
    public PoolDemand demand(DatabaseConnectorConfig config) {
        return SharedPoolSupport.poolDemand(config, DatabaseConnectorConfig.Transport.JDBC, config.poolConfig().minConnections());
    }

    @Override
    public Option<Promise<ServerProbe>> probe(SqlConnector pool) {
        return SharedPoolSupport.probe(pool);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static SqlConnector connector(DatabaseConnectorConfig config) {
        var dataSource = hikariDataSource(config);

        try {
            return JdbcSqlConnector.jdbcSqlConnector(config, dataSource);
        } catch (Exception e) {
            dataSource.close();

            throw e;
        }
    }

    private static HikariDataSource hikariDataSource(DatabaseConnectorConfig config) {
        return new HikariDataSource(hikariConfig(config));
    }

    /// Package-visible so what the pool is built with (URL, credentials) is checkable without connecting (#784): the
    /// JDBC pool reads ITS OWN transport's values.
    static HikariConfig hikariConfig(DatabaseConnectorConfig config) {
        var hikariConfig = new HikariConfig();

        hikariConfig.setJdbcUrl(config.effectiveJdbcUrl());
        config.effectiveUsername(DatabaseConnectorConfig.Transport.JDBC).onPresent(hikariConfig::setUsername);
        config.effectivePassword(DatabaseConnectorConfig.Transport.JDBC).onPresent(hikariConfig::setPassword);
        hikariConfig.setConnectionTimeout(config.poolConfig().connectionTimeout().toMillis());
        hikariConfig.setIdleTimeout(config.poolConfig().idleTimeout().toMillis());
        hikariConfig.setMaxLifetime(config.poolConfig().maxLifetime().toMillis());
        hikariConfig.setMinimumIdle(config.poolConfig().minConnections());
        hikariConfig.setMaximumPoolSize(config.poolConfig().maxConnections());

        return hikariConfig;
    }
}
