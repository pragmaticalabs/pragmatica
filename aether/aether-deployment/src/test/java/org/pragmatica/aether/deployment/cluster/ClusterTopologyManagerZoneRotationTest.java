// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// #334 — auto-heal replacement zone rotation. The CTM cloud-provision path must rotate across the
/// configured `[source.*] zones` exactly like the bootstrap path on [EnvironmentError.CapacityUnavailable]:
/// pin each attempt to a specific zone, advance to the next zone on capacity exhaustion, fail
/// immediately on any other (non-retryable) error, fail when all zones are exhausted, and degrade to
/// a single (unpinned) attempt when no cloud zones are configured — so a zone running out of capacity
/// during a replacement no longer wedges the cluster's heal.
class ClusterTopologyManagerZoneRotationTest {
    /// RFC-0017 C1 — desired topology replaced the core-only scalar; tests that only care about a
    /// core count build a single-source entry.
    private static java.util.List<org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry> coreTopology(int count) {
        return java.util.List.of(new org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry("primary", "core", count));
    }

    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();
    private static final NodeId DEAD_PEER = nodeId("node-dead").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("10.0.0.1", 6000).unwrap());
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("10.0.0.2", 6000).unwrap());
    private static final NodeInfo INFO_B = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("10.0.0.3", 6000).unwrap());

    private static final String MULTI_ZONE_TOML = """
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
            zones = ["fsn1", "nbg1", "hel1"]

            [source.eu-1.core]
            count = 3
            """;

    private StubSnapshotSource snapshotSource;
    private TopologyObserver observer;
    private RecordingLifecycleManager lifecycleManager;
    private RecordingClusterStore clusterStore;
    private ClusterTopologyManager ctm;

    @BeforeEach
    void setUp() {
        snapshotSource = new StubSnapshotSource();
        var config = new TopologyConfig(SELF,
                                        5,
                                        timeSpan(60).seconds(),
                                        timeSpan(1).seconds(),
                                        List.of(INFO_SELF, INFO_A, INFO_B));
        observer = TopologyObserver.topologyObserver(config, quietRouter(), snapshotSource).unwrap();
        lifecycleManager = new RecordingLifecycleManager();
        clusterStore = new RecordingClusterStore();
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(1).millis(), AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT).unwrap();
        ctm = ClusterTopologyManager.clusterTopologyManager(observer,
                                                            lifecycleManager,
                                                            autoHeal,
                                                            DeploymentMap.deploymentMap(),
                                                            snapshotSource,
                                                            clusterStore::current,
                                                            clusterStore::apply,
                                                            () -> ClusterPhase.NORMAL);
    }

    @Test
    void realSourceAndCapacityChainRotatesOnlyAfterExplicitCapacityRefusal() {
        verifyRealSourceFallback(false);
    }

    @Test
    void realSourceAndCapacityChainRetainsAmbiguousCreateWithoutFallback() {
        verifyRealSourceFallback(true);
    }

    private void verifyRealSourceFallback(boolean ambiguous) {
        var attempts = new java.util.ArrayList<org.pragmatica.aether.environment.ProvisionRequest>();
        var kv = new org.pragmatica.cluster.state.kvstore.KVStore<org.pragmatica.cluster.state.kvstore.StructuredKey, Object>(quietRouter(),
            new org.pragmatica.serialization.Serializer() {
                @Override public <T> void write(io.netty.buffer.ByteBuf buffer, T value) {}
            }, new org.pragmatica.serialization.Deserializer() {
                @Override public <T> T read(io.netty.buffer.ByteBuf buffer) { return null; }
            });
        var authority = new org.pragmatica.cluster.state.kvstore.LeaderValue(SELF, 1);
        kv.process(kv.createBatch(List.of(new KVCommand.Put<>(org.pragmatica.cluster.state.kvstore.LeaderKey.INSTANCE, authority))));
        var provider = new org.pragmatica.aether.environment.ComputeProvider() {
            @Override public Promise<InstanceInfo> createFrom(org.pragmatica.aether.environment.ProvisionRequest request) {
                attempts.add(request);
                var ledger = kv.getTyped(org.pragmatica.aether.slice.kvstore.AetherKey.CapacityLedgerKey.INSTANCE,
                    org.pragmatica.aether.slice.kvstore.AetherValue.CapacityLedgerValue.class).unwrap();
                assertThat(ledger.allocated()).isEqualTo(1);
                assertThat(request.context().sourceName().value()).isEqualTo("eu-1");
                if (request.zone().equals("fsn1")) {
                    return ambiguous ? org.pragmatica.lang.utils.Causes.cause("create timed out after dispatch").promise()
                        : EnvironmentError.capacityUnavailable("fsn1", new IllegalStateException("known capacity refusal")).promise();
                }
                return Promise.success(new InstanceInfo(InstanceId.instanceId("native-nbg1").unwrap(), InstanceStatus.RUNNING,
                    List.of("127.0.0.1"), InstanceType.ON_DEMAND, Map.of(), request.context().nodeId(), Option.some(request.zone())));
            }
            @Override public Promise<Unit> terminate(InstanceId id) { return Promise.unitPromise(); }
            @Override public Promise<List<InstanceInfo>> listInstances() { return Promise.success(List.of()); }
            @Override public Promise<InstanceInfo> instanceStatus(InstanceId id) { return org.pragmatica.lang.utils.Causes.cause("unused").promise(); }
        };
        clusterStore.seedToml(MULTI_ZONE_TOML.replace("provider = \"hetzner\"", "provider = \"hetzner\"\ncredentials = \"test-account\"")
            .replace("count = 3", "count = 3\ninstance_type = \"small\"\nimage = \"test-image\""));
        var registry = SourceComputeRegistry.sourceComputeRegistry(clusterStore::current,
            config -> org.pragmatica.lang.Result.success(org.pragmatica.aether.environment.EnvironmentIntegration.withCompute(provider)));
        var delegate = NodeLifecycleManager.nodeLifecycleManager(registry,
            _ -> org.pragmatica.lang.Result.success(org.pragmatica.aether.environment.SourceName.sourceName("eu-1").unwrap()),
            Option.none(), Option.none());
        // The generic store contains framework LeaderKey alongside AetherKey values.
        @SuppressWarnings({"rawtypes", "unchecked"})
        var typed = (org.pragmatica.cluster.state.kvstore.KVStore<AetherKey, org.pragmatica.aether.slice.kvstore.AetherValue>) (Object) kv;
        var capacity = CapacityControlledLifecycle.capacityControlledLifecycle(delegate, SELF, typed,
            commands -> Promise.success(typed.process(typed.createBatch(commands))), () -> true, () -> 1);
        var manager = ClusterTopologyManager.clusterTopologyManager(observer, capacity,
            AutoHealConfig.autoHealConfig(timeSpan(1).millis(), AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT).unwrap(),
            DeploymentMap.deploymentMap(), snapshotSource, clusterStore::current, clusterStore::apply, () -> ClusterPhase.NORMAL);
        manager.activate();
        var target = nodeId("real-fallback-target").unwrap();
        var outcome = manager.provisionReplacement(target, Option.some(DEAD_PEER), Set.of(SELF, PEER_A, PEER_B), NodeRole.CORE).await();
        assertThat(attempts.stream().map(org.pragmatica.aether.environment.ProvisionRequest::zone).toList())
            .containsExactlyElementsOf(ambiguous ? List.of("fsn1") : List.of("fsn1", "nbg1"));
        var reservation = typed.getTyped(new AetherKey.CapacityReservationKey(target),
            org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationValue.class).unwrap();
        assertThat(reservation.sourceName()).isEqualTo("eu-1");
        assertThat(reservation.phase()).isEqualTo(ambiguous
            ? org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationPhase.DISPATCHED
            : org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationPhase.OBSERVED);
        assertThat(outcome.isSuccess()).isEqualTo(!ambiguous);
        outcome.onSuccess(instance -> assertThat(instance.observedZone()).isEqualTo(Option.some("nbg1")));
        manager.deactivate();
    }

    @Nested
    class ZoneRotation {

        @Test
        void provisionReplacement_rotatesToNextZone_whenFirstZoneCapacityUnavailable() {
            lifecycleManager.failCapacityFor("fsn1");
            clusterStore.seedToml(MULTI_ZONE_TOML);
            ctm.activate();

            var result = ctm.provisionReplacement(nodeId("node-r1").unwrap(),
                                                  Option.some(DEAD_PEER),
                                                  Set.of(SELF, PEER_A, PEER_B),
                                                  NodeRole.CORE)
                            .await();

            assertThat(result.isSuccess())
                    .as("rotation past the capacity-exhausted fsn1 to nbg1 heals the replacement")
                    .isTrue();
            assertThat(lifecycleManager.attemptedZones())
                    .as("first attempt fsn1 (capacity), rotated to nbg1 (success), no further attempts")
                    .containsExactly("fsn1", "nbg1");
        }
    }

    @Nested
    class NonCapacityErrors {

        @Test
        void provisionReplacement_failsImmediately_onNonCapacityError() {
            lifecycleManager.failProvisionFor("fsn1");
            clusterStore.seedToml(MULTI_ZONE_TOML);
            ctm.activate();

            var result = ctm.provisionReplacement(nodeId("node-r2").unwrap(),
                                                  Option.none(),
                                                  Set.of(SELF, PEER_A, PEER_B),
                                                  NodeRole.CORE)
                            .await();

            assertThat(result.isFailure())
                    .as("a non-capacity provisioning error is non-retryable — propagated immediately")
                    .isTrue();
            assertThat(lifecycleManager.attemptedZones())
                    .as("non-capacity error must NOT rotate — only the first zone is attempted")
                    .containsExactly("fsn1");
        }
    }

    @Nested
    class Exhaustion {

        @Test
        void provisionReplacement_fails_whenAllZonesCapacityUnavailable() {
            lifecycleManager.failCapacityFor("fsn1");
            lifecycleManager.failCapacityFor("nbg1");
            lifecycleManager.failCapacityFor("hel1");
            clusterStore.seedToml(MULTI_ZONE_TOML);
            ctm.activate();

            var result = ctm.provisionReplacement(nodeId("node-r3").unwrap(),
                                                  Option.none(),
                                                  Set.of(SELF, PEER_A, PEER_B),
                                                  NodeRole.CORE)
                            .await();

            assertThat(result.isFailure())
                    .as("all configured zones full — provisioning fails (no phantom success)")
                    .isTrue();
            result.onFailure(cause -> assertThat(cause.message())
                    .as("the exhaustion cause names the zones that were tried")
                    .contains("fsn1", "nbg1", "hel1"));
            assertThat(lifecycleManager.attemptedZones())
                    .as("each zone attempted exactly once in order, then bail (no infinite loop)")
                    .containsExactly("fsn1", "nbg1", "hel1");
        }
    }

    @Nested
    class BackwardCompat {

        @Test
        void provisionReplacement_noZonesConfigured_attemptsOnce() {
            clusterStore.seedToml(SINGLE_ZONELESS_TOML);
            ctm.activate();

            var result = ctm.provisionReplacement(nodeId("node-r4").unwrap(),
                                                  Option.none(),
                                                  Set.of(SELF, PEER_A, PEER_B),
                                                  NodeRole.CORE)
                            .await();

            assertThat(result.isSuccess())
                    .as("a zoneless cloud config provisions exactly as before")
                    .isTrue();
            assertThat(lifecycleManager.provisionCount())
                    .as("no configured zones → single provisionNode attempt (today's behavior)")
                    .isEqualTo(1);
        }

        @Test
        void provisionReplacement_blankToml_attemptsOnce() {
            clusterStore.seedToml("");
            ctm.activate();

            var result = ctm.provisionReplacement(nodeId("node-r5").unwrap(),
                                                  Option.none(),
                                                  Set.of(SELF, PEER_A, PEER_B),
                                                  NodeRole.CORE)
                            .await();

            assertThat(result.isSuccess())
                    .as("a blank persisted config provisions with a single (unpinned) attempt")
                    .isTrue();
            assertThat(lifecycleManager.provisionCount())
                    .as("blank config → no zones → single provisionNode attempt")
                    .isEqualTo(1);
        }
    }

    private static final String SINGLE_ZONELESS_TOML = """
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

    private static MessageRouter.MutableRouter quietRouter() {
        var router = MessageRouter.mutable();
        router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});
        return router;
    }

    private static final class StubSnapshotSource implements GenerationSnapshotSource {
        private final AtomicReference<Option<MembershipView>> view = new AtomicReference<>(Option.none());

        @Override public Option<MembershipView> currentMembershipView() {
            return view.get();
        }

        @Override public long observedRabiaTerm() {
            return 0L;
        }
    }

    private static final class RecordingClusterStore {
        private final AtomicReference<Option<ClusterConfigValue>> current = new AtomicReference<>(Option.none());

        void seedToml(String toml) {
            current.set(Option.some(new ClusterConfigValue(toml, "prod-cluster", "1.0.0", coreTopology(5), 3, 9, "cloud", 1L, System.currentTimeMillis())));
        }

        Option<ClusterConfigValue> current() {
            return current.get();
        }

        Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            return Promise.success(List.of());
        }
    }

    /// Records the zone of every provision attempt (read back from `spec.placement()`) and returns a
    /// scripted outcome per zone: a [EnvironmentError.CapacityUnavailable] (retryable), a
    /// [EnvironmentError.ProvisionFailed] (non-retryable), or — by default — success.
    private static final class RecordingLifecycleManager implements NodeLifecycleManager {
        private final List<String> attemptedZones = new CopyOnWriteArrayList<>();
        private final Set<String> capacityFailZones = ConcurrentHashMap.newKeySet();
        private final Set<String> provisionFailZones = ConcurrentHashMap.newKeySet();

        void failCapacityFor(String zone) {
            capacityFailZones.add(zone);
        }

        void failProvisionFor(String zone) {
            provisionFailZones.add(zone);
        }

        List<String> attemptedZones() {
            return new ArrayList<>(attemptedZones);
        }

        int provisionCount() {
            return attemptedZones.size();
        }

        @Override public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStarted(InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
                                                                                          InstanceStatus.RUNNING,
                                                                                          List.of("127.0.0.1"),
                                                                                          InstanceType.ON_DEMAND).unwrap()));
        }

        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            var zone = zoneOf(spec);
            attemptedZones.add(zone);

            if (capacityFailZones.contains(zone)) {
                return EnvironmentError.capacityUnavailable(zone, new RuntimeException("error during placement")).promise();
            }
            if (provisionFailZones.contains(zone)) {
                return EnvironmentError.provisionFailed(new RuntimeException("unauthorized")).promise();
            }

            return Promise.success(InstanceInfo.instanceInfo(InstanceId.instanceId("stub-" + zone).unwrap(),
                                                             InstanceStatus.RUNNING,
                                                             List.of("127.0.0.1"),
                                                             InstanceType.ON_DEMAND).unwrap());
        }

        private static String zoneOf(ProvisionSpec spec) {
            return spec.placement()
                       .filter(hint -> hint instanceof PlacementHint.ZoneHint)
                       .map(hint -> ((PlacementHint.ZoneHint) hint).zoneName())
                       .or("");
        }

        @Override public Promise<Unit> terminateNode(NodeId nodeId) {
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> restartNode(NodeId nodeId) {
            return Promise.success(Unit.unit());
        }

        @Override public boolean isCloudManaged() {
            return true;
        }
    }
}
