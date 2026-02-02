package org.pragmatica.aether.config;
/**
 * Kubernetes resource configuration for pods.
 *
 * @param cpuRequest    CPU request (e.g., "500m")
 * @param cpuLimit      CPU limit (e.g., "2")
 * @param memoryRequest Memory request (e.g., "1Gi")
 * @param memoryLimit   Memory limit (e.g., "2Gi")
 */
public record ResourcesConfig(String cpuRequest,
                              String cpuLimit,
                              String memoryRequest,
                              String memoryLimit) {
    public static ResourcesConfig defaults() {
        return new ResourcesConfig("500m", "2", "1Gi", "2Gi");
    }

    /**
     * Create minimal resources for local/test environments.
     */
    public static ResourcesConfig minimal() {
        return new ResourcesConfig("100m", "500m", "256Mi", "512Mi");
    }
}
