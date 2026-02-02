package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

/**
 * Deployment environment with environment-specific defaults.
 *
 * <p>Each environment has sensible defaults based on its typical use case:
 * <ul>
 *   <li>LOCAL - Single machine development (3 nodes, minimal resources)</li>
 *   <li>DOCKER - Production-like Docker Compose (5 nodes, moderate resources)</li>
 *   <li>KUBERNETES - Cloud-native deployment (5 nodes, TLS enabled)</li>
 * </ul>
 */
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
    /**
     * Parse environment from string, case-insensitive.
     *
     * @return parsed environment, or failure if unknown
     */
    public static Result<Environment> environment(String value) {
        if (value == null || value.isBlank()) {
            return Result.success(DOCKER);
        }
        return switch (value.toLowerCase()
                            .trim()) {
            case "local" -> Result.success(LOCAL);
            case "docker" -> Result.success(DOCKER);
            case "kubernetes", "k8s" -> Result.success(KUBERNETES);
            default -> UNKNOWN_ENVIRONMENT.apply(value)
                                          .result();
        };
    }
}
