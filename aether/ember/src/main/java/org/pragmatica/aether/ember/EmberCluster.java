// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.invoke.ObservabilityConfig;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProviderDefaults;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.aether.config.ApiVersioningDetection;
import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.config.TtmConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.config.TimeoutsConfig;
import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.concurrent.CancellableTask;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.consensus.net.ClusterFormationConfig;
import org.pragmatica.consensus.topology.BackoffConfig;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.SelfSignedCertificateProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;


@Contract
@SuppressWarnings("JBCT-RET-03")
public final class EmberCluster {
    private static final Logger log = LoggerFactory.getLogger(EmberCluster.class);
    public static final int DEFAULT_BASE_PORT = 6000;
    public static final int DEFAULT_BASE_MGMT_PORT = 6100;
    public static final int DEFAULT_BASE_APP_HTTP_PORT = 8070;
    private static final TimeSpan NODE_TIMEOUT = TimeSpan.timeSpan(10).seconds();
    private static final long ROLLING_RESTART_DELAY_MS = 5_000;

    private final Map<String, AetherNode> nodes = new ConcurrentHashMap<>();
    /// TEST SEAM (#509 probe) — nodes CREATED by [#start] with the full configured topology but whose
    /// `start()` was DEFERRED, keyed by their stable node id. Held instances keep their identity, port
    /// and slot, so [#startHeldBackNodes] brings the SAME node up later. They are deliberately absent
    /// from [#nodes] while held: `nodes` is the RUNNING-node registry every accessor reads
    /// ([#currentLeader], [#allNodes], [#setClusterSize], [#stop]), and an unstarted entry there would
    /// let a leader lookup answer from a node that has no consensus state. Empty in every production
    /// and existing-test path (plain [#start] holds nothing back).
    private final Map<String, AetherNode> heldBackNodes = new ConcurrentHashMap<>();
    private final Map<String, NodeInfo> nodeInfos = new ConcurrentHashMap<>();
    /// #694: per-instance tag maps, stamped by the compute provider at provision time (see
    /// `EmberComputeProvider.stampAndDescribe`). Keyed by node id; nodes created outside the
    /// provider (initial cluster, direct addNode calls) have no entry and read as UNTAGGED — the
    /// pre-#694 shape, preserved deliberately so only provisioned instances change what
    /// listInstances returns. Entries survive a restart (labels live on the VM in production) and
    /// die with terminate/kill or cluster teardown.
    private final Map<String, Map<String, String>> instanceTags = new ConcurrentHashMap<>();
    private final AtomicInteger nodeCounter = new AtomicInteger(0);
    private final Queue<Integer> availableSlots = new ConcurrentLinkedQueue<>();
    private final Map<String, Integer> slotsByNodeId = new ConcurrentHashMap<>();
    private final int initialClusterSize;
    private final int basePort;
    private final int baseMgmtPort;
    private final int baseAppHttpPort;
    private final String nodeIdPrefix;
    private final AtomicBoolean rollingRestartActive = new AtomicBoolean(false);
    private final ScheduledExecutorService rollingRestartExecutor = Executors.newSingleThreadScheduledExecutor();
    private final CancellableTask rollingRestartTask = CancellableTask.cancellableTask();
    private final Random random = new Random();
    private long lastTotalInvocations = 0;
    private long lastTotalSuccess = 0;
    private double emaRps = 0.0;
    private double emaSuccessRate = 1.0;
    private double emaAvgLatencyMs = 0.0;

    private static final double EMA_ALPHA = 0.2;

    private final int targetClusterSize;
    private final AtomicInteger effectiveSize;
    private final Option<ConfigurationProvider> configProvider;
    private final ObservabilityConfig observability;

    /// The literal `MemberDescriptor.isCoreRole` tests against — anything else, including blank,
    /// classifies as CORE. Duplicated here because that constant is private to the FSM module; if it
    /// ever moves, `EmberAddNodeRoleLabelTest` fails rather than this drifting silently.
    private static final String WORKER_ROLE = "worker";

    private final int coreMax;

    /// TEST SEAM (#336 probe) — decorator applied to the base [EmberComputeProvider] before the
    /// shared [EnvironmentIntegration] is built. Defaults to identity (production behaviour
    /// unchanged). A test installs a fault-injecting wrapper via [#withComputeProviderDecorator]
    /// BEFORE [#start] so the leader's CTM `provisionReplacement` path exercises the wrapper. The
    /// integration is built lazily (first node creation) so the decorator set after construction
    /// but before start is honoured.
    private final AtomicReference<Functions.Fn1<ComputeProvider, ComputeProvider>> computeProviderDecorator = new AtomicReference<>(provider -> provider);

    private final AtomicReference<EnvironmentIntegration> emberEnvironmentRef = new AtomicReference<>();

    /// TEST SEAM (#198 §7) — cluster-level API-version detection mode applied to every node's
    /// [AppHttpConfig]. Defaults to PATH (production behaviour). A test sets HEADER via
    /// [#withApiVersioningDetection] BEFORE [#start] to deploy a slice in header mode.
    private final AtomicReference<ApiVersioningDetection> apiVersioningDetection = new AtomicReference<>(ApiVersioningDetection.PATH);

    private final AtomicReference<String> apiVersionHeaderName = new AtomicReference<>(AppHttpConfig.DEFAULT_API_VERSION_HEADER);

    /// Cluster-level app-HTTP security applied to every node's [AppHttpConfig]. Defaults to
    /// [SecurityMode#NONE] with no keys, which is what an in-JVM harness wants: `securityEnabled()` is
    /// false, so the node installs `denyUnlessPublicValidator` and only Public routes answer.
    ///
    /// Forge sets these from the sibling `aether.toml`'s `[app-http]` via [#withAppHttpSecurity] BEFORE
    /// [#start]. Without that seam a Forge cluster could not authenticate ANY caller — the convenience
    /// factory hard-codes NONE and an empty key map, so an application declaring `role:admin` or
    /// `authenticated` routes had every one of them refused with no credential able to satisfy them,
    /// and no config or environment path could reach the node. That made Aether's own local simulator
    /// unable to demonstrate the access-control model applications are expected to declare.
    private final AtomicReference<SecurityMode> appHttpSecurityMode = new AtomicReference<>(SecurityMode.NONE);

    private final AtomicReference<Map<String, ApiKeyEntry>> appHttpApiKeys = new AtomicReference<>(Map.of());

    /// TEST SEAM (streaming A-WAL) — opt-in writable, restart-stable per-node data dir. Defaults to
    /// [Option#none] (production + existing tests: nodes fall back to the default read-only `/data`
    /// stream path → WAL off, streaming non-crash-durable). A test sets a writable base dir (e.g. a
    /// JUnit `@TempDir`) via [#withDataBaseDir] BEFORE [#start] to turn the disk tier and the
    /// per-partition stream WAL on; see [#perNodeStorageConfig].
    private final AtomicReference<Option<Path>> dataBaseDir = new AtomicReference<>(Option.none());
    /// #491 pinned convergence variant — when set (via [#withRaisedSwimTimeouts]) every node is created
    /// with raised SWIM / transport / membership timeouts so a single graceful owner-kill does not trip
    /// the transient QuorumLost→PASSIVE false-removal cascade that falsely marks LIVE survivors DEAD.
    private final AtomicBoolean raisedSwimTimeouts = new AtomicBoolean(false);
    /// #715 — this instance's own cluster QUIC/SWIM identity secret. Defaults to a fresh
    /// `SecureRandom` value so distinct `EmberCluster` instances never share cluster identity and
    /// cannot admit each other's nodes; [#withClusterSecret] is the only sanctioned override.
    /// Exposed to tests via [#currentClusterSecret].
    private final AtomicReference<byte[]> clusterSecret = new AtomicReference<>(generateClusterSecret());

    /// #715 — the [AetherNodeConfig] object actually passed to the most recently constructed node's
    /// constructor. Captured from the real `config` value itself, never mirrored independently at
    /// construction time, so [#wiredCertificateProvider] and [#wiredQuicTls] can never drift from
    /// what a node was truly wired with.
    private final AtomicReference<Option<AetherNodeConfig>> lastNodeConfig = new AtomicReference<>(Option.empty());

    private static byte[] generateClusterSecret() {
        var secret = new byte[32];

        new SecureRandom().nextBytes(secret);

        return secret;
    }

    private final class EmberComputeProvider implements ComputeProvider {
        @Override
        public ProviderDefaults providerDefaults() {
            return ProviderDefaults.providerDefaults("in-jvm", "", "", "", Option.none(), false);
        }

        /// Provisions an in-JVM node advertising the role the request ALREADY carries (#590).
        ///
        /// Every production provider translates [ProvisionContext#role] into its native encoding
        /// (`-e AETHER_ROLE=` / `-l aether-role=`), which the booting node then re-asserts as its SWIM
        /// [NodeInfo#LABEL_ROLE] via `Main.collectNodeLabels`. This provider used to drop that field on
        /// the floor and call the bare [#addNode], so a CTM-minted worker came up in-JVM classifying as
        /// a CORE — the same infidelity [#addWorkerNode] documents, on the path that no test can opt out
        /// of. Faithful-by-construction is the fix: the role rides the request, so it rides the node.
        ///
        /// The label is stamped VERBATIM — no normalisation, no mapping. `MemberDescriptor.isCoreRole`
        /// compares against the literal `worker`, so normalising here would mask exactly the mislabel a
        /// harness exists to expose. A blank role stamps NO label, mirroring a production node booted
        /// without `AETHER_ROLE` (`collectNodeLabels` only puts the key when the env var is present);
        /// blank and `core` are equivalent through `isCoreRole`, which is what keeps every existing
        /// core-provisioning path unchanged — pinned, not assumed, by `EmberAddNodeRoleLabelTest`.
        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return addNode(roleLabels(request.context().role())).map(nodeId -> stampAndDescribe(request.context(),
                                                                                                nodeId.id()));
        }

        /// #694: the tag map is built from the provisioning context AT PROVISION TIME and stored per
        /// node, because [#toInstanceInfo] is also reached from [#listInstances]/[#instanceStatus],
        /// which hold no request. Without the stamp every in-JVM instance carried an EMPTY tag map, an
        /// instance with no tags matches no non-empty selector, and the CTM's worker reconcile — which
        /// lists ACTUAL inventory through the `aether-cluster`/`aether-source`/`aether-role` selector —
        /// read `actual = 0` forever: re-provisioning every pass, never able to see a scale-down
        /// victim, and pointing the next investigation at the CTM instead of this harness (the #590
        /// role-label infidelity one layer up).
        private InstanceInfo stampAndDescribe(ProvisionContext context, String nodeIdStr) {
            instanceTags.put(nodeIdStr, provisionTags(context, nodeIdStr));

            return toInstanceInfo(nodeIdStr);
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            return killNode(instanceId.value());
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            var infos = nodes.keySet().stream().map(this::toInstanceInfo).toList();

            return Promise.success(infos);
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return Option.option(nodes.get(instanceId.value()))
                         .map(_ -> toInstanceInfo(instanceId.value()))
                         .async(EnvironmentError.instanceNotFound(instanceId));
        }

        private InstanceInfo toInstanceInfo(String nodeIdStr) {
            var addresses = Option.option(nodeInfos.get(nodeIdStr))
                                  .map(info -> List.of("localhost:" + info.address().port()))
                                  .or(List.of());

            return new InstanceInfo(new InstanceId(nodeIdStr),
                                    InstanceStatus.RUNNING,
                                    addresses,
                                    InstanceType.ON_DEMAND,
                                    Option.option(instanceTags.get(nodeIdStr)).or(Map.of()),
                                    Option.some(nodeIdStr));
        }

        /// Mirrors `HetznerComputeProvider.labelsFor`, the reference native stamping: the three
        /// selector keys the CTM's worker-reconcile filter is built from, with the blank role
        /// defaulting to `core` exactly as the Hetzner provider defaults it, plus the
        /// provider-agnostic dotted `aether.node-id` upper layers (`NodeLifecycleManager.NODE_ID_TAG`)
        /// select by — Ember has no native key charset, so no dotted-to-hyphenated translation
        /// applies. One deliberate divergence, recorded rather than hidden: an ABSENT cluster name
        /// stamps the empty string where every cloud provider refuses to create the VM outright
        /// (RFC-0017 C2) — the CTM's selector renders an unresolvable name as the same empty string,
        /// so the round-trip holds, and refusing here would be a behavior change to every existing
        /// harness consumer that provisions without a cluster name.
        private static Map<String, String> provisionTags(ProvisionContext context, String nodeIdStr) {
            return Map.of("aether-cluster",
                          context.clusterName().map(ClusterName::value).or(""),
                          "aether-role",
                          context.role().isEmpty()
                          ? "core"
                          : context.role(),
                          "aether-source",
                          context.sourceName().value(),
                          "aether.node-id",
                          nodeIdStr);
        }
    }

    private EmberCluster(int initialClusterSize,
                         int basePort,
                         int baseMgmtPort,
                         int baseAppHttpPort,
                         String nodeIdPrefix,
                         Option<ConfigurationProvider> configProvider,
                         ObservabilityConfig observability,
                         int coreMax) {
        this.initialClusterSize = initialClusterSize;
        this.basePort = basePort;
        this.baseMgmtPort = baseMgmtPort;
        this.baseAppHttpPort = baseAppHttpPort;
        this.nodeIdPrefix = nodeIdPrefix;
        this.targetClusterSize = initialClusterSize;
        this.effectiveSize = new AtomicInteger(initialClusterSize);
        this.configProvider = configProvider;
        this.observability = observability;
        this.coreMax = coreMax;
    }

    /// TEST SEAM (#336 probe) — install a decorator that wraps the base [EmberComputeProvider]
    /// used by EVERY node in this cluster. MUST be called before [#start] (the shared
    /// [EnvironmentIntegration] is built lazily on first node creation and then frozen). Used by
    /// the provisioning-recovery probe to inject a transient burst of `provision` failures that
    /// trips the CTM #148 circuit breaker, then delegate to the real provider so recovery can be
    /// observed. Production paths never call this (decorator stays identity).
    public void withComputeProviderDecorator(Functions.Fn1<ComputeProvider, ComputeProvider> decorator) {
        computeProviderDecorator.set(decorator);
    }

    /// TEST SEAM (#198 §7) — select the cluster-level API-version detection mode for every node.
    /// MUST be called before [#start]. Deploys the same compiled slice in header mode (versions
    /// share a bare path, selected from the `headerName` request header) instead of path mode.
    ///
    /// @param detection  the API-version detection mode
    /// @param headerName the version header name (header mode)
    @Contract
    public void withApiVersioningDetection(ApiVersioningDetection detection, String headerName) {
        apiVersioningDetection.set(detection);
        apiVersionHeaderName.set(headerName);
    }

    /// Set the app-HTTP security mode and API keys for EVERY node in this cluster. MUST be called
    /// before [#start]. Forge calls this from the sibling `aether.toml`'s `[app-http]` section, the
    /// same file [#withApiVersioningDetection] is fed from.
    ///
    /// Passing [SecurityMode#NONE] keeps the prior behaviour exactly (deny-unless-public), so callers
    /// that never invoke this are byte-for-byte unchanged.
    @Contract
    public void withAppHttpSecurity(SecurityMode securityMode, Map<String, ApiKeyEntry> apiKeys) {
        appHttpSecurityMode.set(securityMode);
        appHttpApiKeys.set(Map.copyOf(apiKeys));
    }

    /// TEST SEAM (streaming A-WAL) — set the writable, restart-stable base data dir for EVERY node in
    /// this cluster (opt-in). MUST be called before [#start]. Each node then gets a `storageConfig`
    /// `artifacts` [StorageConfig] keyed by its STABLE node id, so a `stop()`→`start()` restart reuses
    /// the same dir and the stream WAL/segments survive. Production node paths never call this (the
    /// default empty `storageConfig` keeps the read-only `/data` fallback → WAL off); Forge — a local
    /// dev simulator, not a production path — calls it at startup (`ForgeServer.applyForgeDataDir`) to
    /// home node data under `$AETHER_HOME/forge-data`. See [#perNodeStorageConfig].
    ///
    /// @param baseDir writable base dir (e.g. a JUnit `@TempDir`) under which each node gets `<baseDir>/<nodeId>`
    @Contract
    public void withDataBaseDir(Path baseDir) {
        dataBaseDir.set(Option.option(baseDir));
    }

    /// TEST SEAM (#491 pinned convergence variant) — raise the SWIM suspect timeout, the transport
    /// hello timeout, and the membership split timeout for EVERY node in this cluster so a single
    /// graceful owner-kill does not trip the transient QuorumLost→PASSIVE window's false-removal
    /// cascade (LIVE survivors evicted as stale links → SWIM-DEAD-stuck → quorum-loss self-fence).
    /// MUST be called before [#start]. The killed owner still departs via graceful SWIM leave
    /// (`handlePeerLeft`), NOT suspect-timeout, so raising these does NOT slow the real failover — it
    /// only keeps LIVE survivors from being falsely evicted/FAULTY during the transient. Harness-scoped
    /// (Ember is the in-JVM test harness, not shipped runtime); production paths never call this.
    @Contract
    public void withRaisedSwimTimeouts() {
        raisedSwimTimeouts.set(true);
    }

    /// #715 — override this instance's cluster QUIC/SWIM identity secret. MUST be called before
    /// [#start] (nodes derive their certificate/gossip-key material from this secret at creation).
    /// Production callers never call this: each instance otherwise gets a fresh `SecureRandom`
    /// secret, so two independently-created `EmberCluster`/Forge instances never share cluster
    /// identity and cannot admit each other's nodes. This setter is the ONLY sanctioned way two
    /// instances can join one cluster — callers who want that pass the SAME bytes to both.
    @Contract
    public void withClusterSecret(byte[] secret) {
        clusterSecret.set(secret.clone());
    }

    /// TEST SEAM (#715) — exposes this instance's current cluster QUIC/SWIM identity secret, so
    /// tests can pin whether distinct `EmberCluster` instances are cryptographically distinguishable.
    byte[] currentClusterSecret() {
        return clusterSecret.get()
                            .clone();
    }

    /// TEST SEAM (#715) — exposes the `certificateProvider` actually present in the most recently
    /// constructed node's real [AetherNodeConfig] object, so tests can pin whether SWIM gossip
    /// encryption is wired for real nodes rather than inferring it from a value set independently of
    /// the config that was actually built (reverting the config argument alone flips this, since it
    /// reads the config object itself).
    Option<CertificateProvider> wiredCertificateProvider() {
        return lastNodeConfig.get()
                             .flatMap(AetherNodeConfig::certificateProvider);
    }

    /// TEST SEAM (#715) — exposes the QUIC `TlsConfig` actually present in the most recently
    /// constructed node's real [AetherNodeConfig] object — the same value [AetherNode]'s QUIC
    /// transport uses — so tests can build genuine cross-instance QUIC clients/servers through the
    /// production wiring instead of re-deriving TLS material independently of it.
    Option<TlsConfig> wiredQuicTls() {
        return lastNodeConfig.get()
                             .map(AetherNodeConfig::quicTls);
    }

    private EnvironmentIntegration emberEnvironment() {
        return emberEnvironmentRef.updateAndGet(this::resolveEnvironment);
    }

    private EnvironmentIntegration resolveEnvironment(EnvironmentIntegration existing) {
        return Option.option(existing).or(() -> EnvironmentIntegration.withCompute(computeProviderDecorator.get()
                                                                                                           .apply(new EmberComputeProvider())));
    }

    public static EmberCluster emberCluster() {
        return emberCluster(5);
    }

    public static EmberCluster emberCluster(int initialSize) {
        return new EmberCluster(initialSize,
                                DEFAULT_BASE_PORT,
                                DEFAULT_BASE_MGMT_PORT,
                                DEFAULT_BASE_APP_HTTP_PORT,
                                "node",
                                Option.empty(),
                                ObservabilityConfig.DEFAULT,
                                0);
    }

    public static EmberCluster emberCluster(int initialSize, int basePort, int baseMgmtPort) {
        return new EmberCluster(initialSize,
                                basePort,
                                baseMgmtPort,
                                DEFAULT_BASE_APP_HTTP_PORT,
                                "node",
                                Option.empty(),
                                ObservabilityConfig.DEFAULT,
                                0);
    }

    public static EmberCluster emberCluster(int initialSize, int basePort, int baseMgmtPort, String nodeIdPrefix) {
        return new EmberCluster(initialSize,
                                basePort,
                                baseMgmtPort,
                                DEFAULT_BASE_APP_HTTP_PORT,
                                nodeIdPrefix,
                                Option.empty(),
                                ObservabilityConfig.DEFAULT,
                                0);
    }

    public static EmberCluster emberCluster(int initialSize,
                                            int basePort,
                                            int baseMgmtPort,
                                            int baseAppHttpPort,
                                            String nodeIdPrefix) {
        return emberCluster(initialSize,
                            basePort,
                            baseMgmtPort,
                            baseAppHttpPort,
                            nodeIdPrefix,
                            Option.empty(),
                            ObservabilityConfig.DEFAULT,
                            0);
    }

    public static EmberCluster emberCluster(int initialSize,
                                            int basePort,
                                            int baseMgmtPort,
                                            int baseAppHttpPort,
                                            String nodeIdPrefix,
                                            Option<ConfigurationProvider> configProvider) {
        return emberCluster(initialSize,
                            basePort,
                            baseMgmtPort,
                            baseAppHttpPort,
                            nodeIdPrefix,
                            configProvider,
                            ObservabilityConfig.DEFAULT,
                            0);
    }

    public static EmberCluster emberCluster(int initialSize,
                                            int basePort,
                                            int baseMgmtPort,
                                            int baseAppHttpPort,
                                            String nodeIdPrefix,
                                            Option<ConfigurationProvider> configProvider,
                                            ObservabilityConfig observability) {
        return emberCluster(initialSize,
                            basePort,
                            baseMgmtPort,
                            baseAppHttpPort,
                            nodeIdPrefix,
                            configProvider,
                            observability,
                            0);
    }

    public static EmberCluster emberCluster(int initialSize,
                                            int basePort,
                                            int baseMgmtPort,
                                            int baseAppHttpPort,
                                            String nodeIdPrefix,
                                            Option<ConfigurationProvider> configProvider,
                                            ObservabilityConfig observability,
                                            int coreMax) {
        return new EmberCluster(initialSize,
                                basePort,
                                baseMgmtPort,
                                baseAppHttpPort,
                                nodeIdPrefix,
                                configProvider,
                                observability,
                                coreMax);
    }

    public Promise<Unit> start() {
        return start(Set.of());
    }

    /// TEST SEAM (#509 probe) — start the cluster with `heldBackNodeIds` CREATED but NOT started.
    /// Every node, held or started, is created with the SAME complete `initialNodes` topology list, so
    /// each started node's configured core set (and therefore its `MembershipFsm` config seed) names
    /// all [#initialClusterSize] members while the held ones are physically absent. That is exactly
    /// the "configured stable-id members are merely SLOW to rejoin" shape, produced deterministically
    /// instead of by racing a real restart. [#startHeldBackNodes] brings them up afterwards, on their
    /// original identities, ports and slots. An id naming no member of the initial set is ignored.
    ///
    /// Failure semantics for the STARTED subset are unchanged (all-or-nothing: any failure stops the
    /// successfully-started nodes and clears cluster state). Held-back nodes take no part in that
    /// cleanup beyond being dropped — they were never started, so there is nothing to stop.
    ///
    /// @param heldBackNodeIds ids of initial nodes whose `start()` is deferred; empty = plain [#start]
    public Promise<Unit> start(Set<String> heldBackNodeIds) {
        log.info("Starting Ember cluster with {} nodes on ports {}-{} ({} held back: {})",
                 initialClusterSize,
                 basePort,
                 basePort + initialClusterSize - 1,
                 heldBackNodeIds.size(),
                 heldBackNodeIds);
        int poolSize = 2 * targetClusterSize;

        availableSlots.clear();
        for (int i = 0; i < poolSize; i++) {
            availableSlots.offer(i);
        }

        var initialNodes = new ArrayList<NodeInfo>();

        for (int i = 1; i <= initialClusterSize; i++) {
            var slot = availableSlots.poll();
            var nodeId = nodeId(nodeIdPrefix + "-" + i).unwrap();
            var port = basePort + slot;
            var info = NodeInfo.nodeInfo(nodeId, nodeAddress("localhost", port).unwrap());

            initialNodes.add(info);
            nodeInfos.put(nodeId.id(), info);
            slotsByNodeId.put(nodeId.id(), slot);
        }

        nodeCounter.set(initialClusterSize);
        var startPromises = new ArrayList<Promise<NodeStartResult>>();

        for (int i = 0; i < initialClusterSize; i++) {
            var nodeInfo = initialNodes.get(i);
            var nodeIdStr = nodeInfo.id().id();
            var slot = slotsByNodeId.get(nodeIdStr);
            var port = basePort + slot;
            var mgmtPort = baseMgmtPort + slot;
            var appHttpPort = baseAppHttpPort + slot;
            var node = createNode(nodeInfo.id(), port, mgmtPort, appHttpPort, initialNodes, false);

            if (heldBackNodeIds.contains(nodeIdStr)) {
                heldBackNodes.put(nodeIdStr, node);
                log.info("Node {} created on port {} but HELD BACK (start deferred)", nodeIdStr, port);
                continue;
            }

            nodes.put(nodeIdStr, node);
            startPromises.add(node.start()
                                  .map(_ -> NodeStartResult.nodeStartResult(nodeIdStr,
                                                                            port,
                                                                            mgmtPort,
                                                                            Option.none()))
                                  .recover(cause -> NodeStartResult.nodeStartResult(nodeIdStr,
                                                                                    port,
                                                                                    mgmtPort,
                                                                                    Option.some(cause))));
        }

        return Promise.allOf(startPromises).flatMap(this::handleStartResults);
    }

    /// TEST SEAM (#509 probe) — start the instances [#start] created and held back, in their original
    /// identities/ports/slots, registering each in [#nodes] only once its `start()` has SUCCEEDED (so a
    /// concurrent [#currentLeader] never answers from a node that is still coming up). Succeeds
    /// trivially when nothing was held back. Failures are accumulated and propagated; unlike [#start]
    /// no cleanup of the already-running cluster is attempted, because a held-back start failure is a
    /// probe-setup failure, not a formation failure.
    public Promise<Unit> startHeldBackNodes() {
        var heldIds = List.copyOf(heldBackNodes.keySet());

        if (heldIds.isEmpty()) {
            return Promise.success(Unit.unit());
        }

        log.info("Starting {} held-back node(s): {}", heldIds.size(), heldIds);
        var startPromises = heldIds.stream().map(this::startHeldBackNode).toList();

        return Promise.allOf(startPromises).flatMap(EmberCluster::allHeldBackStartedOrFail);
    }

    private Promise<Unit> startHeldBackNode(String nodeIdStr) {
        return Option.option(heldBackNodes.remove(nodeIdStr))
                     .map(node -> startHeldBackInstance(nodeIdStr, node))
                     .or(() -> nodeNotFound(nodeIdStr));
    }

    private Promise<Unit> startHeldBackInstance(String nodeIdStr, AetherNode node) {
        return node.start()
                   .onSuccess(_ -> nodes.put(nodeIdStr, node))
                   .onSuccess(_ -> log.info("Held-back node {} started and rejoined the cluster", nodeIdStr))
                   .onFailure(cause -> log.error("Held-back node {} failed to start: {}",
                                                 nodeIdStr,
                                                 cause.message()));
    }

    private static Promise<Unit> allHeldBackStartedOrFail(List<Result<Unit>> results) {
        return Result.allOf(results)
                     .mapToUnit()
                     .async();
    }

    private record NodeStartResult(String nodeId, int port, int mgmtPort, Option<Cause> failure) {
        static NodeStartResult nodeStartResult(String nodeId, int port, int mgmtPort, Option<Cause> failure) {
            return new NodeStartResult(nodeId, port, mgmtPort, failure);
        }

        boolean succeeded() {
            return failure.isEmpty();
        }
    }

    private Promise<Unit> handleStartResults(List<Result<NodeStartResult>> results) {
        var nodeResults = results.stream().flatMap(Result::stream).toList();
        var failed = nodeResults.stream().filter(r -> !r.succeeded()).toList();
        var succeeded = nodeResults.stream().filter(NodeStartResult::succeeded).toList();
        // Nodes ATTEMPTED, not configured: with the #509 held-back seam these differ.
        var attempted = nodeResults.size();

        if (failed.isEmpty()) {
            log.info("All nodes started, waiting for cluster stabilization...");

            return Promise.promise(timeSpan(2).seconds(),
                                   () -> Result.success(Unit.unit()))
                          .onSuccess(_ -> log.info("Ember cluster started with {} of {} nodes ({} held back)",
                                                   attempted,
                                                   initialClusterSize,
                                                   heldBackNodes.size()));
        }

        for (var f : failed) {
            f.failure()
             .onPresent(cause -> log.error("Node {} failed to start on port {} (mgmt: {}): {}",
                                           f.nodeId(),
                                           f.port(),
                                           f.mgmtPort(),
                                           cause.message()));
        }

        log.error("Cluster startup failed: {} of {} nodes failed to start", failed.size(), attempted);
        var stopPromises = succeeded.stream()
                                    .map(r -> Option.option(nodes.get(r.nodeId()))
                                                    .map(node -> node.stop()
                                                                     .timeout(NODE_TIMEOUT)
                                                                     .recover(_ -> Unit.unit()))
                                                    .or(Promise.success(Unit.unit())))
                                    .toList();

        return Promise.allOf(stopPromises)
                      .mapToUnit()
                      .onSuccess(this::clearClusterStateOnFailure)
                      .flatMap(_ -> failed.getFirst()
                                          .failure()
                                          .<Promise<Unit>> map(Cause::promise)
                                          .or(Promise.success(Unit.unit())));
    }

    private void clearClusterStateOnFailure(Unit unit) {
        nodes.clear();
        // Held-back instances were never started, so dropping the references disposes them fully.
        heldBackNodes.clear();
        nodeInfos.clear();
        instanceTags.clear();
        slotsByNodeId.clear();
        availableSlots.clear();
        nodeCounter.set(0);
    }

    public Promise<Unit> stop() {
        log.info("Stopping Ember cluster");
        rollingRestartTask.cancel();
        rollingRestartActive.set(false);
        var stopPromises = nodes.values().stream().map(node -> node.stop()
                                                                   .timeout(NODE_TIMEOUT)).toList();

        return Promise.allOf(stopPromises)
                      .map(_ -> Unit.unit())
                      .onSuccess(this::clearClusterState);
    }

    private void clearClusterState(Unit unit) {
        nodes.clear();
        // Still-held instances were never started — nothing to stop, dropping them disposes them.
        heldBackNodes.clear();
        nodeInfos.clear();
        instanceTags.clear();
        slotsByNodeId.clear();
        availableSlots.clear();
        log.info("Ember cluster stopped");
    }

    /// Adds a node with NO role label — production-default shape, and byte-identical to the behaviour
    /// every existing forge test relies on. Pinned by `EmberAddNodeRoleLabelTest`.
    public Promise<NodeId> addNode() {
        return addNode(Map.of());
    }

    /// Adds a node advertising `role=worker` (#590 fidelity restoration).
    ///
    /// ## Why this exists, and why it is the ROLE LABEL rather than a peer-list tweak
    ///
    /// Community-tier mechanisms are gated on a node being positively known NOT to be a core. The
    /// chain is `TopologyObserver.coreNodes()` → `MembershipFsm.coreObservedMembers` →
    /// `isCoreCountedMember()` → `MemberDescriptor.isCore()` → `isCoreRole(role) = !"worker".equals(role)`
    /// — so **a blank or unknown role counts as CORE**, deliberately (fencing on an unresolved view is
    /// the dangerous direction).
    ///
    /// In production that role is the self-asserted SWIM label `NodeInfo.LABEL_ROLE`, set from
    /// `AETHER_ROLE` (`Main.collectNodeLabels`) or the cloud providers' `aether-role`. **Ember set no
    /// label at all**, so every in-JVM node read as a core and the #590 core-absence fence was
    /// structurally suppressed — measured on a live 6-node cluster as `armed=true sinceLastPingMs=40922
    /// remainingMs=0 thresholdMs=10000 fenced=false`: every precondition met, window exceeded fourfold,
    /// correctly suppressed.
    ///
    /// That is a harness INFIDELITY, not a product defect, and this restores the production shape at
    /// its cause. Excluding the joiner from its own `coreNodes` list would have treated a symptom and
    /// left the label — the thing production actually keys on — still absent.
    public Promise<NodeId> addWorkerNode() {
        return addNode(Map.of(NodeInfo.LABEL_ROLE, WORKER_ROLE));
    }

    /// The one place a provisioning role becomes a SWIM label. `Verify.Is.present` is non-null AND
    /// non-blank, so an unset role yields an EMPTY map rather than `role=""` — a node advertising a
    /// blank role and a node advertising none are indistinguishable to `isCoreRole` today, but only
    /// the empty map matches what production actually puts on the wire.
    private static Map<String, String> roleLabels(String role) {
        return Verify.Is.present(role)
               ? Map.of(NodeInfo.LABEL_ROLE, role)
               : Map.of();
    }

    /// Shared implementation. `labels` are attached to the node's advertised `NodeInfo`, which is what
    /// peers and its own `MemberDescriptor` classify from — the same field production populates.
    public Promise<NodeId> addNode(Map<String, String> labels) {
        var slotOpt = Option.option(availableSlots.poll());

        if (slotOpt.isEmpty()) {
            log.warn("Slot pool exhausted — no available ports for new node");

            return EnvironmentError.operationNotSupported("No available port slots for new node").promise();
        }

        var slot = slotOpt.unwrap();
        var nodeNum = nodeCounter.incrementAndGet();
        var nodeId = nodeId(nodeIdPrefix + "-" + nodeNum).unwrap();
        var port = basePort + slot;
        var mgmtPort = baseMgmtPort + slot;
        var appHttpPort = baseAppHttpPort + slot;
        var info = labels.isEmpty()
                   ? NodeInfo.nodeInfo(nodeId, nodeAddress("localhost", port).unwrap())
                   : NodeInfo.nodeInfo(nodeId, nodeAddress("localhost", port).unwrap(), labels);

        log.info("Adding new node {} on port {} labels={}", nodeId.id(), port, labels);
        slotsByNodeId.put(nodeId.id(), slot);
        nodeInfos.put(nodeId.id(), info);
        var allNodes = new ArrayList<>(nodeInfos.values());
        var node = createNode(nodeId, port, mgmtPort, appHttpPort, allNodes, false);

        nodes.put(nodeId.id(), node);

        return node.start()
                   .map(_ -> nodeId)
                   .onSuccess(_ -> log.info("Node {} joined the cluster",
                                            nodeId.id()));
    }

    public Promise<Unit> killNode(String nodeIdStr) {
        return killNode(nodeIdStr, true);
    }

    /// Black-hole fault injection (silent death) — distinct from [#killNode]. The node stays
    /// registered in the cluster and its QUIC channels stay OPEN, but it silently drops all
    /// inbound and outbound application traffic (SWIM probes/acks, ClusterSync ping/pong,
    /// consensus). Peers continue to believe the link is connected, exactly as after a hard
    /// `docker kill` with QUIC MAX_IDLE_TIMEOUT disabled. Reproduces the Docker-only
    /// silent-death failure-detection bug on the fast in-process loop.
    public Promise<Unit> blackhole(String nodeIdStr) {
        return Option.option(nodes.get(nodeIdStr))
                     .onPresent(node -> log.info("Black-holing node {} (silent death; channels stay open)",
                                                 nodeIdStr))
                     .onPresent(node -> node.blackhole(true))
                     .map(_ -> Promise.success(Unit.unit()))
                     .or(() -> nodeNotFound(nodeIdStr));
    }

    public Promise<Unit> killNode(String nodeIdStr, boolean graceful) {
        return Option.option(nodes.get(nodeIdStr))
                     .map(node -> killNodeInternal(nodeIdStr, node, graceful))
                     .or(() -> nodeNotFound(nodeIdStr));
    }

    private Promise<Unit> nodeNotFound(String nodeIdStr) {
        log.warn("Node {} not found", nodeIdStr);

        return Promise.success(Unit.unit());
    }

    private Promise<Unit> killNodeInternal(String nodeIdStr, AetherNode node, boolean graceful) {
        var timeout = graceful
                      ? NODE_TIMEOUT
                      : TimeSpan.timeSpan(1).seconds();

        log.info("{} node {}",
                 graceful
                 ? "Stopping"
                 : "Force-killing",
                 nodeIdStr);
        nodes.remove(nodeIdStr);
        nodeInfos.remove(nodeIdStr);
        instanceTags.remove(nodeIdStr);
        var slotOpt = Option.option(slotsByNodeId.remove(nodeIdStr));

        return node.stop()
                   .timeout(timeout)
                   .recover(_ -> Unit.unit())
                   .onSuccess(_ -> slotOpt.onPresent(availableSlots::offer))
                   .onSuccess(_ -> log.info("Node {} removed from cluster", nodeIdStr));
    }

    public int targetClusterSize() {
        return targetClusterSize;
    }

    @Contract
    public void setClusterSize(int newSize) {
        effectiveSize.set(newSize);
        var message = new TopologyManagementMessage.SetClusterSize(newSize);

        nodes.values().forEach(node -> node.route(message));
        log.info("SetClusterSize({}) routed to {} nodes", newSize, nodes.size());
    }

    public int effectiveClusterSize() {
        return effectiveSize.get();
    }

    public Option<String> currentLeader() {
        return Option.option(nodes.values().stream().findFirst().orElse(null))
                     .flatMap(AetherNode::leader)
                     .map(NodeId::id);
    }

    public ClusterStatus status() {
        var nodeStatuses = nodes.entrySet().stream().map(this::toNodeStatus).toList();

        return new ClusterStatus(nodeStatuses, currentLeader().or("none"));
    }

    private NodeStatus toNodeStatus(Map.Entry<String, AetherNode> entry) {
        var clusterPort = nodeInfos.get(entry.getKey()).address().port();

        return new NodeStatus(entry.getKey(),
                              clusterPort,
                              baseMgmtPort + (clusterPort - basePort),
                              "healthy",
                              currentLeader().map(leaderId -> leaderId.equals(entry.getKey())).or(false));
    }

    public Option<AetherNode> getNode(String nodeIdStr) {
        return Option.option(nodes.get(nodeIdStr));
    }

    /// TEST SEAM (#644 contract test) — the created-but-never-started instance for a held-back id,
    /// while it is still held back ([#startHeldBackNodes] moves it into [#getNode]'s view). Lets a
    /// test observe what a constructed-but-unstarted node holds (it must hold NO armed periodic
    /// work) without reaching into this class's private state.
    public Option<AetherNode> heldBackNode(String nodeIdStr) {
        return Option.option(heldBackNodes.get(nodeIdStr));
    }

    public List<AetherNode> allNodes() {
        return new ArrayList<>(nodes.values());
    }

    public int nodeCount() {
        return nodes.size();
    }

    public Option<Integer> getLeaderManagementPort() {
        return currentLeader().flatMap(leaderId -> Option.option(nodeInfos.get(leaderId)))
                            .map(info -> baseMgmtPort + (info.address()
                                                             .port() - basePort));
    }

    public int getAppHttpPort() {
        return baseAppHttpPort;
    }

    public List<NodeInfo> getNodeInfos() {
        return List.copyOf(nodeInfos.values());
    }

    public List<Integer> getAvailableAppHttpPorts() {
        return nodes.entrySet()
                    .stream()
                    .filter(entry -> entry.getValue()
                                          .appHttpServer()
                                          .isRouteReady())
                    .map(entry -> slotsByNodeId.get(entry.getKey()))
                    .filter(slot -> slot != null)
                    .map(slot -> baseAppHttpPort + slot)
                    .sorted()
                    .toList();
    }

    private static TlsConfig buildForgeQuicTls(NodeId nodeId, CertificateProvider provider) {
        return TlsConfig.fromProvider(provider,
                                      nodeId.id(),
                                      "localhost")
                        .unwrap();
    }

    // #491 pinned convergence variant — raised timeouts applied when [#withRaisedSwimTimeouts] was set.
    // hello ×10 (5s→50s) so the transport stale-link eviction (staleness ~ helloTimeout×3) cannot fire
    // inside the transient PASSIVE window; SWIM suspect 10s→60s so LIVE survivors are not marked DEAD;
    // membership split 15s→60s so the minority does not self-fence during the same window.
    private static final TimeSpan RAISED_HELLO_TIMEOUT = timeSpan(50).seconds();
    private static final TimeoutsConfig RAISED_TIMEOUTS = raisedSwimTimeoutsConfig();

    private static final Option<MembershipConfig> RAISED_MEMBERSHIP = Option.some(new MembershipConfig(timeSpan(60).seconds()));

    private static TimeoutsConfig raisedSwimTimeoutsConfig() {
        var defaults = TimeoutsConfig.timeoutsConfig();
        var swim = new TimeoutsConfig.SwimTimeouts(defaults.swim().period(),
                                                   defaults.swim().probeTimeout(),
                                                   timeSpan(60).seconds());

        return new TimeoutsConfig(defaults.invocation(),
                                  defaults.forwarding(),
                                  defaults.deployment(),
                                  defaults.rollingUpdate(),
                                  defaults.cluster(),
                                  defaults.consensus(),
                                  defaults.election(),
                                  swim,
                                  defaults.observability(),
                                  defaults.dht(),
                                  defaults.worker(),
                                  defaults.security(),
                                  defaults.repository(),
                                  defaults.scaling(),
                                  defaults.storageMaintenance());
    }

    private AetherNode createNode(NodeId nodeId,
                                  int port,
                                  int mgmtPort,
                                  int appHttpPort,
                                  List<NodeInfo> coreNodes,
                                  boolean activationGated) {
        var raised = raisedSwimTimeouts.get();
        var helloTimeout = raised
                           ? RAISED_HELLO_TIMEOUT
                           : TopologyConfig.DEFAULT_HELLO_TIMEOUT;
        var nodeTimeouts = raised
                           ? RAISED_TIMEOUTS
                           : TimeoutsConfig.timeoutsConfig();
        Option<MembershipConfig> membership = raised
                                              ? RAISED_MEMBERSHIP
                                              : Option.empty();
        var topology = new TopologyConfig(nodeId,
                                          targetClusterSize,
                                          timeSpan(1).seconds(),
                                          timeSpan(10).seconds(),
                                          helloTimeout,
                                          coreNodes,
                                          Option.empty(),
                                          BackoffConfig.DEFAULT,
                                          coreMax,
                                          targetClusterSize);
        var certificateProvider = SelfSignedCertificateProvider.selfSignedCertificateProvider(clusterSecret.get()).unwrap();
        var quicTls = buildForgeQuicTls(nodeId, certificateProvider);
        var config = new AetherNodeConfig(topology,
                                          ProtocolConfig.testConfig(),
                                          SliceActionConfig.sliceActionConfig(),
                                          SliceConfig.sliceConfig(),
                                          mgmtPort,
                                          DHTConfig.FULL,
                                          DHTConfig.CACHE_DEFAULT,
                                          Option.empty(),
                                          quicTls,
                                          TtmConfig.ttmConfig(),
                                          RollbackConfig.rollbackConfig(),
                                          AppHttpConfig.appHttpConfig(true,
                                                                      appHttpPort,
                                                                      appHttpApiKeys.get(),
                                                                      AppHttpConfig.DEFAULT_MAX_REQUEST_SIZE,
                                                                      appHttpSecurityMode.get(),
                                                                      Option.empty(),
                                                                      HttpProtocol.H1,
                                                                      apiVersioningDetection.get(),
                                                                      apiVersionHeaderName.get())
                                                       .unwrap(),
                                          ControllerConfig.forgeDefaults(),
                                          configProvider,
                                          Option.some(emberEnvironment()),
                                          AutoHealConfig.DEFAULT,
                                          observability,
                                          ClusterDeploymentManager.DeploymentAtomicity.ALL_OR_NOTHING,
                                          activationGated,
                                          nodeTimeouts,
                                          Option.some(certificateProvider),
                                          Option.empty(),
                                          AetherNodeConfig.DeploymentDefaults.DEFAULT,
                                          HttpProtocol.H1,
                                          perNodeStorageConfig(nodeId),
                                          Option.empty(),
                                          membership,

        // membership-config override: raised split-timeout ONLY for the #491 pinned convergence variant
        // (via withRaisedSwimTimeouts); otherwise none — forge nodes use MembershipConfig defaults
        StreamingConfig.streamingConfig(),
                                          ClusterFormationConfig.defaults(),

        // #298 — in-process nodes never pass through Main, so no cluster
        // name is stamped and the fleet cap stays inert here. Forge has no
        // cloud provider to cap in the first place.
        Option.empty());

        lastNodeConfig.set(Option.some(config));
        // Single-JVM hosting: when this node's SelfDrainCoordinator completes its drain
        // phase, do NOT halt the JVM (would kill all other in-process nodes). Stop the
        // node gracefully and remove it from the cluster's registry instead.
        Runnable jvmExit = () -> handleSelfDrain(nodeId.id());

        return AetherNode.aetherNode(config, jvmExit).unwrap();
    }

    /// Per-node `storageConfig` map for [#createNode]. When a writable base dir was set via
    /// [#withDataBaseDir] (opt-in), each node gets an `artifacts` [StorageConfig] whose `diskPath` is
    /// `<baseDir>/<nodeId>/storage` — turning the artifact disk tier AND the per-partition stream WAL
    /// (`<...>/stream-segments/<nodeId>/wal`) writable so streaming runs crash-durable. The id-keyed
    /// dir is restart-stable: [#start] after [#stop] regenerates the same `<nodeIdPrefix>-<i>` ids, so
    /// each node reuses its dir and the WAL/segments survive the restart. Empty map ⇒ default
    /// behaviour (read-only `/data` fallback → WAL off), so non-opted-in callers are unaffected.
    private Map<String, StorageConfig> perNodeStorageConfig(NodeId nodeId) {
        return dataBaseDir.get()
                          .map(base -> artifactsStorageConfig(base, nodeId))
                          .or(Map.of());
    }

    private static Map<String, StorageConfig> artifactsStorageConfig(Path base, NodeId nodeId) {
        var nodeDir = base.resolve(nodeId.id());
        var defaults = StorageConfig.storageConfig();
        var config = StorageConfig.storageConfig(defaults.memoryMaxBytes(),
                                                 defaults.diskMaxBytes(),
                                                 nodeDir.resolve("storage").toString(),
                                                 nodeDir.resolve("metadata-snapshots").toString(),
                                                 defaults.snapshotMutationThreshold(),
                                                 defaults.snapshotMaxInterval(),
                                                 defaults.snapshotRetentionCount());

        return Map.of("artifacts", config);
    }

    private void handleSelfDrain(String nodeIdStr) {
        var node = nodes.remove(nodeIdStr);

        if (node == null) {
            return;
        }

        node.stop().await(timeSpan(10).seconds()).onFailure(cause -> {});
    }

    public List<NodeMetrics> nodeMetrics() {
        var leaderId = currentLeader().or("");
        var leaderNode = nodes.get(leaderId);

        if (leaderNode == null) {
            if (nodes.isEmpty()) {
                return List.of();
            }

            leaderNode = nodes.values().iterator().next();
        }

        var allMetrics = leaderNode.metricsCollector().allMetrics();

        return allMetrics.entrySet()
                         .stream()
                         .map(entry -> toNodeMetrics(entry.getKey().id(),
                                                     entry.getValue(),
                                                     leaderId))
                         .toList();
    }

    public AetherAggregates aetherAggregates() {
        var leaderId = currentLeader().or("");
        var leaderNode = nodes.get(leaderId);

        if (leaderNode == null) {
            if (nodes.isEmpty()) {
                return new AetherAggregates(0, 1.0, 0, 0, 0, 0);
            }

            leaderNode = nodes.values().iterator().next();
        }

        var allNodeMetrics = leaderNode.metricsCollector().allMetrics();
        long totalInvocations = 0;
        long totalSuccess = 0;
        long totalFailure = 0;
        double totalDurationNs = 0.0;

        for (var nodeMetrics : allNodeMetrics.values()) {
            for (var entry : nodeMetrics.entrySet()) {
                var key = entry.getKey();

                if (!key.startsWith("inv|")) {
                    continue;
                }

                if (key.endsWith("|count")) {
                    totalInvocations += entry.getValue().longValue();
                } else if (key.endsWith("|success")) {
                    totalSuccess += entry.getValue().longValue();
                } else if (key.endsWith("|failure")) {
                    totalFailure += entry.getValue().longValue();
                } else if (key.endsWith("|totalNs")) {
                    totalDurationNs += entry.getValue();
                }
            }
        }

        long deltaInvocations = Math.max(0, totalInvocations - lastTotalInvocations);
        long deltaSuccess = Math.max(0, totalSuccess - lastTotalSuccess);
        double instantRps = deltaInvocations;
        double instantSuccessRate = deltaInvocations > 0
                                    ? (double) deltaSuccess / deltaInvocations
                                    : 1.0;
        double avgLatencyMs = totalInvocations > 0
                              ? totalDurationNs / totalInvocations / 1_000_000.0
                              : 0.0;

        emaRps = EMA_ALPHA * instantRps + (1 - EMA_ALPHA) * emaRps;
        emaSuccessRate = EMA_ALPHA * instantSuccessRate + (1 - EMA_ALPHA) * emaSuccessRate;
        emaAvgLatencyMs = EMA_ALPHA * avgLatencyMs + (1 - EMA_ALPHA) * emaAvgLatencyMs;
        lastTotalInvocations = totalInvocations;
        lastTotalSuccess = totalSuccess;

        return new AetherAggregates(emaRps,
                                    emaSuccessRate * 100.0,
                                    emaAvgLatencyMs,
                                    totalInvocations,
                                    totalSuccess,
                                    totalFailure);
    }

    public List<InvocationDetail> invocationDetails() {
        var allNodeMetrics = leaderOrFirstNodeMetrics();

        if (allNodeMetrics.isEmpty()) {
            return List.of();
        }

        var aggregated = new HashMap<String, long[]>();

        for (var nodeMetrics : allNodeMetrics.values()) {
            for (var entry : nodeMetrics.entrySet()) {
                var key = entry.getKey();

                if (!key.startsWith("inv|")) {
                    continue;
                }

                var parts = key.split("\\|");

                if (parts.length != 4) {
                    continue;
                }

                var compositeKey = parts[1] + "|" + parts[2];
                var values = aggregated.computeIfAbsent(compositeKey, _ -> new long[4]);

                accumulateInvocationMetric(values, parts[3], entry.getValue());
            }
        }

        return aggregated.entrySet()
                         .stream()
                         .map(EmberCluster::toInvocationDetail)
                         .toList();
    }

    private static void accumulateInvocationMetric(long[] values, String suffix, double value) {
        switch (suffix) {
            case "count" -> values[0] += (long) value;
            case "success" -> values[1] += (long) value;
            case "failure" -> values[2] += (long) value;
            case "totalNs" -> values[3] += (long) value;
            default -> {}
        }
    }

    private static InvocationDetail toInvocationDetail(Map.Entry<String, long[]> entry) {
        var parts = entry.getKey().split("\\|", 2);
        var values = entry.getValue();
        var count = values[0];
        var avgMs = count > 0
                    ? (double) values[3] / count / 1_000_000.0
                    : 0.0;

        return new InvocationDetail(parts[0], parts[1], count, values[1], values[2], avgMs);
    }

    private Map<NodeId, Map<String, Double>> leaderOrFirstNodeMetrics() {
        var leaderId = currentLeader().or("");
        var leaderNode = nodes.get(leaderId);

        if (leaderNode == null) {
            if (nodes.isEmpty()) {
                return Map.of();
            }

            leaderNode = nodes.values().iterator().next();
        }

        return leaderNode.metricsCollector()
                         .allMetrics();
    }

    private NodeMetrics toNodeMetrics(String nodeId, Map<String, Double> metrics, String leaderId) {
        var cpuUsage = metrics.getOrDefault("cpu.usage", 0.0);
        var heapUsed = metrics.getOrDefault("heap.used", 0.0);
        var heapMax = metrics.getOrDefault("heap.max", 1.0);

        return new NodeMetrics(nodeId,
                               leaderId.equals(nodeId),
                               cpuUsage,
                               (long)(heapUsed / 1024 / 1024),
                               (long)(heapMax / 1024 / 1024));
    }

    public record NodeStatus(String id, int port, int mgmtPort, String state, boolean isLeader) {}

    public record ClusterStatus(List<NodeStatus> nodes, String leaderId) {}

    public record NodeMetrics(String nodeId, boolean isLeader, double cpuUsage, long heapUsedMb, long heapMaxMb) {}

    public record SliceStatus(String artifact, String state, List<SliceInstanceStatus> instances) {}

    public record SliceInstanceStatus(String nodeId, String state, String health) {}

    public record EventLogEntry(String type, String message) {}

    public record RollingRestartResponse(boolean success, String message) {}

    public record RollingRestartStatusResponse(boolean active) {}

    public record AetherAggregates(double rps,
                                   double successRate,
                                   double avgLatencyMs,
                                   long totalInvocations,
                                   long totalSuccess,
                                   long totalFailures) {}

    public record InvocationDetail(String artifact,
                                   String method,
                                   long count,
                                   long successCount,
                                   long failureCount,
                                   double avgLatencyMs) {}

    public List<SliceStatus> slicesStatus() {
        if (nodes.isEmpty()) {
            return List.of();
        }

        var node = nodes.values().iterator().next();

        return node.deploymentMap()
                   .allDeployments()
                   .stream()
                   .map(info -> new SliceStatus(info.artifact(),
                                                info.aggregateState().name(),
                                                info.instances()
                                                    .stream()
                                                    .map(i -> new SliceInstanceStatus(i.nodeId(),
                                                                                      i.state().name(),
                                                                                      i.state() == SliceState.ACTIVE
                                                                                      ? "HEALTHY"
                                                                                      : "UNHEALTHY"))
                                                    .toList()))
                   .toList();
    }

    public Promise<RollingRestartResponse> startRollingRestart(Consumer<EventLogEntry> eventLogger) {
        if (rollingRestartActive.compareAndSet(false, true)) {
            eventLogger.accept(new EventLogEntry("ROLLING_RESTART", "Rolling restart started"));
            log.info("Starting rolling restart cycle");
            scheduleNextCycle(eventLogger);

            return Promise.success(new RollingRestartResponse(true, "Rolling restart started"));
        }

        return Promise.success(new RollingRestartResponse(false, "Rolling restart already active"));
    }

    private void scheduleNextCycle(Consumer<EventLogEntry> eventLogger) {
        if (!rollingRestartActive.get()) {
            return;
        }

        rollingRestartTask.set(rollingRestartExecutor.schedule(() -> performRollingRestartCycle(eventLogger),
                                                               ROLLING_RESTART_DELAY_MS,
                                                               TimeUnit.MILLISECONDS));
    }

    private void performRollingRestartCycle(Consumer<EventLogEntry> eventLogger) {
        if (!rollingRestartActive.get() || nodes.isEmpty()) {
            return;
        }

        var nodeIds = new ArrayList<>(nodes.keySet());
        var targetNodeId = nodeIds.get(random.nextInt(nodeIds.size()));

        log.info("Rolling restart: killing node {}", targetNodeId);
        eventLogger.accept(new EventLogEntry("ROLLING_RESTART", "Killing node " + targetNodeId));
        killNode(targetNodeId).onSuccess(_ -> {
                                             eventLogger.accept(new EventLogEntry("ROLLING_RESTART",
                                                                                  "CDM auto-heal will replace node"));
                                             scheduleNextCycleWithDelay(eventLogger, ROLLING_RESTART_DELAY_MS * 2);
                                         })
                .onFailure(cause -> handleRollingRestartFailure(eventLogger, "kill node", cause));
    }

    private void scheduleNextCycleWithDelay(Consumer<EventLogEntry> eventLogger, long delayMs) {
        if (!rollingRestartActive.get()) {
            return;
        }

        rollingRestartTask.set(rollingRestartExecutor.schedule(() -> performRollingRestartCycle(eventLogger),
                                                               delayMs,
                                                               TimeUnit.MILLISECONDS));
    }

    private void handleRollingRestartFailure(Consumer<EventLogEntry> eventLogger, String operation, Cause cause) {
        log.error("Rolling restart: failed to {}: {}", operation, cause.message());
        eventLogger.accept(new EventLogEntry("ROLLING_RESTART_ERROR", "Failed to " + operation + ": " + cause.message()));
        scheduleNextCycle(eventLogger);
    }

    public Promise<RollingRestartResponse> stopRollingRestart(Consumer<EventLogEntry> eventLogger) {
        if (rollingRestartActive.compareAndSet(true, false)) {
            rollingRestartTask.cancel();
            eventLogger.accept(new EventLogEntry("ROLLING_RESTART", "Rolling restart stopped"));
            log.info("Rolling restart stopped");

            return Promise.success(new RollingRestartResponse(true, "Rolling restart stopped"));
        }

        return Promise.success(new RollingRestartResponse(false, "Rolling restart not active"));
    }

    public RollingRestartStatusResponse rollingRestartStatus() {
        return new RollingRestartStatusResponse(rollingRestartActive.get());
    }
}
