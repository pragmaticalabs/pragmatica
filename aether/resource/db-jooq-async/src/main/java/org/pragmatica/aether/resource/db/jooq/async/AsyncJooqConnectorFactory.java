// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.db.jooq.async;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.jooq.JooqConnector;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;
import org.pragmatica.postgres.r2dbc.PgAsyncConnectionFactory;
import org.pragmatica.lang.Promise;


public final class AsyncJooqConnectorFactory implements ResourceFactory<JooqConnector, DatabaseConnectorConfig> {
    @Override public Class<JooqConnector> resourceType() {
        return JooqConnector.class;
    }

    @Override public Class<DatabaseConnectorConfig> configType() {
        return DatabaseConnectorConfig.class;
    }

    @Override public int priority() {
        return 20;
    }

    @Override public boolean supports(DatabaseConnectorConfig config) {
        return config.asyncUrl().isPresent();
    }

    @Override public Promise<JooqConnector> provision(DatabaseConnectorConfig config) {
        return Promise.lift(DatabaseConnectorError::databaseFailure, () -> connector(config));
    }

    private static JooqConnector connector(DatabaseConnectorConfig config) {
        var builder = new NettyConnectibleBuilder();
        builder.hostname(config.effectiveHost()).port(config.effectivePort())
                        .database(config.effectiveDatabase());
        config.effectiveUsername().onPresent(builder::username);
        config.effectivePassword().onPresent(builder::password);
        builder.maxConnections(config.poolConfig().maxConnections());
        config.poolConfig().validationQuery()
                         .onPresent(builder::validationQuery);
        var connectible = builder.pool();
        var connectionFactory = PgAsyncConnectionFactory.pgAsyncConnectionFactory(connectible);
        return AsyncJooqConnector.asyncJooqConnector(config, connectionFactory);
    }
}
