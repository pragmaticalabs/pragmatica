// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

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
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;


/// A [NodeShareable] factory (#2973): slices on one node whose datasource resolves to the same connection settings share
/// one pool, and each receives its own view of it.
public final class AsyncSqlConnectorFactory implements ResourceFactory<SqlConnector, DatabaseConnectorConfig>, NodeShareable<SqlConnector, DatabaseConnectorConfig> {
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
        return 20;
    }

    @Override
    public boolean supports(DatabaseConnectorConfig config) {
        return config.asyncUrl()
                     .isPresent();
    }

    @Override
    public Promise<SqlConnector> provision(DatabaseConnectorConfig config) {
        // D2.8 (#2973): the driver and the pool's event-loop threads are created under this factory's class loader, never a
        // slice's, because the pool may be shared by every slice on the node and outlive the slice that opened it.
        return Promise.lift(DatabaseConnectorError::databaseFailure,
                            () -> SharedPoolSupport.underClassLoaderOf(getClass(),
                                                                       () -> assemble(config)))
                      .flatMap(assembled -> warmed(assembled, config));
    }

    private Promise<SqlConnector> warmed(PgPoolSettings.Assembled<SqlConnector> assembled,
                                         DatabaseConnectorConfig config) {
        return SharedPoolSupport.underClassLoaderOf(getClass(),
                                                    () -> assembled.warmed(config.poolConfig()));
    }

    /// #2973: the effective endpoint, credentials, pool settings, properties and application name of the ASYNC transport (the application name the connector sends, derived or explicit).
    @Override
    public Option<Object> shareKey(DatabaseConnectorConfig config) {
        return DbShareKey.dbShareKey(getClass(),
                                     SqlConnector.class,
                                     config,
                                     DatabaseConnectorConfig.Transport.ASYNC,
                                     Option.some(PgPoolSettings.applicationName(config)));
    }

    @Override
    public SqlConnector view(SqlConnector pool, Fn0<Promise<Unit>> release) {
        return SqlConnectorView.sqlConnectorView(pool, release);
    }

    /// The warm-up is the transport's real one: pg-async warms `min(min_connections, max_connections)` sessions at provisioning.
    @Override
    public PoolDemand demand(DatabaseConnectorConfig config) {
        return SharedPoolSupport.poolDemand(config,
                                            DatabaseConnectorConfig.Transport.ASYNC,
                                            PgPoolSettings.warmCount(config.poolConfig()));
    }

    @Override
    public Option<Promise<ServerProbe>> probe(SqlConnector pool) {
        return SharedPoolSupport.probe(pool).map(probe -> probe.mapError(PgPoolSettings::typedProbeFailure));
    }

    private static PgPoolSettings.Assembled<SqlConnector> assemble(DatabaseConnectorConfig config) {
        var builder = new NettyConnectibleBuilder();

        configureConnection(builder, config);
        configurePool(builder, config);
        var pool = builder.pool();

        return new PgPoolSettings.Assembled<>(PgAsyncSqlConnector.pgAsyncSqlConnector(config, pool, builder.plain()),
                                              pool);
    }

    private static void configureConnection(NettyConnectibleBuilder builder, DatabaseConnectorConfig config) {
        builder.hostname(config.effectiveHost(DatabaseConnectorConfig.Transport.ASYNC));
        builder.port(config.effectivePort(DatabaseConnectorConfig.Transport.ASYNC));
        builder.database(config.effectiveDatabase(DatabaseConnectorConfig.Transport.ASYNC));
        config.effectiveUsername(DatabaseConnectorConfig.Transport.ASYNC).onPresent(builder::username);
        config.effectivePassword(DatabaseConnectorConfig.Transport.ASYNC).onPresent(builder::password);
        builder.converters(JsonTextConverter.INSTANCE);
        PgPoolSettings.configureSession(builder, config);
    }

    private static void configurePool(NettyConnectibleBuilder builder, DatabaseConnectorConfig config) {
        builder.maxConnections(config.poolConfig().maxConnections());
        builder.ioThreads(config.poolConfig().effectiveIoThreads());
        config.poolConfig().validationQuery().onPresent(builder::validationQuery);
    }
}
