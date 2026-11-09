// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jooq.async;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.db.jooq.JooqSharedPoolSupport;
import org.pragmatica.aether.resource.db.jooq.JooqConnectorView;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.aether.resource.db.SharedPoolSupport;
import org.pragmatica.aether.resource.db.DbShareKey;
import org.pragmatica.aether.resource.NodeShareable;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.jooq.JooqConnector;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;
import org.pragmatica.postgres.r2dbc.PgAsyncConnectionFactory;
import org.pragmatica.lang.Promise;


/// A [NodeShareable] factory (#2973): slices on one node whose datasource resolves to the same connection settings share
/// one pool, and each receives its own view of it.
public final class AsyncJooqConnectorFactory implements ResourceFactory<JooqConnector, DatabaseConnectorConfig>, NodeShareable<JooqConnector, DatabaseConnectorConfig> {
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

    /// #2973: the effective endpoint, credentials, pool settings, properties and application name of the ASYNC transport (this connector sends no application name; the configured value is keyed all the same).
    @Override
    public Option<Object> shareKey(DatabaseConnectorConfig config) {
        return DbShareKey.dbShareKey(getClass(), JooqConnector.class, config, DatabaseConnectorConfig.Transport.ASYNC);
    }

    @Override
    public JooqConnector view(JooqConnector pool, Fn0<Promise<Unit>> release) {
        return JooqConnectorView.jooqConnectorView(pool, release);
    }

    /// The warm-up is the transport's real one: this connector builds its pg-async pool without a warm-up, so it opens nothing at provisioning.
    @Override
    public PoolDemand demand(DatabaseConnectorConfig config) {
        return SharedPoolSupport.poolDemand(config, DatabaseConnectorConfig.Transport.ASYNC, 0);
    }

    @Override
    public Option<Promise<ServerProbe>> probe(JooqConnector pool) {
        return JooqSharedPoolSupport.probe(pool);
    }

    private static JooqConnector connector(DatabaseConnectorConfig config) {
        var builder = new NettyConnectibleBuilder();

        builder.hostname(config.effectiveHost(DatabaseConnectorConfig.Transport.ASYNC))
               .port(config.effectivePort(DatabaseConnectorConfig.Transport.ASYNC))
               .database(config.effectiveDatabase(DatabaseConnectorConfig.Transport.ASYNC));
        config.effectiveUsername(DatabaseConnectorConfig.Transport.ASYNC).onPresent(builder::username);
        config.effectivePassword(DatabaseConnectorConfig.Transport.ASYNC).onPresent(builder::password);
        builder.maxConnections(config.poolConfig().maxConnections());
        config.poolConfig().validationQuery().onPresent(builder::validationQuery);
        var connectible = builder.pool();
        var connectionFactory = PgAsyncConnectionFactory.pgAsyncConnectionFactory(connectible);

        return AsyncJooqConnector.asyncJooqConnector(config, connectionFactory);
    }
}
