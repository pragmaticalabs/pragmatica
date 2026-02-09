package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.Map;
import java.util.Set;

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
        return getString(key).flatMap(this::parseInteger);
    }

    /// Get a long value from the configuration.
    ///
    /// @param key Dot-separated key path
    /// @return Option containing the value if present and parseable as long
    default Option<Long> getLong(String key) {
        return getString(key).flatMap(this::parseLong);
    }

    /// Get a boolean value from the configuration.
    ///
    /// @param key Dot-separated key path
    /// @return Option containing the value if present and parseable as boolean
    default Option<Boolean> getBoolean(String key) {
        return getString(key).flatMap(this::parseBoolean);
    }

    /// Get a double value from the configuration.
    ///
    /// @param key Dot-separated key path
    /// @return Option containing the value if present and parseable as double
    default Option<Double> getDouble(String key) {
        return getString(key).flatMap(this::parseDouble);
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
        return Result.success(this);
    }

    private Option<Integer> parseInteger(String value) {
        try {
            return Option.some(Integer.parseInt(value));
        } catch (NumberFormatException _) {
            return Option.none();
        }
    }

    private Option<Long> parseLong(String value) {
        try {
            return Option.some(Long.parseLong(value));
        } catch (NumberFormatException _) {
            return Option.none();
        }
    }

    private Option<Boolean> parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return Option.some(true);
        }
        if ("false".equalsIgnoreCase(value)) {
            return Option.some(false);
        }
        return Option.none();
    }

    private Option<Double> parseDouble(String value) {
        try {
            return Option.some(Double.parseDouble(value));
        } catch (NumberFormatException _) {
            return Option.none();
        }
    }
}
