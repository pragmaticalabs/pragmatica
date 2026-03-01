package org.pragmatica.aether.ember;

import org.pragmatica.aether.invoke.ObservabilityConfig;
import org.pragmatica.lang.Result;

/// Fluent builder for creating embedded Aether clusters.
///
/// Example usage:
/// ```java
/// var instance = Ember.cluster(5)
///                     .withH2()
///                     .withManagementPort(5150)
///                     .withLb(9090)
///                     .start();
/// // ... use the cluster ...
/// instance.stop();
/// ```
public final class Ember {
    private int nodes;
    private int managementPort = EmberConfig.DEFAULT_MANAGEMENT_PORT;
    private int dashboardPort = EmberConfig.DEFAULT_DASHBOARD_PORT;
    private int appHttpPort = EmberConfig.DEFAULT_APP_HTTP_PORT;
    private EmberH2Config h2Config = EmberH2Config.disabled();
    private boolean lbEnabled = EmberConfig.DEFAULT_LB_ENABLED;
    private int lbPort = EmberConfig.DEFAULT_LB_PORT;

    private Ember(int nodes) {
        this.nodes = nodes;
    }

    /// Start building a cluster with the specified number of nodes.
    public static Ember cluster(int nodes) {
        return new Ember(nodes);
    }

    /// Enable embedded H2 database with default settings.
    public Ember withH2() {
        this.h2Config = EmberH2Config.enabledWithDefaults();
        return this;
    }

    /// Enable embedded H2 database on a specific port.
    public Ember withH2(int port) {
        this.h2Config = EmberH2Config.enabledOnPort(port);
        return this;
    }

    /// Set the H2 database configuration.
    public Ember withH2Config(EmberH2Config h2Config) {
        this.h2Config = h2Config;
        return this;
    }

    /// Set the management port.
    public Ember withManagementPort(int port) {
        this.managementPort = port;
        return this;
    }

    /// Set the dashboard port.
    public Ember withDashboardPort(int port) {
        this.dashboardPort = port;
        return this;
    }

    /// Set the application HTTP port.
    public Ember withAppHttpPort(int port) {
        this.appHttpPort = port;
        return this;
    }

    /// Enable load balancer with default port.
    public Ember withLb() {
        this.lbEnabled = true;
        this.lbPort = EmberConfig.DEFAULT_LB_PORT;
        return this;
    }

    /// Enable load balancer on the specified port.
    public Ember withLb(int port) {
        this.lbEnabled = true;
        this.lbPort = port;
        return this;
    }

    /// Disable load balancer.
    public Ember withoutLb() {
        this.lbEnabled = false;
        return this;
    }

    /// Build configuration and start the cluster.
    public Result<EmberInstance> start() {
        return EmberConfig.emberConfig(nodes,
                                       managementPort,
                                       dashboardPort,
                                       appHttpPort,
                                       h2Config,
                                       ObservabilityConfig.DEFAULT,
                                       lbEnabled,
                                       lbPort)
                          .map(config -> EmberInstance.emberInstance(config));
    }
}
