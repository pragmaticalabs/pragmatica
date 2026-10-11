// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Promise;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;


public final class AsyncSqlConnectorFactory implements ResourceFactory<SqlConnector, DatabaseConnectorConfig> {
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
        return Promise.lift(DatabaseConnectorError::databaseFailure, () -> connector(config));
    }

    private static SqlConnector connector(DatabaseConnectorConfig config) {
        var builder = new NettyConnectibleBuilder();

        configureConnection(builder, config);
        configurePool(builder, config);

        return PgAsyncSqlConnector.pgAsyncSqlConnector(config, builder.pool());
    }

    private static void configureConnection(NettyConnectibleBuilder builder, DatabaseConnectorConfig config) {
        builder.hostname(config.effectiveHost(DatabaseConnectorConfig.Transport.ASYNC));
        builder.port(config.effectivePort(DatabaseConnectorConfig.Transport.ASYNC));
        builder.database(config.effectiveDatabase(DatabaseConnectorConfig.Transport.ASYNC));
        config.effectiveUsername(DatabaseConnectorConfig.Transport.ASYNC).onPresent(builder::username);
        config.effectivePassword(DatabaseConnectorConfig.Transport.ASYNC).onPresent(builder::password);
    }

    private static void configurePool(NettyConnectibleBuilder builder, DatabaseConnectorConfig config) {
        builder.maxConnections(config.poolConfig().maxConnections());
        builder.ioThreads(config.poolConfig().effectiveIoThreads());
        config.poolConfig().validationQuery().onPresent(builder::validationQuery);
    }
}
