// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.slice.PgNotificationConfig;
import org.pragmatica.aether.slice.PgNotificationSubscriber;
import org.pragmatica.config.ConfigService;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.Listening;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;

import static org.pragmatica.postgres.net.ReleaseChain.releaseAll;
import static org.pragmatica.postgres.net.ReleaseChain.releasingOnFailure;


public final class NotificationListenerFactory implements ResourceFactory<PgNotificationSubscriber, PgNotificationConfig> {
    private static final Cause CONFIG_SERVICE_MISSING = Causes.cause("ConfigService not available for datasource resolution");

    @Override
    public Class<PgNotificationSubscriber> resourceType() {
        return PgNotificationSubscriber.class;
    }

    @Override
    public Class<PgNotificationConfig> configType() {
        return PgNotificationConfig.class;
    }

    @Override
    public Promise<PgNotificationSubscriber> provision(PgNotificationConfig config) {
        return resolveDatasourceConfig(config.datasource()).flatMap(dbConfig -> provisionWith(dbConfig,
                                                                                              config.channels()));
    }

    @Override
    public Promise<Unit> close(PgNotificationSubscriber resource) {
        if (resource instanceof NotificationListenerHandle handle) {
            return handle.close();
        }

        return Promise.unitPromise();
    }

    private static Promise<DatabaseConnectorConfig> resolveDatasourceConfig(String datasource) {
        return ConfigService.instance()
                            .toResult(CONFIG_SERVICE_MISSING)
                            .flatMap(svc -> svc.config(datasource, DatabaseConnectorConfig.class))
                            .async();
    }

    static Promise<PgNotificationSubscriber> provisionWith(DatabaseConnectorConfig dbConfig, List<String> channels) {
        return Promise.lift(DatabaseConnectorError::databaseFailure, () -> buildPlainConnectible(dbConfig)).flatMap(connectible -> listenOn(connectible,
                                                                                                                                            channels));
    }

    /// The Connectible holds the lease on its builder's event-loop group and the connection holds the channel (#3635).
    /// A failed connect closes the Connectible; a failed subscribe closes the connection, then the Connectible
    /// (ReleaseChain invariant); a successful provision hands both to the handle.
    static Promise<PgNotificationSubscriber> listenOn(Connectible connectible, List<String> channels) {
        return releasingOnFailure(connectible.getConnection()
                                             .flatMap(connection -> subscribeOn(connection, connectible, channels)),
                                  connectible::close);
    }

    private static Promise<PgNotificationSubscriber> subscribeOn(Connection connection,
                                                                 Connectible connectible,
                                                                 List<String> channels) {
        return releasingOnFailure(subscribeToChannels(connection, connectible, channels), connection::close);
    }

    private static Connectible buildPlainConnectible(DatabaseConnectorConfig config) {
        var builder = new NettyConnectibleBuilder();

        builder.hostname(config.effectiveHost(DatabaseConnectorConfig.Transport.ASYNC));
        builder.port(config.effectivePort(DatabaseConnectorConfig.Transport.ASYNC));
        builder.database(config.effectiveDatabase(DatabaseConnectorConfig.Transport.ASYNC));
        config.effectiveUsername(DatabaseConnectorConfig.Transport.ASYNC).onPresent(builder::username);
        config.effectivePassword(DatabaseConnectorConfig.Transport.ASYNC).onPresent(builder::password);
        builder.maxConnections(1);

        return builder.plain();
    }

    private static Promise<PgNotificationSubscriber> subscribeToChannels(Connection connection,
                                                                         Connectible connectible,
                                                                         List<String> channels) {
        var handle = new NotificationListenerHandle(connection, connectible);
        var subscriptions = channels.stream()
                                    .map(channel -> connection.subscribe(channel, handle::onNotification)
                                                              .map(handle::addListening))
                                    .toList();

        return Promise.allOf(subscriptions)
                      .flatMap(NotificationListenerFactory::allSubscribed)
                      .map(_ -> handle);
    }

    /// A refused LISTEN fails the provision: a subscriber missing one of its channels is not what was configured.
    private static Promise<Unit> allSubscribed(List<Result<Listening>> subscriptions) {
        return Result.allOf(subscriptions)
                     .mapToUnit()
                     .async();
    }
}

@SuppressWarnings("JBCT-RET-01")
final class NotificationListenerHandle implements PgNotificationSubscriber {
    private final Connection connection;
    private final Connectible connectible;
    private final List<Listening> listenings = new CopyOnWriteArrayList<>();
    private volatile java.util.function.Consumer<org.pragmatica.aether.slice.PgNotification> callback;

    NotificationListenerHandle(Connection connection, Connectible connectible) {
        this.connection = connection;
        this.connectible = connectible;
    }

    void setCallback(java.util.function.Consumer<org.pragmatica.aether.slice.PgNotification> callback) {
        this.callback = callback;
    }

    void onNotification(String channel, String payload, int pid) {
        var cb = this.callback;

        if (cb != null) {
            cb.accept(org.pragmatica.aether.slice.PgNotification.pgNotification(channel, payload, pid));
        }
    }

    Listening addListening(Listening listening) {
        listenings.add(listening);

        return listening;
    }

    /// Unlisten, close the connection, close the Connectible (its group lease): each runs whatever the earlier ones did,
    /// and the first failure is the result (ReleaseChain invariant, #3635).
    Promise<Unit> close() {
        return releaseAll(this::unlistenAll, connection::close, connectible::close);
    }

    private Promise<Unit> unlistenAll() {
        return Promise.allOf(listenings.stream().map(Listening::unlisten).toList()).flatMap(NotificationListenerHandle::allUnlistened);
    }

    private static Promise<Unit> allUnlistened(List<Result<Unit>> unlistens) {
        return Result.allOf(unlistens)
                     .mapToUnit()
                     .async();
    }
}
