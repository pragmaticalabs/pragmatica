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
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
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
import static org.pragmatica.aether.deployment.cluster.ClusterTopologyManager.ScalingAsker.COMMUNITY;
import static org.pragmatica.aether.deployment.cluster.ClusterTopologyManager.ScalingAsker.RECONCILER;
import static org.pragmatica.aether.deployment.cluster.ClusterTopologyManager.ScalingAsker.RETIREMENT;
import static org.pragmatica.aether.deployment.cluster.ClusterTopologyManager.ScalingAsker.WORKER_DELTA;
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
            credentials = "hcloud-token"
            region = "eu-central"

            [source.eu-1.core]
            count = 3
            """;

    /// Two cloud sources with workers, each pinned to one zone (#1650).
    private static final String TWO_WORKER_SOURCES_TOML = """
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
            credentials = "hcloud-token"
            region = "eu-central"
            zone = "nbg1"

            [source.eu-1.core]
            count = 3

            [source.eu-1.worker]
            count = 1

            [source.eu-2]
            type = "cloud"
            provider = "hetzner"
            credentials = "hcloud-token"
            region = "eu-central"
            zone = "fsn1"

            [source.eu-2.worker]
            count = 1
            """;

    private WorkerRecordingLifecycleManager lifecycleManager;
    private AtomicReference<Option<ClusterConfigValue>> configRef;
    private ClusterTopologyManager ctm;
    private final AtomicReference<Option<AetherValue.AutoHealStateValue>> autoHealRef = new AtomicReference<>(Option.none());
    private final java.util.List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.List<String> warningTexts = new java.util.concurrent.CopyOnWriteArrayList<>();
    private TopologyObserver observer;
    private StubSnapshotSource snapshotSource;

    @BeforeEach
    void setUp() {
        snapshotSource = new StubSnapshotSource();
        var config = new TopologyConfig(SELF,
                                        3,
                                        timeSpan(60).seconds(),
                                        timeSpan(1).seconds(),
                                        List.of(INFO_SELF, INFO_A, INFO_B));
        observer = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
        lifecycleManager = new WorkerRecordingLifecycleManager();
        configRef = new AtomicReference<>(Option.none());
        ctm = buildCtm(MembershipLiveness.UNWIRED);
    }

    private ClusterTopologyManager buildCtm(MembershipLiveness liveness) {
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(1).millis(), AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT).unwrap();
        var built = ClusterTopologyManager.clusterTopologyManager(observer,
                                                            lifecycleManager,
                                                            autoHeal,
                                                            DeploymentMap.deploymentMap(),
                                                            snapshotSource,
                                                            configRef::get,
                                                            ClusterTopologyManagerWorkerReconcileTest::applyNoop,
                                                            () -> AetherValue.ClusterPhase.NORMAL,
                                                            _ -> {},
                                                            _ -> {},
                                                            autoHealRef::get,
                                                            liveness);
        built.setOperatorWarningSink(org.pragmatica.utility.warning.OperatorWarningSink.handingOffTo(this::addWarning));
        var placements = new java.util.concurrent.ConcurrentHashMap<AetherKey, AetherValue>();
        built.setHierarchyStateWriter(HierarchyStateWriter.hierarchyStateWriter(
            () -> Option.some(new org.pragmatica.cluster.state.kvstore.LeaderValue(SELF, 1)),
            key -> Option.option(placements.get(key)), commands -> {
                var results = new java.util.ArrayList<Object>();
                for (var command : commands) {
                    var transaction = (KVCommand.LeaderTransaction<AetherKey, AetherValue>) command;
                    var accepted = transaction.mutations().stream().allMatch(mutation ->
                        Option.option(placements.get(mutation.key())).equals(mutation.expected()));
                    if (accepted) {
                        transaction.mutations().forEach(mutation -> mutation.replacement().onPresent(value -> placements.put(mutation.key(), value)));
                    }
                    results.add(new KVCommand.TransactionResult(transaction.transactionId(), accepted));
                }
                return Promise.success(results);
            }));

        return built;
    }

    private static Promise<List<Object>> applyNoop(List<KVCommand<AetherKey>> commands) {
        return Promise.success(List.of());
    }

    private void seedTopology(AetherValue.TopologyEntry... entries) {
        seedConfig(Option.none(), entries);
    }

    private void seedConfig(String toml, AetherValue.TopologyEntry... entries) {
        seedConfig(Option.some(toml), entries);
    }

    private void seedConfig(Option<String> toml, AetherValue.TopologyEntry... entries) {
        configRef.set(Option.some(new ClusterConfigValue(toml, CLUSTER, "1.0.0", List.of(entries), 3, 9, "test", 1L,
                                                         System.currentTimeMillis())));
    }

    private void commitConfigVersion(long version, AetherValue.TopologyEntry... entries) {
        configRef.set(Option.some(new ClusterConfigValue(Option.none(), CLUSTER, "1.0.0", List.of(entries), 3, 9, "test", version,
                                                         System.currentTimeMillis())));
    }

    /// Disables auto-heal against the configuration seeded now, recording each worker source/role's count as the disable does.
    private void disableAutoHealAtConfigVersion(long version) {
        var scaling = configRef.get()
                               .map(value -> value.desiredTopology()
                                                  .stream()
                                                  .collect(java.util.stream.Collectors.toMap(e -> "worker " + e.sourceName() + "/" + e.role(),
                                                                                             AetherValue.TopologyEntry::count)))
                               .or(Map.of());
        autoHealRef.set(Option.some(AetherValue.AutoHealStateValue.disabledAt("test", version, scaling)));
    }

    /// The text is stored before the code, so a test that awaited the code reads the text.
    private void addWarning(org.pragmatica.utility.warning.OperatorWarning warning) {
        warningTexts.add(warning.message());
        warnings.add(warning.code().code());
    }

    private static AetherValue.TopologyEntry entry(String source, String role, int count) {
        return new AetherValue.TopologyEntry(source, role, count);
    }

    /// Spec §8.4 — auto-heal disabled: the worker path provisions nothing for a deficit that was already committed, and tells the operator once.
    @Test
    void reconcile_workerDeficit_autoHealDisabled_provisionsNothing_andWarnsOncePerHold() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 2));
        disableAutoHealAtConfigVersion(1L);
        ctm.activate();

        ctm.reconcileWorkerTopology();
        ctm.reconcileWorkerTopology();

        assertThat(lifecycleManager.provisionedNodeIds()).isEmpty();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> !warnings.isEmpty());
        assertThat(warnings).as("one event for the run of holds").containsExactly("auto-heal-scaling-held");
    }

    @Test
    void reconcile_workerSurplus_autoHealDisabled_requestsNoRetirement() {
        var requested = captureRetirementRequests();
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 1));
        lifecycleManager.preExisting(workerInstance("primary", "primary-worker-0"), workerInstance("primary", "primary-worker-1"));
        disableAutoHealAtConfigVersion(1L);
        ctm.activate();

        ctm.reconcileWorkerTopology();

        assertThat(requested).isEmpty();
        assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();
    }

    /// Exception (b): a desired count CHANGED after the disable is explicit operator intent and is acted on while auto-heal stays disabled.
    @Test
    void reconcile_workerDeficit_countCommittedWhileDisabled_isProvisioned() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 2));
        disableAutoHealAtConfigVersion(1L);
        commitConfigVersion(2L, entry("primary", "core", 3), entry("primary", "worker", 3));
        ctm.activate();

        ctm.reconcileWorkerTopology();

        assertThat(lifecycleManager.provisionedNodeIds()).hasSize(3);
    }

    /// Spec §8.4 (v2711 finding 2): a configuration commit that leaves this source/role's count unchanged is not intent for it. The hold
    /// stays, so the deficit that was already there is not corrected on the cluster's own initiative.
    @Test
    void reconcile_workerDeficit_unrelatedConfigCommitWhileDisabled_staysHeld() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 2));
        disableAutoHealAtConfigVersion(1L);
        ctm.activate();
        ctm.reconcileWorkerTopology();
        commitConfigVersion(2L, entry("primary", "core", 3), entry("primary", "worker", 2));

        ctm.reconcileWorkerTopology();

        assertThat(lifecycleManager.provisionedNodeIds()).as("an unchanged count releases nothing").isEmpty();
    }

    /// The release is per source/role: changing one source's count releases that source only; the other's deficit stays held.
    @Test
    void reconcile_twoWorkerSources_countChangedForOneWhileDisabled_releasesOnlyThatSource() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 1), entry("secondary", "worker", 1));
        disableAutoHealAtConfigVersion(1L);
        commitConfigVersion(2L, entry("primary", "core", 3), entry("primary", "worker", 1), entry("secondary", "worker", 2));
        ctm.activate();

        ctm.reconcileWorkerTopology();

        assertThat(lifecycleManager.provisionedSourceNames()).as("activate() already runs one pass").containsOnly("secondary");
    }

    /// v2711 finding 7 (i): releasing a held subject announces its resume, so the held event does not stay open while the path acts.
    @Test
    void reconcile_heldSubjectReleasedByAChangedCount_raisesResumed() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 1));
        disableAutoHealAtConfigVersion(1L);
        ctm.activate();
        ctm.reconcileWorkerTopology();
        commitConfigVersion(2L, entry("primary", "core", 3), entry("primary", "worker", 2));

        ctm.reconcileWorkerTopology();

        assertThat(lifecycleManager.provisionedNodeIds()).as("released: the deficit is filled").hasSize(2);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 2);
        assertThat(warnings).containsExactly("auto-heal-scaling-held", "auto-heal-scaling-resumed");
    }

    /// #3358: the hold of a worker surplus closes when the surplus is gone by the exit the warning names, an explicit drain of the node,
    /// with auto-heal still disabled: the subject is asked on the pass that finds nothing to scale, and says so.
    @Test
    void reconcile_heldWorkerSurplus_drainedByHand_raisesResumed() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 1));
        lifecycleManager.preExisting(workerInstance("primary", "primary-worker-0"), workerInstance("primary", "primary-worker-1"));
        disableAutoHealAtConfigVersion(1L);
        ctm.activate();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 1);
        assertThat(warnings).as("control: the surplus is held").containsExactly("auto-heal-scaling-held");

        lifecycleManager.terminateNode(nodeId("primary-worker-1").unwrap()).await();
        ctm.reconcileWorkerTopology();

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 2);
        assertThat(warnings).as("no surplus left, auto-heal still disabled: the hold closes").containsExactly("auto-heal-scaling-held", "auto-heal-scaling-resumed");
        assertThat(lifecycleManager.terminatedNodeIds()).as("only the operator's drain terminated anything").containsExactly("primary-worker-1");
    }

    /// #3358 early return of the worker ask: once explicit communities place the `worker` role the worker pass owns no change to its
    /// subject, so it answers "nothing pending" instead of skipping the ask, and a hold raised before the communities closes.
    @Test
    void reconcile_heldWorkerDeficit_explicitCommunitiesDeclared_raisesResumed() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 2));
        disableAutoHealAtConfigVersion(1L);
        ctm.activate();
        ctm.reconcileWorkerTopology();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 1);
        assertThat(warnings).as("control: the deficit is held").containsExactly("auto-heal-scaling-held");

        seedConfig(communityToml(1), entry("primary", "core", 3), entry("primary", "worker", 2));
        ctm.reconcileWorkerTopology();

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 2);
        assertThat(warnings).containsExactly("auto-heal-scaling-held", "auto-heal-scaling-resumed");
        assertThat(lifecycleManager.provisionedNodeIds()).as("the community path owns the role: nothing provisioned here").isEmpty();
    }

    /// #3358: the same for a deficit that fills by itself (the missing instance came back), and the close is not a latch: a deficit that
    /// returns is held and warned again.
    @Test
    void reconcile_heldWorkerDeficit_filledByItself_raisesResumed_andAReturningDeficitIsHeldAgain() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 1));
        disableAutoHealAtConfigVersion(1L);
        ctm.activate();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 1);

        lifecycleManager.preExisting(workerInstance("primary", "primary-worker-0"));
        ctm.reconcileWorkerTopology();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 2);
        assertThat(warnings).containsExactly("auto-heal-scaling-held", "auto-heal-scaling-resumed");

        lifecycleManager.terminateNode(nodeId("primary-worker-0").unwrap()).await();
        ctm.reconcileWorkerTopology();

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 3);
        assertThat(warnings).containsExactly("auto-heal-scaling-held", "auto-heal-scaling-resumed", "auto-heal-scaling-held");
        assertThat(lifecycleManager.provisionedNodeIds()).as("held throughout: nothing was provisioned").isEmpty();
    }

    /// #3358: a hold that closed because nothing was left to change is closed: re-enabling auto-heal afterwards announces nothing more.
    /// Control: the held and resumed events of the same subject are awaited first, so the instrument sees a warning when one is raised.
    @Test
    void reconcile_closedHold_thenAutoHealReEnabled_raisesNothing() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 1));
        disableAutoHealAtConfigVersion(1L);
        ctm.activate();
        lifecycleManager.preExisting(workerInstance("primary", "primary-worker-0"));
        ctm.reconcileWorkerTopology();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 2);

        autoHealRef.set(Option.none());
        ctm.reconcileWorkerTopology();

        org.awaitility.Awaitility.await()
                                 .during(java.time.Duration.ofMillis(500))
                                 .atMost(java.time.Duration.ofSeconds(5))
                                 .until(() -> warnings.size() == 2);
        assertThat(warnings).as("the resume was raised when the hold closed, not again at the re-enable")
                            .containsExactly("auto-heal-scaling-held", "auto-heal-scaling-resumed");
    }

    /// #3358: auto-heal disabled with nothing to scale raises no hold at all: the subject is asked on every pass, and asking with nothing
    /// pending is not a hold.
    @Test
    void reconcile_workerAtDesiredCount_autoHealDisabled_raisesNoHold() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 1));
        lifecycleManager.preExisting(workerInstance("primary", "primary-worker-0"));
        disableAutoHealAtConfigVersion(1L);
        ctm.activate();

        ctm.reconcileWorkerTopology();

        org.awaitility.Awaitility.await()
                                 .during(java.time.Duration.ofMillis(500))
                                 .atMost(java.time.Duration.ofSeconds(5))
                                 .until(warnings::isEmpty);
    }

    /// #3358: the one rule at the accessor: a disabled subject whose value is unchanged is held only while a change is pending; asked with
    /// nothing pending it is not held, and a hold open for it closes with its resume. Pinned for the core subject, the one the reconciler asks.
    @Test
    void scalingHeld_core_nothingPending_closesTheOpenHold() {
        var manager = (ClusterTopologyManagerRecord) ctm;
        var core = ClusterTopologyManager.coreSubject();
        seedTopology(entry("pool", "core", 3), entry("pool", "worker", 1));
        autoHealRef.set(Option.some(AetherValue.AutoHealStateValue.disabledAt("test", 1L, ClusterTopologyManagerRecord.scalingOf(configRef.get()))));

        assertThat(manager.scalingHeld(core, RECONCILER, true)).as("a surplus is pending").isTrue();
        assertThat(manager.scalingHeld(core, RECONCILER, false)).as("nothing pending any more").isFalse();

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 2);
        assertThat(warnings).containsExactly("auto-heal-scaling-held", "auto-heal-scaling-resumed");
        assertThat(manager.scalingHeld(core, RECONCILER, false)).as("asked again with nothing pending: no second resume").isFalse();
        assertThat(manager.scalingHeld(core, RECONCILER, true)).as("a surplus returns: held again").isTrue();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 3);
        assertThat(warnings).last().isEqualTo("auto-heal-scaling-held");
    }

    /// The community subject is held on its own `target_size`: unchanged it stays held, changed it is released.
    @Test
    void scalingHeld_community_followsItsOwnTargetSize() {
        var manager = (ClusterTopologyManagerRecord) ctm;
        seedConfig(communityToml(1), entry("pool", "core", 3));
        autoHealRef.set(Option.some(AetherValue.AutoHealStateValue.disabledAt("test", 1L, Map.of("community stable", 1))));

        assertThat(manager.scalingHeld("community stable", COMMUNITY, true)).as("target_size unchanged").isTrue();

        seedConfig(communityToml(2), entry("pool", "core", 3));

        assertThat(manager.scalingHeld("community stable", COMMUNITY, true)).as("target_size changed while disabled").isFalse();
    }

    /// #3324: the core surplus subject is recorded by the disable from the committed core count, as every other subject is. Unchanged it
    /// stays held and raises the same held warning; a core count the operator commits after the disable is explicit intent and releases it.
    @Test
    void scalingHeld_core_followsTheCommittedCoreCount() {
        var manager = (ClusterTopologyManagerRecord) ctm;
        var core = ClusterTopologyManager.coreSubject();
        seedTopology(entry("pool", "core", 3), entry("pool", "worker", 1));
        var recorded = ClusterTopologyManagerRecord.scalingOf(configRef.get());
        autoHealRef.set(Option.some(AetherValue.AutoHealStateValue.disabledAt("test", 1L, recorded)));

        assertThat(recorded).as("the disable records the committed core count").containsEntry(core, 3);
        assertThat(manager.scalingHeld(core, RECONCILER, true)).as("core count unchanged").isTrue();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 1);
        assertThat(warnings).containsExactly("auto-heal-scaling-held");
        assertThat(warningTexts.getFirst()).as("the held warning names both exits: an explicit drain and re-enabling auto-heal")
                                           .contains("aether nodes drain <nodeId>")
                                           .contains("aether cluster topology auto-heal enable");

        commitConfigVersion(2L, entry("pool", "core", 2), entry("pool", "worker", 1));

        assertThat(manager.scalingHeld(core, RECONCILER, true)).as("core count committed after the disable").isFalse();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 2);
        assertThat(warnings).as("the release of the core subject is announced").containsExactly("auto-heal-scaling-held", "auto-heal-scaling-resumed");
    }

    /// #3324: re-enabling auto-heal announces the held core subject as resumed, as it does every other held subject.
    @Test
    void scalingHeld_core_resumedWhenAutoHealIsReEnabled() {
        var manager = (ClusterTopologyManagerRecord) ctm;
        var core = ClusterTopologyManager.coreSubject();
        seedTopology(entry("pool", "core", 3), entry("pool", "worker", 1));
        autoHealRef.set(Option.some(AetherValue.AutoHealStateValue.disabledAt("test", 1L, ClusterTopologyManagerRecord.scalingOf(configRef.get()))));

        assertThat(manager.scalingHeld(core, RECONCILER, true)).as("held while disabled").isTrue();

        autoHealRef.set(Option.none());

        assertThat(manager.scalingHeld(core, RECONCILER, true)).as("auto-heal enabled again").isFalse();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 2);
        assertThat(warnings).containsExactly("auto-heal-scaling-held", "auto-heal-scaling-resumed");
    }

    /// v2711 survivor 9 (c34): with no auto-heal record at all, auto-heal is enabled, and every subject held earlier is announced as resumed
    /// by the one call, not only the subject asked about.
    @Test
    void scalingHeld_noStateRecord_isEnabled_andResumesEveryHeldSubject() {
        var manager = (ClusterTopologyManagerRecord) ctm;
        var worker = ClusterTopologyManagerRecord.workerSubject("pool", "worker");
        seedConfig(communityToml(1), entry("pool", "core", 3), entry("pool", "worker", 1));
        autoHealRef.set(Option.some(AetherValue.AutoHealStateValue.disabledAt("test", 1L, Map.of("community stable", 1, worker, 1))));
        assertThat(manager.scalingHeld("community stable", COMMUNITY, true)).isTrue();
        assertThat(manager.scalingHeld(worker, WORKER_DELTA, true)).isTrue();

        autoHealRef.set(Option.none());

        assertThat(manager.scalingHeld("community stable", COMMUNITY, true)).as("no record: enabled").isFalse();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 4);
        assertThat(warnings).containsExactly("auto-heal-scaling-held",
                                             "auto-heal-scaling-held",
                                             "auto-heal-scaling-resumed",
                                             "auto-heal-scaling-resumed");
    }

    /// v2711 survivor 9 (c39): a deactivation ends the holds of that activation; the next activation holds, and warns, afresh.
    @Test
    void deactivate_endsTheHolds_soTheNextActivationWarnsAgain() {
        var manager = (ClusterTopologyManagerRecord) ctm;
        seedConfig(communityToml(1), entry("pool", "core", 3));
        autoHealRef.set(Option.some(AetherValue.AutoHealStateValue.disabledAt("test", 1L, Map.of("community stable", 1))));
        ctm.activate();
        assertThat(manager.scalingHeld("community stable", COMMUNITY, true)).isTrue();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 1);

        ctm.deactivate();
        ctm.activate();
        assertThat(manager.scalingHeld("community stable", COMMUNITY, true)).isTrue();

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 2);
        assertThat(warnings).containsExactly("auto-heal-scaling-held", "auto-heal-scaling-held");
    }

    /// v2711 survivor 5 (c49): installing the community reconciler hands it the CTM's own auto-heal hold, so the community path honours a
    /// disable; without that wiring the reconciler's default hold answers "never held".
    @Test
    void installCommunityPlacement_handsTheReconcilerTheAutoHealHold() {
        var manager = (ClusterTopologyManagerRecord) ctm;
        var installed = new java.util.concurrent.atomic.AtomicReference<java.util.function.Predicate<String>>();
        seedConfig(communityToml(1), entry("pool", "core", 3));
        autoHealRef.set(Option.some(AetherValue.AutoHealStateValue.disabledAt("test", 1L, Map.of("community stable", 1))));

        manager.installCommunityPlacement(new CommunityPlacementReconciler() {
            public Promise<org.pragmatica.lang.Unit> reconcile() { return Promise.unitPromise(); }
            public Promise<org.pragmatica.lang.Unit> requestRetirement(NodeId node, AetherValue.TopologyEntry entry) {
                return Promise.unitPromise();
            }
            public Promise<Boolean> onDrainCompleted(NodeId node, String operation) { return Promise.success(false); }
            public org.pragmatica.lang.Unit holdAutonomy(java.util.function.Predicate<String> held) {
                installed.set(held);
                return org.pragmatica.lang.Unit.unit();
            }
        });

        assertThat(installed.get()).as("the reconciler was handed a hold").isNotNull();
        assertThat(installed.get().test(CommunityPlacementReconciler.holdSubject("stable"))).as("disabled, target_size unchanged").isTrue();

        seedConfig(communityToml(2), entry("pool", "core", 3));

        assertThat(installed.get().test(CommunityPlacementReconciler.holdSubject("stable"))).as("target_size changed while disabled").isFalse();
    }

    /// The `core` subject, disabled with the committed core count recorded, so it is held while an asker has a change pending (#3358).
    private ClusterTopologyManagerRecord coreDisabled() {
        seedTopology(entry("pool", "core", 3), entry("pool", "worker", 1));
        autoHealRef.set(Option.some(AetherValue.AutoHealStateValue.disabledAt("test", 1L, ClusterTopologyManagerRecord.scalingOf(configRef.get()))));

        return (ClusterTopologyManagerRecord) ctm;
    }

    private void awaitWarnings(int count) {
        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == count);
    }

    private void assertWarningsStay(String... expected) {
        await().during(java.time.Duration.ofMillis(300)).atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.equals(List.of(expected)));
    }

    /// #3358 kept-both pairing: with a surge replacement counted the reconciler's drain room is zero while the retirement still names a
    /// victim, so the two askers of `core` answer differently on alternating passes. The subject stays held: exactly one held event over
    /// ten alternations, and no resume until BOTH answer "nothing pending".
    @Test
    void scalingHeld_keptBothPairing_alternatingAskers_oneHeld_noResumeUntilBothAnswerFalse() {
        var manager = coreDisabled();
        var core = ClusterTopologyManager.coreSubject();

        for (var pass = 0; pass < 10; pass++) {
            assertThat(manager.scalingHeld(core, RECONCILER, false)).as("the reconciler has no drain room").isFalse();
            assertThat(manager.scalingHeld(core, RETIREMENT, true)).as("the retirement names a victim").isTrue();
        }

        awaitWarnings(1);
        assertWarningsStay("auto-heal-scaling-held");

        assertThat(manager.scalingHeld(core, RETIREMENT, false)).isFalse();

        awaitWarnings(2);
        assertThat(warnings).as("both asked false: closed, once").containsExactly("auto-heal-scaling-held", "auto-heal-scaling-resumed");
    }

    /// #3358: a retirement victim alone holds `core`, and its "no victim" alone closes it (the reconciler never asked).
    @Test
    void scalingHeld_retirementVictimOnly_holdsAndCloses() {
        var manager = coreDisabled();
        var core = ClusterTopologyManager.coreSubject();

        assertThat(manager.scalingHeld(core, RETIREMENT, true)).isTrue();
        awaitWarnings(1);
        assertThat(manager.scalingHeld(core, RETIREMENT, false)).isFalse();

        awaitWarnings(2);
        assertThat(warnings).containsExactly("auto-heal-scaling-held", "auto-heal-scaling-resumed");
    }

    /// #3358: the reconciler's surplus alone holds `core`, and its "no surplus" alone closes it (the retirement never asked).
    @Test
    void scalingHeld_reconcilerOnly_holdsAndCloses() {
        var manager = coreDisabled();
        var core = ClusterTopologyManager.coreSubject();

        assertThat(manager.scalingHeld(core, RECONCILER, true)).isTrue();
        awaitWarnings(1);
        assertThat(manager.scalingHeld(core, RECONCILER, false)).isFalse();

        awaitWarnings(2);
        assertThat(warnings).containsExactly("auto-heal-scaling-held", "auto-heal-scaling-resumed");
    }

    /// #3358: one asker answers false while the other's answer is still true: the subject stays open, whichever asker answers first.
    @Test
    void scalingHeld_oneAskerFalse_otherStillTrue_staysOpen() {
        var manager = coreDisabled();
        var core = ClusterTopologyManager.coreSubject();
        manager.scalingHeld(core, RECONCILER, true);
        manager.scalingHeld(core, RETIREMENT, true);
        awaitWarnings(1);

        manager.scalingHeld(core, RECONCILER, false);
        assertWarningsStay("auto-heal-scaling-held");

        manager.scalingHeld(core, RECONCILER, true);
        manager.scalingHeld(core, RETIREMENT, false);
        assertWarningsStay("auto-heal-scaling-held");

        manager.scalingHeld(core, RECONCILER, false);
        awaitWarnings(2);
        assertThat(warnings).containsExactly("auto-heal-scaling-held", "auto-heal-scaling-resumed");
    }

    /// #3358: a leader change drops every answer (the CTM's deactivation, silent as before). The next activation answers for itself: an
    /// answer the old leader's retirement left true must not keep the new leader's subject open once the new reconciler answers false.
    @Test
    void scalingHeld_leaderChange_dropsEveryAnswer() {
        var manager = coreDisabled();
        var core = ClusterTopologyManager.coreSubject();
        ctm.activate();
        manager.scalingHeld(core, RETIREMENT, true);
        awaitWarnings(1);

        ctm.deactivate();
        ctm.activate();
        manager.scalingHeld(core, RECONCILER, true);
        awaitWarnings(2);
        assertThat(warnings).as("the new activation holds and warns afresh").containsExactly("auto-heal-scaling-held", "auto-heal-scaling-held");

        manager.scalingHeld(core, RECONCILER, false);

        awaitWarnings(3);
        assertThat(warnings).as("the old leader's retirement answer is gone: the new reconciler's false closes").last().isEqualTo("auto-heal-scaling-resumed");
    }

    /// #3358: two threads answer for one subject, released by one latch. Whatever the interleaving, the events of the subject alternate
    /// held, resumed, held... (a close can never be published before the open it closes) and the final state equals the union of the
    /// last answers: the retirement ends true, the reconciler false, so the subject ends open (an odd count, the last event held).
    @Test
    void scalingHeld_twoThreadsOneSubject_eventsAlternate_finalStateIsTheUnion() throws InterruptedException {
        var manager = coreDisabled();
        var core = ClusterTopologyManager.coreSubject();
        var start = new java.util.concurrent.CountDownLatch(1);
        var passes = 40;
        var retirement = Thread.ofPlatform().start(() -> answerAlternating(manager, core, RETIREMENT, start, passes, true));
        var reconciler = Thread.ofPlatform().start(() -> answerAlternating(manager, core, RECONCILER, start, passes, false));

        start.countDown();
        retirement.join();
        reconciler.join();

        await().atMost(java.time.Duration.ofSeconds(5)).during(java.time.Duration.ofMillis(300)).until(() -> warnings.size() % 2 == 1);
        var events = List.copyOf(warnings);
        for (var i = 0; i < events.size(); i++) {
            assertThat(events.get(i)).as("event %d of %s", i, events)
                                     .isEqualTo(i % 2 == 0 ? "auto-heal-scaling-held" : "auto-heal-scaling-resumed");
        }
        assertThat(events).as("the last answers' union is non-empty: open").hasSizeGreaterThanOrEqualTo(1);
    }

    private static void answerAlternating(ClusterTopologyManagerRecord manager,
                                          String subject,
                                          ClusterTopologyManager.ScalingAsker asker,
                                          java.util.concurrent.CountDownLatch start,
                                          int passes,
                                          boolean endsTrue) {
        awaitStart(start);
        for (var pass = 0; pass < passes; pass++) {
            manager.scalingHeld(subject, asker, pass % 2 == 0);
            Thread.yield();
        }
        manager.scalingHeld(subject, asker, endsTrue);
    }

    private static void awaitStart(java.util.concurrent.CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// v2711c (c25), as the real hold: releasing a subject that is not held (never asked, or already released) raises no resume.
    @Test
    void scalingHeld_aSubjectNotHeld_raisesNoResume() {
        var manager = coreDisabled();
        var core = ClusterTopologyManager.coreSubject();

        manager.scalingHeld(core, RECONCILER, false);
        manager.scalingHeld(core, RETIREMENT, false);
        assertWarningsStay();

        manager.scalingHeld(core, RECONCILER, true);
        manager.scalingHeld(core, RECONCILER, false);
        manager.scalingHeld(core, RECONCILER, false);

        awaitWarnings(2);
        assertWarningsStay("auto-heal-scaling-held", "auto-heal-scaling-resumed");
    }

    /// v2711 finding 6: a source is declared by the committed configuration's desired topology or by its `[source.*]` tables; any other
    /// name, or no configuration at all, is not.
    @Test
    void declaresSource_committedSourcesOnly() {
        assertThat(ctm.declaresSource(sourceName("primary"))).as("no committed configuration").isFalse();
        seedTopology(entry("primary", "core", 3));
        assertThat(ctm.declaresSource(sourceName("primary"))).as("a desired-topology source").isTrue();
        assertThat(ctm.declaresSource(sourceName("prmary"))).as("a typo").isFalse();
        seedConfig(communityToml(1));
        assertThat(ctm.declaresSource(sourceName("pool"))).as("a [source.*] table").isTrue();
    }

    /// v2711c (c04): a committed, parseable configuration that does not declare the source does not declare it (the reset of it is
    /// refused by the route, which asks this).
    @Test
    void declaresSource_aParseableConfigThatDoesNotDeclareIt_isNotDeclared() {
        seedConfig(communityToml(1));

        assertThat(ctm.declaresSource(sourceName("pool"))).as("declared by [source.pool]").isTrue();
        assertThat(ctm.declaresSource(sourceName("prmary"))).as("a typo of a name the TOML does not declare").isFalse();
        assertThat(ctm.declaresSource(sourceName("elsewhere"))).as("another source").isFalse();
    }

    private static org.pragmatica.aether.environment.SourceName sourceName(String name) {
        return org.pragmatica.aether.environment.SourceName.sourceName(name).unwrap();
    }

    private static String communityToml(int targetSize) {
        return """
            config_version = "1.0.0"
            [cluster]
            name = "test"
            version = "1.0.0"
            [source.pool]
            type = "forge"
            zones = ["old", "new"]
            [source.pool.core]
            count = 3
            [source.pool.worker]
            count = 1
            [community.stable]
            target_size = %d
            [community.stable.placement.destination]
            source = "pool"
            zone = "new"
            """.formatted(targetSize);
    }

    @Test
    void reconcile_workerSurplus_countCommittedWhileDisabled_isRetired() {
        var requested = captureRetirementRequests();
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 2));
        lifecycleManager.preExisting(workerInstance("primary", "primary-worker-0"), workerInstance("primary", "primary-worker-1"));
        disableAutoHealAtConfigVersion(1L);
        commitConfigVersion(2L, entry("primary", "core", 3), entry("primary", "worker", 1));
        ctm.activate();

        ctm.reconcileWorkerTopology();

        assertThat(requested).as("activate() already runs one pass, so the request may repeat")
                             .containsOnly("primary-worker-1");
    }

    /// Re-enabling resumes the path and tells the operator; disabling again re-arms the hold and its event.
    @Test
    void reconcile_autoHealReenabled_resumes_andADisableAfterwardsHoldsAndWarnsAgain() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 1));
        disableAutoHealAtConfigVersion(1L);
        ctm.activate();
        ctm.reconcileWorkerTopology();
        autoHealRef.set(Option.some(AetherValue.AutoHealStateValue.autoHealStateValue(true, "test")));

        ctm.reconcileWorkerTopology();

        assertThat(lifecycleManager.provisionedNodeIds()).as("resumed: the deficit is filled").hasSize(1);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 2);
        assertThat(warnings).containsExactly("auto-heal-scaling-held", "auto-heal-scaling-resumed");
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 3));
        disableAutoHealAtConfigVersion(1L);
        ctm.reconcileWorkerTopology();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() == 3);
        assertThat(warnings.getLast()).isEqualTo("auto-heal-scaling-held");
        assertThat(lifecycleManager.provisionedNodeIds()).as("held again: nothing more is provisioned").hasSize(1);
    }

    /// #1601: a new worker is seeded with the LIVE installed voters. PEER_B is a configured voter the observer
    /// still has NodeInfo for, but membership no longer counts it (dead). Before #1601 the seed was the voter
    /// set itself, so PEER_B's address rode into the new worker's PEERS.
    @Test
    void reconcile_workerDeficit_seedsNewWorkersWithLiveVotersOnly_neverADeadOne() {
        observer.handleDiscoveredNodes(new NetworkMessage.DiscoveredNodes(SELF,
                                                                                                        List.of(INFO_A, INFO_B)));
        assertThat(observer.get(PEER_B).isPresent()).as("arming: the observer still knows the dead voter's address")
                                                    .isTrue();
        ctm = buildCtm(new MembershipLiveness(() -> Set.of(SELF, PEER_A),
                                              () -> Set.of(SELF, PEER_A),
                                              _ -> false,
                                              _ -> false,
                                              Set::of,
                                              () -> 3,
                                              _ -> Option.none()));
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 1));
        ctm.activate();

        ctm.reconcileWorkerTopology();

        assertThat(lifecycleManager.provisionedPeers()).hasSize(1);
        assertThat(lifecycleManager.provisionedPeers().getFirst())
            .contains(PEER_A.id())
            .doesNotContain(PEER_B.id());
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

    /// #1650: two cloud sources, each backing workers in its own zone. Every worker the leader provisions must
    /// BOOT with its own source and zone -- the provision context already carried the right source (above), but
    /// the rendered cloud-init env took `AETHER_SOURCE`/`AETHER_ZONE` from the leader's own process env, and a
    /// node learns its source only from that variable. Asserted on the user-data the provider receives.
    @Test
    void reconcile_twoCloudWorkerSources_eachWorkerBootsWithItsOwnSourceAndZone() {
        seedConfig(TWO_WORKER_SOURCES_TOML,
                   entry("eu-1", "core", 3),
                   entry("eu-1", "worker", 1),
                   entry("eu-2", "worker", 1));

        ctm.activate();

        var userData = lifecycleManager.userDataBySource();

        assertThat(userData).containsOnlyKeys("eu-1", "eu-2");
        assertThat(userData.get("eu-1")).contains("AETHER_SOURCE=\"eu-1\"", "AETHER_ZONE=\"nbg1\"")
                                        .doesNotContain("AETHER_SOURCE=\"eu-2\"", "AETHER_ZONE=\"fsn1\"");
        assertThat(userData.get("eu-2")).contains("AETHER_SOURCE=\"eu-2\"", "AETHER_ZONE=\"fsn1\"")
                                        .doesNotContain("AETHER_SOURCE=\"eu-1\"", "AETHER_ZONE=\"nbg1\"");
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

    private List<String> captureRetirementRequests() {
        var requested = new java.util.concurrent.CopyOnWriteArrayList<String>();
        ctm.installCommunityPlacement(new CommunityPlacementReconciler() {
            public Promise<org.pragmatica.lang.Unit> reconcile() { return Promise.unitPromise(); }
            public Promise<org.pragmatica.lang.Unit> requestRetirement(NodeId node, AetherValue.TopologyEntry entry) {
                requested.add(node.id());
                return Promise.unitPromise();
            }
            public Promise<Boolean> onDrainCompleted(NodeId node, String operation) { return Promise.success(false); }
        });
        return requested;
    }

    /// Newest first: reconciler-minted `-r<clock36>` ids sort after bootstrap `-<index>` ids, so a
    /// scale-down reaps cluster-provisioned workers before bootstrap-provisioned ones.
    @Test
    void reconcile_workerSurplus_requestsDurableRetirementNewestFirst() {
        var requested = captureRetirementRequests();
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 1));
        lifecycleManager.preExisting(workerInstance("primary", "primary-worker-0"),
                                     workerInstance("primary", "primary-worker-1"),
                                     workerInstance("primary", "primary-worker-rzzz-0"));
        ctm.activate();

        ctm.reconcileWorkerTopology();

        assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();
        assertThat(requested).startsWith("primary-worker-rzzz-0", "primary-worker-1");
        assertThat(lifecycleManager.provisionedNodeIds()).isEmpty();
    }

    /// #1543 rule 3 through the reconcile pass: a paired +1 replacement is surge, so the pass retires
    /// nothing; once the pairing reaches RETIRING_OLD the paired original is the one retired.
    @Test
    void reconcile_workerSurplus_honoursTheReplacementPairingSetOnTheCtm() {
        var requested = captureRetirementRequests();
        var pairings = NodeReplacementIndex.nodeReplacementIndex();
        var pairing = new org.pragmatica.aether.slice.kvstore.AetherKey.NodeReplacementKey(new NodeId("primary-worker-1"));
        ctm.setNodeReplacements(pairings);
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 2));
        lifecycleManager.preExisting(workerInstance("primary", "primary-worker-0"),
                                     workerInstance("primary", "primary-worker-1"),
                                     workerInstance("primary", "primary-worker-rzzz-0"));
        pairings.put(pairing, new AetherValue.NodeReplacementValue(new NodeId("primary-worker-rzzz-0"), "worker",
                                                                   AetherValue.NodeReplacementPhase.JOINING, 0L));
        ctm.activate();

        ctm.reconcileWorkerTopology();
        assertThat(requested).isEmpty();

        pairings.put(pairing, new AetherValue.NodeReplacementValue(new NodeId("primary-worker-rzzz-0"), "worker",
                                                                   AetherValue.NodeReplacementPhase.RETIRING_OLD, 0L));
        ctm.reconcileWorkerTopology();
        assertThat(requested).containsExactly("primary-worker-1");
        assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();
    }

    /// Defect A's scale-down consequence, on the reconciler's OWN mints rather than on inventory the
    /// test planted: with the label round-trip broken, surplus was structurally unreachable —
    /// `actual` was always empty, so `terminateSurplusWorkers` had no victims to choose from and a
    /// scale-down could only ever add VMs.
    @Test
    void reconcile_scaleDownAfterOwnMints_neverBypassesDurableRetirement() {
        var requested = captureRetirementRequests();
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
        assertThat(requested).containsExactlyElementsOf(newestTwo);
        assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();
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

    /// #1404: one source whose inventory never answers must not hold back another source's convergence. Worker entries reconcile in
    /// bounded lanes, each item on its own deadline; before, the pass was one chain and a stalled listing stopped every later entry.
    @Test
    void reconcile_stalledSourceInventory_doesNotPreventAnotherSourceFromConverging() {
        seedTopology(entry("unavailable", "worker", 1), entry("healthy", "worker", 1));
        lifecycleManager.blockedSource = "unavailable";
        var held = lifecycleManager.gateListing();

        ctm.activate();

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(lifecycleManager.provisionedSourceNames()).containsExactly("healthy"));
        held.succeed(unit()).await().onFailure(cause -> fail("listing gate release failed: " + cause.message()));
        ctm.deactivate();
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

    /// #1650 F1 (v1650): `eu-1` is pinned to `nbg1` and runs the cores; `us-1` is a ZONELESS source with one
    /// worker. With no configured zone the worker falls back to zone BALANCING, which must count `us-1`'s own
    /// nodes only -- another source's zone names a location this source's provider may not have.
    private static final String ZONELESS_SECOND_SOURCE_TOML = """
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
            credentials = "hcloud-token"
            region = "eu-central"
            zone = "nbg1"

            [source.eu-1.core]
            count = 3

            [source.us-1]
            type = "cloud"
            provider = "hetzner"
            credentials = "hcloud-token"
            region = "us-east"

            [source.us-1.worker]
            count = 1
            """;

    /// CONTROL, the pre-#1650 world: no node carries a source or zone label, so there is nothing to balance.
    @Test
    void reconcile_zonelessSource_unlabelledTopology_getsNoPlacement() {
        assertThat(us1WorkerPlacements(Map.of(), Map.of(), Map.of())).containsExactly(Option.none());
    }

    /// The v1650 defect: once the renderer stamps `eu-1`'s cores with `source=eu-1, zone=nbg1`, the zoneless
    /// `us-1` worker must NOT be steered into `nbg1` -- Hetzner would put it there, AWS and GCP would fail the create.
    @Test
    void reconcile_zonelessSource_isNeverPlacedInAnotherSourcesZone() {
        var eu1 = Map.of(NodeInfo.LABEL_SOURCE, "eu-1", NodeInfo.LABEL_ZONE, "nbg1");

        assertThat(us1WorkerPlacements(eu1, eu1, eu1)).containsExactly(Option.none());
    }

    /// Balancing still works WITHIN the source: `us-1`'s own nodes sit in `ash`, so the hint is `ash`. Counted
    /// across sources, `nbg1` (one node) would be the under-represented zone and the hint would be `nbg1`.
    @Test
    void reconcile_zonelessSource_balancesAcrossItsOwnNodesZones_only() {
        var eu1 = Map.of(NodeInfo.LABEL_SOURCE, "eu-1", NodeInfo.LABEL_ZONE, "nbg1");
        var us1 = Map.of(NodeInfo.LABEL_SOURCE, "us-1", NodeInfo.LABEL_ZONE, "ash");

        assertThat(us1WorkerPlacements(eu1, us1, us1)).containsExactly(Option.some(PlacementHint.zoneHint("ash")));
    }

    private List<Option<PlacementHint>> us1WorkerPlacements(Map<String, String> selfLabels,
                                                           Map<String, String> peerALabels,
                                                           Map<String, String> peerBLabels) {
        var config = new TopologyConfig(SELF,
                                        3,
                                        timeSpan(60).seconds(),
                                        timeSpan(1).seconds(),
                                        List.of(labelled(INFO_SELF, selfLabels),
                                                labelled(INFO_A, peerALabels),
                                                labelled(INFO_B, peerBLabels)));
        observer = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
        // The observer holds only SELF until SWIM discovers the peers; configured peers are not in its topology.
        observer.handleDiscoveredNodes(new NetworkMessage.DiscoveredNodes(SELF,
                                                                          List.of(labelled(INFO_A, peerALabels),
                                                                                  labelled(INFO_B, peerBLabels))));
        assertThat(observer.topology()).as("CONTROL: the balancer sees all three nodes").hasSize(3);
        ctm = buildCtm(MembershipLiveness.UNWIRED);
        seedConfig(ZONELESS_SECOND_SOURCE_TOML, entry("eu-1", "core", 3), entry("us-1", "worker", 1));
        ctm.activate();

        ctm.reconcileWorkerTopology();

        var us1 = lifecycleManager.provisioned.stream()
                                              .filter(spec -> spec.context()
                                                                  .sourceName()
                                                                  .value()
                                                                  .equals("us-1"))
                                              .toList();

        assertThat(us1).as("CONTROL: the zoneless source's worker was provisioned").hasSize(1);

        return us1.stream().map(ProvisionSpec::placement).toList();
    }

    private static NodeInfo labelled(NodeInfo info, Map<String, String> labels) {
        return NodeInfo.nodeInfo(info.id(), info.address(), labels);
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
                                Option.some(nodeId), org.pragmatica.lang.Option.none());
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
        /// The one source whose listing is held by [#gateListing]; every other source answers at once. Empty holds them all.
        private String blockedSource = "";

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

        List<String> provisionedPeers() {
            return provisioned.stream().map(spec -> spec.context().peers().or("")).toList();
        }

        List<String> provisionedRoles() {
            return provisioned.stream().map(spec -> spec.context().role()).distinct().toList();
        }

        /// Raw values, not [SourceName]s: these assertions pin what the provider stamps as the
        /// `aether-source` label, which is the string the reconcile selector must round-trip.
        List<String> provisionedSourceNames() {
            return provisioned.stream().map(spec -> spec.context().sourceName().value()).distinct().toList();
        }

        /// The cloud-init user-data each provision carried, by the source it was provisioned for.
        Map<String, String> userDataBySource() {
            return provisioned.stream()
                              .collect(java.util.stream.Collectors.toMap(spec -> spec.context().sourceName().value(),
                                                                         spec -> spec.userData().or(""),
                                                                         (first, _) -> first));
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

            var gate = blockedSource.isEmpty() || blockedSource.equals(tagFilter.get("aether-source"))
                       ? listGate.get()
                       : Promise.unitPromise();

            return gate.map(_ -> matching(tagFilter));
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
        public boolean isCloudManaged() {
            return true;
        }
    }
}
