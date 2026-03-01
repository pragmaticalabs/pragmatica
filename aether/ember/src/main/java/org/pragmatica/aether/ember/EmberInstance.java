package org.pragmatica.aether.ember;

import org.pragmatica.aether.lb.AetherLoadBalancer;
import org.pragmatica.aether.lb.Backend;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// A running Ember cluster instance.
///
/// Manages the lifecycle of an embedded Aether cluster including
/// optional H2 database, cluster nodes, load balancer, and configuration provider.
public final class EmberInstance {
    private static final Logger log = LoggerFactory.getLogger(EmberInstance.class);

    private final EmberConfig config;
    private final EmberCluster cluster;
    private volatile Option<EmberH2Server> h2Server = Option.empty();
    private volatile Option<AetherLoadBalancer> loadBalancer = Option.empty();

    private EmberInstance(EmberConfig config) {
        this.config = config;
        this.cluster = EmberCluster.emberCluster(config.nodes(),
                                                  EmberCluster.DEFAULT_BASE_PORT,
                                                  config.managementPort(),
                                                  config.appHttpPort(),
                                                  "node",
                                                  Option.empty(),
                                                  config.observability());
    }

    /// Create a new Ember instance with the given configuration.
    static EmberInstance emberInstance(EmberConfig config) {
        var instance = new EmberInstance(config);
        instance.startH2();
        instance.cluster.start()
                        .await(TimeSpan.timeSpan(60).seconds())
                        .onFailure(cause -> log.error("Failed to start cluster: {}", cause.message()))
                        .onSuccess(_ -> instance.startLoadBalancer());
        return instance;
    }

    private void startH2() {
        if (!config.h2Config().enabled()) {
            return;
        }
        var server = EmberH2Server.emberH2Server(config.h2Config());
        server.start()
              .await(TimeSpan.timeSpan(10).seconds())
              .onSuccess(_ -> {
                  h2Server = Option.some(server);
                  log.info("H2 database available at: {}", server.jdbcUrl());
              })
              .onFailure(cause -> log.error("Failed to start H2 server: {}", cause.message()));
    }

    private void startLoadBalancer() {
        if (!config.lbEnabled()) {
            return;
        }
        var backends = buildBackendList();
        if (backends.isEmpty()) {
            log.warn("No app HTTP ports available, skipping load balancer start");
            return;
        }
        var lb = AetherLoadBalancer.aetherLoadBalancer(config.lbPort(), backends);
        lb.start()
          .await(TimeSpan.timeSpan(10).seconds())
          .onSuccess(_ -> {
              loadBalancer = Option.some(lb);
              log.info("Load balancer started on port {} with {} backends", config.lbPort(), backends.size());
          })
          .onFailure(cause -> log.error("Failed to start load balancer: {}", cause.message()));
    }

    private java.util.List<Backend> buildBackendList() {
        var appHttpPorts = cluster.getAvailableAppHttpPorts();
        return appHttpPorts.stream()
                           .map(port -> Backend.backend("localhost", port, managementPortForAppPort(port)))
                           .toList();
    }

    private int managementPortForAppPort(int appPort) {
        var slot = appPort - config.appHttpPort();
        return config.managementPort() + slot;
    }

    /// Get the underlying cluster.
    public EmberCluster cluster() {
        return cluster;
    }

    /// Get the configuration.
    public EmberConfig config() {
        return config;
    }

    /// Get the H2 JDBC URL if H2 is enabled and running.
    public Option<String> h2JdbcUrl() {
        return h2Server.filter(EmberH2Server::isRunning)
                       .map(EmberH2Server::jdbcUrl);
    }

    /// Get the load balancer if enabled and running.
    public Option<AetherLoadBalancer> loadBalancer() {
        return loadBalancer;
    }

    /// Stop the cluster and all associated services.
    public Promise<Unit> stop() {
        return stopLoadBalancer()
                .flatMap(_ -> cluster.stop())
                .flatMap(_ -> stopH2());
    }

    private Promise<Unit> stopLoadBalancer() {
        return loadBalancer.map(AetherLoadBalancer::stop)
                           .or(Promise.success(Unit.unit()));
    }

    private Promise<Unit> stopH2() {
        return h2Server.map(EmberH2Server::stop)
                       .or(Promise.success(Unit.unit()));
    }
}
