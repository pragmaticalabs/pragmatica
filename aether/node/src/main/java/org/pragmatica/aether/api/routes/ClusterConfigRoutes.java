package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.ApplyConfigRequest;
import org.pragmatica.aether.api.ManagementApiResponses.ApplyConfigResponse;
import org.pragmatica.aether.api.ManagementApiResponses.CertificateStatusResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterConfigResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterStatusNodeInfo;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterStatusResponse;
import org.pragmatica.aether.api.ManagementApiResponses.LoadBalancerStatusInfo;
import org.pragmatica.aether.api.ManagementApiResponses.DryRunResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ScaleClusterResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ScaleRequest;
import org.pragmatica.aether.api.ManagementApiResponses.UpgradeRequest;
import org.pragmatica.aether.api.ManagementApiResponses.UpgradeResponse;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigDiff;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigValidator;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.aether.deployment.cluster.ClusterConfigApplier;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
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
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-PAT-01"}) public final class ClusterConfigRoutes implements RouteSource {
    private static final Logger log = LoggerFactory.getLogger(ClusterConfigRoutes.class);

    private final Supplier<ManageableNode> nodeSupplier;
    private final ClusterConfigApplier applier;

    private ClusterConfigRoutes(Supplier<ManageableNode> nodeSupplier, ClusterConfigApplier applier) {
        this.nodeSupplier = nodeSupplier;
        this.applier = applier;
    }

    public static ClusterConfigRoutes clusterConfigRoutes(Supplier<ManageableNode> nodeSupplier,
                                                          ClusterConfigApplier applier) {
        return new ClusterConfigRoutes(nodeSupplier, applier);
    }

    public static ClusterConfigRoutes clusterConfigRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new ClusterConfigRoutes(nodeSupplier, new ClusterConfigApplier.unused());
    }

    @Override public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<ClusterConfigResponse>route(ManagementRoute.CLUSTER_CONFIG_GET)
                                         .to(_ -> buildConfigResponse())
                                         .asJson(),
                         ManagementRoutes.<ClusterStatusResponse>route(ManagementRoute.CLUSTER_CONFIG_STATUS)
                                         .to(_ -> buildStatusResponse())
                                         .asJson(),
                         ManagementRoutes.<Object>route(ManagementRoute.CLUSTER_CONFIG_APPLY)
                                         .withBody(ApplyConfigRequest.class)
                                         .toJson(this::handleApplyConfig),
                         ManagementRoutes.<ScaleClusterResponse>route(ManagementRoute.CLUSTER_SCALE)
                                         .withBody(ScaleRequest.class)
                                         .toJson(this::handleScale),
                         ManagementRoutes.<UpgradeResponse>route(ManagementRoute.CLUSTER_UPGRADE)
                                         .withBody(UpgradeRequest.class)
                                         .toJson(this::handleUpgrade));
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

    private ClusterStatusResponse assembleStatus(ManageableNode node, ClusterConfigValue config) {
        var leaderId = node.leader().map(NodeId::id)
                                  .or("none");
        var nodeInfos = buildNodeInfos(node, leaderId);
        var sliceCount = node.sliceStore().loaded()
                                        .size();
        var sliceInstances = countSliceInstances(node);
        var certExpiry = buildCertificateExpiry(node);
        var lbInfo = buildLoadBalancerInfo(node);
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
                                         node.uptimeSeconds(),
                                         lbInfo);
    }

    private static int countSliceInstances(ManageableNode node) {
        return node.deploymentMap().allDeployments()
                                 .stream()
                                 .mapToInt(d -> d.instances().size())
                                 .sum();
    }

    private static List<ClusterStatusNodeInfo> buildNodeInfos(ManageableNode node, String leaderId) {
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

    private static String reconcilerStateName(ManageableNode node) {
        var actualCount = node.connectedNodeCount() + 1;
        return actualCount >= node.topologyConfig().clusterSize()
              ? "CONVERGED"
              : "RECONCILING";
    }

    private static Option<CertificateStatusResponse> buildCertificateExpiry(ManageableNode node) {
        return node.certRenewalScheduler().map(ClusterConfigRoutes::toCertStatus);
    }

    private static CertificateStatusResponse toCertStatus(CertificateRenewalScheduler scheduler) {
        return new CertificateStatusResponse(scheduler.currentNotAfter().toString(),
                                             scheduler.secondsUntilExpiry(),
                                             scheduler.lastRenewalAt().toString(),
                                             scheduler.renewalStatus().name());
    }

    private static Option<LoadBalancerStatusInfo> buildLoadBalancerInfo(ManageableNode node) {
        return node.taskGroupAssignmentRegistry().ownerFor(TaskGroup.DEPLOYMENT)
                                               .option()
                                               .flatMap(ownerId -> node.topologyManager().get(ownerId)
                                                                                       .map(info -> toLbStatusInfo(ownerId,
                                                                                                                   info,
                                                                                                                   node)));
    }

    private static LoadBalancerStatusInfo toLbStatusInfo(NodeId ownerId, NodeInfo info, ManageableNode node) {
        var host = info.address().host();
        var appEndpoint = "http://" + host + ":" + node.appHttpPort();
        var mgmtEndpoint = "http://" + host + ":" + node.managementPort();
        return new LoadBalancerStatusInfo("elected", ownerId.id(), appEndpoint, mgmtEndpoint);
    }

    private Promise<Object> handleApplyConfig(ApplyConfigRequest request) {
        return parseAndValidateConfig(request.tomlContent()).async()
                                     .flatMap(desired -> lookupClusterConfig().flatMap(stored -> processApply(stored,
                                                                                                              desired,
                                                                                                              request))
                                                                            .orElse(() -> storeInitialConfig(desired,
                                                                                                             request.tomlContent())));
    }

    @SuppressWarnings("unchecked") private Promise<Object> storeInitialConfig(ClusterBootstrapConfig desired,
                                                                              String tomlContent) {
        var cluster = desired.cluster();
        var coreCount = desired.derivedCoreCount();
        var coreMin = desired.coreTopology().min()
                                          .or(coreCount);
        var coreMax = desired.coreTopology().max()
                                          .or(coreCount);
        var sourceType = desired.sources().values()
                                        .stream()
                                        .map(s -> s.type().value())
                                        .findFirst()
                                        .orElse("unknown");
        var configValue = new ClusterConfigValue(tomlContent,
                                                 cluster.name(),
                                                 cluster.version(),
                                                 coreCount,
                                                 coreMin,
                                                 coreMax,
                                                 sourceType,
                                                 1,
                                                 System.currentTimeMillis());
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(ClusterConfigKey.CURRENT, configValue);
        return nodeSupplier.get().<Object>apply(List.of(command))
                               .map(_ -> (Object) new ApplyConfigResponse(1,
                                                                          cluster.name(),
                                                                          coreCount,
                                                                          configValue.updatedAt()));
    }

    private Promise<Object> processApply(ClusterConfigValue stored,
                                         ClusterBootstrapConfig desired,
                                         ApplyConfigRequest request) {
        return checkVersionAsync(stored.configVersion(),
                                 request.expectedVersion()).flatMap(_ -> rebuildStoredConfigAsync(stored))
                                .flatMap(storedConfig -> executeDiff(stored,
                                                                     storedConfig,
                                                                     desired,
                                                                     request.tomlContent()));
    }

    private Promise<Object> executeDiff(ClusterConfigValue stored,
                                        ClusterBootstrapConfig storedConfig,
                                        ClusterBootstrapConfig desired,
                                        String tomlContent) {
        var plan = ClusterBootstrapConfigDiff.diff(storedConfig, desired);
        if (plan.hasImmutableChanges()) {return buildImmutableChangeError(plan).promise();}
        if (plan.isEmpty()) {return buildDryRunResponse(stored, plan);}
        return applier.apply(plan.allActions())
                            .flatMap(_ -> storeUpdatedConfig(desired,
                                                             tomlContent,
                                                             stored.configVersion() + 1));
    }

    private static ClusterConfigError.ValidationFailed buildImmutableChangeError(DiffPlan plan) {
        var errors = plan.immutable().stream()
                                   .map(a -> (ClusterConfigError) new ClusterConfigError.ImmutableFieldChange(a.description()))
                                   .toList();
        return new ClusterConfigError.ValidationFailed(errors);
    }

    private static Result<ClusterBootstrapConfig> parseAndValidateConfig(String tomlContent) {
        return ClusterBootstrapConfigParser.parse(tomlContent).flatMap(ClusterBootstrapConfigValidator::validate);
    }

    private static Promise<ClusterBootstrapConfig> rebuildStoredConfigAsync(ClusterConfigValue stored) {
        return ClusterBootstrapConfigParser.parse(stored.tomlContent()).async();
    }

    private static Promise<Object> checkVersionAsync(long storedVersion, long expectedVersion) {
        if (expectedVersion != 0 && storedVersion != expectedVersion) {return new ClusterConfigError.VersionConflict(expectedVersion,
                                                                                                                     storedVersion).promise();}
        return Promise.unitPromise().map(u -> (Object) u);
    }

    @SuppressWarnings("unchecked") private Promise<Object> storeUpdatedConfig(ClusterBootstrapConfig desired,
                                                                              String tomlContent,
                                                                              long newVersion) {
        var node = nodeSupplier.get();
        var cluster = desired.cluster();
        var coreCount = desired.derivedCoreCount();
        var coreMin = desired.coreTopology().min()
                                          .or(coreCount);
        var coreMax = desired.coreTopology().max()
                                          .or(coreCount);
        var sourceType = desired.sources().values()
                                        .stream()
                                        .map(s -> s.type().value())
                                        .findFirst()
                                        .orElse("unknown");
        var configValue = new ClusterConfigValue(tomlContent,
                                                 cluster.name(),
                                                 cluster.version(),
                                                 coreCount,
                                                 coreMin,
                                                 coreMax,
                                                 sourceType,
                                                 newVersion,
                                                 System.currentTimeMillis());
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(ClusterConfigKey.CURRENT, configValue);
        return node.<Object>apply(List.of(command))
                   .map(_ -> (Object) new ApplyConfigResponse(newVersion,
                                                              cluster.name(),
                                                              coreCount,
                                                              configValue.updatedAt()));
    }

    private static Promise<Object> buildDryRunResponse(ClusterConfigValue stored, DiffPlan plan) {
        var descriptions = plan.allActions().stream()
                                          .map(ClusterConfigRoutes::formatAction)
                                          .toList();
        return Promise.success((Object) new DryRunResponse(stored.clusterName(),
                                                           stored.configVersion(),
                                                           stored.configVersion(),
                                                           descriptions,
                                                           0,
                                                           0));
    }

    private static String formatAction(DiffAction action) {
        return action.symbol() + " " + action.description();
    }

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
        var scaleAction = new DiffAction.ScaleUp("cluster",
                                                 org.pragmatica.aether.config.cluster.NodeRole.CORE,
                                                 previousCount,
                                                 request.coreCount());
        return applier.apply(List.of(scaleAction)).flatMap(_ -> storeScaledConfig(stored,
                                                                                  request.coreCount(),
                                                                                  newVersion))
                            .map(_ -> new ScaleClusterResponse(true,
                                                               previousCount,
                                                               request.coreCount(),
                                                               newVersion));
    }

    private static Promise<Object> validateScaleAsync(int coreCount, int coreMin, int coreMax) {
        if (coreCount <3) {return new ClusterConfigError.QuorumSafetyViolation(coreCount, 3).promise();}
        if (coreCount % 2 == 0) {return new ClusterConfigError.InvalidCoreCount(coreCount).promise();}
        if (coreCount <coreMin) {return new ClusterConfigError.QuorumSafetyViolation(coreCount, coreMin).promise();}
        if (coreCount > coreMax) {return new ClusterConfigError.InvalidCoreMax(coreMax, coreCount).promise();}
        return Promise.unitPromise().map(u -> (Object) u);
    }

    @SuppressWarnings("unchecked") private Promise<Object> storeScaledConfig(ClusterConfigValue stored,
                                                                             int newCoreCount,
                                                                             long newVersion) {
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

    private Promise<UpgradeResponse> handleUpgrade(UpgradeRequest request) {
        return lookupClusterConfig().flatMap(stored -> initiateUpgrade(stored, request));
    }

    private Promise<UpgradeResponse> initiateUpgrade(ClusterConfigValue stored, UpgradeRequest request) {
        var currentVersion = stored.version();
        var targetVersion = request.targetVersion();
        if (currentVersion.equals(targetVersion)) {return new UpgradeError.AlreadyAtVersion(targetVersion).promise();}
        log.info("Cluster upgrade initiated: {} -> {}",
                 currentVersion,
                 targetVersion);
        return storeUpgradedVersion(stored, targetVersion).map(_ -> new UpgradeResponse("INITIATED",
                                                                                        currentVersion,
                                                                                        targetVersion));
    }

    @SuppressWarnings("unchecked") private Promise<Object> storeUpgradedVersion(ClusterConfigValue stored,
                                                                                String targetVersion) {
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
