package org.pragmatica.aether.node;

import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.BackupConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.config.WorkerConfig;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.TimeoutsConfig;
import org.pragmatica.aether.config.TtmConfig;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.invoke.ObservabilityConfig;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.blueprint.DeploymentConfig;
import org.pragmatica.aether.slice.blueprint.DeploymentConfig.CanaryStageConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.topology.BackoffConfig;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.net.tcp.security.CertificateProvider;

import java.util.List;
import java.util.Map;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Configuration for an Aether cluster node.
///
/// @param topology            Cluster topology configuration
/// @param protocol            Consensus protocol configuration
/// @param sliceAction         Slice lifecycle configuration
/// @param sliceConfig         Slice repository configuration (types to create at runtime)
/// @param managementPort      Port for HTTP management API (0 to disable)
/// @param artifactRepo        DHT configuration for artifact repository (replication factor, 0 = full)
/// @param cache               DHT configuration for ephemeral cache (single replica by default)
/// @param tls                 TLS configuration for QUIC cluster transport and HTTP (empty for auto-generated self-signed)
/// @param ttm                 TTM (Tiny Time Mixers) predictive scaling configuration
/// @param rollback            Automatic rollback configuration
/// @param appHttp             Application HTTP server configuration for slice routes
/// @param controllerConfig    Controller configuration for scaling thresholds and behavior
/// @param configProvider      Configuration provider for resource provisioning (empty to disable)
/// @param environment         Environment integration for compute/secrets (empty to disable)
/// @param autoHeal            Auto-heal retry configuration
/// @param observability       Observability configuration (depth threshold, sampling target)
/// @param atomicity           Blueprint deployment atomicity mode (BEST_EFFORT or ALL_OR_NOTHING)
/// @param activationGated     If true, node waits for CDM activation instead of auto-activating
/// @param timeouts            Centralized timeout configuration for all subsystems
/// @param certificateProvider Certificate provider for mTLS and gossip encryption (empty to disable)
/// @param workerConfig              Worker configuration for worker-role nodes (empty for core-only nodes)
/// @param deploymentDefaults        Node-level deployment defaults (canary evaluation interval, default stages)
/// @param managementHttpProtocol    HTTP protocol for management server (H1, H3, BOTH) — default H1
/// @param storageConfig            Named hierarchical storage instance configurations (empty map for defaults)
/// @param backupConfig             Consensus state backup configuration (empty for in-memory only)
public record AetherNodeConfig(TopologyConfig topology,
                               ProtocolConfig protocol,
                               SliceActionConfig sliceAction,
                               SliceConfig sliceConfig,
                               int managementPort,
                               DHTConfig artifactRepo,
                               DHTConfig cache,
                               Option<TlsConfig> tls,
                               TtmConfig ttm,
                               RollbackConfig rollback,
                               AppHttpConfig appHttp,
                               ControllerConfig controllerConfig,
                               Option<ConfigurationProvider> configProvider,
                               Option<EnvironmentIntegration> environment,
                               AutoHealConfig autoHeal,
                               ObservabilityConfig observability,
                               DeploymentAtomicity atomicity,
                               boolean activationGated,
                               TimeoutsConfig timeouts,
                               Option<CertificateProvider> certificateProvider,
                               Option<WorkerConfig> workerConfig,
                               DeploymentDefaults deploymentDefaults,
                               HttpProtocol managementHttpProtocol,
                               Map<String, StorageConfig> storageConfig,
                               Option<BackupConfig> backupConfig,
                               StreamingConfig streaming) {
    public record DeploymentDefaults(long canaryEvaluationIntervalMs, List<CanaryStageConfig> defaultCanaryStages) {
        @SuppressWarnings("JBCT-VO-02") public static final DeploymentDefaults DEFAULT = new DeploymentDefaults(30_000,
                                                                                                                DeploymentConfig.defaultCanaryStages());
    }

    public static final int DEFAULT_MANAGEMENT_PORT = 8080;

    public static final int MANAGEMENT_DISABLED = 0;

    // --- Fluent builder ---

    public static SelfStage builder() {
        return self -> coreNodes -> managementPort -> sliceConfig -> artifactRepo -> coreMax
            -> appHttp -> tls -> certificateProvider -> configProvider -> environment
            -> managementHttpProtocol -> storageConfig -> backupConfig -> streaming
            -> protocol -> sliceAction -> cache -> ttm -> rollback -> controllerConfig
            -> autoHeal -> observability -> atomicity -> activationGated -> timeouts
            -> workerConfig -> deploymentDefaults -> {
            var effectiveClusterSize = coreMax > 0 ? coreMax : coreNodes.size();
            var topology = new TopologyConfig(self, effectiveClusterSize,
                                              timeSpan(5).seconds(), timeSpan(1).seconds(),
                                              TopologyConfig.DEFAULT_HELLO_TIMEOUT, coreNodes,
                                              Option.empty(), BackoffConfig.DEFAULT,
                                              coreMax, effectiveClusterSize);
            return new AetherNodeConfig(topology, protocol, sliceAction, sliceConfig,
                                        managementPort, artifactRepo, cache,
                                        tls, ttm, rollback, appHttp,
                                        controllerConfig, configProvider,
                                        environment, autoHeal, observability,
                                        atomicity, activationGated, timeouts,
                                        certificateProvider, workerConfig, deploymentDefaults,
                                        managementHttpProtocol, storageConfig,
                                        backupConfig, streaming);
        };
    }

    // Mandatory stages

    public interface SelfStage { CoreNodesStage self(NodeId self); }

    public interface CoreNodesStage { WithManagementPort coreNodes(List<NodeInfo> coreNodes); }

    // Optional stages — frequently customized

    public interface WithManagementPort {
        WithSliceConfig managementPort(int port);
        default AetherNodeConfig build() { return managementPort(DEFAULT_MANAGEMENT_PORT).build(); }
    }

    public interface WithSliceConfig {
        WithArtifactRepo sliceConfig(SliceConfig config);
        default AetherNodeConfig build() { return sliceConfig(SliceConfig.sliceConfig()).build(); }
    }

    public interface WithArtifactRepo {
        WithCoreMax artifactRepo(DHTConfig config);
        default AetherNodeConfig build() { return artifactRepo(DHTConfig.DEFAULT).build(); }
    }

    public interface WithCoreMax {
        WithAppHttp coreMax(int coreMax);
        default AetherNodeConfig build() { return coreMax(0).build(); }
    }

    public interface WithAppHttp {
        WithTls appHttp(AppHttpConfig config);
        default AetherNodeConfig build() { return appHttp(AppHttpConfig.appHttpConfig()).build(); }
    }

    public interface WithTls {
        WithCertificateProvider tls(Option<TlsConfig> config);
        default WithCertificateProvider tls(TlsConfig config) { return tls(Option.some(config)); }
        default AetherNodeConfig build() { return tls(Option.none()).build(); }
    }

    public interface WithCertificateProvider {
        WithConfigProvider certificateProvider(Option<CertificateProvider> provider);
        default WithConfigProvider certificateProvider(CertificateProvider provider) { return certificateProvider(Option.some(provider)); }
        default AetherNodeConfig build() { return certificateProvider(Option.none()).build(); }
    }

    public interface WithConfigProvider {
        WithEnvironment configProvider(Option<ConfigurationProvider> provider);
        default WithEnvironment configProvider(ConfigurationProvider provider) { return configProvider(Option.some(provider)); }
        default AetherNodeConfig build() { return configProvider(Option.none()).build(); }
    }

    public interface WithEnvironment {
        WithManagementHttpProtocol environment(Option<EnvironmentIntegration> env);
        default WithManagementHttpProtocol environment(EnvironmentIntegration env) { return environment(Option.some(env)); }
        default AetherNodeConfig build() { return environment(Option.none()).build(); }
    }

    public interface WithManagementHttpProtocol {
        WithStorageConfig managementHttpProtocol(HttpProtocol protocol);
        default AetherNodeConfig build() { return managementHttpProtocol(HttpProtocol.H1).build(); }
    }

    public interface WithStorageConfig {
        WithBackupConfig storageConfig(Map<String, StorageConfig> config);
        default AetherNodeConfig build() { return storageConfig(Map.of()).build(); }
    }

    public interface WithBackupConfig {
        WithStreaming backupConfig(Option<BackupConfig> config);
        default WithStreaming backupConfig(BackupConfig config) { return backupConfig(Option.some(config)); }
        default AetherNodeConfig build() { return backupConfig(Option.none()).build(); }
    }

    public interface WithStreaming {
        WithProtocol streaming(StreamingConfig config);
        default AetherNodeConfig build() { return streaming(StreamingConfig.streamingConfig()).build(); }
    }

    // Optional stages — rarely customized

    public interface WithProtocol {
        WithSliceAction protocol(ProtocolConfig config);
        default AetherNodeConfig build() { return protocol(ProtocolConfig.defaultConfig()).build(); }
    }

    public interface WithSliceAction {
        WithCache sliceAction(SliceActionConfig config);
        default AetherNodeConfig build() { return sliceAction(SliceActionConfig.sliceActionConfig()).build(); }
    }

    public interface WithCache {
        WithTtm cache(DHTConfig config);
        default AetherNodeConfig build() { return cache(DHTConfig.CACHE_DEFAULT).build(); }
    }

    public interface WithTtm {
        WithRollback ttm(TtmConfig config);
        default AetherNodeConfig build() { return ttm(TtmConfig.ttmConfig()).build(); }
    }

    public interface WithRollback {
        WithControllerConfig rollback(RollbackConfig config);
        default AetherNodeConfig build() { return rollback(RollbackConfig.rollbackConfig()).build(); }
    }

    public interface WithControllerConfig {
        WithAutoHeal controllerConfig(ControllerConfig config);
        default AetherNodeConfig build() { return controllerConfig(ControllerConfig.DEFAULT).build(); }
    }

    public interface WithAutoHeal {
        WithObservability autoHeal(AutoHealConfig config);
        default AetherNodeConfig build() { return autoHeal(AutoHealConfig.DEFAULT).build(); }
    }

    public interface WithObservability {
        WithAtomicity observability(ObservabilityConfig config);
        default AetherNodeConfig build() { return observability(ObservabilityConfig.DEFAULT).build(); }
    }

    public interface WithAtomicity {
        WithActivationGated atomicity(DeploymentAtomicity mode);
        default AetherNodeConfig build() { return atomicity(DeploymentAtomicity.ALL_OR_NOTHING).build(); }
    }

    public interface WithActivationGated {
        WithTimeouts activationGated(boolean gated);
        default AetherNodeConfig build() { return activationGated(false).build(); }
    }

    public interface WithTimeouts {
        WithWorkerConfig timeouts(TimeoutsConfig config);
        default AetherNodeConfig build() { return timeouts(TimeoutsConfig.timeoutsConfig()).build(); }
    }

    public interface WithWorkerConfig {
        WithDeploymentDefaults workerConfig(Option<WorkerConfig> config);
        default WithDeploymentDefaults workerConfig(WorkerConfig config) { return workerConfig(Option.some(config)); }
        default AetherNodeConfig build() { return workerConfig(Option.none()).build(); }
    }

    public interface WithDeploymentDefaults {
        AetherNodeConfig deploymentDefaults(DeploymentDefaults defaults);
        default AetherNodeConfig build() { return deploymentDefaults(DeploymentDefaults.DEFAULT); }
    }

    // Utility methods

    public NodeId self() {
        return topology.self();
    }

    public Result<Unit> validate() {
        if (managementPort < 0 || managementPort > 65535) {
            return Causes.cause("Invalid management port: " + managementPort).result();
        }
        if (managementPort != MANAGEMENT_DISABLED && topology.coreNodes().isEmpty()) {
            return Causes.cause("At least one core node required when management is enabled").result();
        }
        return Result.unitResult();
    }
}
