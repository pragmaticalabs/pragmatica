package org.pragmatica.aether.slice;

/// Runtime-togglable aspect modes for slice method invocations.
/// Allows selecting which cross-cutting concerns are active without restart.
public enum DynamicAspectMode {
    NONE,
    LOG,
    METRICS,
    LOG_AND_METRICS;

    /// Returns {@code true} when logging is active ({@link #LOG} or {@link #LOG_AND_METRICS}).
    public boolean isLoggingEnabled() {
        return this == LOG || this == LOG_AND_METRICS;
    }

    /// Returns {@code true} when metrics collection is active ({@link #METRICS} or {@link #LOG_AND_METRICS}).
    public boolean isMetricsEnabled() {
        return this == METRICS || this == LOG_AND_METRICS;
    }
}
