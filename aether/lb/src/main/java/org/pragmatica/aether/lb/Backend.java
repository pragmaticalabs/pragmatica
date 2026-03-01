package org.pragmatica.aether.lb;

/// A backend server that the load balancer can route traffic to.
///
/// @param host Backend hostname
/// @param port Application HTTP port (for forwarding requests)
/// @param healthPort Management port (for health checks)
public record Backend(String host, int port, int healthPort) {
    /// Create a backend with the same port for app and health.
    public static Backend backend(String host, int port) {
        return new Backend(host, port, port);
    }

    /// Create a backend with separate app and health ports.
    public static Backend backend(String host, int port, int healthPort) {
        return new Backend(host, port, healthPort);
    }

    /// Base URL for forwarding application requests.
    public String baseUrl() {
        return "http://" + host + ":" + port;
    }

    /// Health check URL.
    public String healthUrl() {
        return "http://" + host + ":" + healthPort + "/health/ready";
    }
}
