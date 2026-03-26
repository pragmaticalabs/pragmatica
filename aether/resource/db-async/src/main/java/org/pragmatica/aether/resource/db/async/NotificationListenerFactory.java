package org.pragmatica.aether.resource.db.async;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.slice.PgNotificationConfig;
import org.pragmatica.aether.slice.PgNotificationSubscriber;
import org.pragmatica.config.ConfigService;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.Listening;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/// SPI factory for creating PostgreSQL LISTEN/NOTIFY subscription resources.
///
/// Creates a dedicated (non-pooled) connection for each notification config section,
/// issues LISTEN for each configured channel, and delegates notifications to the slice method.
///
/// The resource type is the marker interface PgNotificationSubscriber; the provisioned
/// object is a NotificationListenerHandle used internally for lifecycle management.
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
        return resolveDatasourceConfig(config.datasource())
        .flatMap(dbConfig -> createDedicatedConnection(dbConfig)
        .flatMap(connection -> subscribeToChannels(connection, config.channels())));
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

    private static Promise<Connection> createDedicatedConnection(DatabaseConnectorConfig config) {
        return Promise.lift(DatabaseConnectorError::databaseFailure,
                            () -> buildPlainConnectible(config))
                      .flatMap(Connectible::getConnection);
    }

    private static Connectible buildPlainConnectible(DatabaseConnectorConfig config) {
        var builder = new NettyConnectibleBuilder();
        builder.hostname(config.effectiveHost());
        builder.port(config.effectivePort());
        builder.database(config.effectiveDatabase());
        config.effectiveUsername()
              .onPresent(builder::username);
        config.effectivePassword()
              .onPresent(builder::password);
        builder.maxConnections(1);
        return builder.plain();
    }

    private static Promise<PgNotificationSubscriber> subscribeToChannels(Connection connection, List<String> channels) {
        var handle = new NotificationListenerHandle(connection);
        var subscriptions = channels.stream()
                                    .map(channel -> connection.subscribe(channel, handle::onNotification)
                                                              .map(handle::addListening))
                                    .toList();
        return Promise.allOf(subscriptions)
                      .map(_ -> handle);
    }
}

/// Internal handle tracking the dedicated connection and its LISTEN subscriptions.
/// Implements PgNotificationSubscriber as the provisioned resource instance.
@SuppressWarnings("JBCT-RET-01")
final class NotificationListenerHandle implements PgNotificationSubscriber {
    private final Connection connection;
    private final List<Listening> listenings = new CopyOnWriteArrayList<>();
    private volatile java.util.function.Consumer<org.pragmatica.aether.slice.PgNotification> callback;

    NotificationListenerHandle(Connection connection) {
        this.connection = connection;
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

    Promise<Unit> close() {
        var unlistens = listenings.stream()
                                  .map(Listening::unlisten)
                                  .toList();
        return Promise.allOf(unlistens)
                      .flatMap(_ -> connection.close());
    }
}
