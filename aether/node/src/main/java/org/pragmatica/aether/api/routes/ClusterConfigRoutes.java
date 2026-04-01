package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.ApplyConfigRequest;
import org.pragmatica.aether.api.ManagementApiResponses.ApplyConfigResponse;
import org.pragmatica.aether.api.ManagementApiResponses.CertificateStatusResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterConfigResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterStatusNodeInfo;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterStatusResponse;
import org.pragmatica.aether.api.ManagementApiResponses.DryRunResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ScaleClusterResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ScaleRequest;
import org.pragmatica.aether.api.ManagementApiResponses.UpgradeRequest;
import org.pragmatica.aether.api.ManagementApiResponses.UpgradeResponse;
import org.pragmatica.aether.config.cluster.ClusterConfigDiff;
import org.pragmatica.aether.config.cluster.ClusterConfigDiff.ConfigChange;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.ClusterConfigParser;
import org.pragmatica.aether.config.cluster.ClusterConfigValidator;
import org.pragmatica.aether.config.cluster.ClusterManagementConfig;
import org.pragmatica.aether.deployment.cluster.ClusterConfigApplier;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Routes for declarative cluster configuration: read config, aggregated status,
/// apply config changes, and scale operations.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-PAT-01"})
public final class ClusterConfigRoutes implements RouteSource {
    private static final Logger log = LoggerFactory.getLogger(ClusterConfigRoutes.class);

    private final Supplier<AetherNode> nodeSupplier;
    private final ClusterConfigApplier applier;

    private ClusterConfigRoutes(Supplier<AetherNode> nodeSupplier, ClusterConfigApplier applier) {
        this.nodeSupplier = nodeSupplier;
        this.applier = applier;
    }

    public static ClusterConfigRoutes clusterConfigRoutes(Supplier<AetherNode> nodeSupplier,
                                                          ClusterConfigApplier applier) {
        return new ClusterConfigRoutes(nodeSupplier, applier);
    }

    /// Backward-compatible factory for existing wiring that does not yet provide an applier.
    public static ClusterConfigRoutes clusterConfigRoutes(Supplier<AetherNode> nodeSupplier) {
        return new ClusterConfigRoutes(nodeSupplier, new ClusterConfigApplier.unused());
    }

    @Override public Stream<Route<?>> routes() {
        return Stream.of(Route.<ClusterConfigResponse>get("/api/cluster/config")
                              .to(_ -> buildConfigResponse())
                              .asJson(),
                         Route.<ClusterStatusResponse>get("/api/cluster/status")
                              .to(_ -> buildStatusResponse())
                              .asJson(),
                         Route.<Object>post("/api/cluster/config")
                              .withBody(ApplyConfigRequest.class)
                              .toJson(this::handleApplyConfig),
                         Route.<ScaleClusterResponse>post("/api/cluster/scale")
                              .withBody(ScaleRequest.class)
                              .toJson(this::handleScale),
                         Route.<UpgradeResponse>post("/api/cluster/upgrade")
                              .withBody(UpgradeRequest.class)
                              .toJson(this::handleUpgrade));
    }

    // ===== GET /api/cluster/config =====
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

    // ===== GET /api/cluster/status =====
    private Promise<ClusterStatusResponse> buildStatusResponse() {
        var node = nodeSupplier.get();
        return lookupClusterConfig().map(config -> assembleStatus(node, config));
    }

    private ClusterStatusResponse assembleStatus(AetherNode node, ClusterConfigValue config) {
        var leaderId = node.leader().map(NodeId::id)
                                  .or("none");
        var nodeInfos = buildNodeInfos(node, leaderId);
        var sliceCount = node.sliceStore().loaded()
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
                                         certExpiry.map(CertificateStatusResponse::expiresAt).or("N/A"),
                                         certExpiry.map(CertificateStatusResponse::secondsUntilExpiry).or(0L),
                                         config.configVersion(),
                                         node.uptimeSeconds());
    }

    private static int countSliceInstances(AetherNode node) {
        return node.deploymentMap().allDeployments()
                                 .stream()
                                 .mapToInt(d -> d.instances().size())
                                 .sum();
    }

    private static List<ClusterStatusNodeInfo> buildNodeInfos(AetherNode node, String leaderId) {
        return node.metricsCollector().allMetrics()
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
                                         nid.id().equals(leaderId));
    }

    private static String reconcilerStateName(AetherNode node) {
        var actualCount = node.connectedNodeCount() + 1;
        return actualCount >= node.topologyConfig().clusterSize()
               ? "CONVERGED"
               : "RECONCILING";
    }

    private static Option<CertificateStatusResponse> buildCertificateExpiry(AetherNode node) {
        return node.certRenewalScheduler().map(ClusterConfigRoutes::toCertStatus);
    }

    private static CertificateStatusResponse toCertStatus(CertificateRenewalScheduler scheduler) {
        return new CertificateStatusResponse(scheduler.currentNotAfter().toString(),
                                             scheduler.secondsUntilExpiry(),
                                             scheduler.lastRenewalAt().toString(),
                                             scheduler.renewalStatus().name());
    }

    // ===== POST /api/cluster/config =====
    private Promise<Object> handleApplyConfig(ApplyConfigRequest request) {
        return parseAndValidateConfig(request.tomlContent()).async()
                                     .flatMap(desired -> lookupClusterConfig().flatMap(stored -> processApply(stored,
                                                                                                              desired,
                                                                                                              request)));
    }

    private Promise<Object> processApply(ClusterConfigValue stored,
                                         ClusterManagementConfig desired,
                                         ApplyConfigRequest request) {
        return checkVersionAsync(stored.configVersion(),
                                 request.expectedVersion()).flatMap(_ -> rebuildStoredConfigAsync(stored))
                                .flatMap(storedConfig -> executeDiff(stored,
                                                                     storedConfig,
                                                                     desired,
                                                                     request.tomlContent()));
    }

    private Promise<Object> executeDiff(ClusterConfigValue stored,
                                        ClusterManagementConfig storedConfig,
                                        ClusterManagementConfig desired,
                                        String tomlContent) {
        var diff = ClusterConfigDiff.diff(storedConfig, desired);
        if ( diff.hasImmutableChanges()) {
        return diff.validateAndExtractActions().async()
                                             .map(actions -> (Object) actions);}
        var actions = diff.actionableChanges();
        if ( actions.isEmpty()) {
        return buildDryRunResponse(stored, diff);}
        return applier.apply(actions)
        .flatMap(_ -> storeUpdatedConfig(desired, tomlContent, stored.configVersion() + 1));
    }

    private static Result<ClusterManagementConfig> parseAndValidateConfig(String tomlContent) {
        return ClusterConfigParser.parse(tomlContent).flatMap(ClusterConfigValidator::validate);
    }

    private static Promise<ClusterManagementConfig> rebuildStoredConfigAsync(ClusterConfigValue stored) {
        return ClusterConfigParser.parse(stored.tomlContent()).async();
    }

    private static Promise<Object> checkVersionAsync(long storedVersion, long expectedVersion) {
        if ( expectedVersion != 0 && storedVersion != expectedVersion) {
        return new org.pragmatica.aether.config.cluster.ClusterConfigError.VersionConflict(expectedVersion,
                                                                                           storedVersion).promise();}
        return Promise.unitPromise().map(u -> (Object) u);
    }

    @SuppressWarnings("unchecked")
    private Promise<Object> storeUpdatedConfig(ClusterManagementConfig desired,
                                               String tomlContent,
                                               long newVersion) {
        var node = nodeSupplier.get();
        var cluster = desired.cluster();
        var configValue = new ClusterConfigValue(tomlContent,
                                                 cluster.name(),
                                                 cluster.version(),
                                                 cluster.core().count(),
                                                 cluster.core().min(),
                                                 cluster.core().max(),
                                                 desired.deployment().type()
                                                                   .value(),
                                                 newVersion,
                                                 System.currentTimeMillis());
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(ClusterConfigKey.CURRENT, configValue);
        return node.<Object>apply(List.of(command))
                   .map(_ -> (Object) new ApplyConfigResponse(newVersion,
                                                              cluster.name(),
                                                              cluster.core().count(),
                                                              configValue.updatedAt()));
    }

    private static Promise<Object> buildDryRunResponse(ClusterConfigValue stored, ClusterConfigDiff diff) {
        var descriptions = diff.changes().stream()
                                       .map(ClusterConfigRoutes::formatChange)
                                       .toList();
        return Promise.success((Object) new DryRunResponse(stored.clusterName(),
                                                           stored.configVersion(),
                                                           stored.configVersion(),
                                                           descriptions,
                                                           0,
                                                           0));
    }

    private static String formatChange(ConfigChange change) {
        var tag = change.isImmutable()
                  ? "[REJECTED] "
                  : change.requiresAction()
                  ? "[ACTION]   "
                  : "[NOOP]     ";
        return tag + change.description();
    }

    // ===== POST /api/cluster/scale =====
    private Promise<ScaleClusterResponse> handleScale(ScaleRequest request) {
        return lookupClusterConfig().flatMap(stored -> applyScale(stored, request));
    }

    private Promise<ScaleClusterResponse> applyScale(ClusterConfigValue stored, ScaleRequest request) {
        return checkVersionAsync(stored.configVersion(),
                                 request.expectedVersion()).flatMap(_ -> validateScaleAsync(request.coreCount(),
                                                                                            stored.coreMin(),
                                                                                            stored.coreMax()))
                                .flatMap(_ -> executeScale(stored, request));
    }

    private Promise<ScaleClusterResponse> executeScale(ClusterConfigValue stored, ScaleRequest request) {
        var previousCount = stored.coreCount();
        var newVersion = stored.configVersion() + 1;
        var scaleChange = new ConfigChange.ScaleCore(previousCount, request.coreCount());
        return applier.apply(List.of(scaleChange)).flatMap(_ -> storeScaledConfig(stored,
                                                                                  request.coreCount(),
                                                                                  newVersion))
                            .map(_ -> new ScaleClusterResponse(true,
                                                               previousCount,
                                                               request.coreCount(),
                                                               newVersion));
    }

    private static Promise<Object> validateScaleAsync(int coreCount, int coreMin, int coreMax) {
        if ( coreCount < 3) {
        return new ClusterConfigError.QuorumSafetyViolation(coreCount, 3).promise();}
        if ( coreCount % 2 == 0) {
        return new ClusterConfigError.InvalidCoreCount(coreCount).promise();}
        if ( coreCount < coreMin) {
        return new ClusterConfigError.QuorumSafetyViolation(coreCount, coreMin).promise();}
        if ( coreCount > coreMax) {
        return new ClusterConfigError.InvalidCoreMax(coreMax, coreCount).promise();}
        return Promise.unitPromise().map(u -> (Object) u);
    }

    @SuppressWarnings("unchecked")
    private Promise<Object> storeScaledConfig(ClusterConfigValue stored, int newCoreCount, long newVersion) {
        var configValue = new ClusterConfigValue(stored.tomlContent(),
                                                 stored.clusterName(),
                                                 stored.version(),
                                                 newCoreCount,
                                                 stored.coreMin(),
                                                 stored.coreMax(),
                                                 stored.deploymentType(),
                                                 newVersion,
                                                 System.currentTimeMillis());
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(ClusterConfigKey.CURRENT, configValue);
        return nodeSupplier.get().<Object>apply(List.of(command))
                               .map(_ -> (Object) configValue);
    }

    // ===== POST /api/cluster/upgrade =====
    private Promise<UpgradeResponse> handleUpgrade(UpgradeRequest request) {
        return lookupClusterConfig().flatMap(stored -> initiateUpgrade(stored, request));
    }

    private Promise<UpgradeResponse> initiateUpgrade(ClusterConfigValue stored, UpgradeRequest request) {
        var currentVersion = stored.version();
        var targetVersion = request.targetVersion();
        if ( currentVersion.equals(targetVersion)) {
        return new UpgradeError.AlreadyAtVersion(targetVersion).promise();}
        log.info("Cluster upgrade initiated: {} -> {}",
                 currentVersion,
                 targetVersion);
        return storeUpgradedVersion(stored, targetVersion)
        .map(_ -> new UpgradeResponse("INITIATED", currentVersion, targetVersion));
    }

    @SuppressWarnings("unchecked")
    private Promise<Object> storeUpgradedVersion(ClusterConfigValue stored, String targetVersion) {
        var configValue = new ClusterConfigValue(stored.tomlContent(),
                                                 stored.clusterName(),
                                                 targetVersion,
                                                 stored.coreCount(),
                                                 stored.coreMin(),
                                                 stored.coreMax(),
                                                 stored.deploymentType(),
                                                 stored.configVersion() + 1,
                                                 System.currentTimeMillis());
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(ClusterConfigKey.CURRENT, configValue);
        return nodeSupplier.get().<Object>apply(List.of(command))
                               .map(_ -> (Object) configValue);
    }

    sealed interface UpgradeError extends Cause {
        record AlreadyAtVersion(String version) implements UpgradeError {
            @Override public String message() {
                return "Cluster is already at version " + version;
            }
        }
    }

    // ===== Shared helpers =====
    private Promise<ClusterConfigValue> lookupClusterConfig() {
        return nodeSupplier.get().kvStore()
                               .get(ClusterConfigKey.CURRENT)
                               .flatMap(ClusterConfigRoutes::narrowToConfig)
                               .async(ConfigNotFoundError.NOT_FOUND);
    }

    private static Option<ClusterConfigValue> narrowToConfig(AetherValue value) {
        return value instanceof ClusterConfigValue config
               ? Option.some(config)
               : Option.empty();
    }

    private enum ConfigNotFoundError implements Cause {
        NOT_FOUND("No cluster configuration stored");
        private final String message;
        ConfigNotFoundError(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }
}
