package org.pragmatica.config;

import java.util.Map;
import java.util.Set;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// Abstraction for configuration sources.
///
/// A ConfigSource provides hierarchical key-value access to configuration data.
/// Keys use dot-notation for nested values (e.g., "database.primary.host").
///
/// Implementations may load from various sources: environment variables,
/// system properties, TOML files, JSON files, or in-memory maps.
public interface ConfigSource {
    Fn1<Cause, String> NOT_A_BOOLEAN = Causes.forOneValue("Not a boolean: %s");

    /// Get a string value from the configuration.
    ///
    /// @param key Dot-separated key path (e.g., "database.host")
    /// @return Option containing the value if present
    Option<String> getString(String key);

    /// Get an integer value from the configuration.
    ///
    /// @param key Dot-separated key path
    /// @return `Success(None)` when the key is absent, `Success(Some)` when present and parseable,
    ///         or a [ConfigError.TypeMismatch] naming the key and the raw value when present but not
    ///         an integer (#1098 — a malformed value must never read as "not configured")
    default Result<Option<Integer>> getInt(String key) {
        return parseOptional(key, "integer", Number::parseInt);
    }

    /// Get a long value from the configuration.
    ///
    /// @param key Dot-separated key path
    /// @return as [#getInt]: absent → `Success(None)`, malformed → [ConfigError.TypeMismatch]
    default Result<Option<Long>> getLong(String key) {
        return parseOptional(key, "long", Number::parseLong);
    }

    /// Get a boolean value from the configuration. Only `true`/`false` (any case) are booleans.
    ///
    /// @param key Dot-separated key path
    /// @return as [#getInt]: absent → `Success(None)`, malformed → [ConfigError.TypeMismatch]
    default Result<Option<Boolean>> getBoolean(String key) {
        return parseOptional(key, "boolean", ConfigSource::parseBoolean);
    }

    /// Get a double value from the configuration.
    ///
    /// @param key Dot-separated key path
    /// @return as [#getInt]: absent → `Success(None)`, malformed → [ConfigError.TypeMismatch]
    default Result<Option<Double>> getDouble(String key) {
        return parseOptional(key, "double", Number::parseDouble);
    }

    /// The one parse discipline every typed reader shares: the raw string is read, and its parse
    /// failure is replaced by a cause that names the key and the value — `Number.parseX`'s own
    /// cause is a wrapped `NumberFormatException` with the key nowhere in it.
    private <T> Result<Option<T>> parseOptional(String key, String expected, Fn1<Result<T>, String> parser) {
        return getString(key).fold(() -> success(none()),
                                   raw -> parser.apply(raw)
                                                .map(Option::some)
                                                .mapError(_ -> ConfigError.typeMismatch(key, expected, raw)));
    }

    /// Strict boolean parse: `Boolean.parseBoolean` reads every non-`true` string as `false`, which
    /// turns `enabled = "yes"` into a silent `false`. Shared with [ProviderBasedConfigService].
    static Result<Boolean> parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return success(true);
        }

        if ("false".equalsIgnoreCase(value)) {
            return success(false);
        }

        return NOT_A_BOOLEAN.apply(value).result();
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
}
