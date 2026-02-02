package org.pragmatica.jbct.slice.model;

import org.pragmatica.lang.Option;

import java.util.List;

/**
 * Aspect configuration extracted from @Aspect annotation on a slice method.
 * <p>
 * Currently supported aspects:
 * <ul>
 *   <li>{@code CACHE} - Fully implemented with key extraction</li>
 *   <li>{@code LOG} - Planned, not yet implemented</li>
 *   <li>{@code METRICS} - Planned, not yet implemented</li>
 *   <li>{@code RETRY} - Planned, not yet implemented</li>
 *   <li>{@code TIMEOUT} - Planned, not yet implemented</li>
 * </ul>
 *
 * @param kinds        List of AspectKind values as strings (e.g., "CACHE", "LOG")
 * @param keyExtractor Key extractor info for CACHE aspect (if present)
 */
public record AspectModel(List<String> kinds, Option<KeyExtractorInfo> keyExtractor) {
    public AspectModel {
        kinds = List.copyOf(kinds);
    }

    /**
     * Create an empty aspect model (no aspects).
     */
    public static AspectModel none() {
        return new AspectModel(List.of(), Option.none());
    }

    /**
     * Check if CACHE aspect is present.
     */
    public boolean hasCache() {
        return kinds.contains("CACHE");
    }

    /**
     * Check if LOG aspect is present.
     * <p>
     * Note: LOG aspect is planned but not yet implemented in code generation.
     */
    public boolean hasLog() {
        return kinds.contains("LOG");
    }

    /**
     * Check if METRICS aspect is present.
     * <p>
     * Note: METRICS aspect is planned but not yet implemented in code generation.
     */
    public boolean hasMetrics() {
        return kinds.contains("METRICS");
    }

    /**
     * Check if RETRY aspect is present.
     * <p>
     * Note: RETRY aspect is planned but not yet implemented in code generation.
     */
    public boolean hasRetry() {
        return kinds.contains("RETRY");
    }

    /**
     * Check if TIMEOUT aspect is present.
     * <p>
     * Note: TIMEOUT aspect is planned but not yet implemented in code generation.
     */
    public boolean hasTimeout() {
        return kinds.contains("TIMEOUT");
    }

    /**
     * Check if any aspects are present.
     */
    public boolean hasAspects() {
        return ! kinds.isEmpty();
    }
}
