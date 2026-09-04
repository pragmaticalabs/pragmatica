// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.BackupConfig;
import org.pragmatica.aether.config.CommunitySizing;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.config.StorageEncryptionConfig;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.config.WorkerConfig;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.TimeoutsConfig;
import org.pragmatica.aether.config.TtmConfig;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.invoke.ObservabilityConfig;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.blueprint.DeploymentConfig;
import org.pragmatica.aether.slice.blueprint.DeploymentConfig.CanaryStageConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.ClusterFormationConfig;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.topology.BackoffConfig;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.net.tcp.security.CertificateProvider;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public record AetherNodeConfig(TopologyConfig topology,
                               ProtocolConfig protocol,
                               SliceActionConfig sliceAction,
                               SliceConfig sliceConfig,
                               int managementPort,
                               DHTConfig artifactRepo,
                               DHTConfig cache,
                               Option<TlsConfig> tls,
                               TlsConfig quicTls,
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
                               Option<MembershipConfig> membership,
                               StreamingConfig streaming,
                               ClusterFormationConfig clusterFormation,
                               Option<ClusterName> clusterName,
                               Option<StorageEncryptionConfig> storageEncryption) {
    /// Cluster-wide deployment defaults. `canaryEvaluationInterval` / `defaultCanaryStages` drive
    /// progressive rollout; `communitySizing` is the leader's per-community target size and viability
    /// floor (worker-membership-spec §3.3 / §4.1) read by the cluster deployment FSM. A test/dev
    /// deployment overrides `communitySizing` here to run small communities under the production
    /// default (target 100, floor 3).
    public record DeploymentDefaults(TimeSpan canaryEvaluationInterval,
                                     List<CanaryStageConfig> defaultCanaryStages,
                                     CommunitySizing communitySizing) {
        public static final DeploymentDefaults DEFAULT = new DeploymentDefaults(timeSpan(30).seconds(),
                                                                                DeploymentConfig.defaultCanaryStages(),
                                                                                CommunitySizing.DEFAULT);
    }

    public static final int DEFAULT_MANAGEMENT_PORT = 8080;
    public static final int MANAGEMENT_DISABLED = 0;

    public static SelfStage builder() {
        return self -> coreNodes -> managementPort -> sliceConfig -> artifactRepo -> coreMax -> appHttp -> tls -> quicTls -> certificateProvider -> configProvider -> environment -> managementHttpProtocol -> storageConfig -> backupConfig -> membership -> streaming -> protocol -> sliceAction -> cache -> ttm -> rollback -> controllerConfig -> autoHeal -> observability -> atomicity -> activationGated -> timeouts -> workerConfig -> deploymentDefaults -> clusterFormation -> {
            var effectiveClusterSize = coreMax > 0
                                       ? coreMax
                                       : coreNodes.size();
            var topology = new TopologyConfig(self,
                                              effectiveClusterSize,
                                              timeSpan(5).seconds(),
                                              timeSpan(1).seconds(),
                                              TopologyConfig.DEFAULT_HELLO_TIMEOUT,
                                              coreNodes,
                                              Option.empty(),
                                              BackoffConfig.DEFAULT,
                                              coreMax,
                                              effectiveClusterSize);

            return new AetherNodeConfig(topology,
                                        protocol,
                                        sliceAction,
                                        sliceConfig,
                                        managementPort,
                                        artifactRepo,
                                        cache,
                                        tls,
                                        quicTls,
                                        ttm,
                                        rollback,
                                        appHttp,
                                        controllerConfig,
                                        configProvider,
                                        environment,
                                        autoHeal,
                                        observability,
                                        atomicity,
                                        activationGated,
                                        timeouts,
                                        certificateProvider,
                                        workerConfig,
                                        deploymentDefaults,
                                        managementHttpProtocol,
                                        storageConfig,
                                        backupConfig,
                                        membership,
                                        streaming,
                                        clusterFormation,
                                        Option.empty(),
                                        Option.empty());
        };
    }

    /// #298 — this node's cluster identity. Absent in the builder because the authoritative source
    /// is the `AETHER_CLUSTER_NAME` environment variable, which `Main` already boot-gates
    /// (`enforceClusterNamePresent` aborts startup when it is missing or malformed) and validates
    /// against the lowercase-DNS-label `CLUSTER_NAME_PATTERN`. `Main` stamps it here so runtime
    /// components get it from config rather than re-reading the environment.
    ///
    /// This is the runtime `[cluster] name` that `Main.verifyClusterLabelConsistency` was written
    /// in anticipation of — that method's doc notes it was waiting on the field existing outside
    /// bootstrap config.
    ///
    /// Absent means "not stamped": an in-process harness (Ember/forge) that never goes through
    /// `Main`. The fleet cap declines to enforce rather than guess a scope in that case.
    /// #298 — replace the auto-heal config after construction. The builder is a STAGED chain and
    /// `autoHeal` sits six stages after `streaming`, where `Main` stops and lets `default build()`
    /// fill the remainder; reaching it mid-chain would force `Main` to supply six unrelated stages
    /// it has no opinion about. Same post-build shape as [#withClusterName].
    public AetherNodeConfig withAutoHeal(AutoHealConfig autoHeal) {
        return new AetherNodeConfig(topology,
                                    protocol,
                                    sliceAction,
                                    sliceConfig,
                                    managementPort,
                                    artifactRepo,
                                    cache,
                                    tls,
                                    quicTls,
                                    ttm,
                                    rollback,
                                    appHttp,
                                    controllerConfig,
                                    configProvider,
                                    environment,
                                    autoHeal,
                                    observability,
                                    atomicity,
                                    activationGated,
                                    timeouts,
                                    certificateProvider,
                                    workerConfig,
                                    deploymentDefaults,
                                    managementHttpProtocol,
                                    storageConfig,
                                    backupConfig,
                                    membership,
                                    streaming,
                                    clusterFormation,
                                    clusterName,
                                    storageEncryption);
    }

    public AetherNodeConfig withClusterName(Option<ClusterName> clusterName) {
        return new AetherNodeConfig(topology,
                                    protocol,
                                    sliceAction,
                                    sliceConfig,
                                    managementPort,
                                    artifactRepo,
                                    cache,
                                    tls,
                                    quicTls,
                                    ttm,
                                    rollback,
                                    appHttp,
                                    controllerConfig,
                                    configProvider,
                                    environment,
                                    autoHeal,
                                    observability,
                                    atomicity,
                                    activationGated,
                                    timeouts,
                                    certificateProvider,
                                    workerConfig,
                                    deploymentDefaults,
                                    managementHttpProtocol,
                                    storageConfig,
                                    backupConfig,
                                    membership,
                                    streaming,
                                    clusterFormation,
                                    clusterName,
                                    storageEncryption);
    }

    /// #253 — the `[storage.encryption]` keyring, when configured. Same post-build shape as
    /// [#withAutoHeal] / [#withClusterName]: optional and late-bound, so `Main` stamps it after
    /// `build()` rather than forcing every caller of the staged builder through a stage it has no
    /// opinion about. Defaults to [Option#empty()] in [#builder()] — encryption is opt-in.
    public AetherNodeConfig withStorageEncryption(Option<StorageEncryptionConfig> storageEncryption) {
        return new AetherNodeConfig(topology,
                                    protocol,
                                    sliceAction,
                                    sliceConfig,
                                    managementPort,
                                    artifactRepo,
                                    cache,
                                    tls,
                                    quicTls,
                                    ttm,
                                    rollback,
                                    appHttp,
                                    controllerConfig,
                                    configProvider,
                                    environment,
                                    autoHeal,
                                    observability,
                                    atomicity,
                                    activationGated,
                                    timeouts,
                                    certificateProvider,
                                    workerConfig,
                                    deploymentDefaults,
                                    managementHttpProtocol,
                                    storageConfig,
                                    backupConfig,
                                    membership,
                                    streaming,
                                    clusterFormation,
                                    clusterName,
                                    storageEncryption);
    }

    public interface SelfStage {
        CoreNodesStage self(NodeId self);
    }

    public interface CoreNodesStage {
        WithManagementPort coreNodes(List<NodeInfo> coreNodes);
    }

    public interface WithManagementPort {
        WithSliceConfig managementPort(int port);
    }

    public interface WithSliceConfig {
        WithArtifactRepo sliceConfig(SliceConfig config);
    }

    public interface WithArtifactRepo {
        WithCoreMax artifactRepo(DHTConfig config);
    }

    public interface WithCoreMax {
        WithAppHttp coreMax(int coreMax);
    }

    public interface WithAppHttp {
        WithTls appHttp(AppHttpConfig config);
    }

    public interface WithTls {
        WithQuicTls tls(Option<TlsConfig> config);

        default WithQuicTls tls(TlsConfig config) {
            return tls(Option.some(config));
        }
    }

    public interface WithQuicTls {
        WithCertificateProvider quicTls(TlsConfig config);
    }

    public interface WithCertificateProvider {
        WithConfigProvider certificateProvider(Option<CertificateProvider> provider);

        default WithConfigProvider certificateProvider(CertificateProvider provider) {
            return certificateProvider(Option.some(provider));
        }

        default AetherNodeConfig build() {
            return certificateProvider(Option.none()).build();
        }
    }

    public interface WithConfigProvider {
        WithEnvironment configProvider(Option<ConfigurationProvider> provider);

        default WithEnvironment configProvider(ConfigurationProvider provider) {
            return configProvider(Option.some(provider));
        }

        default AetherNodeConfig build() {
            return configProvider(Option.none()).build();
        }
    }

    public interface WithEnvironment {
        WithManagementHttpProtocol environment(Option<EnvironmentIntegration> env);

        default WithManagementHttpProtocol environment(EnvironmentIntegration env) {
            return environment(Option.some(env));
        }

        default AetherNodeConfig build() {
            return environment(Option.none()).build();
        }
    }

    public interface WithManagementHttpProtocol {
        WithStorageConfig managementHttpProtocol(HttpProtocol protocol);

        default AetherNodeConfig build() {
            return managementHttpProtocol(HttpProtocol.H1).build();
        }
    }

    public interface WithStorageConfig {
        WithBackupConfig storageConfig(Map<String, StorageConfig> config);

        default AetherNodeConfig build() {
            return storageConfig(Map.of()).build();
        }
    }

    public interface WithBackupConfig {
        WithMembership backupConfig(Option<BackupConfig> config);

        default WithMembership backupConfig(BackupConfig config) {
            return backupConfig(Option.some(config));
        }

        default AetherNodeConfig build() {
            return backupConfig(Option.none()).build();
        }
    }

    public interface WithMembership {
        WithStreaming membership(Option<MembershipConfig> config);

        default WithStreaming membership(MembershipConfig config) {
            return membership(Option.some(config));
        }

        default AetherNodeConfig build() {
            return membership(Option.none()).build();
        }
    }

    public interface WithStreaming {
        WithProtocol streaming(StreamingConfig config);

        default AetherNodeConfig build() {
            return streaming(StreamingConfig.streamingConfig()).build();
        }
    }

    public interface WithProtocol {
        WithSliceAction protocol(ProtocolConfig config);

        default AetherNodeConfig build() {
            return protocol(ProtocolConfig.defaultConfig()).build();
        }
    }

    public interface WithSliceAction {
        WithCache sliceAction(SliceActionConfig config);

        default AetherNodeConfig build() {
            return sliceAction(SliceActionConfig.sliceActionConfig()).build();
        }
    }

    public interface WithCache {
        WithTtm cache(DHTConfig config);

        default AetherNodeConfig build() {
            return cache(DHTConfig.CACHE_DEFAULT).build();
        }
    }

    public interface WithTtm {
        WithRollback ttm(TtmConfig config);

        default AetherNodeConfig build() {
            return ttm(TtmConfig.ttmConfig()).build();
        }
    }

    public interface WithRollback {
        WithControllerConfig rollback(RollbackConfig config);

        default AetherNodeConfig build() {
            return rollback(RollbackConfig.rollbackConfig()).build();
        }
    }

    public interface WithControllerConfig {
        WithAutoHeal controllerConfig(ControllerConfig config);

        default AetherNodeConfig build() {
            return controllerConfig(ControllerConfig.DEFAULT).build();
        }
    }

    public interface WithAutoHeal {
        WithObservability autoHeal(AutoHealConfig config);

        default AetherNodeConfig build() {
            return autoHeal(AutoHealConfig.DEFAULT).build();
        }
    }

    public interface WithObservability {
        WithAtomicity observability(ObservabilityConfig config);

        default AetherNodeConfig build() {
            return observability(ObservabilityConfig.DEFAULT).build();
        }
    }

    public interface WithAtomicity {
        WithActivationGated atomicity(DeploymentAtomicity mode);

        default AetherNodeConfig build() {
            return atomicity(DeploymentAtomicity.ALL_OR_NOTHING).build();
        }
    }

    public interface WithActivationGated {
        WithTimeouts activationGated(boolean gated);

        default AetherNodeConfig build() {
            return activationGated(false).build();
        }
    }

    public interface WithTimeouts {
        WithWorkerConfig timeouts(TimeoutsConfig config);

        default AetherNodeConfig build() {
            return timeouts(TimeoutsConfig.timeoutsConfig()).build();
        }
    }

    public interface WithWorkerConfig {
        WithDeploymentDefaults workerConfig(Option<WorkerConfig> config);

        default WithDeploymentDefaults workerConfig(WorkerConfig config) {
            return workerConfig(Option.some(config));
        }

        default AetherNodeConfig build() {
            return workerConfig(Option.none()).build();
        }
    }

    public interface WithDeploymentDefaults {
        WithClusterFormation deploymentDefaults(DeploymentDefaults defaults);

        default AetherNodeConfig build() {
            return deploymentDefaults(DeploymentDefaults.DEFAULT).build();
        }
    }

    public interface WithClusterFormation {
        AetherNodeConfig clusterFormation(ClusterFormationConfig config);

        default AetherNodeConfig build() {
            return clusterFormation(ClusterFormationConfig.defaults());
        }
    }

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
