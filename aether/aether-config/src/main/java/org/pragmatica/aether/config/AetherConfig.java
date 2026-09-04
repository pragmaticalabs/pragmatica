// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import java.util.Map;

import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


public record AetherConfig(ClusterConfig cluster,
                           NodeConfig node,
                           Option<TlsConfig> tls,
                           Option<DockerConfig> docker,
                           Option<KubernetesConfig> kubernetes,
                           TtmConfig ttm,
                           SliceConfig slice,
                           AppHttpConfig appHttp,
                           BackupConfig backup,
                           DhtReplicationConfig dhtReplication,
                           TimeoutsConfig timeouts,
                           Map<String, StorageConfig> storage,
                           Option<CloudConfig> cloud,
                           Map<String, EndpointConfig> endpoints,
                           StreamingConfig streaming,
                           Option<MembershipConfigBinding> membership,
                           Option<StorageEncryptionConfig> storageEncryption) {
    public static Result<AetherConfig> aetherConfig(ClusterConfig cluster,
                                                    NodeConfig node,
                                                    Option<TlsConfig> tls,
                                                    Option<DockerConfig> docker,
                                                    Option<KubernetesConfig> kubernetes,
                                                    TtmConfig ttm,
                                                    SliceConfig slice,
                                                    AppHttpConfig appHttp,
                                                    BackupConfig backup,
                                                    DhtReplicationConfig dhtReplication,
                                                    TimeoutsConfig timeouts) {
        return success(new AetherConfig(cluster,
                                        node,
                                        tls,
                                        docker,
                                        kubernetes,
                                        ttm,
                                        slice,
                                        appHttp,
                                        backup,
                                        dhtReplication,
                                        timeouts,
                                        Map.of(),
                                        none(),
                                        Map.of(),
                                        StreamingConfig.streamingConfig(),
                                        none(),
                                        none()));
    }

    @SuppressWarnings("JBCT-SEQ-01")
    public static AetherConfig aetherConfig(Environment env) {
        return aetherConfig(ClusterConfig.clusterConfig(env),
                            NodeConfig.nodeConfig(env),
                            tlsForEnvironment(env),
                            dockerForEnvironment(env),
                            kubernetesForEnvironment(env),
                            TtmConfig.ttmConfig(),
                            SliceConfig.sliceConfig(),
                            AppHttpConfig.appHttpConfig(),
                            BackupConfig.backupConfig(env),
                            DhtReplicationConfig.dhtReplicationConfig(),
                            TimeoutsConfig.timeoutsConfig()).unwrap();
    }

    public static AetherConfig aetherConfig() {
        return aetherConfig(Environment.DOCKER);
    }

    public Environment environment() {
        return cluster.environment();
    }

    public boolean tlsEnabled() {
        return cluster.tls();
    }

    public AetherConfig withStorage(Map<String, StorageConfig> storage) {
        return new AetherConfig(cluster,
                                node,
                                tls,
                                docker,
                                kubernetes,
                                ttm,
                                slice,
                                appHttp,
                                backup,
                                dhtReplication,
                                timeouts,
                                storage,
                                cloud,
                                endpoints,
                                streaming,
                                membership,
                                storageEncryption);
    }

    public AetherConfig withEndpoints(Map<String, EndpointConfig> endpoints) {
        return new AetherConfig(cluster,
                                node,
                                tls,
                                docker,
                                kubernetes,
                                ttm,
                                slice,
                                appHttp,
                                backup,
                                dhtReplication,
                                timeouts,
                                storage,
                                cloud,
                                endpoints,
                                streaming,
                                membership,
                                storageEncryption);
    }

    public AetherConfig withCloud(CloudConfig cloud) {
        return new AetherConfig(cluster,
                                node,
                                tls,
                                docker,
                                kubernetes,
                                ttm,
                                slice,
                                appHttp,
                                backup,
                                dhtReplication,
                                timeouts,
                                storage,
                                some(cloud),
                                endpoints,
                                streaming,
                                membership,
                                storageEncryption);
    }

    public AetherConfig withStreaming(StreamingConfig streaming) {
        return new AetherConfig(cluster,
                                node,
                                tls,
                                docker,
                                kubernetes,
                                ttm,
                                slice,
                                appHttp,
                                backup,
                                dhtReplication,
                                timeouts,
                                storage,
                                cloud,
                                endpoints,
                                streaming,
                                membership,
                                storageEncryption);
    }

    public AetherConfig withMembership(MembershipConfigBinding membership) {
        return new AetherConfig(cluster,
                                node,
                                tls,
                                docker,
                                kubernetes,
                                ttm,
                                slice,
                                appHttp,
                                backup,
                                dhtReplication,
                                timeouts,
                                storage,
                                cloud,
                                endpoints,
                                streaming,
                                some(membership),
                                storageEncryption);
    }

    public AetherConfig withStorageEncryption(StorageEncryptionConfig storageEncryption) {
        return new AetherConfig(cluster,
                                node,
                                tls,
                                docker,
                                kubernetes,
                                ttm,
                                slice,
                                appHttp,
                                backup,
                                dhtReplication,
                                timeouts,
                                storage,
                                cloud,
                                endpoints,
                                streaming,
                                membership,
                                some(storageEncryption));
    }

    public static Builder builder() {
        return new Builder();
    }

    private static Option<TlsConfig> tlsForEnvironment(Environment env) {
        return env.defaultTls()
               ? some(TlsConfig.tlsConfig())
               : none();
    }

    private static Option<DockerConfig> dockerForEnvironment(Environment env) {
        return env == Environment.DOCKER
               ? some(DockerConfig.dockerConfig())
               : none();
    }

    private static Option<KubernetesConfig> kubernetesForEnvironment(Environment env) {
        return env == Environment.KUBERNETES
               ? some(KubernetesConfig.kubernetesConfig())
               : none();
    }

    public static class Builder {
        private Environment environment = Environment.DOCKER;
        private Integer nodes;
        private Boolean tls;
        private String heap;
        private String gc;
        private PortsConfig ports;
        private TlsConfig tlsConfig;
        private DockerConfig dockerConfig;
        private KubernetesConfig kubernetesConfig;
        private TtmConfig ttmConfig;
        private SliceConfig sliceConfig;
        private AppHttpConfig appHttpConfig;
        private BackupConfig backupConfig;
        private DhtReplicationConfig dhtReplicationConfig;
        private TimeoutsConfig timeoutsConfig;
        private Integer coreMax;
        private Integer maxNodes;
        private CloudConfig cloudConfig;
        private Map<String, StorageConfig> storageConfig;
        private Map<String, EndpointConfig> endpointsConfig;
        private StreamingConfig streamingConfig;
        private MembershipConfigBinding membershipConfig;
        private StorageEncryptionConfig storageEncryptionConfig;

        @SuppressWarnings("JBCT-NAM-01")
        public Builder withEnvironment(Environment environment) {
            this.environment = environment;

            return this;
        }

        public Builder nodes(int nodes) {
            this.nodes = nodes;

            return this;
        }

        public Builder tls(boolean tls) {
            this.tls = tls;

            return this;
        }

        public Builder heap(String heap) {
            this.heap = heap;

            return this;
        }

        public Builder gc(String gc) {
            this.gc = gc;

            return this;
        }

        public Builder ports(PortsConfig ports) {
            this.ports = ports;

            return this;
        }

        public Builder tlsConfig(TlsConfig tlsConfig) {
            this.tlsConfig = tlsConfig;

            return this;
        }

        public Builder dockerConfig(DockerConfig dockerConfig) {
            this.dockerConfig = dockerConfig;

            return this;
        }

        public Builder kubernetesConfig(KubernetesConfig kubernetesConfig) {
            this.kubernetesConfig = kubernetesConfig;

            return this;
        }

        public Builder ttm(TtmConfig ttmConfig) {
            this.ttmConfig = ttmConfig;

            return this;
        }

        public Builder sliceConfig(SliceConfig sliceConfig) {
            this.sliceConfig = sliceConfig;

            return this;
        }

        public Builder appHttp(AppHttpConfig appHttpConfig) {
            this.appHttpConfig = appHttpConfig;

            return this;
        }

        public Builder backup(BackupConfig backupConfig) {
            this.backupConfig = backupConfig;

            return this;
        }

        public Builder dhtReplication(DhtReplicationConfig dhtReplicationConfig) {
            this.dhtReplicationConfig = dhtReplicationConfig;

            return this;
        }

        public Builder timeouts(TimeoutsConfig timeoutsConfig) {
            this.timeoutsConfig = timeoutsConfig;

            return this;
        }

        public Builder coreMax(int coreMax) {
            this.coreMax = coreMax;

            return this;
        }

        /// #298 — cluster-wide provisioning ceiling. Absent leaves [ClusterConfig#UNBOUNDED].
        public Builder maxNodes(int maxNodes) {
            this.maxNodes = maxNodes;

            return this;
        }

        public Builder cloud(CloudConfig cloudConfig) {
            this.cloudConfig = cloudConfig;

            return this;
        }

        public Builder storage(Map<String, StorageConfig> storageConfig) {
            this.storageConfig = storageConfig;

            return this;
        }

        public Builder endpoints(Map<String, EndpointConfig> endpointsConfig) {
            this.endpointsConfig = endpointsConfig;

            return this;
        }

        public Builder streaming(StreamingConfig streamingConfig) {
            this.streamingConfig = streamingConfig;

            return this;
        }

        public Builder membership(MembershipConfigBinding membershipConfig) {
            this.membershipConfig = membershipConfig;

            return this;
        }

        public Builder storageEncryption(StorageEncryptionConfig storageEncryptionConfig) {
            this.storageEncryptionConfig = storageEncryptionConfig;

            return this;
        }

        public AetherConfig build() {
            var base = AetherConfig.aetherConfig(environment);
            var clusterConfig = applyClusterOverrides(base.cluster());
            var nodeConfig = applyNodeOverrides(base.node());
            var finalTls = tlsFor(clusterConfig);
            var finalDocker = dockerFor();
            var finalK8s = kubernetesFor();
            var finalTtm = ttmFor();
            var finalSlice = sliceFor();
            var finalAppHttp = appHttpFor();
            var finalBackup = backupFor();
            var finalDhtReplication = dhtReplicationFor();
            var finalTimeouts = timeoutsFor();
            var config = AetherConfig.aetherConfig(clusterConfig,
                                                   nodeConfig,
                                                   finalTls,
                                                   finalDocker,
                                                   finalK8s,
                                                   finalTtm,
                                                   finalSlice,
                                                   finalAppHttp,
                                                   finalBackup,
                                                   finalDhtReplication,
                                                   finalTimeouts).unwrap();
            var finalStorage = storageFor();
            var withStorage = finalStorage.isEmpty()
                              ? config
                              : config.withStorage(finalStorage);
            var finalEndpoints = endpointsFor();
            var withEp = finalEndpoints.isEmpty()
                         ? withStorage
                         : withStorage.withEndpoints(finalEndpoints);
            var withStreaming = option(streamingConfig).map(withEp::withStreaming).or(withEp);
            var withCloudConfig = option(cloudConfig).fold(() -> withStreaming, withStreaming::withCloud);
            var withMembership = option(membershipConfig).fold(() -> withCloudConfig, withCloudConfig::withMembership);

            return option(storageEncryptionConfig).fold(() -> withMembership, withMembership::withStorageEncryption);
        }

        private ClusterConfig applyClusterOverrides(ClusterConfig base) {
            var withNodes = option(nodes).map(base::withNodes).or(base);
            var withTls = option(tls).map(withNodes::withTls).or(withNodes);
            var withPorts = option(ports).map(withTls::withPorts).or(withTls);
            var withCoreMax = option(coreMax).map(withPorts::withCoreMax).or(withPorts);

            return option(maxNodes).map(withCoreMax::withMaxNodes)
                         .or(withCoreMax);
        }

        private NodeConfig applyNodeOverrides(NodeConfig base) {
            var withHeap = option(heap).map(base::withHeap).or(base);

            return option(gc).map(withHeap::withGc)
                         .or(withHeap);
        }

        private Option<TlsConfig> tlsFor(ClusterConfig clusterCfg) {
            return option(tlsConfig).fold(() -> defaultTlsFor(clusterCfg), Option::some);
        }

        private static Option<TlsConfig> defaultTlsFor(ClusterConfig clusterCfg) {
            return clusterCfg.tls()
                   ? some(TlsConfig.tlsConfig())
                   : none();
        }

        private Option<DockerConfig> dockerFor() {
            return option(dockerConfig).fold(() -> dockerForEnvironment(environment), Option::some);
        }

        private Option<KubernetesConfig> kubernetesFor() {
            return option(kubernetesConfig).fold(() -> kubernetesForEnvironment(environment), Option::some);
        }

        private TtmConfig ttmFor() {
            return option(ttmConfig).or(TtmConfig.ttmConfig());
        }

        private SliceConfig sliceFor() {
            return option(sliceConfig).or(SliceConfig.sliceConfig());
        }

        private AppHttpConfig appHttpFor() {
            return option(appHttpConfig).or(AppHttpConfig.appHttpConfig());
        }

        private BackupConfig backupFor() {
            return option(backupConfig).or(BackupConfig.backupConfig(environment));
        }

        private DhtReplicationConfig dhtReplicationFor() {
            return option(dhtReplicationConfig).or(DhtReplicationConfig.dhtReplicationConfig());
        }

        private TimeoutsConfig timeoutsFor() {
            return option(timeoutsConfig).or(TimeoutsConfig.timeoutsConfig());
        }

        private Map<String, StorageConfig> storageFor() {
            return option(storageConfig).or(Map.of());
        }

        private Map<String, EndpointConfig> endpointsFor() {
            return option(endpointsConfig).or(Map.of());
        }
    }
}
