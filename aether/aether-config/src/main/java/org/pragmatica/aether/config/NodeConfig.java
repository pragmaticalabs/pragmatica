package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;

import java.time.Duration;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Per-node configuration.
///
/// @param heap              JVM heap size (e.g., "256m", "1g")
/// @param gc                Garbage collector (zgc, g1)
/// @param metricsInterval   Metrics collection interval
/// @param reconciliation    Cluster reconciliation interval
/// @param resources         Kubernetes resource limits (optional)
public record NodeConfig(String heap,
                         String gc,
                         Duration metricsInterval,
                         Duration reconciliation,
                         Option<ResourcesConfig> resources) {
    public static final String DEFAULT_GC = "zgc";
    public static final Duration DEFAULT_METRICS_INTERVAL = Duration.ofSeconds(1);
    public static final Duration DEFAULT_RECONCILIATION = Duration.ofSeconds(5);

    /// Factory method following JBCT naming convention.
    public static NodeConfig nodeConfig(String heap, String gc, Duration metricsInterval, Duration reconciliation, Option<ResourcesConfig> resources) {
        return new NodeConfig(heap, gc, metricsInterval, reconciliation, resources);
    }

    /// Create node config with environment defaults.
    public static NodeConfig forEnvironment(Environment env) {
        return nodeConfig(env.defaultHeap(),
                          DEFAULT_GC,
                          DEFAULT_METRICS_INTERVAL,
                          DEFAULT_RECONCILIATION,
                          env == Environment.KUBERNETES
                          ? some(ResourcesConfig.defaultConfig())
                          : none());
    }

    /// Create with custom heap.
    public NodeConfig withHeap(String heap) {
        return nodeConfig(heap, gc, metricsInterval, reconciliation, resources);
    }

    /// Create with custom GC.
    public NodeConfig withGc(String gc) {
        return nodeConfig(heap, gc, metricsInterval, reconciliation, resources);
    }

    /// Create with custom resources.
    public NodeConfig withResources(Option<ResourcesConfig> resources) {
        return nodeConfig(heap, gc, metricsInterval, reconciliation, resources);
    }

    /// Build JAVA_OPTS string for this configuration.
    public String javaOpts() {
        var gcOpt = switch (gc.toLowerCase()) {
            case "zgc" -> "-XX:+UseZGC -XX:+ZGenerational";
            case "g1" -> "-XX:+UseG1GC";
            default -> "-XX:+UseZGC -XX:+ZGenerational";
        };
        return "-Xmx" + heap + " " + gcOpt;
    }
}
