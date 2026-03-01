package org.pragmatica.aether.lb;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/// Thread-safe pool of backends with health tracking.
///
/// Tracks which backends are healthy or unhealthy.
/// New backends start unhealthy until first successful health check.
public final class BackendPool {
    private final List<Backend> allBackends;
    private final Set<Backend> unhealthy = ConcurrentHashMap.newKeySet();

    private BackendPool(List<Backend> backends) {
        this.allBackends = List.copyOf(backends);
        // All backends start unhealthy until first health check passes
        this.unhealthy.addAll(backends);
    }

    /// Create a backend pool.
    public static BackendPool backendPool(List<Backend> backends) {
        return new BackendPool(backends);
    }

    /// Get all currently healthy backends.
    public List<Backend> healthy() {
        return allBackends.stream()
                          .filter(b -> !unhealthy.contains(b))
                          .toList();
    }

    /// Get all backends.
    public List<Backend> all() {
        return allBackends;
    }

    /// Mark a backend as healthy.
    public void markHealthy(Backend backend) {
        unhealthy.remove(backend);
    }

    /// Mark a backend as unhealthy.
    public void markUnhealthy(Backend backend) {
        unhealthy.add(backend);
    }

    /// Check if a backend is healthy.
    public boolean isHealthy(Backend backend) {
        return !unhealthy.contains(backend);
    }

    /// Number of healthy backends.
    public int healthyCount() {
        return healthy().size();
    }

    /// Total number of backends.
    public int size() {
        return allBackends.size();
    }
}
