package org.pragmatica.jbct.slice.model;

import org.pragmatica.lang.Option;

import java.util.List;

/// Aspect configuration extracted from @Aspect annotation on a slice method.
///
/// Currently supported aspects:
///
///   - `CACHE` - Fully implemented with key extraction
///   - `LOG` - Planned, not yet implemented
///   - `METRICS` - Planned, not yet implemented
///   - `RETRY` - Planned, not yet implemented
///   - `TIMEOUT` - Planned, not yet implemented
///
///
/// @param kinds        List of AspectKind values as strings (e.g., "CACHE", "LOG")
/// @param keyExtractor Key extractor info for CACHE aspect (if present)
public record AspectModel(List<String> kinds, Option<KeyExtractorInfo> keyExtractor) {
    public AspectModel {
        kinds = List.copyOf(kinds);
    }

    /// Create an empty aspect model (no aspects).
    public static AspectModel none() {
        return new AspectModel(List.of(), Option.none());
    }

    /// Check if CACHE aspect is present.
    public boolean hasCache() {
        return kinds.contains("CACHE");
    }

    /// Check if LOG aspect is present.
    ///
    /// Note: LOG aspect is planned but not yet implemented in code generation.
    public boolean hasLog() {
        return kinds.contains("LOG");
    }

    /// Check if METRICS aspect is present.
    ///
    /// Note: METRICS aspect is planned but not yet implemented in code generation.
    public boolean hasMetrics() {
        return kinds.contains("METRICS");
    }

    /// Check if RETRY aspect is present.
    ///
    /// Note: RETRY aspect is planned but not yet implemented in code generation.
    public boolean hasRetry() {
        return kinds.contains("RETRY");
    }

    /// Check if TIMEOUT aspect is present.
    ///
    /// Note: TIMEOUT aspect is planned but not yet implemented in code generation.
    public boolean hasTimeout() {
        return kinds.contains("TIMEOUT");
    }

    /// Check if any aspects are present.
    public boolean hasAspects() {
        return ! kinds.isEmpty();
    }
}
