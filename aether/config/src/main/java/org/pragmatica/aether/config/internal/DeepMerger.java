package org.pragmatica.aether.config.internal;

import org.pragmatica.aether.config.ConfigSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility for deep-merging configuration maps.
 * <p>
 * When merging hierarchical configuration sources, values are merged
 * in priority order (sources should be pre-sorted, highest priority first).
 * Later sources override earlier sources for the same keys.
 */
public sealed interface DeepMerger {

    /**
     * Merge multiple configuration sources into a single flat map.
     * <p>
     * Sources should be sorted by priority (highest first).
     * Values from lower-priority sources are overwritten by higher-priority sources.
     *
     * @param sources List of sources sorted by priority (highest first)
     * @return Merged map of all key-value pairs
     */
    static Map<String, String> mergeSources(List<ConfigSource> sources) {
        var result = new LinkedHashMap<String, String>();

        // Process in reverse order so highest priority wins
        for (int i = sources.size() - 1; i >= 0; i--) {
            var source = sources.get(i);
            result.putAll(source.asMap());
        }

        return Map.copyOf(result);
    }

    /**
     * Merge two flat configuration maps.
     * <p>
     * Values from the higher-priority map override the base map.
     *
     * @param base         Base configuration (lower priority)
     * @param override     Override configuration (higher priority)
     * @return Merged map
     */
    static Map<String, String> merge(Map<String, String> base, Map<String, String> override) {
        var result = new LinkedHashMap<>(base);
        result.putAll(override);
        return Map.copyOf(result);
    }

    /**
     * Deep merge two hierarchical maps (Object values).
     * <p>
     * When both maps contain a nested map for the same key, the nested maps
     * are recursively merged. Other values are replaced.
     *
     * @param base     Base map (lower priority)
     * @param override Override map (higher priority)
     * @return Deep-merged map
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> override) {
        var result = new LinkedHashMap<>(base);

        for (var entry : override.entrySet()) {
            var key = entry.getKey();
            var overrideValue = entry.getValue();
            var baseValue = result.get(key);

            if (isNestedMap(baseValue) && isNestedMap(overrideValue)) {
                // Recursively merge nested maps
                var mergedNested = deepMerge(
                    (Map<String, Object>) baseValue,
                    (Map<String, Object>) overrideValue
                );
                result.put(key, mergedNested);
            } else {
                // Override value replaces base
                result.put(key, overrideValue);
            }
        }

        return result;
    }

    /**
     * Convert a flat dot-notation map to a hierarchical nested map.
     * <p>
     * Example: {"database.host": "localhost", "database.port": "5432"}
     * becomes: {"database": {"host": "localhost", "port": "5432"}}
     *
     * @param flat Flat map with dot-notation keys
     * @return Hierarchical nested map
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> toHierarchical(Map<String, String> flat) {
        var result = new LinkedHashMap<String, Object>();

        for (var entry : flat.entrySet()) {
            var keyPath = entry.getKey().split("\\.");
            Map<String, Object> current = result;

            for (int i = 0; i < keyPath.length - 1; i++) {
                var segment = keyPath[i];
                var nested = current.computeIfAbsent(segment, _ -> new LinkedHashMap<String, Object>());
                current = (Map<String, Object>) nested;
            }

            current.put(keyPath[keyPath.length - 1], entry.getValue());
        }

        return result;
    }

    /**
     * Convert a hierarchical nested map to a flat dot-notation map.
     * <p>
     * Example: {"database": {"host": "localhost", "port": "5432"}}
     * becomes: {"database.host": "localhost", "database.port": "5432"}
     *
     * @param hierarchical Hierarchical nested map
     * @return Flat map with dot-notation keys
     */
    static Map<String, String> toFlat(Map<String, Object> hierarchical) {
        var result = new LinkedHashMap<String, String>();
        flattenRecursive("", hierarchical, result);
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static void flattenRecursive(String prefix, Map<String, Object> map, Map<String, String> result) {
        for (var entry : map.entrySet()) {
            var key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            var value = entry.getValue();

            if (isNestedMap(value)) {
                flattenRecursive(key, (Map<String, Object>) value, result);
            } else {
                result.put(key, String.valueOf(value));
            }
        }
    }

    private static boolean isNestedMap(Object value) {
        return value instanceof Map;
    }

    /**
     * Marker record to satisfy sealed interface requirement.
     */
    record unused() implements DeepMerger {}
}
