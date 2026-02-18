package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// Deployment environment with environment-specific defaults.
///
///
/// Each environment has sensible defaults based on its typical use case:
///
///   - LOCAL - Single machine development (3 nodes, minimal resources)
///   - DOCKER - Production-like Docker Compose (5 nodes, moderate resources)
///   - KUBERNETES - Cloud-native deployment (5 nodes, TLS enabled)
///
public enum Environment {
    LOCAL("local", 3, "256m", false),
    DOCKER("docker", 5, "512m", false),
    KUBERNETES("kubernetes", 5, "1g", true);
    private static final Fn1<Cause, String> UNKNOWN_ENVIRONMENT = Causes.forOneValue("Unknown environment: {}. Valid: local, docker, kubernetes");
    private final String name;
    private final int defaultNodes;
    private final String defaultHeap;
    private final boolean defaultTls;
    Environment(String name, int defaultNodes, String defaultHeap, boolean defaultTls) {
        this.name = name;
        this.defaultNodes = defaultNodes;
        this.defaultHeap = defaultHeap;
        this.defaultTls = defaultTls;
    }
    public String displayName() {
        return name;
    }
    public int defaultNodes() {
        return defaultNodes;
    }
    public String defaultHeap() {
        return defaultHeap;
    }
    public boolean defaultTls() {
        return defaultTls;
    }
    /// Parse environment from string, case-insensitive.
    ///
    /// @return parsed environment, or failure if unknown
    public static Result<Environment> environment(String value) {
        return option(value).map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .fold(() -> success(DOCKER),
                           Environment::fromNormalized);
    }
    private static Result<Environment> fromNormalized(String value) {
        return switch (value.toLowerCase()) {
            case "local" -> success(LOCAL);
            case "docker" -> success(DOCKER);
            case "kubernetes", "k8s" -> success(KUBERNETES);
            default -> UNKNOWN_ENVIRONMENT.apply(value)
                                          .result();
        };
    }
}
