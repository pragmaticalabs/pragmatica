package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.slice.PgNotificationConfig;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Result;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;

/// Parses `[pg-notifications.xxx]` sections from TOML configuration into PgNotificationConfig instances.
///
/// Expected format:
/// ```toml
/// [pg-notifications.order-events]
/// datasource = "database.primary"
/// channels = ["orders_changed", "orders_deleted", "inventory_updated"]
///
/// [pg-notifications.user-events]
/// datasource = "database.primary"
/// channels = ["users_created", "users_updated"]
/// ```
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-ZONE-03"})
public interface PgNotificationConfigParser {
    String PREFIX = "pg-notifications.";

    /// Parse all pg-notification configurations from a TOML configuration string.
    /// Returns a map of config name to PgNotificationConfig.
    static Result<Map<String, PgNotificationConfig>> parse(String toml) {
        if ( toml == null || toml.isBlank()) {
        return success(Map.of());}
        return TomlParser.parse(toml).mapError(err -> cause("PG notification config parse error: " + err.message()))
                               .map(doc -> {
                                        var result = new LinkedHashMap<String, PgNotificationConfig>();
                                        for ( var sectionName : doc.sectionNames()) {
        if ( isPgNotificationSection(sectionName)) {
                                            var name = sectionName.substring(PREFIX.length());
                                            if ( !name.contains(".")) {
                                                var datasource = doc.getString(sectionName, "datasource")
        .or("database");
                                                var channels = doc.getStringList(sectionName, "channels").or(List.of());
                                                result.put(name,
                                                           PgNotificationConfig.pgNotificationConfig(datasource,
                                                                                                     channels));
                                            }
                                        }}
                                        return Map.copyOf(result);
                                    });
    }

    private static boolean isPgNotificationSection(String sectionName) {
        return sectionName.startsWith(PREFIX) && sectionName.length() > PREFIX.length();
    }
}
