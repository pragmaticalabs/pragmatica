package org.pragmatica.aether.lb;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// Configuration for the Aether load balancer.
///
/// @param port Port the load balancer listens on
/// @param healthCheckIntervalMs Interval between active health checks in milliseconds
/// @param forwardTimeoutMs Timeout for forwarding requests to backends in milliseconds
/// @param maxRetries Maximum number of retry attempts on backend failure
public record LoadBalancerConfig(int port,
                                 long healthCheckIntervalMs,
                                 long forwardTimeoutMs,
                                 int maxRetries) {
    public static final int DEFAULT_PORT = 8080;
    public static final long DEFAULT_HEALTH_CHECK_INTERVAL_MS = 5000;
    public static final long DEFAULT_FORWARD_TIMEOUT_MS = 5000;
    public static final int DEFAULT_MAX_RETRIES = 2;

    /// Create configuration with validation.
    public static Result<LoadBalancerConfig> loadBalancerConfig(int port,
                                                                long healthCheckIntervalMs,
                                                                long forwardTimeoutMs,
                                                                int maxRetries) {
        if (port < 1 || port > 65535) {
            return LoadBalancerError.configError("port must be between 1 and 65535: " + port)
                                    .result();
        }
        if (healthCheckIntervalMs < 100) {
            return LoadBalancerError.configError("health check interval must be at least 100ms")
                                    .result();
        }
        if (forwardTimeoutMs < 100) {
            return LoadBalancerError.configError("forward timeout must be at least 100ms")
                                    .result();
        }
        if (maxRetries < 0) {
            return LoadBalancerError.configError("max retries must be non-negative")
                                    .result();
        }
        return success(new LoadBalancerConfig(port, healthCheckIntervalMs, forwardTimeoutMs, maxRetries));
    }

    /// Default configuration.
    public static LoadBalancerConfig loadBalancerConfig() {
        return new LoadBalancerConfig(DEFAULT_PORT,
                                      DEFAULT_HEALTH_CHECK_INTERVAL_MS,
                                      DEFAULT_FORWARD_TIMEOUT_MS,
                                      DEFAULT_MAX_RETRIES);
    }

    /// Create with specific port, defaults for everything else.
    public static LoadBalancerConfig loadBalancerConfig(int port) {
        return new LoadBalancerConfig(port,
                                      DEFAULT_HEALTH_CHECK_INTERVAL_MS,
                                      DEFAULT_FORWARD_TIMEOUT_MS,
                                      DEFAULT_MAX_RETRIES);
    }
}
