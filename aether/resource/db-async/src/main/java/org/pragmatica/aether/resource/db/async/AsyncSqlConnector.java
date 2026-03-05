package org.pragmatica.aether.resource.db.async;

import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Promise;
import org.pragmatica.postgres.net.Listening;

import java.util.function.Consumer;

/// Extended SqlConnector with LISTEN/NOTIFY support.
///
/// Users who provision SqlConnector and get an async instance can check
/// for this interface to access PostgreSQL notification capabilities.
public interface AsyncSqlConnector extends SqlConnector {
    /// Subscribes to a PostgreSQL NOTIFY channel.
    ///
    /// @param channel        Channel name to listen on
    /// @param onNotification Callback invoked when notification arrives
    /// @return Promise with Listening handle to unlisten later
    Promise<Listening> subscribe(String channel, Consumer<String> onNotification);
}
