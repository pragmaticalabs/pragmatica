package org.pragmatica.aether.invoke;
public record ObservabilityConfig( int depthThreshold, int targetTracesPerSec) {
    /// Default configuration: depth=1, target=500 traces/sec.
    public static final ObservabilityConfig DEFAULT = new ObservabilityConfig(1, 500);

    /// Factory following JBCT naming.
    public static ObservabilityConfig observabilityConfig(int depthThreshold, int targetTracesPerSec) {
        return new ObservabilityConfig(depthThreshold, targetTracesPerSec);
    }
}
