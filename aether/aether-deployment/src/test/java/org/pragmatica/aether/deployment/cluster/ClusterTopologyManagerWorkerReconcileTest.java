// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// RFC-0017 stage 5 — the CTM's worker-topology reconcile pass: ACTUAL provider inventory (label
/// listing) converged toward the desired per-(source, role) topology in cluster state. The core
/// tier is deliberately untouched — its reconciliation lives in the hardened LeaderReconciler
/// path; a worker deficit is never quorum-ambiguous.
///
/// The fake provider is label-faithful in BOTH directions: a provision stamps the context's
/// cluster/source/role onto the instance, and a listing matches EVERY key of the selector. An
/// earlier role-only fake made the pass look convergent while production read `actual=0` on every
/// pass, because minted VMs carried `aether-source: default` and the selector asked for the
/// topology entry's source — proven live on Hetzner (cluster rfc17-final, 6 VMs for desired 4).
class ClusterTopologyManagerWorkerReconcileTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("localhost", 5001).unwrap());
    private static final NodeInfo INFO_B = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("localhost", 5002).unwrap());

    private static final String CLUSTER = "prod";

    /// A cloud source named `eu-1` backing the CORE role — the core auto-heal path resolves its
    /// source name from exactly this stanza instead of hardcoding one.
    private static final String CLOUD_TOML = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [operations.ports]
            cluster = 6000
            management = 5160
            app_http = 8070

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"

            [source.eu-1.core]
            count = 3
            """;

    private WorkerRecordingLifecycleManager lifecycleManager;
    private AtomicReference<Option<ClusterConfigValue>> configRef;
    private ClusterTopologyManager ctm;

    @BeforeEach
    void setUp() {
        var snapshotSource = new StubSnapshotSource();
        var config = new TopologyConfig(SELF,
                                        3,
                                        timeSpan(60).seconds(),
                                        timeSpan(1).seconds(),
                                        List.of(INFO_SELF, INFO_A, INFO_B));
        var observer = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
        lifecycleManager = new WorkerRecordingLifecycleManager();
        configRef = new AtomicReference<>(Option.none());
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                     timeSpan(1).millis(),
                                                     AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                     AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                     AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT,
                                                     timeSpan(0).millis())
                                     .unwrap();
        ctm = ClusterTopologyManager.clusterTopologyManager(observer,
                                                            lifecycleManager,
                                                            autoHeal,
                                                            DeploymentMap.deploymentMap(),
                                                            snapshotSource,
                                                            configRef::get,
                                                            ClusterTopologyManagerWorkerReconcileTest::applyNoop,
                                                            () -> AetherValue.ClusterPhase.NORMAL,
                                                            _ -> {},
                                                            _ -> {},
                                                            Option::none);
    }

    private static Promise<List<Object>> applyNoop(List<KVCommand<AetherKey>> commands) {
        return Promise.success(List.of());
    }

    private void seedTopology(AetherValue.TopologyEntry... entries) {
        seedConfig("", entries);
    }

    private void seedConfig(String toml, AetherValue.TopologyEntry... entries) {
        configRef.set(Option.some(new ClusterConfigValue(toml, CLUSTER, "1.0.0", List.of(entries), 3, 9, "test", 1L,
                                                         System.currentTimeMillis())));
    }

    private static AetherValue.TopologyEntry entry(String source, String role, int count) {
        return new AetherValue.TopologyEntry(source, role, count);
    }

    @Test
    void reconcile_workerDeficit_provisionsMissingWorkers_withWorkerRoleAndScopedFilter() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 2));
        ctm.activate();

        ctm.reconcileWorkerTopology();

        assertThat(lifecycleManager.provisionedRoles()).containsOnly("worker");
        assertThat(lifecycleManager.provisionedNodeIds())
                .hasSize(2)
                .allMatch(id -> id.startsWith("primary-worker-r"));
        assertThat(lifecycleManager.lastListFilter())
                .containsEntry("aether-cluster", CLUSTER)
                .containsEntry("aether-source", "primary")
                .containsEntry("aether-role", "worker");
    }

    /// Defect A. The minted VM must carry the TOPOLOGY ENTRY's source name, because that is the
    /// `aether-source` value the very next listing selects on. Pre-fix the provision context
    /// hardcoded `default` (`ProvisionContext.forReplacement`), so the pass could never see its own
    /// mints: `actual=0` forever, `desired` fresh VMs every pass, and no surplus victim ever
    /// visible.
    @Test
    void reconcile_workerDeficit_stampsTheTopologyEntrySourceOnEveryProvision() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 2));

        ctm.activate();

        assertThat(lifecycleManager.provisionedSourceNames()).containsOnly("primary");
    }

    /// Defect A, multi-source variant: each entry's provisions are stamped with ITS OWN source, not
    /// with whichever source the config happens to list first.
    @Test
    void reconcile_twoWorkerSources_stampsEachEntrysOwnSource() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 1), entry("secondary", "worker", 1));

        ctm.activate();

        assertThat(lifecycleManager.provisionedSourceNames()).containsExactlyInAnyOrder("primary", "secondary");
    }

    /// Defect A, core-tier half: the auto-heal replacement path has no topology entry to read, so it
    /// resolves the source name from the persisted cluster TOML — the same `cloudSourceFor` lookup
    /// that already resolves the role's zones and instance type. Pre-fix it stamped `default`.
    @Test
    void provisionReplacement_coreRole_stampsTheSourceNameResolvedFromClusterConfig() {
        seedConfig(CLOUD_TOML, entry("eu-1", "core", 3));

        ctm.provisionReplacement(nodeId("node-replacement").unwrap(),
                                 Option.none(),
                                 Set.of(SELF, PEER_A, PEER_B),
                                 NodeRole.CORE)
           .await()
           .onFailure(cause -> fail("provisionReplacement failed: " + cause.message()));

        assertThat(lifecycleManager.provisionedSourceNames()).containsOnly("eu-1");
    }

    /// The source-less fallback is preserved where it is honest: no parseable cluster TOML means no
    /// cloud source profile backs the role (tests, forge, Docker), so there is no source name to
    /// round-trip and nothing lists those instances by a source-scoped selector.
    @Test
    void provisionReplacement_coreRole_fallsBackToDefaultSource_whenNoCloudSourceBacksTheRole() {
        seedTopology(entry("primary", "core", 3));

        ctm.provisionReplacement(nodeId("node-replacement").unwrap(),
                                 Option.none(),
                                 Set.of(SELF, PEER_A, PEER_B),
                                 NodeRole.CORE)
           .await()
           .onFailure(cause -> fail("provisionReplacement failed: " + cause.message()));

        assertThat(lifecycleManager.provisionedSourceNames()).containsOnly(ProvisionContext.DEFAULT_SOURCE_NAME.value());
    }

    /// A pass sees what earlier provisions created (the recording manager feeds them back through
    /// `listInstances`) — a second poke finds desired == actual and provisions nothing more. This is
    /// the over-provisioning pin: pre-fix the label/selector mismatch made every pass re-mint
    /// `desired` more workers.
    @Test
    void reconcile_isConvergent_secondPassProvisionsNothing() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 2));
        ctm.activate();
        var afterFirst = lifecycleManager.provisionedNodeIds().size();

        ctm.reconcileWorkerTopology();

        assertThat(afterFirst).isEqualTo(2);
        assertThat(lifecycleManager.provisionedNodeIds()).hasSize(2);
    }

    /// Newest first: reconciler-minted `-r<clock36>` ids sort after bootstrap `-<index>` ids, so a
    /// scale-down reaps cluster-provisioned workers before bootstrap-provisioned ones.
    @Test
    void reconcile_workerSurplus_terminatesNewestFirst() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 1));
        lifecycleManager.preExisting(workerInstance("primary", "primary-worker-0"),
                                     workerInstance("primary", "primary-worker-1"),
                                     workerInstance("primary", "primary-worker-rzzz-0"));
        ctm.activate();

        ctm.reconcileWorkerTopology();

        assertThat(lifecycleManager.terminatedNodeIds())
                .containsExactly("primary-worker-rzzz-0", "primary-worker-1");
        assertThat(lifecycleManager.provisionedNodeIds()).isEmpty();
    }

    /// Defect A's scale-down consequence, on the reconciler's OWN mints rather than on inventory the
    /// test planted: with the label round-trip broken, surplus was structurally unreachable —
    /// `actual` was always empty, so `terminateSurplusWorkers` had no victims to choose from and a
    /// scale-down could only ever add VMs.
    @Test
    void reconcile_scaleDownAfterOwnMints_terminatesItsOwnNewestWorkers() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 3));
        ctm.activate();
        var newestTwo = lifecycleManager.provisionedNodeIds()
                                        .stream()
                                        .sorted(Comparator.reverseOrder())
                                        .limit(2)
                                        .toList();
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 1));

        ctm.reconcileWorkerTopology();

        assertThat(lifecycleManager.provisionedNodeIds()).hasSize(3);
        assertThat(lifecycleManager.terminatedNodeIds()).containsExactlyElementsOf(newestTwo);
    }

    @Test
    void reconcile_coreOnlyTopology_touchesNothing() {
        seedTopology(entry("primary", "core", 5));
        ctm.activate();

        ctm.reconcileWorkerTopology();

        assertThat(lifecycleManager.listCalls()).isZero();
        assertThat(lifecycleManager.provisionedNodeIds()).isEmpty();
    }

    /// The `active` flag IS the leadership gate (same guard as the membership actuator path) — a
    /// non-leader CTM poked by the config fan-out must not act.
    @Test
    void reconcile_inactiveCtm_neverListsOrProvisions() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 2));

        ctm.reconcileWorkerTopology();

        assertThat(lifecycleManager.listCalls()).isZero();
        assertThat(lifecycleManager.provisionedNodeIds()).isEmpty();
    }

    /// Defect B. A scale commit landing while a pass holds the serialization flag used to be logged
    /// and dropped — measured live as a cluster frozen at 6 workers after a commit to 1, with no
    /// further pass all day. Triggers missed mid-pass are now replayed as exactly ONE follow-up
    /// pass, and several missed triggers coalesce into that one pass.
    @Test
    void reconcile_triggersDuringInFlightPass_runExactlyOneFollowUpPass() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 2));
        var gate = lifecycleManager.gateListing();

        ctm.activate();
        ctm.reconcileWorkerTopology();
        ctm.reconcileWorkerTopology();
        gate.succeed(unit()).await().onFailure(cause -> fail("listing gate release failed: " + cause.message()));

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(lifecycleManager.listCalls()).isEqualTo(2));
        await().during(250, TimeUnit.MILLISECONDS)
               .atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(lifecycleManager.listCalls()).isEqualTo(2));
        assertThat(lifecycleManager.provisionedNodeIds()).hasSize(2);
    }

    /// The replay is armed by a MISSED trigger only — an uncontended pass must not re-poke itself,
    /// or every commit would cost two provider listings forever.
    @Test
    void reconcile_noTriggerDuringPass_runsNoFollowUpPass() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 2));

        ctm.activate();

        await().during(250, TimeUnit.MILLISECONDS)
               .atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(lifecycleManager.listCalls()).isEqualTo(1));
    }

    private static InstanceInfo workerInstance(String sourceName, String nodeId) {
        return instance(CLUSTER, sourceName, "worker", nodeId);
    }

    private static InstanceInfo instance(String clusterName, String sourceName, String role, String nodeId) {
        return new InstanceInfo(InstanceId.instanceId("i-" + nodeId).unwrap(),
                                InstanceStatus.RUNNING,
                                List.of("127.0.0.1"),
                                InstanceType.ON_DEMAND,
                                Map.of("aether-cluster", clusterName, "aether-source", sourceName, "aether-role", role),
                                Option.some(nodeId));
    }

    private static final class StubSnapshotSource implements GenerationSnapshotSource {
        @Override public Option<MembershipView> currentMembershipView() {
            return Option.none();
        }

        @Override public long observedRabiaTerm() {
            return 0L;
        }
    }

    /// Production-faithful recording manager: provisioned workers become VISIBLE to subsequent
    /// `listInstances` calls under the labels their provision context carried, and a listing honours
    /// EVERY selector key the way a provider's label query does. Convergence is therefore testable,
    /// and a label that does not round-trip with the selector shows up as divergence instead of
    /// being masked.
    private static final class WorkerRecordingLifecycleManager implements NodeLifecycleManager {
        private final CopyOnWriteArrayList<ProvisionSpec> provisioned = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<InstanceInfo> inventory = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> terminated = new CopyOnWriteArrayList<>();
        private final AtomicReference<Map<String, String>> lastFilter = new AtomicReference<>(Map.of());
        private final AtomicReference<Promise<Unit>> listGate = new AtomicReference<>(Promise.success(unit()));
        private final AtomicLong listCalls = new AtomicLong();

        void preExisting(InstanceInfo... instances) {
            inventory.addAll(List.of(instances));
        }

        /// Hold every listing (and therefore the pass that issued it) until the returned promise is
        /// resolved — the window in which a config commit races an in-flight pass.
        Promise<Unit> gateListing() {
            var gate = Promise.<Unit> promise();

            listGate.set(gate);

            return gate;
        }

        List<String> provisionedRoles() {
            return provisioned.stream().map(spec -> spec.context().role()).distinct().toList();
        }

        /// Raw values, not [SourceName]s: these assertions pin what the provider stamps as the
        /// `aether-source` label, which is the string the reconcile selector must round-trip.
        List<String> provisionedSourceNames() {
            return provisioned.stream().map(spec -> spec.context().sourceName().value()).distinct().toList();
        }

        List<String> provisionedNodeIds() {
            return provisioned.stream().flatMap(spec -> spec.context().nodeId().stream()).toList();
        }

        List<String> terminatedNodeIds() {
            return List.copyOf(terminated);
        }

        Map<String, String> lastListFilter() {
            return lastFilter.get();
        }

        long listCalls() {
            return listCalls.get();
        }

        @Override
        public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            provisioned.add(spec);

            var info = instanceFor(spec.context());

            inventory.add(info);

            return Promise.success(info);
        }

        private static InstanceInfo instanceFor(ProvisionContext context) {
            return instance(context.clusterName().map(ClusterName::value).or(""),
                            context.sourceName().value(),
                            context.role(),
                            context.nodeId().or("unknown"));
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
            listCalls.incrementAndGet();
            lastFilter.set(tagFilter);

            return listGate.get().map(_ -> matching(tagFilter));
        }

        private List<InstanceInfo> matching(Map<String, String> tagFilter) {
            return inventory.stream().filter(info -> matchesAll(info, tagFilter)).toList();
        }

        private static boolean matchesAll(InstanceInfo info, Map<String, String> tagFilter) {
            return tagFilter.entrySet()
                            .stream()
                            .allMatch(selector -> selector.getValue().equals(info.tags().get(selector.getKey())));
        }

        @Override
        public Promise<Unit> terminateNode(NodeId nodeId) {
            terminated.add(nodeId.id());
            inventory.removeIf(info -> info.nodeId().or("").equals(nodeId.id()));

            return Promise.success(unit());
        }

        @Override
        public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStopped(SELF));
        }

        @Override
        public Promise<Unit> restartNode(NodeId nodeId) {
            return Promise.success(unit());
        }

        @Override
        public boolean isCloudManaged() {
            return true;
        }
    }
}
