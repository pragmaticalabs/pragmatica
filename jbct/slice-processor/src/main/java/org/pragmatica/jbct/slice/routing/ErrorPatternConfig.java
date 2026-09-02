// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.routing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;


/// Error pattern configuration for HTTP status code mapping.
///
/// Provides two mechanisms for mapping error types to HTTP status codes:
///
///   - `statusPatterns` - Glob patterns or exact type references matched against Cause types
///     (e.g., "*NotFound*" -> 404, or "SeatError.SeatNotFound" -> 404)
///   - `explicitMappings` - Direct simple-name to status code mappings (`[errors.explicit]`)
///
///
/// `strict` (from `[errors] strict = true`) escalates the compile-time totality check: when set,
/// a Cause record with no HTTP status mapping fails the build instead of warning (#385). It can
/// also be enabled per-compilation via the `-Ajbct.routes.errors.strict=true` processor option.
///
/// @param defaultStatus    default HTTP status for unmatched errors
/// @param statusPatterns   map of HTTP status code to list of glob patterns / exact type references
/// @param explicitMappings map of exact simple name to HTTP status code
/// @param strict           whether an unmapped Cause record fails the build (vs. warns)
public record ErrorPatternConfig(int defaultStatus,
                                 Map<Integer, List<String>> statusPatterns,
                                 Map<String, Integer> explicitMappings,
                                 boolean strict) {
    public ErrorPatternConfig {
        statusPatterns = Map.copyOf(statusPatterns);
        explicitMappings = Map.copyOf(explicitMappings);
    }

    /// Empty configuration with 500 as default status.
    public static final ErrorPatternConfig EMPTY = errorPatternConfig();

    /// Factory method for empty configuration.
    public static ErrorPatternConfig errorPatternConfig() {
        return new ErrorPatternConfig(500, Map.of(), Map.of(), false);
    }

    /// Factory method without the strict flag (defaults to non-strict / warn-only).
    public static ErrorPatternConfig errorPatternConfig(int defaultStatus,
                                                        Map<Integer, List<String>> statusPatterns,
                                                        Map<String, Integer> explicitMappings) {
        return new ErrorPatternConfig(defaultStatus, statusPatterns, explicitMappings, false);
    }

    /// Factory method with all parameters.
    public static ErrorPatternConfig errorPatternConfig(int defaultStatus,
                                                        Map<Integer, List<String>> statusPatterns,
                                                        Map<String, Integer> explicitMappings,
                                                        boolean strict) {
        return new ErrorPatternConfig(defaultStatus, statusPatterns, explicitMappings, strict);
    }

    /// Merge this configuration with another, with other taking precedence.
    ///
    /// Merging behavior:
    ///
    ///   - defaultStatus: other's value if different from 500
    ///   - statusPatterns: combined, with other's patterns added to this's
    ///   - explicitMappings: combined, with other's mappings overriding this's
    ///   - strict: enabled if either configuration enables it
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

        return errorPatternConfig(mergedDefault, mergedPatterns, mergedExplicit, this.strict || other.strict);
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

    private static Map<String, Integer> mergeMappings(Map<String, Integer> base, Map<String, Integer> overlay) {
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
        return Option.option(explicitMappings.get(typeName)).or(() -> resolveFromPatterns(typeName));
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
