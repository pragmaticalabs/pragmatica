package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;

/// Root configuration for Aether cluster.
///
///
/// This is the unified configuration used by all Aether tools:
///
///   - aether-up: Reads config to generate deployment artifacts
///   - AetherNode: Reads config at runtime
///   - AetherCli: Reads connection info from config
///
///
///
/// Example aether.toml:
/// ```
/// [cluster]
/// environment = "docker"
/// nodes = 5
/// tls = false
///
/// [cluster.ports]
/// management = 8080
/// cluster = 8090
///
/// [node]
/// heap = "512m"
/// gc = "zgc"
/// ```
///
/// @param cluster    Cluster-level configuration
/// @param node       Per-node configuration
/// @param tls        TLS configuration (when cluster.tls = true)
/// @param docker     Docker-specific settings
/// @param kubernetes Kubernetes-specific settings
/// @param ttm        TTM (Tiny Time Mixers) predictive scaling configuration
/// @param slice      Slice loading and repository configuration
/// @param appHttp    Application HTTP server configuration
public record AetherConfig(ClusterConfig cluster,
                           NodeConfig node,
                           Option<TlsConfig> tls,
                           Option<DockerConfig> docker,
                           Option<KubernetesConfig> kubernetes,
                           TtmConfig ttm,
                           SliceConfig slice,
                           AppHttpConfig appHttp) {
    /// Factory method following JBCT naming convention.
    public static Result<AetherConfig> aetherConfig(ClusterConfig cluster,
                                                    NodeConfig node,
                                                    Option<TlsConfig> tls,
                                                    Option<DockerConfig> docker,
                                                    Option<KubernetesConfig> kubernetes,
                                                    TtmConfig ttm,
                                                    SliceConfig slice,
                                                    AppHttpConfig appHttp) {
        return success(new AetherConfig(cluster, node, tls, docker, kubernetes, ttm, slice, appHttp));
    }

    /// Create configuration with defaults for specified environment.
    @SuppressWarnings("JBCT-SEQ-01")
    public static AetherConfig aetherConfig(Environment env) {
        return aetherConfig(ClusterConfig.clusterConfig(env),
                            NodeConfig.nodeConfig(env),
                            tlsForEnvironment(env),
                            dockerForEnvironment(env),
                            kubernetesForEnvironment(env),
                            TtmConfig.ttmConfig(),
                            SliceConfig.sliceConfig(),
                            AppHttpConfig.appHttpConfig()).unwrap();
    }

    /// Create default configuration (Docker environment).
    public static AetherConfig aetherConfig() {
        return aetherConfig(Environment.DOCKER);
    }

    /// Get the environment from cluster config.
    public Environment environment() {
        return cluster.environment();
    }

    /// Check if TLS is enabled.
    public boolean tlsEnabled() {
        return cluster.tls();
    }

    /// Builder for fluent configuration.
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
            return AetherConfig.aetherConfig(clusterConfig,
                                             nodeConfig,
                                             finalTls,
                                             finalDocker,
                                             finalK8s,
                                             finalTtm,
                                             finalSlice,
                                             finalAppHttp)
                               .unwrap();
        }

        private ClusterConfig applyClusterOverrides(ClusterConfig base) {
            var withNodes = option(nodes).map(base::withNodes)
                                  .or(base);
            var withTls = option(tls).map(withNodes::withTls)
                                .or(withNodes);
            return option(ports).map(withTls::withPorts)
                         .or(withTls);
        }

        private NodeConfig applyNodeOverrides(NodeConfig base) {
            var withHeap = option(heap).map(base::withHeap)
                                 .or(base);
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
    }
}
