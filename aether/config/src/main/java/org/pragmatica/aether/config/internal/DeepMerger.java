package org.pragmatica.aether.config.internal;

import org.pragmatica.aether.config.ConfigSource;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.pragmatica.lang.Result.success;

/// Utility for deep-merging configuration maps.
///
/// When merging hierarchical configuration sources, values are merged
/// in priority order (sources should be pre-sorted, highest priority first).
/// Later sources override earlier sources for the same keys.
public sealed interface DeepMerger {
    /// Merge multiple configuration sources into a single flat map.
    ///
    /// Sources should be sorted by priority (highest first).
    /// Values from lower-priority sources are overwritten by higher-priority sources.
    ///
    /// @param sources List of sources sorted by priority (highest first)
    /// @return Merged map of all key-value pairs
    static Map<String, String> mergeSources(List<ConfigSource> sources) {
        var result = new LinkedHashMap<String, String>();
        var reversed = sources.reversed();
        reversed.stream()
                .map(ConfigSource::asMap)
                .forEach(result::putAll);
        return Map.copyOf(result);
    }

    /// Merge two flat configuration maps.
    ///
    /// Values from the higher-priority map override the base map.
    ///
    /// @param base         Base configuration (lower priority)
    /// @param override     Override configuration (higher priority)
    /// @return Merged map
    static Map<String, String> merge(Map<String, String> base, Map<String, String> override) {
        var result = new LinkedHashMap<>(base);
        result.putAll(override);
        return Map.copyOf(result);
    }

    /// Deep merge two hierarchical maps (Object values).
    ///
    /// When both maps contain a nested map for the same key, the nested maps
    /// are recursively merged. Other values are replaced.
    ///
    /// @param base     Base map (lower priority)
    /// @param override Override map (higher priority)
    /// @return Deep-merged map
    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> override) {
        var result = new LinkedHashMap<>(base);
        override.forEach((key, overrideValue) -> result.merge(key, overrideValue, DeepMerger::mergeValues));
        return result;
    }

    /// Convert a flat dot-notation map to a hierarchical nested map.
    ///
    /// Example: {"database.host": "localhost", "database.port": "5432"}
    /// becomes: {"database": {"host": "localhost", "port": "5432"}}
    ///
    /// @param flat Flat map with dot-notation keys
    /// @return Hierarchical nested map
    @SuppressWarnings("unchecked")
    static Map<String, Object> toHierarchical(Map<String, String> flat) {
        var result = new LinkedHashMap<String, Object>();
        flat.forEach((key, value) -> insertHierarchical(result, key.split("\\."), value));
        return result;
    }

    /// Convert a hierarchical nested map to a flat dot-notation map.
    ///
    /// Example: {"database": {"host": "localhost", "port": "5432"}}
    /// becomes: {"database.host": "localhost", "database.port": "5432"}
    ///
    /// @param hierarchical Hierarchical nested map
    /// @return Flat map with dot-notation keys
    static Map<String, String> toFlat(Map<String, Object> hierarchical) {
        var result = new LinkedHashMap<String, String>();
        flattenRecursive("", hierarchical, result);
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static void flattenRecursive(String prefix, Map<String, Object> map, Map<String, String> result) {
        map.forEach((entryKey, value) -> flattenEntry(prefix, entryKey, value, result));
    }

    @SuppressWarnings("unchecked")
    private static void flattenEntry(String prefix, String entryKey, Object value, Map<String, String> result) {
        var key = buildKey(prefix, entryKey);
        if (isNestedMap(value)) {
            flattenRecursive(key, (Map<String, Object>) value, result);
        } else {
            result.put(key, String.valueOf(value));
        }
    }

    private static String buildKey(String prefix, String entryKey) {
        if (Verify.Is.empty(prefix)) {
            return entryKey;
        }
        return prefix + "." + entryKey;
    }

    @SuppressWarnings("unchecked")
    private static Object mergeValues(Object baseValue, Object overrideValue) {
        if (isNestedMap(baseValue) && isNestedMap(overrideValue)) {
            return deepMerge((Map<String, Object>) baseValue, (Map<String, Object>) overrideValue);
        }
        return overrideValue;
    }

    @SuppressWarnings("unchecked")
    private static void insertHierarchical(Map<String, Object> result, String[] keyPath, String value) {
        var parentDepth = keyPath.length - 1;
        var current = navigateToParent(result, keyPath, parentDepth);
        current.put(keyPath[parentDepth], value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> navigateToParent(Map<String, Object> root, String[] keyPath, int depth) {
        var container = new Object[]{root};
        IntStream.range(0, depth)
                 .forEach(i -> container[0] = ((Map<String, Object>) container[0])
        .computeIfAbsent(keyPath[i],
                         _ -> new LinkedHashMap<String, Object>()));
        return (Map<String, Object>) container[0];
    }

    private static boolean isNestedMap(Object value) {
        return value instanceof Map;
    }

    /// Marker record to satisfy sealed interface requirement.
    record unused() implements DeepMerger {
        static Result<unused> unused() {
            return success(new unused());
        }
    }
}
