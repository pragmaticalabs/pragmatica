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
    Promise<Listening> subscribe(String channel, Consumer<String> onNotification);
}
