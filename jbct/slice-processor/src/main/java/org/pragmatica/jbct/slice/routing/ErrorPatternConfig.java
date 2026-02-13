package org.pragmatica.jbct.slice.routing;

import org.pragmatica.lang.Option;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Error pattern configuration for HTTP status code mapping.
///
/// Provides two mechanisms for mapping error types to HTTP status codes:
///
///   - `statusPatterns` - Glob patterns matched against type names (e.g., "*NotFound*" -> 404)
///   - `explicitMappings` - Direct type name to status code mappings
///
///
/// @param defaultStatus    default HTTP status for unmatched errors
/// @param statusPatterns   map of HTTP status code to list of glob patterns
/// @param explicitMappings map of exact type name to HTTP status code
public record ErrorPatternConfig(int defaultStatus,
                                 Map<Integer, List<String>> statusPatterns,
                                 Map<String, Integer> explicitMappings) {
    public ErrorPatternConfig {
        statusPatterns = Map.copyOf(statusPatterns);
        explicitMappings = Map.copyOf(explicitMappings);
    }

    /// Empty configuration with 500 as default status.
    public static final ErrorPatternConfig EMPTY = errorPatternConfig();

    /// Factory method for empty configuration.
    public static ErrorPatternConfig errorPatternConfig() {
        return new ErrorPatternConfig(500, Map.of(), Map.of());
    }

    /// Factory method with all parameters.
    public static ErrorPatternConfig errorPatternConfig(int defaultStatus,
                                                        Map<Integer, List<String>> statusPatterns,
                                                        Map<String, Integer> explicitMappings) {
        return new ErrorPatternConfig(defaultStatus, statusPatterns, explicitMappings);
    }

    /// Merge this configuration with another, with other taking precedence.
    ///
    /// Merging behavior:
    ///
    ///   - defaultStatus: other's value if different from 500
    ///   - statusPatterns: combined, with other's patterns added to this's
    ///   - explicitMappings: combined, with other's mappings overriding this's
    ///
    ///
    /// @param other the configuration to merge with (takes precedence)
    /// @return merged configuration
    public ErrorPatternConfig merge(Option<ErrorPatternConfig> other) {
        return other.map(this::mergeWith)
                    .or(this);
    }

    private ErrorPatternConfig mergeWith(ErrorPatternConfig other) {
        var mergedDefault = other.defaultStatus != 500
                            ? other.defaultStatus
                            : this.defaultStatus;
        var mergedPatterns = mergePatterns(this.statusPatterns, other.statusPatterns);
        var mergedExplicit = mergeMappings(this.explicitMappings, other.explicitMappings);
        return errorPatternConfig(mergedDefault, mergedPatterns, mergedExplicit);
    }

    private static Map<Integer, List<String>> mergePatterns(Map<Integer, List<String>> base,
                                                            Map<Integer, List<String>> overlay) {
        var merged = new HashMap<>(base);
        overlay.forEach((status, patterns) -> merged.merge(status,
                                                           patterns,
                                                           (existing, added) -> {
                                                               var combined = new java.util.ArrayList<>(existing);
                                                               combined.addAll(added);
                                                               return List.copyOf(combined);
                                                           }));
        return Map.copyOf(merged);
    }

    private static Map<String, Integer> mergeMappings(Map<String, Integer> base,
                                                      Map<String, Integer> overlay) {
        var merged = new HashMap<>(base);
        merged.putAll(overlay);
        return Map.copyOf(merged);
    }

    /// Resolve HTTP status code for an error type name.
    ///
    /// Resolution order:
    /// <ol>
    ///   - Explicit mapping (exact match)
    ///   - Pattern matching (glob patterns)
    ///   - Default status
    /// </ol>
    ///
    /// @param typeName the error type name to resolve
    /// @return resolved HTTP status code
    public int resolveStatus(String typeName) {
        return Option.option(explicitMappings.get(typeName))
                     .or(() -> resolveFromPatterns(typeName));
    }

    private int resolveFromPatterns(String typeName) {
        for (var entry : statusPatterns.entrySet()) {
            for (var pattern : entry.getValue()) {
                if (ErrorTypeMatcher.matches(typeName, pattern)) {
                    return entry.getKey();
                }
            }
        }
        return defaultStatus;
    }
}
