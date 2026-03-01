package org.pragmatica.aether.lb;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Active health checker that polls backends periodically.
///
/// Sends GET /health/ready to each backend's management port.
/// Backends that respond with 200 are marked healthy; all others unhealthy.
@SuppressWarnings({"JBCT-RET-03", "JBCT-EX-01"})
public final class HealthChecker {
    private static final Logger log = LoggerFactory.getLogger(HealthChecker.class);
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(3);

    private final BackendPool pool;
    private final long intervalMs;
    private volatile ScheduledExecutorService scheduler;

    private HealthChecker(BackendPool pool, long intervalMs) {
        this.pool = pool;
        this.intervalMs = intervalMs;
    }

    /// Create a health checker for the given pool.
    public static HealthChecker healthChecker(BackendPool pool, long intervalMs) {
        return new HealthChecker(pool, intervalMs);
    }

    /// Start periodic health checking.
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "lb-health-checker");
            thread.setDaemon(true);
            return thread;
        });
        // Initial check immediately
        scheduler.execute(this::checkAll);
        // Then periodic
        scheduler.scheduleAtFixedRate(this::checkAll, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("Health checker started (interval={}ms)", intervalMs);
    }

    /// Stop health checking.
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
            log.info("Health checker stopped");
        }
    }

    private void checkAll() {
        for (var backend : pool.all()) {
            checkBackend(backend);
        }
    }

    private void checkBackend(Backend backend) {
        try (var client = HttpClient.newBuilder()
                                     .connectTimeout(CHECK_TIMEOUT)
                                     .build()) {
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create(backend.healthUrl()))
                                     .GET()
                                     .timeout(CHECK_TIMEOUT)
                                     .build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 200) {
                if (!pool.isHealthy(backend)) {
                    log.info("Backend {}:{} is now healthy", backend.host(), backend.port());
                }
                pool.markHealthy(backend);
            } else {
                if (pool.isHealthy(backend)) {
                    log.warn("Backend {}:{} is now unhealthy (HTTP {})", backend.host(), backend.port(), response.statusCode());
                }
                pool.markUnhealthy(backend);
            }
        } catch (Exception e) {
            if (pool.isHealthy(backend)) {
                log.warn("Backend {}:{} is now unhealthy: {}", backend.host(), backend.port(), e.getMessage());
            }
            pool.markUnhealthy(backend);
        }
    }
}
