// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.concurrent.TimeUnit;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.ClusterConfig;
import org.pragmatica.aether.config.ClusterSizeGate;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.AutoHealConfig;
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
import org.pragmatica.aether.environment.DiscoveryProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.aether.environment.PeerInfo;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.node.SwimGossipEncryptors;
import org.pragmatica.aether.node.labels.ContainerLabelInspector;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.SelfSignedCertificateProvider;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.swim.NettySwimTransport;

import com.sun.management.HotSpotDiagnosticMXBean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
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
        publishConfigPath();
        var aetherConfig = loadConfig();

        verifyClusterLabelConsistency(aetherConfig);
        enforceClusterNamePresent();
        enforceDevModeCompatibility(aetherConfig);
        var nodeId = parseNodeId(aetherConfig);
        var port = parsePort(aetherConfig);
        var managementPort = parseManagementPort(aetherConfig);
        var nodeLabels = collectNodeLabels();
        var environment = resolveEnvironment(aetherConfig);
        var peers = parsePeers(nodeId, port, nodeLabels, aetherConfig, environment);

        enforceMinimumClusterSize(staticPeersConfigured(), configuredClusterNodes(aetherConfig), peers.size());
        var sliceConfig = parseSliceConfig(aetherConfig);
        var dhtConfig = parseDhtConfig(aetherConfig);

        logStartupInfo(nodeId, port, managementPort, peers, aetherConfig, sliceConfig);
        var coreMax = parseCoreMax(aetherConfig);
        var tlsBundle = resolveTls(nodeId, peers, aetherConfig).expect("Failed to resolve TLS configuration at node startup");
        var appHttpTls = aetherConfig.filter(AetherConfig::tlsEnabled).map(_ -> tlsBundle.tls());
        var config = AetherNodeConfig.builder()
                                     .self(nodeId)
                                     .coreNodes(peers)
                                     .managementPort(managementPort)
                                     .sliceConfig(sliceConfig)
                                     .artifactRepo(dhtConfig)
                                     .coreMax(coreMax)
                                     .appHttp(resolveAppHttp(aetherConfig))
                                     .tls(appHttpTls)
                                     .quicTls(tlsBundle.tls())
                                     .certificateProvider(tlsBundle.provider())
                                     .configProvider(resolveConfigProvider())
                                     .environment(environment)
                                     .managementHttpProtocol(resolveManagementHttpProtocol(aetherConfig))
                                     .storageConfig(resolveStorage(aetherConfig))
                                     .backupConfig(resolveBackup(aetherConfig))
                                     .membership(resolveMembership(aetherConfig))
                                     .streaming(resolveStreaming(aetherConfig))
                                     .build()
                                     // #298 — stamp the boot-gated cluster identity onto runtime config.
                                     // enforceClusterNamePresent() above already aborted the boot if this
                                     // were missing or malformed, so the value is present and validated.
                                     .withClusterName(resolveClusterName())
                                     .withAutoHeal(resolveAutoHeal(aetherConfig));

        enforceWalDurabilityBootable(config);
        // Review catch (#634 batch): assembly failures — the routed-type codec guard included — get
        // the same FATAL + exit shape as the other boot gates, not an uncaught-exception stack trace.
        var node = AetherNode.aetherNode(config).onFailure(this::abortBoot).expect("unreachable: abortBoot exits");

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
                                                                                                                      labels)
                                                                                               .onSuccess(_ -> log.info("Cluster label consistency OK: aether.cluster='{}' matches AETHER_CLUSTER_NAME",
                                                                                                                        configured))
                                                                                               .onFailure(cause -> {
                                                                                                              log.error("FATAL: {}",
                                                                                                                        cause.message());
                                                                                                              System.exit(1);
                                                                                                          }));
    }

    private static final Cause MISSING_CLUSTER_NAME = Causes.cause("AETHER_CLUSTER_NAME is not set. A running node must know its cluster name. "
                                                                  + "Set the AETHER_CLUSTER_NAME environment variable (or bootstrap-seed it) before start.");

    private static final Cause DEV_MODE_WITH_REAL_TLS = Causes.cause("AETHER_INSECURE_DEV_MODE refused — operator TLS certificates are configured "
                                                                    + "([tls] auto_generate=false with cert/key paths). Insecure dev-mode is "
                                                                    + "fundamentally incompatible with a production TLS deployment.");

    /// Loud boot banner emitted when [#enforceDevModeCompatibility] confirms insecure dev-mode is
    /// ACTIVE (and permitted — real operator certs would already have aborted the boot). The flag
    /// opens test-only injection backdoors that must never be reachable in production; the gate above
    /// blocks it on a certificated cluster, but an auto-cert deployment that sets the flag should not
    /// run silently. Logged at WARN so it surfaces in any standard log capture.
    private static final String INSECURE_DEV_MODE_BANNER = "============================================================================\n"
                                                         + "  AETHER_INSECURE_DEV_MODE=true — INSECURE TEST MODE IS ACTIVE.\n"
                                                         + "  Test-injection backdoors are ENABLED: DHT inject, scheduled-task inject,\n"
                                                         + "  metrics backfill, and short-validity certificate issuance.\n"
                                                         + "  DO NOT run this mode in production. It is for development and testing only,\n"
                                                         + "  and is refused outright when operator TLS certificates are configured.\n"
                                                         + "============================================================================";

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

    /// #782 fix round — a cluster is at least three nodes, gated on the CONFIGURED topology, never
    /// on however many peers a boot attempt happened to resolve. The two differ exactly at cloud
    /// discovery's majority-at-timeout arm (`awaitDiscoveredCorePeers`, :611-663): a healthy
    /// majority boot of a three-node cluster can legitimately resolve 2 peers when one VM is slow,
    /// and gating on that resolved count would abort the exact boot that arm exists to allow. See
    /// [#expectedClusterSize] for the per-arm rule. Delegates to the pure, unit-tested
    /// [ClusterSizeGate#enforce] guard and exits on violation, the same `verify* -> abortBoot` idiom
    /// as the cluster-name and dev-mode gates above.
    @Contract
    private void enforceMinimumClusterSize(boolean staticPeersConfigured, int configuredNodes, int resolvedPeerCount) {
        ClusterSizeGate.enforce(expectedClusterSize(staticPeersConfigured, configuredNodes, resolvedPeerCount)).onFailure(this::abortBoot);
    }

    /// The CONFIGURED expected topology size for the size gate above — pure and unit-tested at the
    /// real call-site arithmetic (not just `ClusterSizeGate#enforce` in isolation).
    ///
    /// Static peers (`--peers=`/`CLUSTER_PEERS`) have no found-vs-expected split: the parsed list
    /// plus self IS the configuration (`MainPeerAssemblyTest` proves 2 peers + self = 3), so
    /// `resolvedPeerCount` is already correct there. Discovery and config-generated peers DO have
    /// that split — `configuredNodes` is `cluster().nodes()`, the SAME value
    /// `awaitDiscoveredCorePeers`'s `expected` parameter is given (both read it via
    /// [#configuredClusterNodes], so they cannot silently drift apart) — so a majority-at-timeout
    /// boot must gate on what was configured, not on what was found.
    static int expectedClusterSize(boolean staticPeersConfigured, int configuredNodes, int resolvedPeerCount) {
        if (staticPeersConfigured) {
            return resolvedPeerCount;
        }

        return configuredNodes > 0
               ? configuredNodes
               : resolvedPeerCount;
    }

    /// Whether `--peers=`/`CLUSTER_PEERS` is what `parsePeers` will resolve against — mirrors that
    /// method's own precedence check so [#expectedClusterSize] asks the identical question `parsePeers`
    /// already answered, rather than re-deriving it from the resolved list after the fact.
    private boolean staticPeersConfigured() {
        return findArg("--peers=").isPresent() || findEnv("CLUSTER_PEERS").isPresent();
    }

    /// Single source for the discovery arm's target (`discoverCloudCorePeers`'s `expected`) and the
    /// boot-time size gate's `configuredNodes` (`enforceMinimumClusterSize`) — both must read the
    /// identical field, or a gate that silently drifted from the discovery wait would reproduce the
    /// #782 fix-round bug this replaces. 0 means "unset" (mirrors `ClusterConfig#UNBOUNDED`'s
    /// convention).
    private static int configuredClusterNodes(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(cfg -> cfg.cluster()
                                          .nodes())
                           .or(0);
    }

    /// #298 — the cluster name for runtime config, read from the same env var the boot gate keys on
    /// and parsed with the SAME grammar ([ClusterName#PATTERN], which [#verifyClusterNamePresent]
    /// now also uses). Reached only after [#enforceClusterNamePresent] has passed, so the empty case
    /// is a total-function fallback rather than a real one — but it is [Option#empty] now, not the
    /// empty string, so a runtime component that receives it declines to scope rather than scoping on
    /// a name that matches nothing.
    private static Option<ClusterName> resolveClusterName() {
        return Option.option(System.getenv("AETHER_CLUSTER_NAME"))
                     .map(String::trim)
                     .flatMap(ClusterName::maybeClusterName);
    }

    /// Pure, unit-testable guard: present + format-valid cluster name → success; missing,
    /// blank, or malformed → failure. No side effects (no exit, no env read).
    static Result<Unit> verifyClusterNamePresent(String clusterNameEnv) {
        return Option.option(clusterNameEnv)
                     .map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .toResult(MISSING_CLUSTER_NAME)
                     .filter(MALFORMED_CLUSTER_NAME,
                             name -> ClusterName.PATTERN.matcher(name).matches())
                     .mapToUnit();
    }

    private static final Fn1<Cause, String> MALFORMED_CLUSTER_NAME = Causes.forOneValue("AETHER_CLUSTER_NAME '%s' is malformed — must match "
                                                                                       + "[a-z]([a-z0-9-]{0,61}[a-z0-9])? (lowercase DNS label, 1-63 chars, e.g. a or prod-eu).");

    /// Boot gate (#634 item 2): a node whose stream WAL directory is unwritable must not start unless
    /// non-durable streams were opted into explicitly — otherwise every publish acks with NO fsync and
    /// "durable entity" silently becomes "in-memory entity". Delegates to the pure
    /// [AetherNode#verifyWalBootable] guard (tested via `WalAvailabilityGateTest`), exits on violation —
    /// the same `verify* -> abortBoot` idiom as the cluster-name and dev-mode gates above.
    @Contract
    private void enforceWalDurabilityBootable(AetherNodeConfig config) {
        AetherNode.verifyWalBootable(config).onFailure(this::abortBoot);
    }

    /// Boot gate: insecure dev-mode must be fundamentally incompatible with a real
    /// (operator-supplied) TLS deployment. Reads the dev-mode env and the resolved TLS
    /// config, delegates to the pure [#verifyDevModeCompatibility] guard, exits on violation.
    @Contract
    private void enforceDevModeCompatibility(Option<AetherConfig> aetherConfig) {
        var devMode = Option.option(System.getenv("AETHER_INSECURE_DEV_MODE"))
                            .map(v -> v.equalsIgnoreCase("true"))
                            .or(false);

        verifyDevModeCompatibility(devMode, resolveTlsConfig(aetherConfig)).onFailure(this::abortBoot);
        if (devMode) {
            log.warn("\n{}", INSECURE_DEV_MODE_BANNER);
        }
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

    /// #298 — carry `[cluster] max_nodes` into the auto-heal config the node runs with. Until this
    /// existed the builder fell through to `AutoHealConfig.DEFAULT`, so no auto-heal setting was
    /// operator-tunable at all and the fleet cap had no way to be set outside a test.
    ///
    /// `UNBOUNDED` (0, the same "unset" sentinel `coreMax` uses) leaves the cap absent, which is
    /// what every existing config gets — provisioning stays unbounded until an operator opts in.
    private static AutoHealConfig resolveAutoHeal(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(AetherConfig::cluster)
                           .map(ClusterConfig::maxNodes)
                           .filter(maxNodes -> maxNodes > ClusterConfig.UNBOUNDED)
                           .map(AutoHealConfig.DEFAULT::withMaxNodes)
                           .or(AutoHealConfig.DEFAULT);
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

    /// #336 — publish the resolved `--config=` path as the `aether.config.path` system property so
    /// the static `AetherNode` assemble site can parse this node's OWN resolved config (its per-node
    /// overlay rendered by the CLI with `${env:...}` placeholders resolved to literals) for the CTM
    /// placeholder-resolution path, WITHOUT threading the path through the 31-stage
    /// [AetherNodeConfig] builder. Only published when the `--config=` file actually exists; absent
    /// (forge / tests / config-by-other-means) the CTM degrades to none() and composed overlays pass
    /// through unchanged.
    @Contract
    private void publishConfigPath() {
        findArg("--config=").map(Path::of)
               .filter(p -> p.toFile()
                             .exists())
               .map(Path::toAbsolutePath)
               .onPresent(p -> System.setProperty(AetherNode.CONFIG_PATH_PROPERTY,
                                                  p.toString()));
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

    /// #749: a shutdown hook with no bound at all parks forever if `node.stop()` never settles. 30s is
    /// generous relative to `EmberCluster`'s 10s-per-node test bound (this covers ONE node, not N in
    /// parallel) while still failing the container's stop/restart grace period loudly instead of silently.
    private static final TimeSpan SHUTDOWN_TIMEOUT = TimeSpan.timeSpan(30).seconds();
    /// #838 review round 1, BLOCKING 1 (owner-confirmed): `System.exit()` from inside a shutdown hook
    /// DEADLOCKS -- `Shutdown.exit()` blocks on the `Shutdown` class monitor already held by the thread
    /// running `runHooks()`, which is the very thread executing this hook, so it joins itself. Proven by
    /// the reviewer: hung past 2 minutes, ignored SIGTERM, needed SIGKILL. `Runtime.getRuntime().halt(int)`
    /// bypasses that machinery entirely -- the same pattern already used by the drain-completed self-exit
    /// at `AetherNode.java:415,437` -- at the cost of running no further shutdown hooks and no appender
    /// flush of its own, so both are done explicitly and synchronously immediately before the halt.
    ///
    /// Exit code 3 = shutdown did not complete within [#SHUTDOWN_TIMEOUT]; documented at
    /// `aether/docs/reference/node-operations.md#exit-codes`. Code 2 is already owned by the
    /// drain-completed self-exit in `AetherNode`/`DrainProcedure`; reusing it here would have been a
    /// second, independent bug stacked on top of the deadlock. Which call inside `node.stop()` actually
    /// blocked is UNKNOWN (#749 acceptance item 1) -- the thread dump below is what will eventually answer
    /// that from a real occurrence, not a diagnosis made here.
    static final int SHUTDOWN_TIMEOUT_EXIT_CODE = 3;
    /// #838 review round 1: bound on [#flushLogs] so a wedged appender (blocking async queue,
    /// unreachable network sink) cannot re-introduce the unbounded hang the halt seam below exists
    /// to remove. Generous relative to typical flush latency, small relative to [#SHUTDOWN_TIMEOUT]
    /// so it cannot itself consume the process's shutdown budget.
    private static final TimeSpan FLUSH_TIMEOUT = TimeSpan.timeSpan(5).seconds();

    private void registerShutdownHook(AetherNode node) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdownNode(node,
                                                                           SHUTDOWN_TIMEOUT,
                                                                           Main::flushLogs,
                                                                           Runtime.getRuntime()::halt)));
    }

    /// Synchronous flush of the log4j2 context, bounded by [#FLUSH_TIMEOUT] -- halt() performs no
    /// appender flush of its own, so anything not flushed here is lost with the process. #838 review
    /// round 1: [LogManager#shutdown()] has no timeout of its own; [LoggerContext#stop(long,TimeUnit)]
    /// does, and is used instead so a wedged appender bounds the flush rather than hanging it.
    private static void flushLogs() {
        var ctx = (LoggerContext) LogManager.getContext(false);

        ctx.stop(FLUSH_TIMEOUT.millis(), TimeUnit.MILLISECONDS);
    }

    /// Package-private and parameterized (timeout / log-flush / halt as injected seams) so the expiry
    /// path is unit-testable without pinning a real 30s clock or touching the shared log4j2 context in
    /// the surefire fork. Production wiring is [#registerShutdownHook].
    static void shutdownNode(AetherNode node, TimeSpan timeout, Runnable flushLogs, IntConsumer haltFn) {
        log.info("Shutdown requested, stopping node...");
        var stopping = node.stop();
        var result = stopping.await(timeout);

        if (!stopping.isResolved()) {
            log.error("Node did not stop within {}; halting with code {} (shutdown timeout -- see "
                     + "aether/docs/reference/node-operations.md#exit-codes). Thread dump follows.",
                      timeout,
                      SHUTDOWN_TIMEOUT_EXIT_CODE);
            dumpThreads();
            // #838 review round 1: halt is in `finally` so a throwing flushLogs.run() cannot preempt
            // it and restore the unbounded hang this method exists to remove -- flushLogs itself is
            // bounded (see its doc), so this can delay the halt by at most FLUSH_TIMEOUT, never hang it.
            try {
                flushLogs.run();
            } finally {
                haltFn.accept(SHUTDOWN_TIMEOUT_EXIT_CODE);
            }

            return;
        }

        result.onFailure(cause -> log.error("Node stop() completed with failure: {}", cause.message()));
        log.info("Node stopped");
    }

    /// Best-effort diagnostic: a failed dump is logged and never blocks the halt it was meant to explain.
    private static void dumpThreads() {
        captureThreadDump().onSuccess(lines -> lines.forEach(line -> log.error("{}", line)))
                         .onFailure(cause -> log.error("Failed to capture shutdown-timeout thread dump: {}",
                                                       cause.message()));
    }

    /// #838 review round 1, SHOULD-FIX: [ThreadMXBean#dumpAllThreads] omits virtual threads -- exactly the
    /// kind of thread a hung `node.stop()` is likely blocked on, since the async offloads it waits on run
    /// on them. [HotSpotDiagnosticMXBean#dumpThreads] includes virtual threads; it writes a pre-formatted
    /// dump to a file rather than returning `ThreadInfo[]`, and refuses to overwrite an existing file, so
    /// a fresh path is created and deleted around each call regardless of outcome.
    ///
    /// #838 review round 1, JBCT-EX-01: returns [Result] rather than throwing -- a checked [IOException]
    /// on this best-effort diagnostic path must not propagate past [#dumpThreads], which logs-and-continues
    /// on either outcome rather than branching on a caught exception.
    static Result<List<String>> captureThreadDump() {
        return Result.lift(() -> {
            var dumpPath = Files.createTempFile("aether-thread-dump-", ".txt");

            Files.delete(dumpPath);  // dumpThreads(String, ...) refuses to write over an existing file
            try {
                ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).dumpThreads(dumpPath.toString(),
                                                                                               HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);

                return Files.readAllLines(dumpPath, StandardCharsets.UTF_8);
            } finally {
                Files.deleteIfExists(dumpPath);
            }
        });
    }

    private void startNodeAndWait(AetherNode node, NodeId nodeId) {
        node.start()
            .onSuccess(_ -> log.info("Node {} is running. Press Ctrl+C to stop.", nodeId))
            .onFailure(cause -> exitWithError(cause.message()))
            .await();
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

    /// Parse the cluster peers and, when this node is NOT already named in its own seed set,
    /// synthesize a self `NodeInfo` advertising a ROUTABLE host resolved by [SelfAddressResolver]
    /// (override → SWIM WhoAmI reflection → loud hostname fallback) BEFORE building `selfInfo`,
    /// so the advertised address is correct ONCE and propagates immutably to SWIM/QUIC/consensus/DHT.
    ///
    /// Bootstrap (self already present in PEERS) is byte-identical to the prior behaviour: the
    /// parsed list is returned UNCHANGED and no resolution runs — `selfInfo` was discarded there
    /// anyway. The CTM-provisioned-replacement case (self ABSENT) is the one that previously
    /// appended a poisoned hostname-based entry; it now appends a resolved one.
    private List<NodeInfo> parsePeers(NodeId self,
                                      int selfPort,
                                      Map<String, String> labels,
                                      Option<AetherConfig> aetherConfig,
                                      Option<EnvironmentIntegration> environment) {
        return findArg("--peers=").map(peersStr -> resolvePeersFromString(peersStr, self, selfPort, labels, aetherConfig))
                      .orElse(findEnv("CLUSTER_PEERS").map(peersStr -> resolvePeersFromString(peersStr,
                                                                                              self,
                                                                                              selfPort,
                                                                                              labels,
                                                                                              aetherConfig)))
                      .orElse(discoverCloudCorePeers(self, selfPort, labels, aetherConfig, environment))
                      .orElse(aetherConfig.map(cfg -> generatePeersFromConfig(cfg, self)))
                      .or(() -> List.of(bootstrapSelfInfo(self, selfPort, labels)));
    }

    /// RFC-0017 stage 4 — cloud core self-assembly. When nothing EXPLICIT names the peers (no
    /// `--peers=`, no `CLUSTER_PEERS`) and the environment exposes a [DiscoveryProvider], the node
    /// discovers its core peers from the provider API instead of having them pushed over SSH.
    ///
    /// Placement in the chain is deliberate: AFTER the explicit arms — an operator's list always
    /// wins, and CTM-provisioned replacements keep their user-data `CLUSTER_PEERS` path
    /// byte-identical — but BEFORE `generatePeersFromConfig`, whose hostname-indexed synthesis is
    /// meaningless on cloud and is exactly why the SSH push existed. Forge/compose/bare-metal nodes
    /// configure no `[cloud]` section, so no provider materializes and their resolution is
    /// unchanged.
    ///
    /// The expected core count comes from `cluster().nodes()` — the same field the config arm
    /// already trusts. Without a positive expectation the arm stays inert: discovery cannot know
    /// when the set is complete.
    private Option<List<NodeInfo>> discoverCloudCorePeers(NodeId self,
                                                          int selfPort,
                                                          Map<String, String> labels,
                                                          Option<AetherConfig> aetherConfig,
                                                          Option<EnvironmentIntegration> environment) {
        var expected = configuredClusterNodes(aetherConfig);

        return environment.flatMap(EnvironmentIntegration::discovery)
                          .filter(_ -> expected > 0)
                          .map(dp -> awaitDiscoveredCorePeers(dp, self, selfPort, labels, aetherConfig, expected));
    }

    private static final long DISCOVERY_TIMEOUT_MS = 300_000;
    private static final long DISCOVERY_POLL_MS = 5_000;

    /// Poll the provider until the expected number of core peers is visible, then assemble.
    ///
    /// At the deadline a MAJORITY of the expected cores is accepted with a warning — Rabia can form
    /// on a quorum, and a VM that never boots must not deadlock every healthy node's startup — but
    /// less than a majority fails loudly: proceeding would seed a cluster that cannot reach
    /// consensus and would sit half-formed instead of telling the operator.
    @SuppressWarnings("JBCT-EX-01")
    private List<NodeInfo> awaitDiscoveredCorePeers(DiscoveryProvider dp,
                                                    NodeId self,
                                                    int selfPort,
                                                    Map<String, String> labels,
                                                    Option<AetherConfig> aetherConfig,
                                                    int expected) {
        var deadline = System.currentTimeMillis() + DISCOVERY_TIMEOUT_MS;
        var found = List.<NodeInfo> of();

        log.info("Discovering {} core peers via provider API (timeout {}s)", expected, DISCOVERY_TIMEOUT_MS / 1000);
        while (System.currentTimeMillis() < deadline) {
            found = dp.discoverPeers()
                      .await()
                      .map(peerList -> discoveredCorePeers(peerList, selfPort))
                      .onFailure(cause -> log.warn("Peer discovery poll failed: {}",
                                                   cause.message()))
                      .or(List.of());
            if (found.size() >= expected) {
                log.info("Discovered all {} core peers", expected);

                return assembleDiscovered(found, self, selfPort, labels, aetherConfig);
            }

            if (!sleepQuietly(DISCOVERY_POLL_MS)) {
                break;
            }
        }

        if (sufficientAtTimeout(found.size(), expected)) {
            log.warn("Discovery timed out with {}/{} core peers — proceeding on a quorum; missing nodes must be auto-healed",
                     found.size(),
                     expected);

            return assembleDiscovered(found, self, selfPort, labels, aetherConfig);
        }

        throw new IllegalStateException("Peer discovery found only " + found.size()
                                       + " of " + expected
                                       + " expected core peers within " + DISCOVERY_TIMEOUT_MS / 1000
                                       + "s — below quorum, refusing to form a cluster that cannot reach consensus."
                                       + " Check that all core VMs were created and carry the aether-cluster/aether-node-id labels.");
    }

    /// Majority of the EXPECTED set, not of the found set — the whole point is refusing to form
    /// below the quorum the operator asked for.
    static boolean sufficientAtTimeout(int found, int expected) {
        return found >= expected / 2 + 1;
    }

    private List<NodeInfo> assembleDiscovered(List<NodeInfo> peers,
                                              NodeId self,
                                              int selfPort,
                                              Map<String, String> labels,
                                              Option<AetherConfig> aetherConfig) {
        return assembleSelfPeers(peers,
                                 self,
                                 selfPort,
                                 labels,
                                 seeds -> resolveAdvertiseHost(self, selfPort, seeds, aetherConfig));
    }

    /// Map discovered instances to core peers, pure and deterministic (unit-tested directly).
    ///
    /// A peer qualifies iff its labels carry `aether-role=core` AND an `aether-node-id` — the id
    /// the create stamped (`HetznerComputeProvider.labelsFor`). The port is deliberately THIS
    /// node's own cluster port, not the `aether-port` label: that label is applied by
    /// `registerSelf` only AFTER a node joins, so it cannot exist during pre-formation discovery —
    /// while every core shares one cluster port by config composition. Entries are deduplicated by
    /// node id (first wins) and sorted, so every core derives the same seed list from the same
    /// provider state.
    static List<NodeInfo> discoveredCorePeers(List<PeerInfo> discovered, int clusterPort) {
        var seen = new HashSet<String>();

        return discovered.stream()
                         .filter(peer -> "core".equalsIgnoreCase(peer.metadata().getOrDefault("aether-role", "")))
                         .flatMap(peer -> discoveredNodeInfo(peer, clusterPort).stream())
                         .filter(node -> seen.add(node.id().id()))
                         .sorted(Comparator.comparing(node -> node.id()
                                                                  .id()))
                         .toList();
    }

    private static Option<NodeInfo> discoveredNodeInfo(PeerInfo peer, int clusterPort) {
        return Option.option(peer.metadata().get("aether-node-id"))
                     .flatMap(id -> NodeId.nodeId(id).option())
                     .flatMap(id -> nodeAddress(peer.host(),
                                                clusterPort).option()
                                               .map(address -> NodeInfo.nodeInfo(id, address)));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);

            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return false;
        }
    }

    /// Explicit advertise-host override: `--advertise-host=` arg or `AETHER_ADVERTISE_HOST` env.
    /// The primary load-bearing path — CTM user-data sets it to the replacement VM's routable IP.
    private Option<String> findAdvertiseHostOverride() {
        return findArg("--advertise-host=").orElse(findEnv("AETHER_ADVERTISE_HOST"));
    }

    /// Single-node bootstrap fallback (no `--peers=`/`CLUSTER_PEERS`/config): honor the explicit
    /// override if set (cheap, consistent), else the local hostname. No seeds exist to reflect against.
    private NodeInfo bootstrapSelfInfo(NodeId self, int selfPort, Map<String, String> labels) {
        var host = findAdvertiseHostOverride().or(Main::resolveHostname);

        return NodeInfo.nodeInfo(self, nodeAddress(host, selfPort).expect("self host is a valid node address"), labels);
    }

    /// Parse the `--peers=`/`CLUSTER_PEERS` list, then branch on self-presence via the pure
    /// [#assembleSelfPeers] seam, supplying a host resolver bound to this node's args/env/config.
    private List<NodeInfo> resolvePeersFromString(String peersStr,
                                                  NodeId self,
                                                  int selfPort,
                                                  Map<String, String> labels,
                                                  Option<AetherConfig> aetherConfig) {
        return assembleSelfPeers(parsePeerList(peersStr),
                                 self,
                                 selfPort,
                                 labels,
                                 seeds -> resolveAdvertiseHost(self, selfPort, seeds, aetherConfig));
    }

    /// Pure, unit-testable peer-assembly seam: self PRESENT in the parsed list → return it
    /// UNCHANGED (byte-identical bootstrap); self ABSENT → resolve a routable advertise host via
    /// `hostResolver` (override → SWIM reflection → fallback, injected so tests stay deterministic),
    /// build `selfInfo` ONCE, and append it.
    static List<NodeInfo> assembleSelfPeers(List<NodeInfo> peers,
                                            NodeId self,
                                            int selfPort,
                                            Map<String, String> labels,
                                            Fn1<String, List<NodeInfo>> hostResolver) {
        return peers.stream()
                    .anyMatch(p -> p.id()
                                    .equals(self))
               ? peers
               : ensureSelfIncluded(peers, self, selfInfoForHost(self, selfPort, labels, hostResolver.apply(peers)));
    }

    private static NodeInfo selfInfoForHost(NodeId self, int selfPort, Map<String, String> labels, String host) {
        return NodeInfo.nodeInfo(self, nodeAddress(host, selfPort).expect("self host is a valid node address"), labels);
    }

    private List<NodeInfo> parsePeerList(String peersStr) {
        return Arrays.stream(peersStr.split(","))
                     .map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .flatMap(peerStr -> parsePeerAddress(peerStr).stream())
                     .toList();
    }

    /// Drive [SelfAddressResolver] over the seeds, assembling the SAME serializer/encryptor the
    /// running SWIM transport uses (so a seed can decrypt the WhoAmI probe), with a real
    /// `NettySwimTransport` factory.
    private String resolveAdvertiseHost(NodeId self,
                                        int selfPort,
                                        List<NodeInfo> seeds,
                                        Option<AetherConfig> aetherConfig) {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var encryptor = SwimGossipEncryptors.fromCertificateProvider(resolveCertificateProvider(aetherConfig));
        var resolver = SelfAddressResolver.selfAddressResolver(codec,
                                                               codec,
                                                               encryptor,
                                                               NettySwimTransport::nettySwimTransport);

        return resolver.resolve(self, selfPort, seeds, findAdvertiseHostOverride(), Main::resolveHostname);
    }

    /// Resolve the cluster `CertificateProvider` from the same cluster secret [#resolveTls] uses,
    /// so the reflection encryptor's gossip keys match the running transport's. Absent (e.g.
    /// insecure dev-mode without a secret) → `none()`, yielding the no-op encryptor — which matches
    /// a dev-mode seed.
    private Option<CertificateProvider> resolveCertificateProvider(Option<AetherConfig> aetherConfig) {
        return resolveClusterSecret(resolveTlsConfig(aetherConfig)).flatMap(SelfSignedCertificateProvider::selfSignedCertificateProvider)
                                   .option();
    }

    private List<NodeInfo> generatePeersFromConfig(AetherConfig aetherConfig, NodeId self) {
        var nodes = aetherConfig.cluster().nodes();
        var clusterPort = aetherConfig.cluster().ports().cluster();
        var env = aetherConfig.environment();
        var override = findAdvertiseHostOverride();

        return IntStream.range(0, nodes)
                        .mapToObj(i -> createNodeInfoForIndex(i, clusterPort, env))
                        .map(node -> applyOverrideToSelf(node, self, override))
                        .toList();
    }

    /// Config-path override: when the explicit advertise-host is set, replace the host of the
    /// generated entry that IS this node; other entries are untouched (Docker/local behaviour
    /// otherwise unchanged).
    private static NodeInfo applyOverrideToSelf(NodeInfo node, NodeId self, Option<String> override) {
        return node.id()
                   .equals(self)
               ? override.map(host -> overrideHost(node, host))
                         .or(node)
               : node;
    }

    private static NodeInfo overrideHost(NodeInfo node, String host) {
        return NodeInfo.nodeInfo(node.id(),
                                 nodeAddress(host,
                                             node.address().port()).expect("override host is a valid node address"),
                                 node.labels());
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

    private static List<NodeInfo> ensureSelfIncluded(List<NodeInfo> peers, NodeId self, NodeInfo selfInfo) {
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
