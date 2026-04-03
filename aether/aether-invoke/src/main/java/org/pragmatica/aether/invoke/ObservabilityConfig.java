package org.pragmatica.aether.invoke;

public record ObservabilityConfig(int depthThreshold, int targetTracesPerSec) {
    public static final ObservabilityConfig DEFAULT = new ObservabilityConfig(1, 500);

    public static ObservabilityConfig observabilityConfig(int depthThreshold, int targetTracesPerSec) {
        return new ObservabilityConfig(depthThreshold, targetTracesPerSec);
    }
}
