package org.pragmatica.aether.slice;

import java.util.List;


/// Configuration for PostgreSQL notification subscription.
///
/// @param datasource the datasource config section to use for the dedicated connection
/// @param channels   the list of PostgreSQL channels to LISTEN on
public record PgNotificationConfig(String datasource, List<String> channels) {
    public static PgNotificationConfig pgNotificationConfig(String datasource, List<String> channels) {
        return new PgNotificationConfig(datasource, List.copyOf(channels));
    }
}
