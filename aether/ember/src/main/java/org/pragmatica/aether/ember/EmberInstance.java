package org.pragmatica.aether.ember;

import org.pragmatica.aether.lb.AetherPassiveLB;
import org.pragmatica.aether.lb.PassiveLBConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;

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

    private volatile Option<AetherPassiveLB> loadBalancer = Option.empty();

    private EmberInstance(EmberConfig config) {
        this.config = config;
        this.cluster = EmberCluster.emberCluster(config.nodes(),
                                                 EmberCluster.DEFAULT_BASE_PORT,
                                                 config.managementPort(),
                                                 config.appHttpPort(),
                                                 "node",
                                                 Option.empty(),
                                                 config.observability(),
                                                 config.coreMax());
    }

    static EmberInstance emberInstance(EmberConfig config) {
        var instance = new EmberInstance(config);
        instance.startH2();
        instance.cluster.start().await(TimeSpan.timeSpan(60).seconds())
                              .onFailure(cause -> log.error("Failed to start cluster: {}",
                                                            cause.message()))
                              .onSuccess(_ -> instance.startLoadBalancer());
        return instance;
    }

    private void startH2() {
        if (!config.h2Config().enabled()) {return;}
        var server = EmberH2Server.emberH2Server(config.h2Config());
        server.start().await(TimeSpan.timeSpan(10).seconds())
                    .onSuccess(_ -> {
                                   h2Server = Option.some(server);
                                   log.info("H2 database available at: {}",
                                            server.jdbcUrl());
                               })
                    .onFailure(cause -> log.error("Failed to start H2 server: {}",
                                                  cause.message()));
    }

    private void startLoadBalancer() {
        if (!config.lbEnabled()) {return;}
        var selfNodeId = NodeId.nodeId("lb-passive").unwrap();
        var lbClusterPort = EmberCluster.DEFAULT_BASE_PORT + config.nodes() + 10;
        var selfInfo = NodeInfo.nodeInfo(selfNodeId,
                                         NodeAddress.nodeAddress("localhost", lbClusterPort).unwrap(),
                                         NodeRole.PASSIVE);
        var clusterNodeInfos = cluster.getNodeInfos();
        var lbConfig = PassiveLBConfig.passiveLBConfig(config.lbPort(), selfInfo, clusterNodeInfos, config.nodes());
        var lb = AetherPassiveLB.aetherPassiveLB(lbConfig);
        lb.start().await(TimeSpan.timeSpan(30).seconds())
                .onSuccess(_ -> registerLoadBalancer(lb, lbClusterPort))
                .onFailure(cause -> log.error("Failed to start passive LB: {}",
                                              cause.message()));
    }

    private void registerLoadBalancer(AetherPassiveLB lb, int lbClusterPort) {
        loadBalancer = Option.some(lb);
        log.info("Passive LB started on port {} (cluster port {})", config.lbPort(), lbClusterPort);
    }

    public EmberCluster cluster() {
        return cluster;
    }

    public EmberConfig config() {
        return config;
    }

    public Option<String> h2JdbcUrl() {
        return h2Server.filter(EmberH2Server::isRunning).map(EmberH2Server::jdbcUrl);
    }

    public Option<AetherPassiveLB> loadBalancer() {
        return loadBalancer;
    }

    public Promise<Unit> stop() {
        return stopLoadBalancer().flatMap(_ -> cluster.stop()).flatMap(_ -> stopH2());
    }

    private Promise<Unit> stopLoadBalancer() {
        return loadBalancer.map(AetherPassiveLB::stop).or(Promise.success(Unit.unit()));
    }

    private Promise<Unit> stopH2() {
        return h2Server.map(EmberH2Server::stop).or(Promise.success(Unit.unit()));
    }
}
