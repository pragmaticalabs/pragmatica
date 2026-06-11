// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.BackupConfig;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.MembershipConfigBinding;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.aether.config.Environment;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.TlsConfig;
import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.labels.ContainerLabelInspector;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.SelfSignedCertificateProvider;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;


@SuppressWarnings("JBCT-RET-01")
public record Main(String[] args) {
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final int DEFAULT_CLUSTER_PORT = 8090;

    public static void main(String[] args) {
        new Main(args).run();
    }

    private record TlsBundle(org.pragmatica.net.tcp.TlsConfig tls, CertificateProvider provider) {}

    private void run() {
        var aetherConfig = loadConfig();

        verifyClusterLabelConsistency(aetherConfig);
        enforceClusterNamePresent();
        enforceDevModeCompatibility(aetherConfig);
        var nodeId = parseNodeId(aetherConfig);
        var port = parsePort(aetherConfig);
        var managementPort = parseManagementPort(aetherConfig);
        var nodeLabels = collectNodeLabels();
        var peers = parsePeers(nodeId, port, nodeLabels, aetherConfig);
        var sliceConfig = parseSliceConfig(aetherConfig);
        var dhtConfig = parseDhtConfig(aetherConfig);

        logStartupInfo(nodeId, port, managementPort, peers, aetherConfig, sliceConfig);
        var coreMax = parseCoreMax(aetherConfig);
        var tlsBundle = resolveTls(nodeId, peers, aetherConfig).expect("Failed to resolve TLS configuration at node startup");
        var appHttpTls = aetherConfig.filter(AetherConfig::tlsEnabled).map(_ -> tlsBundle.tls());
        var config = AetherNodeConfig.builder().self(nodeId).coreNodes(peers).managementPort(managementPort).sliceConfig(sliceConfig).artifactRepo(dhtConfig).coreMax(coreMax).appHttp(resolveAppHttp(aetherConfig)).tls(appHttpTls).quicTls(tlsBundle.tls()).certificateProvider(tlsBundle.provider()).configProvider(resolveConfigProvider()).environment(resolveEnvironment(aetherConfig)).managementHttpProtocol(resolveManagementHttpProtocol(aetherConfig)).storageConfig(resolveStorage(aetherConfig)).backupConfig(resolveBackup(aetherConfig)).membership(resolveMembership(aetherConfig)).streaming(resolveStreaming(aetherConfig)).build();
        var node = AetherNode.aetherNode(config).expect("Failed to initialize Aether node at startup");

        registerShutdownHook(node);
        startNodeAndWait(node, nodeId);
    }

    private static AppHttpConfig resolveAppHttp(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(AetherConfig::appHttp)
                           .filter(AppHttpConfig::enabled)
                           .or(AppHttpConfig.appHttpConfig());
    }

    /// First-boot consistency gate: compares this container's `aether.cluster` label
    /// (read from the Docker daemon via `/var/run/docker.sock`) against the configured
    /// cluster name (sourced from `AETHER_CLUSTER_NAME` env). On mismatch, aborts startup
    /// with `System.exit(1)` so a misconfigured node never joins the wrong cluster — the
    /// failure surfaces immediately at deployment time, not as a silent membership drift.
    ///
    /// Skipped silently when not running in Docker, when the socket isn't mounted, or
    /// when either side of the comparison is empty (we don't claim disagreement against
    /// missing information). The `aetherConfig` parameter is retained so this hook can
    /// trivially extend to read `[cluster] name` from local TOML once that field exists
    /// on runtime `ClusterConfig` (currently bootstrap-only).
    @SuppressWarnings("unused")
    private void verifyClusterLabelConsistency(Option<AetherConfig> aetherConfig) {
        var configured = Option.option(System.getenv("AETHER_CLUSTER_NAME")).filter(s -> !s.isBlank()).or("");

        if (configured.isEmpty()) {
            log.debug("verifyClusterLabelConsistency: AETHER_CLUSTER_NAME not set — skipping label check");

            return;
        }

        ContainerLabelInspector.inspectSelfLabels().onPresent(labels -> ContainerLabelInspector.compareWithConfigured(configured,
                                                                                                                      labels).onSuccess(_ -> log.info("Cluster label consistency OK: aether.cluster='{}' matches AETHER_CLUSTER_NAME",
                                                                                                                                                      configured))
                                                                                                                     .onFailure(cause -> {
                                                                                                                                    log.error("FATAL: {}",
                                                                                                                                              cause.message());
                                                                                                                                    System.exit(1);
                                                                                                                                }));
    }

    /// Cluster-name format: same shape as the CLI's `InputValidators.CLUSTER_NAME_PATTERN`
    /// (lowercase DNS-label). Inlined here because the `node` module does not depend on `cli`.
    private static final Pattern CLUSTER_NAME_PATTERN = Pattern.compile("^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$");

    private static final Cause MISSING_CLUSTER_NAME = Causes.cause("AETHER_CLUSTER_NAME is not set. A running node must know its cluster name. "
                                                                  + "Set the AETHER_CLUSTER_NAME environment variable (or bootstrap-seed it) before start.");

    private static final Cause DEV_MODE_WITH_REAL_TLS = Causes.cause("AETHER_INSECURE_DEV_MODE refused — operator TLS certificates are configured "
                                                                    + "([tls] auto_generate=false with cert/key paths). Insecure dev-mode is "
                                                                    + "fundamentally incompatible with a production TLS deployment.");

    /// Source the TLS config from the SAME place [#resolveTls] uses, so the dev-mode guard
    /// and the actual TLS setup never disagree about what certificates are configured.
    private static TlsConfig resolveTlsConfig(Option<AetherConfig> aetherConfig) {
        return aetherConfig.flatMap(AetherConfig::tls)
                           .or(TlsConfig.tlsConfig());
    }

    /// Boot gate: a running node must know its cluster name. Reads `AETHER_CLUSTER_NAME`,
    /// delegates to the pure [#verifyClusterNamePresent] guard, and on failure logs + exits
    /// (mirrors the existing `System.exit(1)` pattern). The KV `ClusterConfigValue` is only
    /// available post-join, so this start-time gate keys on the env var, never blocking on KV.
    @Contract
    private void enforceClusterNamePresent() {
        verifyClusterNamePresent(System.getenv("AETHER_CLUSTER_NAME")).onFailure(this::abortBoot);
    }

    /// Pure, unit-testable guard: present + format-valid cluster name → success; missing,
    /// blank, or malformed → failure. No side effects (no exit, no env read).
    static Result<Unit> verifyClusterNamePresent(String clusterNameEnv) {
        return Option.option(clusterNameEnv)
                     .map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .toResult(MISSING_CLUSTER_NAME)
                     .filter(MALFORMED_CLUSTER_NAME,
                             name -> CLUSTER_NAME_PATTERN.matcher(name).matches())
                     .mapToUnit();
    }

    private static final Fn1<Cause, String> MALFORMED_CLUSTER_NAME = Causes.forOneValue("AETHER_CLUSTER_NAME '%s' is malformed — must match "
                                                                                       + "[a-z]([a-z0-9-]{0,61}[a-z0-9])? (lowercase DNS label, 1-63 chars, e.g. a or prod-eu).");

    /// Boot gate: insecure dev-mode must be fundamentally incompatible with a real
    /// (operator-supplied) TLS deployment. Reads the dev-mode env and the resolved TLS
    /// config, delegates to the pure [#verifyDevModeCompatibility] guard, exits on violation.
    @Contract
    private void enforceDevModeCompatibility(Option<AetherConfig> aetherConfig) {
        var devMode = Option.option(System.getenv("AETHER_INSECURE_DEV_MODE")).map(v -> v.equalsIgnoreCase("true")).or(false);

        verifyDevModeCompatibility(devMode, resolveTlsConfig(aetherConfig)).onFailure(this::abortBoot);
    }

    /// Pure, unit-testable guard: dev-mode ON together with real operator certificates
    /// ([TlsConfig#hasProvidedCertificates]) → failure. Auto-generated certs (Hetzner-shaped
    /// `auto_generate=true`/no cert paths) or dev-mode off → success.
    static Result<Unit> verifyDevModeCompatibility(boolean devModeOn, TlsConfig tlsConfig) {
        return devModeOn && tlsConfig.hasProvidedCertificates()
               ? DEV_MODE_WITH_REAL_TLS.result()
               : Result.unitResult();
    }

    @Contract
    private void abortBoot(Cause cause) {
        log.error("FATAL: {}", cause.message());
        System.exit(1);
    }

    private Result<TlsBundle> resolveTls(NodeId nodeId, List<NodeInfo> peers, Option<AetherConfig> aetherConfig) {
        var tlsCfg = resolveTlsConfig(aetherConfig);

        return resolveClusterSecret(tlsCfg).flatMap(SelfSignedCertificateProvider::selfSignedCertificateProvider)
                                   .flatMap(provider -> {
                                                var hostname = findHostnameFromPeers(nodeId, peers);

                                                return org.pragmatica.net.tcp.TlsConfig.fromProvider(provider,
                                                                                                     nodeId.id(),
                                                                                                     hostname)
                                                                                       .map(tc -> new TlsBundle(tc,
                                                                                                                provider));
                                            })
                                   .onFailure(cause -> log.error("Failed to setup TLS: {}",
                                                                 cause.message()));
    }

    private Option<ConfigurationProvider> resolveConfigProvider() {
        return findArg("--config=").map(Path::of)
                      .filter(p -> p.toFile()
                                    .exists())
                      .map(this::buildConfigProvider);
    }

    private Option<EnvironmentIntegration> resolveEnvironment(Option<AetherConfig> aetherConfig) {
        return aetherConfig.flatMap(AetherConfig::cloud)
                           .flatMap(cloudConfig -> EnvironmentIntegrationFactory.createFromConfig(cloudConfig)
                                                                                .onFailure(cause -> log.error("Failed to create cloud environment: {}",
                                                                                                              cause.message()))
                                                                                .option());
    }

    private static HttpProtocol resolveManagementHttpProtocol(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(cfg -> cfg.cluster()
                                          .ports()
                                          .managementHttpProtocol())
                           .or(HttpProtocol.H1);
    }

    private static Map<String, StorageConfig> resolveStorage(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(AetherConfig::storage)
                           .filter(m -> !m.isEmpty())
                           .or(Map.of());
    }

    private static Option<BackupConfig> resolveBackup(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(AetherConfig::backup)
                           .filter(BackupConfig::enabled)
                           .filter(b -> !b.path()
                                          .isBlank());
    }

    private static StreamingConfig resolveStreaming(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(AetherConfig::streaming)
                           .or(StreamingConfig.streamingConfig());
    }

    /// Membership v2 — Stage 6 wiring. Lifts the optional aether-config-side
    /// [`MembershipConfigBinding`] into the aether-deployment-side
    /// [`MembershipConfig`]. Absent → `none()` so [`AetherNodeConfig`] sees the same
    /// "no `[membership]` section, defaults will be applied at use sites" signal it sees
    /// today, preserving default-OFF behaviour exactly.
    private static Option<MembershipConfig> resolveMembership(Option<AetherConfig> aetherConfig) {
        return aetherConfig.flatMap(AetherConfig::membership)
                           .map(Main::liftMembershipBinding);
    }

    private static MembershipConfig liftMembershipBinding(MembershipConfigBinding binding) {
        return new MembershipConfig(binding.splitTimeout());
    }

    private ConfigurationProvider buildConfigProvider(Path configPath) {
        var builder = ConfigurationProvider.builder();

        builder.withTomlFile(configPath);
        builder.withSystemProperties("aether.");
        builder.withEnvironment("AETHER_");

        return builder.build();
    }

    private static String findHostnameFromPeers(NodeId nodeId, List<NodeInfo> peers) {
        return Option.from(peers.stream().filter(n -> n.id()
                                                       .equals(nodeId)).findFirst())
                     .map(n -> n.address()
                                .host())
                     .or("localhost");
    }

    private static Result<byte[]> resolveClusterSecret(TlsConfig tlsCfg) {
        return Option.option(tlsCfg.clusterSecret())
                     .filter(s -> !s.isBlank())
                     .orElse(Option.option(System.getenv("AETHER_CLUSTER_SECRET")).filter(s -> !s.isBlank()))
                     .map(s -> s.getBytes(StandardCharsets.UTF_8))
                     .toResult(MISSING_CLUSTER_SECRET);
    }

    private static final Cause MISSING_CLUSTER_SECRET = Causes.cause("No cluster secret configured. Set 'cluster_secret' in [tls] section "
                                                                    + "or AETHER_CLUSTER_SECRET environment variable. "
                                                                    + "A cluster secret is required for TLS certificate generation.");

    private SliceConfig parseSliceConfig(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(AetherConfig::slice)
                           .or(SliceConfig.sliceConfig());
    }

    private org.pragmatica.dht.DHTConfig parseDhtConfig(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(AetherConfig::dhtReplication)
                           .map(dhtRepl -> org.pragmatica.dht.DHTConfig.withReplication(dhtRepl.targetRf()))
                           .flatMap(Result::option)
                           .or(org.pragmatica.dht.DHTConfig.DEFAULT);
    }

    private Option<AetherConfig> loadConfig() {
        return findArg("--config=").map(Path::of)
                      .filter(p -> p.toFile()
                                    .exists())
                      .flatMap(this::loadConfigFile);
    }

    private Option<AetherConfig> loadConfigFile(Path path) {
        return ConfigLoader.load(path)
                           .onFailure(cause -> log.error("Failed to load config: {}",
                                                         cause.message()))
                           .option();
    }

    private void logStartupInfo(NodeId nodeId,
                                int port,
                                int managementPort,
                                List<NodeInfo> peers,
                                Option<AetherConfig> aetherConfig,
                                SliceConfig sliceConfig) {
        log.info("Starting Aether node {} on port {}", nodeId, port);
        log.info("Management API on port {}", managementPort);
        log.info("Peers: {}", peers);
        log.info("Slice repositories: {}", sliceConfig.repositories());
        aetherConfig.onPresent(this::logConfigDetails);
    }

    private void logConfigDetails(AetherConfig cfg) {
        log.info("Config: environment={}, nodes={}, heap={}",
                 cfg.environment().displayName(),
                 cfg.cluster().nodes(),
                 cfg.node().heap());
    }

    private void registerShutdownHook(AetherNode node) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdownNode(node)));
    }

    private void shutdownNode(AetherNode node) {
        log.info("Shutdown requested, stopping node...");
        node.stop().await();
        log.info("Node stopped");
    }

    private void startNodeAndWait(AetherNode node, NodeId nodeId) {
        node.start().onSuccess(_ -> log.info("Node {} is running. Press Ctrl+C to stop.", nodeId)).onFailure(cause -> exitWithError(cause.message())).await();
        waitForInterrupt();
    }

    private void exitWithError(String message) {
        log.error("Failed to start node: {}", message);
        System.exit(1);
    }

    private void waitForInterrupt() {
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final Cause MISSING_NODE_ID = Causes.cause("No node id: set --node-id=<id>, or env AETHER_NODE_ID / NODE_ID. "
                                                             + "A clustered node requires a stable, explicit identity.");

    /// Boot gate: a clustered node MUST have an explicit, externally-assigned, stable id.
    /// Threads the explicit sources (`--node-id=`, env `AETHER_NODE_ID`, env `NODE_ID`) via
    /// the existing arg/env helpers into the pure [#resolveNodeId] guard, aborting boot when
    /// none is present or valid. No `HOSTNAME` / random fallback — a self-minted id changes
    /// across restarts and won't match how peers address this node in PEERS (SWIM/consensus
    /// split identity).
    private NodeId parseNodeId(Option<AetherConfig> aetherConfig) {
        return resolveNodeId(findArg("--node-id="),
                             findEnv("AETHER_NODE_ID").or(""),
                             findEnv("NODE_ID").or("")).onFailure(this::abortBoot)
                            .expect("Failed to resolve node id at startup");
    }

    /// Pure, unit-testable guard: resolves the node id from the explicit chain
    /// (`--node-id=` arg → `AETHER_NODE_ID` env → `NODE_ID` env), first present-and-valid
    /// wins. Absence/invalidity of all three → failure. No side effects (no exit, no env read).
    static Result<NodeId> resolveNodeId(Option<String> argId, String aetherNodeIdEnv, String nodeIdEnv) {
        return argId.toResult(MISSING_NODE_ID)
                    .flatMap(NodeId::nodeId)
                    .orElse(() -> NodeId.nodeId(aetherNodeIdEnv))
                    .orElse(() -> NodeId.nodeId(nodeIdEnv))
                    .mapError(_ -> MISSING_NODE_ID);
    }

    private int parsePort(Option<AetherConfig> aetherConfig) {
        return findArg("--port=").map(Integer::parseInt)
                      .orElse(findEnv("CLUSTER_PORT").map(Integer::parseInt))
                      .or(() -> portFromConfig(aetherConfig));
    }

    private int portFromConfig(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(cfg -> cfg.cluster()
                                          .ports()
                                          .cluster())
                           .or(DEFAULT_CLUSTER_PORT);
    }

    private int parseManagementPort(Option<AetherConfig> aetherConfig) {
        return findArg("--management-port=").map(Integer::parseInt)
                      .orElse(findEnv("MANAGEMENT_PORT").map(Integer::parseInt))
                      .or(() -> managementPortFromConfig(aetherConfig));
    }

    private int parseCoreMax(Option<AetherConfig> aetherConfig) {
        return findEnv("CORE_MAX").flatMap(s -> Result.lift(() -> Integer.parseInt(s)).option())
                      .orElse(aetherConfig.map(cfg -> cfg.cluster()
                                                         .coreMax()))
                      .or(0);
    }

    private int managementPortFromConfig(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(cfg -> cfg.cluster()
                                          .ports()
                                          .management())
                           .or(AetherNodeConfig.DEFAULT_MANAGEMENT_PORT);
    }

    private List<NodeInfo> parsePeers(NodeId self,
                                      int selfPort,
                                      Map<String, String> labels,
                                      Option<AetherConfig> aetherConfig) {
        var selfHost = resolveHostname();
        var selfInfo = NodeInfo.nodeInfo(self,
                                         nodeAddress(selfHost, selfPort).expect("self host is a valid node address"),
                                         labels);

        return findArg("--peers=").map(peersStr -> parsePeersFromString(peersStr, self, selfInfo))
                      .orElse(findEnv("CLUSTER_PEERS").map(peersStr -> parsePeersFromString(peersStr, self, selfInfo)))
                      .orElse(aetherConfig.map(this::generatePeersFromConfig))
                      .or(() -> List.of(selfInfo));
    }

    private List<NodeInfo> generatePeersFromConfig(AetherConfig aetherConfig) {
        var nodes = aetherConfig.cluster().nodes();
        var clusterPort = aetherConfig.cluster().ports().cluster();
        var env = aetherConfig.environment();

        return IntStream.range(0, nodes)
                        .mapToObj(i -> createNodeInfoForIndex(i, clusterPort, env))
                        .toList();
    }

    private NodeInfo createNodeInfoForIndex(int index, int clusterPort, Environment env) {
        var host = env == Environment.DOCKER
                   ? "aether-node-" + index
                   : "localhost";
        var port = clusterPort + (env == Environment.LOCAL
                                  ? index
                                  : 0);

        return NodeInfo.nodeInfo(NodeId.nodeId("node-" + index).expect("generated node id must be valid"),
                                 nodeAddress(host, port).expect("generated node address must be valid"));
    }

    private List<NodeInfo> parsePeersFromString(String peersStr, NodeId self, NodeInfo selfInfo) {
        var peers = Arrays.stream(peersStr.split(",")).map(String::trim).filter(s -> !s.isEmpty()).flatMap(peerStr -> parsePeerAddress(peerStr).stream()).toList();

        return ensureSelfIncluded(peers, self, selfInfo);
    }

    private List<NodeInfo> ensureSelfIncluded(List<NodeInfo> peers, NodeId self, NodeInfo selfInfo) {
        var selfMissing = peers.stream().noneMatch(p -> p.id()
                                                         .equals(self));

        if (selfMissing) {
            var allPeers = new ArrayList<>(peers);

            allPeers.add(selfInfo);

            return List.copyOf(allPeers);
        }

        return peers;
    }

    private Option<NodeInfo> parsePeerAddress(String peerStr) {
        var parts = peerStr.split(":");

        return switch (parts.length) {
            case 2 -> parseHostPortPeer(parts);
            case 3 -> parseIdHostPortPeer(parts);
            default -> logInvalidPeerFormat(peerStr);
        };
    }

    private Option<NodeInfo> parseHostPortPeer(String[] parts) {
        var host = parts[0];
        var port = Integer.parseInt(parts[1]);
        var nodeId = NodeId.nodeId("node-" + host + "-" + port).expect("generated node id must be valid");

        return nodeAddress(host, port).map(addr -> NodeInfo.nodeInfo(nodeId, addr))
                          .option();
    }

    private Option<NodeInfo> parseIdHostPortPeer(String[] parts) {
        var host = parts[1];
        var port = Integer.parseInt(parts[2]);

        return NodeId.nodeId(parts[0])
                     .flatMap(nodeId -> nodeAddress(host, port).map(addr -> NodeInfo.nodeInfo(nodeId, addr)))
                     .option();
    }

    private Option<NodeInfo> logInvalidPeerFormat(String peerStr) {
        log.warn("Invalid peer format: {}. Expected host:port or nodeId:host:port", peerStr);

        return Option.none();
    }

    private Map<String, String> collectNodeLabels() {
        return collectNodeLabels(resolveHostname(), this::findEnv);
    }

    /// Pure env-to-label mapping seam: hostname is always present; zone, instance-type,
    /// pool, source and role ride only when their env var is present. Extracted as a static
    /// function so the env-to-label contract is testable without a live node boot.
    static Map<String, String> collectNodeLabels(String hostname, Fn1<Option<String>, String> envLookup) {
        var labels = new HashMap<String, String>();

        labels.put(NodeInfo.LABEL_HOSTNAME, hostname);
        envLookup.apply("AETHER_ZONE").onPresent(z -> labels.put(NodeInfo.LABEL_ZONE, z));
        envLookup.apply("AETHER_INSTANCE_TYPE").onPresent(t -> labels.put(NodeInfo.LABEL_INSTANCE_TYPE, t));
        envLookup.apply("AETHER_POOL").onPresent(p -> labels.put(NodeInfo.LABEL_POOL, p));
        envLookup.apply("AETHER_SOURCE").onPresent(s -> labels.put(NodeInfo.LABEL_SOURCE, s));
        envLookup.apply("AETHER_ROLE").onPresent(r -> labels.put(NodeInfo.LABEL_ROLE, r));

        return Map.copyOf(labels);
    }

    @SuppressWarnings("JBCT-RET-01")
    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return findContainerHostname().or("unknown");
        }
    }

    private static Option<String> findContainerHostname() {
        return Option.option(System.getenv("HOSTNAME")).filter(s -> !s.isBlank());
    }

    private Option<String> findArg(String prefix) {
        return Option.from(Arrays.stream(args)
                                 .filter(arg -> arg.startsWith(prefix))
                                 .map(arg -> arg.substring(prefix.length()))
                                 .findFirst());
    }

    private Option<String> findEnv(String name) {
        return Option.option(System.getenv(name)).filter(s -> !s.isBlank());
    }
}
