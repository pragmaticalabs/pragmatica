package org.pragmatica.aether.config.source;

import org.pragmatica.aether.config.ConfigSource;
import org.pragmatica.lang.Option;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * In-memory configuration source backed by a Map.
 * <p>
 * Useful for default values, testing, or programmatic configuration.
 */
public record MapConfigSource(String name, Map<String, String> values, int priority) implements ConfigSource {

    /**
     * Create a MapConfigSource from a map.
     *
     * @param name     Source name for identification
     * @param values   Map of key-value pairs
     * @param priority Source priority
     * @return New MapConfigSource
     */
    public static MapConfigSource mapConfigSource(String name, Map<String, String> values, int priority) {
        return new MapConfigSource(name, Map.copyOf(values), priority);
    }

    /**
     * Create a MapConfigSource with default priority (0).
     *
     * @param name   Source name for identification
     * @param values Map of key-value pairs
     * @return New MapConfigSource
     */
    public static MapConfigSource mapConfigSource(String name, Map<String, String> values) {
        return mapConfigSource(name, values, 0);
    }

    /**
     * Create an empty MapConfigSource.
     *
     * @param name Source name for identification
     * @return New empty MapConfigSource
     */
    public static MapConfigSource emptyMapConfigSource(String name) {
        return mapConfigSource(name, Map.of(), 0);
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
}
