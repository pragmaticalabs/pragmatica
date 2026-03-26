package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.CertificateStatusResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterConfigResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterStatusNodeInfo;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterStatusResponse;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/// Routes for declarative cluster configuration: read config and aggregated status.
public final class ClusterConfigRoutes implements RouteSource {
    private final Supplier<AetherNode> nodeSupplier;

    private ClusterConfigRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static ClusterConfigRoutes clusterConfigRoutes(Supplier<AetherNode> nodeSupplier) {
        return new ClusterConfigRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<ClusterConfigResponse> get("/api/cluster/config")
                              .to(_ -> buildConfigResponse())
                              .asJson(),
                         Route.<ClusterStatusResponse> get("/api/cluster/status")
                              .to(_ -> buildStatusResponse())
                              .asJson());
    }

    private Promise<ClusterConfigResponse> buildConfigResponse() {
        return lookupClusterConfig().map(ClusterConfigRoutes::toConfigResponse);
    }

    private static ClusterConfigResponse toConfigResponse(ClusterConfigValue config) {
        return new ClusterConfigResponse(config.tomlContent(),
                                         config.clusterName(),
                                         config.version(),
                                         config.coreCount(),
                                         config.coreMin(),
                                         config.coreMax(),
                                         config.deploymentType(),
                                         config.configVersion(),
                                         config.updatedAt());
    }

    private Promise<ClusterStatusResponse> buildStatusResponse() {
        var node = nodeSupplier.get();
        return lookupClusterConfig().map(config -> assembleStatus(node, config));
    }

    @SuppressWarnings("JBCT-PAT-01")
    private ClusterStatusResponse assembleStatus(AetherNode node, ClusterConfigValue config) {
        var leaderId = node.leader()
                           .map(NodeId::id)
                           .or("none");
        var nodeInfos = buildNodeInfos(node, leaderId);
        var sliceCount = node.sliceStore()
                             .loaded()
                             .size();
        var sliceInstances = countSliceInstances(node);
        var certExpiry = buildCertificateExpiry(node);
        return new ClusterStatusResponse(config.clusterName(),
                                         config.version(),
                                         config.coreCount(),
                                         node.connectedNodeCount() + 1,
                                         reconcilerStateName(node),
                                         leaderId,
                                         nodeInfos,
                                         sliceCount,
                                         sliceInstances,
                                         certExpiry.map(CertificateStatusResponse::expiresAt)
                                                   .or("N/A"),
                                         certExpiry.map(CertificateStatusResponse::secondsUntilExpiry)
                                                   .or(0L),
                                         config.configVersion(),
                                         node.uptimeSeconds());
    }

    private static int countSliceInstances(AetherNode node) {
        return node.deploymentMap()
                   .allDeployments()
                   .stream()
                   .mapToInt(d -> d.instances()
                                   .size())
                   .sum();
    }

    private static List<ClusterStatusNodeInfo> buildNodeInfos(AetherNode node, String leaderId) {
        return node.metricsCollector()
                   .allMetrics()
                   .keySet()
                   .stream()
                   .map(nid -> toStatusNodeInfo(nid, leaderId))
                   .toList();
    }

    private static ClusterStatusNodeInfo toStatusNodeInfo(NodeId nid, String leaderId) {
        return new ClusterStatusNodeInfo(nid.id(),
                                         "core",
                                         "ON_DUTY",
                                         AetherNode.VERSION,
                                         nid.id()
                                            .equals(leaderId));
    }

    private static String reconcilerStateName(AetherNode node) {
        var actualCount = node.connectedNodeCount() + 1;
        return actualCount >= node.topologyConfig()
                                  .clusterSize()
               ? "CONVERGED"
               : "RECONCILING";
    }

    private static Option<CertificateStatusResponse> buildCertificateExpiry(AetherNode node) {
        return node.certRenewalScheduler()
                   .map(ClusterConfigRoutes::toCertStatus);
    }

    private static CertificateStatusResponse toCertStatus(CertificateRenewalScheduler scheduler) {
        return new CertificateStatusResponse(scheduler.currentNotAfter()
                                                      .toString(),
                                             scheduler.secondsUntilExpiry(),
                                             scheduler.lastRenewalAt()
                                                      .toString(),
                                             scheduler.renewalStatus()
                                                      .name());
    }

    private Promise<ClusterConfigValue> lookupClusterConfig() {
        return nodeSupplier.get()
                           .kvStore()
                           .get(ClusterConfigKey.CURRENT)
                           .flatMap(ClusterConfigRoutes::narrowToConfig)
                           .async(ClusterConfigError.NOT_FOUND);
    }

    private static Option<ClusterConfigValue> narrowToConfig(AetherValue value) {
        return value instanceof ClusterConfigValue config
               ? Option.some(config)
               : Option.empty();
    }

    private enum ClusterConfigError implements Cause {
        NOT_FOUND("No cluster configuration stored");
        private final String message;
        ClusterConfigError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
