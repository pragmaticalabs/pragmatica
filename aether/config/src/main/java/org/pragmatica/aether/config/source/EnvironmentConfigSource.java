package org.pragmatica.aether.config.source;

import org.pragmatica.aether.config.ConfigSource;
import org.pragmatica.lang.Option;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/// Configuration source backed by environment variables.
///
/// Environment variables are filtered by prefix and converted from
/// SCREAMING_SNAKE_CASE to dot.notation.
///
/// Example with prefix "AETHER_":
///
///   - AETHER_DATABASE_HOST -> database.host
///   - AETHER_SERVER_PORT -> server.port
///
public final class EnvironmentConfigSource implements ConfigSource {
    private static final int DEFAULT_PRIORITY = 100;

    private final String prefix;
    private final int priority;
    private final Map<String, String> values;

    private EnvironmentConfigSource(String prefix, int priority, Map<String, String> values) {
        this.prefix = prefix;
        this.priority = priority;
        this.values = Map.copyOf(values);
    }

    /// Create an EnvironmentConfigSource with the specified prefix.
    ///
    /// @param prefix Prefix to filter environment variables (e.g., "AETHER_")
    /// @return New EnvironmentConfigSource
    public static EnvironmentConfigSource environmentConfigSource(String prefix) {
        return environmentConfigSource(prefix, DEFAULT_PRIORITY);
    }

    /// Create an EnvironmentConfigSource with specified prefix and priority.
    ///
    /// @param prefix   Prefix to filter environment variables
    /// @param priority Source priority
    /// @return New EnvironmentConfigSource
    public static EnvironmentConfigSource environmentConfigSource(String prefix, int priority) {
        var values = loadFromEnvironment(prefix);
        return new EnvironmentConfigSource(prefix, priority, values);
    }

    @Override
    public Option<String> getString(String key) {
        return Option.option(values.get(key));
    }

    @Override
    public Set<String> keys() {
        return values.keySet();
    }

    @Override
    public Map<String, String> asMap() {
        return new LinkedHashMap<>(values);
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public String name() {
        return "EnvironmentConfigSource[prefix=" + prefix + "]";
    }

    private static Map<String, String> loadFromEnvironment(String prefix) {
        var result = new LinkedHashMap<String, String>();
        var env = System.getenv();

        for (var entry : env.entrySet()) {
            var key = entry.getKey();
            if (key.startsWith(prefix)) {
                var normalizedKey = normalizeKey(key.substring(prefix.length()));
                result.put(normalizedKey, entry.getValue());
            }
        }

        return result;
    }

    /// Convert SCREAMING_SNAKE_CASE to dot.notation.lowercase.
    private static String normalizeKey(String key) {
        return key.toLowerCase().replace('_', '.');
    }
}
