package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;

import java.util.Map;
import java.util.Set;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;

/// Abstraction for configuration sources.
///
/// A ConfigSource provides hierarchical key-value access to configuration data.
/// Keys use dot-notation for nested values (e.g., "database.primary.host").
///
/// Implementations may load from various sources: environment variables,
/// system properties, TOML files, JSON files, or in-memory maps.
public interface ConfigSource {
    /// Get a string value from the configuration.
    ///
    /// @param key Dot-separated key path (e.g., "database.host")
    /// @return Option containing the value if present
    Option<String> getString(String key);

    /// Get an integer value from the configuration.
    ///
    /// @param key Dot-separated key path
    /// @return Option containing the value if present and parseable as integer
    default Option<Integer> getInt(String key) {
        return getString(key).flatMap(ConfigSource::safeParseInteger);
    }

    /// Get a long value from the configuration.
    ///
    /// @param key Dot-separated key path
    /// @return Option containing the value if present and parseable as long
    default Option<Long> getLong(String key) {
        return getString(key).flatMap(ConfigSource::safeParseLong);
    }

    /// Get a boolean value from the configuration.
    ///
    /// @param key Dot-separated key path
    /// @return Option containing the value if present and parseable as boolean
    default Option<Boolean> getBoolean(String key) {
        return getString(key).flatMap(ConfigSource::safeParseBoolean);
    }

    /// Get a double value from the configuration.
    ///
    /// @param key Dot-separated key path
    /// @return Option containing the value if present and parseable as double
    default Option<Double> getDouble(String key) {
        return getString(key).flatMap(ConfigSource::safeParseDouble);
    }

    /// Get all keys available in this source.
    ///
    /// Keys are returned in dot-notation format for nested values.
    ///
    /// @return Set of all available keys
    Set<String> keys();

    /// Get all values as a flat map with dot-notation keys.
    ///
    /// @return Map of all key-value pairs
    Map<String, String> asMap();

    /// Get the priority of this source (higher = takes precedence).
    ///
    /// When multiple sources provide the same key, the source with
    /// higher priority wins.
    ///
    /// @return Priority value (default: 0)
    default int priority() {
        return 0;
    }

    /// Get a human-readable name for this source.
    ///
    /// Used for logging and debugging.
    ///
    /// @return Source name
    String name();

    /// Load/refresh the configuration from the underlying source.
    ///
    /// For file-based sources, this re-reads the file.
    /// For environment/system property sources, this is typically a no-op.
    ///
    /// @return Result indicating success or failure
    default Result<ConfigSource> reload() {
        return success(this);
    }

    private static Option<Integer> safeParseInteger(String value) {
        return Number.parseInt(value)
                     .option();
    }

    private static Option<Long> safeParseLong(String value) {
        return Number.parseLong(value)
                     .option();
    }

    private static Option<Boolean> safeParseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return some(true);
        }
        if ("false".equalsIgnoreCase(value)) {
            return some(false);
        }
        return none();
    }

    private static Option<Double> safeParseDouble(String value) {
        return Number.parseDouble(value)
                     .option();
    }
}
