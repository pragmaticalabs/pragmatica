package org.pragmatica.aether.config.source;

import org.pragmatica.aether.config.ConfigSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// In-memory configuration source backed by a Map.
///
/// Useful for default values, testing, or programmatic configuration.
public record MapConfigSource(String name, Map<String, String> values, int priority) implements ConfigSource {
    /// Create a MapConfigSource from a map.
    ///
    /// @param name     Source name for identification
    /// @param values   Map of key-value pairs
    /// @param priority Source priority
    /// @return Result containing new MapConfigSource
    public static Result<MapConfigSource> mapConfigSource(String name, Map<String, String> values, int priority) {
        return success(new MapConfigSource(name, Map.copyOf(values), priority));
    }

    /// Create a MapConfigSource with default priority (0).
    ///
    /// @param name   Source name for identification
    /// @param values Map of key-value pairs
    /// @return Result containing new MapConfigSource
    public static Result<MapConfigSource> mapConfigSource(String name, Map<String, String> values) {
        return mapConfigSource(name, values, 0);
    }

    /// Create an empty MapConfigSource.
    ///
    /// @param name Source name for identification
    /// @return Result containing new empty MapConfigSource
    public static Result<MapConfigSource> mapConfigSource(String name) {
        return mapConfigSource(name, Map.of(), 0);
    }

    @Override
    public Option<String> getString(String key) {
        return option(values.get(key));
    }

    @Override
    public Set<String> keys() {
        return values.keySet();
    }

    @Override
    public Map<String, String> asMap() {
        return new LinkedHashMap<>(values);
    }
}
