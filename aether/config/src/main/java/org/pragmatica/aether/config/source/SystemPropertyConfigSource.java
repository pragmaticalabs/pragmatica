package org.pragmatica.aether.config.source;

import org.pragmatica.aether.config.ConfigSource;
import org.pragmatica.lang.Option;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/// Configuration source backed by Java system properties.
///
/// System properties are filtered by prefix and the prefix is stripped.
///
/// Example with prefix "aether.":
///
///   - -Daether.database.host=localhost -> database.host
///   - -Daether.server.port=8080 -> server.port
///
public final class SystemPropertyConfigSource implements ConfigSource {
    private static final int DEFAULT_PRIORITY = 200;

    private final String prefix;
    private final int priority;
    private final Map<String, String> values;

    private SystemPropertyConfigSource(String prefix, int priority, Map<String, String> values) {
        this.prefix = prefix;
        this.priority = priority;
        this.values = Map.copyOf(values);
    }

    /// Create a SystemPropertyConfigSource with the specified prefix.
    ///
    /// @param prefix Prefix to filter system properties (e.g., "aether.")
    /// @return New SystemPropertyConfigSource
    public static SystemPropertyConfigSource systemPropertyConfigSource(String prefix) {
        return systemPropertyConfigSource(prefix, DEFAULT_PRIORITY);
    }

    /// Create a SystemPropertyConfigSource with specified prefix and priority.
    ///
    /// @param prefix   Prefix to filter system properties
    /// @param priority Source priority
    /// @return New SystemPropertyConfigSource
    public static SystemPropertyConfigSource systemPropertyConfigSource(String prefix, int priority) {
        var values = loadFromSystemProperties(prefix);
        return new SystemPropertyConfigSource(prefix, priority, values);
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
        return "SystemPropertyConfigSource[prefix=" + prefix + "]";
    }

    private static Map<String, String> loadFromSystemProperties(String prefix) {
        var result = new LinkedHashMap<String, String>();
        var properties = System.getProperties();

        for (var key : properties.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                var normalizedKey = key.substring(prefix.length());
                result.put(normalizedKey, properties.getProperty(key));
            }
        }

        return result;
    }
}
