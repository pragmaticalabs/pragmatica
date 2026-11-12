// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AutoHealStateKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AutoHealStateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// #685 — the operator's auto-heal disable/enable is a DURABLE cluster fact stored in consensus KV
/// (`AetherKey.AutoHealStateKey`), never a leader's in-memory mood. Prior to this fix,
/// `ClusterTopologyManagerRecord` cached the flag in a local `AtomicBoolean`: a leader failover
/// silently reverted an operator's disable, because the newly-elected leader's fresh CTM instance
/// never saw the in-memory flag the old leader held.
///
/// This test proves the fix with two INDEPENDENT `ClusterTopologyManager` instances sharing ONE
/// real `KVStore<AetherKey, AetherValue>` — modeling exactly the failover scenario: an operator's
/// disable, applied through one node's CTM (`ctmA`, standing in for the pre-failover leader), must
/// be visible to a second CTM (`ctmB`, standing in for the newly-elected leader) that NEVER received
/// the call directly and only ever reads the shared, consensus-materialized KV. Every other CTM test
/// in this package uses a single instance with a per-test fake KV reader/writer — this file is the
/// one place the actual `KVStore` read-after-write path is exercised, because the defect this ticket
/// fixes is specifically about visibility ACROSS instances, not within one.
class ClusterTopologyManagerAutoHealDurabilityTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());

    private KVStore<AetherKey, AetherValue> kvStore;
    private Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier;
    private ClusterTopologyManager ctmA;
    private ClusterTopologyManager ctmB;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(org.pragmatica.cluster.state.kvstore.LeaderKey.INSTANCE,
            new org.pragmatica.cluster.state.kvstore.LeaderValue(SELF, 1)))));
        commandApplier = commands -> Promise.success(kvStore.<Object>process(kvStore.createBatch(commands)));

        ctmA = newCtm();
        ctmB = newCtm();
    }

    /// Builds a fresh, independent CTM instance wired to the SAME `kvStore`/`commandApplier` as every
    /// other instance built by this method within a test — the "#841 pattern": one shared KV, many
    /// CTM instances, none aware of each other, exactly as separate nodes never share process memory
    /// but do share consensus-materialized state.
    private ClusterTopologyManager newCtm() {
        return newCtm(() -> kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class));
    }

    /// Same as [#newCtm()], but with a caller-supplied `autoHealStateReader` instead of one that
    /// reads through to the shared `kvStore` — lets a test build an instance whose LOCAL view is
    /// pinned independently of what the shared, consensus-materialized store actually holds, modeling
    /// a node that is behind on applying the log (#685 review round 1 SHOULD-FIX 2).
    private ClusterTopologyManager newCtm(Supplier<Option<AutoHealStateValue>> autoHealStateReader) {
        return newCtm(autoHealStateReader, Option::none);
    }

    /// Same as [#newCtm(Supplier)], with a caller-supplied view of the committed cluster configuration: lets a test pin this node's
    /// LOCAL configuration view behind what the shared store holds.
    private ClusterTopologyManager newCtm(Supplier<Option<AutoHealStateValue>> autoHealStateReader,
                                          Supplier<Option<ClusterConfigValue>> clusterConfigReader) {
        var config = new TopologyConfig(SELF, 5, timeSpan(60).seconds(), timeSpan(1).seconds(), List.of(INFO_SELF));
        var snapshotSource = new StubSnapshotSource();
        var observer = TopologyObserver.topologyObserver(config, quietRouter(), snapshotSource).unwrap();
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(1).millis(), AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT).unwrap();

        var manager = ClusterTopologyManager.clusterTopologyManager(observer,
                                                             new NoOpLifecycleManager(),
                                                             autoHeal,
                                                             DeploymentMap.deploymentMap(),
                                                             snapshotSource,
                                                             clusterConfigReader,
                                                             commandApplier,
                                                             () -> ClusterPhase.NORMAL,
                                                             _ -> {},
                                                             _ -> {},
                                                             Option::none,
                                                             autoHealStateReader,
                                                             MembershipLiveness.UNWIRED);
        manager.setHierarchyStateWriter(HierarchyStateWriter.hierarchyStateWriter(
            () -> Option.some(new org.pragmatica.cluster.state.kvstore.LeaderValue(SELF, 1)), kvStore::get, commandApplier));
        manager.activate();
        return manager;
    }

    /// Condition 1 — a fresh/empty KV (no operator has ever touched the flag; also the state of
    /// every pre-#685 cluster) answers enabled, on every instance that reads it.
    @Test
    void freshEmptyKv_answersEnabled_onEveryInstance() {
        assertThat(ctmA.isAutoHealEnabled()).isTrue();
        assertThat(ctmB.isAutoHealEnabled()).isTrue();
    }

    @Test
    void disableOnOneInstance_isVisibleOnAnInstanceThatNeverReceivedTheCall() {
        var priorState = ctmA.setAutoHealEnabled(false, "operator: incident response").await().unwrap();

        assertThat(priorState).as("prior state was the default enabled").isTrue();
        assertThat(ctmA.isAutoHealEnabled()).isFalse();
        assertThat(ctmB.isAutoHealEnabled())
                .as("ctmB never saw setAutoHealEnabled — it must read the disable from the shared KV, not an in-memory flag")
                .isFalse();
    }

    @Test
    void enableAfterDisable_isVisibleOnTheOtherInstanceToo() {
        ctmA.setAutoHealEnabled(false, "operator: incident response").await().unwrap();

        var priorState = ctmB.setAutoHealEnabled(true, "operator: incident resolved").await().unwrap();

        assertThat(priorState).as("prior state was disabled").isFalse();
        assertThat(ctmB.isAutoHealEnabled()).isTrue();
        assertThat(ctmA.isAutoHealEnabled())
                .as("ctmA never saw the re-enable call — it must read it from the shared KV")
                .isTrue();
    }

    /// A same-state toggle leaves what every reader observes unchanged — but (#685 review round 1
    /// SHOULD-FIX 2) it is NOT a no-op internally: the write still goes through the shared KV
    /// unconditionally. See [#redundantEnable_writesAnyway_whenThisNodesLocalViewIsStale] for the test
    /// that observes the write itself; this one only pins the observable outcome.
    @Test
    void redundantEnable_leavesObservedStateUnchanged_everywhere() {
        var priorState = ctmA.setAutoHealEnabled(true, "operator: no-op enable").await().unwrap();

        assertThat(priorState).isTrue();
        assertThat(ctmA.isAutoHealEnabled()).isTrue();
        assertThat(ctmB.isAutoHealEnabled()).isTrue();
    }

    /// #685 review round 1 SHOULD-FIX 2 — a node whose own applied-log position lags the durable
    /// truth must not let an equality match on its STALE local read skip the write. Here `staleCtm`'s
    /// `autoHealStateReader` is pinned to a fixed "enabled" answer, independent of what the shared
    /// `kvStore` actually holds (which this test drives to disabled first) — modeling a node that
    /// has not yet applied the disable. Told "enable" (redundant per its stale local view, but NOT
    /// redundant against the durable record), it must still issue the Put: skipping it would leave
    /// the durable record disabled forever while telling the operator the no-op succeeded.
    @Test
    void redundantEnable_writesAnyway_whenThisNodesLocalViewIsStale() {
        ctmA.setAutoHealEnabled(false, "operator: incident response").await().unwrap();

        assertThat(kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class)
                          .map(AutoHealStateValue::enabled)
                          .or(true))
                .as("durable record is disabled")
                .isFalse();

        var staleCtm = newCtm(() -> Option.some(AutoHealStateValue.autoHealStateValue(true, "stale-local-view")));

        var outcome = staleCtm.setAutoHealEnabled(true, "operator: redundant per its stale view").await();
        assertThat(outcome.isFailure()).isTrue();
        assertThat(kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class).unwrap().enabled()).isFalse();

    }

    /// v2711 finding 4: the disable records the version and per-subject scaling values of the configuration it is fenced on.
    @Test
    void disable_recordsTheWitnessedConfigurationsVersionAndScaling() {
        commitConfig(config(2L, 2));
        var ctm = newCtm(() -> kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class), this::storedConfig);

        ctm.setAutoHealEnabled(false, "operator: maintenance").await().unwrap();

        var record = kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class).unwrap();
        assertThat(record.enabled()).isFalse();
        assertThat(record.disabledAtConfigVersion()).isEqualTo(2L);
        assertThat(record.disabledAtScaling()).containsExactlyInAnyOrderEntriesOf(Map.of("worker primary/core", 3, "worker primary/worker", 2));
    }

    /// v2711 finding 4: a node whose applied configuration lags the committed one (v2 decided, this node still on v1) must not record v1's
    /// values: the witness on the configuration refuses the disable, and nothing is written.
    @Test
    void disable_isRefused_whenThisNodesConfigurationViewIsStale() {
        var stale = config(1L, 2);
        commitConfig(config(2L, 3));
        var ctm = newCtm(() -> kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class), () -> Option.some(stale));

        var outcome = ctm.setAutoHealEnabled(false, "operator: maintenance").await();

        assertThat(outcome.isFailure()).as("the stale witness is refused").isTrue();
        assertThat(kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class).isEmpty())
                .as("no disable record was written")
                .isTrue();
    }

    /// #2944: a fence CONFLICT is transient and writes nothing, so the leader-side toggle retries it, re-reading the configuration each time:
    /// the first read is stale (v1), the second is the committed v2, and the caller sees plain success with the v2 baseline recorded.
    @Test
    void disable_retriesTransientFenceConflict_andSucceedsAfterTheViewSettles() {
        var stale = config(1L, 2);
        commitConfig(config(2L, 3));
        var reads = new AtomicInteger();
        var ctm = newCtm(() -> kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class),
                         () -> reads.getAndIncrement() == 0 ? Option.some(stale) : storedConfig());

        reads.set(0);

        var outcome = ctm.setAutoHealEnabled(false, "operator: maintenance").await();

        assertThat(outcome.isSuccess()).as("the transient conflict never reaches the caller").isTrue();
        assertThat(reads.get()).as("one conflicting attempt, one successful attempt").isEqualTo(2);
        assertThat(kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class).unwrap().disabledAtConfigVersion()).isEqualTo(2L);
    }

    /// #2944: the retry is bounded; a view that never settles surfaces the CONFLICT itself (the management API answers 409 for it), after
    /// exactly five attempts, and nothing is written.
    @Test
    void disable_surfacesTheFenceConflict_whenTheBoundIsExhausted() {
        var stale = config(1L, 2);
        commitConfig(config(2L, 3));
        var reads = new AtomicInteger();
        var ctm = newCtm(() -> kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class),
                         () -> {
                             reads.incrementAndGet();
                             return Option.some(stale);
                         });

        reads.set(0);
        var started = System.nanoTime();

        var outcome = ctm.setAutoHealEnabled(false, "operator: maintenance").await();

        assertThat((System.nanoTime() - started) / 1_000_000L).as("pauses of 50, 100, 150 and 200 ms between the five attempts")
                                                               .isGreaterThanOrEqualTo(450L);
        outcome.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("a never-settling view must not succeed"))
               .onFailure(cause -> assertThat(cause).isSameAs(HierarchyStateWriter.Refusal.CONFLICT));
        assertThat(reads.get()).as("the documented bound: five attempts").isEqualTo(5);
        assertThat(kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class).isEmpty()).isTrue();
    }

    /// #2944: a refusal that is not a transient conflict keeps its meaning and is not retried.
    @Test
    void disable_doesNotRetryARefusalThatIsNotAConflict() {
        var reads = new AtomicInteger();
        var ctm = newCtm(() -> kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class),
                         () -> {
                             reads.incrementAndGet();
                             return storedConfig();
                         });
        ctm.setHierarchyStateWriter(HierarchyStateWriter.unavailable());
        reads.set(0);

        var outcome = ctm.setAutoHealEnabled(false, "operator: maintenance").await();

        outcome.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("an inactive writer must refuse"))
               .onFailure(cause -> assertThat(cause).isSameAs(HierarchyStateWriter.Refusal.INACTIVE));
        assertThat(reads.get()).as("INACTIVE is not retried").isEqualTo(1);
    }

    /// v2711 survivor 6: a disable made with no committed configuration records version 0 and no scaling, not a default version.
    @Test
    void disable_withNoCommittedConfiguration_recordsVersionZeroAndNoScaling() {
        ctmA.setAutoHealEnabled(false, "operator: maintenance").await().unwrap();

        var record = kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class).unwrap();
        assertThat(record.enabled()).isFalse();
        assertThat(record.disabledAtConfigVersion()).isZero();
        assertThat(record.disabledAtScaling()).isEmpty();
    }

    /// v2711 survivor 6: the disable records each declared community's `target_size` under its hold subject, next to the worker counts, and
    /// the configuration's own version.
    @Test
    void disable_recordsEachCommunitysTargetSizeBesideTheWorkerCounts() {
        commitConfig(configWith(Option.some(COMMUNITY_TOML.formatted(4)),
                                7L,
                                new AetherValue.TopologyEntry("pool", "core", 3),
                                new AetherValue.TopologyEntry("pool", "worker", 1)));
        var ctm = newCtm(() -> kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class), this::storedConfig);

        ctm.setAutoHealEnabled(false, "operator: maintenance").await().unwrap();

        var record = kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class).unwrap();
        assertThat(record.disabledAtConfigVersion()).isEqualTo(7L);
        assertThat(record.disabledAtScaling()).containsExactlyInAnyOrderEntriesOf(Map.of("worker pool/core", 3,
                                                                                         "worker pool/worker", 1,
                                                                                         "community stable", 4));
    }

    /// v2711 survivor 6: a duplicated (source, role) records its first entry's count, as the desired-count lookup does.
    @Test
    void disable_recordsTheFirstEntryOfADuplicatedSourceRole() {
        commitConfig(configWith(Option.none(),
                                3L,
                                new AetherValue.TopologyEntry("pool", "core", 3),
                                new AetherValue.TopologyEntry("pool", "core", 5)));
        var ctm = newCtm(() -> kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class), this::storedConfig);

        ctm.setAutoHealEnabled(false, "operator: maintenance").await().unwrap();

        var record = kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class).unwrap();
        assertThat(record.disabledAtScaling()).containsExactlyEntriesOf(Map.of("worker pool/core", 3));
    }

    /// v2711 survivor 6: an enable records no configuration version and no scaling, even when a configuration is committed.
    @Test
    void enable_recordsNoVersionAndNoScaling_evenWithACommittedConfiguration() {
        commitConfig(config(2L, 2));
        var ctm = newCtm(() -> kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class), this::storedConfig);
        ctm.setAutoHealEnabled(false, "operator: maintenance").await().unwrap();

        ctm.setAutoHealEnabled(true, "operator: done").await().unwrap();

        var record = kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class).unwrap();
        assertThat(record.enabled()).isTrue();
        assertThat(record.disabledAtConfigVersion()).isZero();
        assertThat(record.disabledAtScaling()).isEmpty();
    }

    private Option<ClusterConfigValue> storedConfig() {
        return kvStore.getTyped(AetherKey.ClusterConfigKey.CURRENT, ClusterConfigValue.class);
    }

    private void commitConfig(ClusterConfigValue value) {
        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT, value))));
    }

    private static final String COMMUNITY_TOML = """
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
        """;

    private static ClusterConfigValue configWith(Option<String> toml, long version, AetherValue.TopologyEntry... entries) {
        return new ClusterConfigValue(toml, "test", "1.0.0", List.of(entries), 3, 9, "test", version, 1L);
    }

    private static ClusterConfigValue config(long version, int workers) {
        return new ClusterConfigValue(Option.none(),
                                      "test",
                                      "1.0.0",
                                      List.of(new AetherValue.TopologyEntry("primary", "core", 3),
                                              new AetherValue.TopologyEntry("primary", "worker", workers)),
                                      3,
                                      9,
                                      "test",
                                      version,
                                      1L);
    }

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

    /// Auto-heal disable/enable never reaches `NodeLifecycleManager` — every method here is
    /// unreachable from this test's exercised surface and exists only to satisfy the interface.
    private static final class NoOpLifecycleManager implements NodeLifecycleManager {
        @Override public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStarted(InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
                                                                                           InstanceStatus.RUNNING,
                                                                                           List.of("127.0.0.1"),
                                                                                           InstanceType.ON_DEMAND).unwrap()));
        }

        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            return Promise.success(InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
                                                              InstanceStatus.RUNNING,
                                                              List.of("127.0.0.1"),
                                                              InstanceType.ON_DEMAND).unwrap());
        }

        @Override public Promise<Unit> terminateNode(NodeId nodeId) {
            return Promise.success(Unit.unit());
        }

        @Override public boolean isCloudManaged() {
            return true;
        }
    }

    private static org.pragmatica.serialization.Serializer stubSerializer() {
        return new org.pragmatica.serialization.Serializer() {
            @Override
            public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {}
        };
    }

    private static org.pragmatica.serialization.Deserializer stubDeserializer() {
        return new org.pragmatica.serialization.Deserializer() {
            @Override
            public <T> T read(io.netty.buffer.ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
