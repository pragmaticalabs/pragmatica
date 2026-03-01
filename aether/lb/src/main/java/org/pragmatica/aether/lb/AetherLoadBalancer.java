package org.pragmatica.aether.lb;

import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Aether Load Balancer -- HTTP reverse proxy with health checking.
///
/// Accepts incoming HTTP requests on a single port and distributes them
/// across healthy backend nodes using round-robin routing.
///
/// Features:
/// - Active health checking (GET /health/ready)
/// - Passive fast-removal on connection failure
/// - Automatic retry on backend failure
/// - X-Forwarded-For/Host/Request-Id headers
@SuppressWarnings({"JBCT-RET-03", "JBCT-EX-01"})
public final class AetherLoadBalancer {
    private static final Logger log = LoggerFactory.getLogger(AetherLoadBalancer.class);

    private final LoadBalancerConfig config;
    private final BackendPool pool;
    private final RoutingStrategy strategy;
    private final ProxyHandler proxy;
    private final HealthChecker healthChecker;
    private volatile Option<HttpServer> server = Option.empty();

    private AetherLoadBalancer(LoadBalancerConfig config,
                                BackendPool pool,
                                RoutingStrategy strategy) {
        this.config = config;
        this.pool = pool;
        this.strategy = strategy;
        this.proxy = ProxyHandler.proxyHandler(config.forwardTimeoutMs());
        this.healthChecker = HealthChecker.healthChecker(pool, config.healthCheckIntervalMs());
    }

    /// Create a load balancer with the given configuration and backends.
    public static AetherLoadBalancer aetherLoadBalancer(LoadBalancerConfig config, List<Backend> backends) {
        return new AetherLoadBalancer(config,
                                      BackendPool.backendPool(backends),
                                      RoundRobinStrategy.roundRobinStrategy());
    }

    /// Create with default config on the given port.
    public static AetherLoadBalancer aetherLoadBalancer(int port, List<Backend> backends) {
        return aetherLoadBalancer(LoadBalancerConfig.loadBalancerConfig(port), backends);
    }

    /// Start the load balancer.
    public Promise<Unit> start() {
        healthChecker.start();
        var serverConfig = HttpServerConfig.httpServerConfig("aether-lb", config.port())
                                           .withMaxContentLength(1048576);
        return HttpServer.httpServer(serverConfig, this::handleRequest)
                         .onSuccess(s -> server = Option.some(s))
                         .onSuccess(_ -> log.info("Load balancer started on port {} with {} backends",
                                                  config.port(), pool.size()))
                         .mapToUnit();
    }

    /// Stop the load balancer.
    public Promise<Unit> stop() {
        healthChecker.stop();
        return server.map(HttpServer::stop)
                     .or(Promise.success(Unit.unit()))
                     .onSuccess(_ -> server = Option.empty())
                     .onSuccess(_ -> log.info("Load balancer stopped"))
                     .mapToUnit();
    }

    /// Get the port the load balancer is listening on.
    public int port() {
        return server.map(HttpServer::port)
                     .or(config.port());
    }

    /// Get the backend pool for monitoring.
    public BackendPool backendPool() {
        return pool;
    }

    private void handleRequest(RequestContext request, ResponseWriter response) {
        var healthy = pool.healthy();
        if (healthy.isEmpty()) {
            response.error(HttpStatus.SERVICE_UNAVAILABLE, "No healthy backend available");
            return;
        }

        for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
            var selected = strategy.select(healthy, request);
            if (selected.isEmpty()) {
                response.error(HttpStatus.SERVICE_UNAVAILABLE, "No healthy backend available");
                return;
            }
            var backend = selected.unwrap();
            if (proxy.forward(request, response, backend)) {
                return;
            }
            // Forward failed -- mark unhealthy, try next
            pool.markUnhealthy(backend);
            log.warn("Backend {}:{} failed, marking unhealthy (attempt {}/{})",
                     backend.host(), backend.port(), attempt + 1, config.maxRetries() + 1);
            healthy = pool.healthy();
            if (healthy.isEmpty()) {
                break;
            }
        }
        response.error(HttpStatus.BAD_GATEWAY, "All backend attempts exhausted");
    }
}
