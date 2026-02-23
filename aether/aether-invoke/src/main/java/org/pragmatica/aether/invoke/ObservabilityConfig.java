package org.pragmatica.aether.invoke;
/// Configuration for unified observability: depth threshold and adaptive sampling target.
///
/// @param depthThreshold     Controls SLF4J logging verbosity per method (default 1)
/// @param targetTracesPerSec Adaptive sampling target per node (default 500)
public record ObservabilityConfig(int depthThreshold, int targetTracesPerSec) {
    /// Default configuration: depth=1, target=500 traces/sec.
    public static final ObservabilityConfig DEFAULT = new ObservabilityConfig(1, 500);

    /// Factory following JBCT naming.
    public static ObservabilityConfig observabilityConfig(int depthThreshold, int targetTracesPerSec) {
        return new ObservabilityConfig(depthThreshold, targetTracesPerSec);
    }
}
