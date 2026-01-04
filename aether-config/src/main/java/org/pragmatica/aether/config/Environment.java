package org.pragmatica.aether.config;
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
     */
    public static Environment fromString(String value) {
        if (value == null || value.isBlank()) {
            return DOCKER;
        }
        return switch (value.toLowerCase()
                            .trim()) {
            case"local" -> LOCAL;
            case"docker" -> DOCKER;
            case"kubernetes", "k8s" -> KUBERNETES;
            default -> throw new IllegalArgumentException(
            "Unknown environment: " + value + ". Valid: local, docker, kubernetes");
        };
    }
}
