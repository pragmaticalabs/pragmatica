// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Unit tests for [`BootstrapModule`] covering leader-gain DHT bootstrap, the cluster-config
/// seed grace window, the bootstrap-committed callback, and the leader-loss reset.
class BootstrapModuleTest {
    private static final NodeId SELF = new NodeId("node-self");

    @Nested
    class CorePartitionBootstrap {
        @Test
        void onLeaderGained_appliesCorePartitionPut() {
            var fixture = newFixture(/* initialCoreSize */ 1);
            fixture.module.onLeaderGained();

            var puts = collectPuts(fixture.cluster.batches);
            assertThat(puts).anyMatch(p -> p.key() instanceof DhtPartitionOwnershipKey
                                            && BootstrapModule.CORE_PARTITION_ID.equals(((DhtPartitionOwnershipKey) p.key()).partitionId()));
        }
    }

    @Nested
    class ClusterConfigSeed {
        @Test
        void initialCoreSizeBelowQuorum_noClusterConfigSeed() {
            // initialCoreSize < 3 → spec §8: skip seed entirely.
            var fixture = newFixture(/* initialCoreSize */ 2);
            fixture.module.onLeaderGained();

            var clusterConfigPuts = collectPuts(fixture.cluster.batches).stream()
                                                                          .filter(p -> p.key() instanceof ClusterConfigKey)
                                                                          .toList();
            assertThat(clusterConfigPuts).isEmpty();
        }

        @Test
        void initialCoreSizeAtQuorum_seedEmitted() {
            // initialCoreSize == 3 (>= quorum) and no existing ClusterConfig — seed is emitted.
            // The lifecycle-count grace window was intentionally removed (commit 62ae7b19f,
            // "drop seed grace period"); the seed no longer waits for NodeLifecycleKey
            // entries to materialize. Spec §8: at-or-above quorum, seed on first leader gain.
            var fixture = newFixture(/* initialCoreSize */ 3);
            fixture.module.onLeaderGained();

            var clusterConfigPuts = collectPuts(fixture.cluster.batches).stream()
                                                                          .filter(p -> p.key() instanceof ClusterConfigKey)
                                                                          .toList();
            assertThat(clusterConfigPuts).hasSize(1);
            var seeded = (ClusterConfigValue) clusterConfigPuts.getFirst().value();
            assertThat(seeded.coreCount()).isEqualTo(3);
        }

        @Test
        void seedClusterName_matchesEnvClusterName() {
            // The seed now sources clusterName from AETHER_CLUSTER_NAME so KV and the
            // node-side env gate agree. Empty remains the last-resort fallback (env unset
            // in CI yields ""), so we assert against the env-derived expected value.
            var fixture = newFixture(/* initialCoreSize */ 3);
            fixture.module.onLeaderGained();

            var clusterConfigPuts = collectPuts(fixture.cluster.batches).stream()
                                                                          .filter(p -> p.key() instanceof ClusterConfigKey)
                                                                          .toList();
            assertThat(clusterConfigPuts).hasSize(1);
            var seeded = (ClusterConfigValue) clusterConfigPuts.getFirst().value();
            var envName = System.getenv("AETHER_CLUSTER_NAME");
            var expected = envName != null && !envName.isBlank() ? envName : "";
            assertThat(seeded.clusterName()).isEqualTo(expected);
        }
    }

    @Nested
    class BootstrapCommittedCallback {
        @Test
        void onLeaderGained_firesCallbackOnceBatchCommits() {
            var fixture = newFixture(/* initialCoreSize */ 1);
            var fired = new AtomicInteger(0);
            fixture.module.onBootstrapCommitted(fired::incrementAndGet);

            fixture.module.onLeaderGained();

            // A core-partition apply happened — completion callback must have fired exactly once.
            assertThat(fired.get()).isEqualTo(1);
        }

        @Test
        void onLeaderGained_emptyBatch_firesCallbackImmediately() {
            // Pre-seed an existing core partition + cluster config so the planner produces no work.
            var existingConfig = ClusterConfigValue.clusterConfigValue("",
                                                                        "",
                                                                        "1.0.0",
                                                                        1,
                                                                        3,
                                                                        15,
                                                                        "test",
                                                                        1L);
            var fixture = newFixture(/* initialCoreSize */ 1);
            fixture.kv.put(ClusterConfigKey.CURRENT, existingConfig);

            // Pre-seed a core partition owned by self so planCoreBootstrap returns none.
            var ownerEpoch = org.pragmatica.aether.slice.generation.Epoch.epoch(0L, 0L);
            var existingOwnership = AetherValue.DhtPartitionOwnershipValue.dhtPartitionOwnershipValue(SELF,
                                                                                                      BootstrapModule.CORE_COMMUNITY_ID,
                                                                                                      ownerEpoch,
                                                                                                      1L,
                                                                                                      org.pragmatica.hlc.HlcTimestamp.ZERO);
            fixture.kv.put(DhtPartitionOwnershipKey.dhtPartitionOwnershipKey(BootstrapModule.CORE_PARTITION_ID), existingOwnership);

            var fired = new AtomicInteger(0);
            fixture.module.onBootstrapCommitted(fired::incrementAndGet);

            fixture.module.onLeaderGained();

            // Empty batch path — callback fires immediately without going through cluster.apply.
            assertThat(fired.get()).isEqualTo(1);
        }
    }

    @Nested
    class LeaderLossResetsBootstrapState {
        @Test
        void onLeaderLost_resetsBootstrapComplete_subsequentLeaderGainAppliesAgain() {
            var fixture = newFixture(/* initialCoreSize */ 1);
            fixture.module.onLeaderGained();
            var batchesAfterFirstGain = fixture.cluster.batches.size();
            assertThat(batchesAfterFirstGain).isGreaterThan(0);

            fixture.module.onLeaderLost();
            // After loss + regain, the module must apply again (bootstrapComplete reset).
            fixture.module.onLeaderGained();
            assertThat(fixture.cluster.batches.size()).isGreaterThan(batchesAfterFirstGain);
        }
    }

    /// RC1 Step 2: the snapshot-then-tail subscriber entry point. Every variant must be
    /// accepted by the canonical `MembershipDecision` channel without throwing.
    @Nested
    class MembershipDecisionTail {
        @Test
        void onMembershipDecision_postLeaderGained_acceptsAllSevenVariants() {
            var fixture = newFixture(/* initialCoreSize */ 1);
            fixture.module.onLeaderGained();
            // All seven variants must be accepted by the tail subscriber.
            fixture.module.onMembershipDecision(MembershipDecision.nodeJoined(SELF, List.of(SELF)));
            fixture.module.onMembershipDecision(MembershipDecision.nodeRemoved(SELF, List.of(SELF)));
            fixture.module.onMembershipDecision(MembershipDecision.nodeDecommissioned(SELF, List.of(SELF)));
            fixture.module.onMembershipDecision(MembershipDecision.nodeJoining(SELF, List.of(SELF)));
            fixture.module.onMembershipDecision(MembershipDecision.nodeDraining(SELF, List.of(SELF)));
            fixture.module.onMembershipDecision(MembershipDecision.nodeFailedDrain(SELF, List.of(SELF)));
            fixture.module.onMembershipDecision(MembershipDecision.nodeShuttingDown(SELF, List.of(SELF)));
            // No exception thrown — single canonical channel coverage is the assertion.
        }
    }

    // ---- Fixture wiring ----

    private static Fixture newFixture(int initialCoreSize) {
        var hlcClock = HlcClock.hlcClock(new NodeId("test-self"));
        var projector = ClusterGenerationProjector.clusterGenerationProjector();
        Map<AetherKey, AetherValue> kv = new HashMap<>();
        var isLeader = new AtomicBoolean(true);
        var cluster = new RecordingClusterNode();
        var module = BootstrapModule.bootstrapModule(isLeader::get,
                                                      () -> 1L,
                                                      () -> Option.<Long>none(),
                                                      hlcClock,
                                                      projector,
                                                      () -> Map.copyOf(kv),
                                                      () -> SELF,
                                                      () -> initialCoreSize,
                                                      cluster);
        return new Fixture(module, cluster, kv);
    }

    @SuppressWarnings({"unchecked"})
    private static List<KVCommand.Put<AetherKey, AetherValue>> collectPuts(List<List<KVCommand<AetherKey>>> batches) {
        var result = new ArrayList<KVCommand.Put<AetherKey, AetherValue>>();
        for (var batch : batches) {
            for (var cmd : batch) {
                if (cmd instanceof KVCommand.Put<?, ?> put) {
                    result.add((KVCommand.Put<AetherKey, AetherValue>) put);
                }
            }
        }
        return result;
    }

    private static final class Fixture {
        final BootstrapModule module;
        final RecordingClusterNode cluster;
        final Map<AetherKey, AetherValue> kv;

        Fixture(BootstrapModule module, RecordingClusterNode cluster, Map<AetherKey, AetherValue> kv) {
            this.module = module;
            this.cluster = cluster;
            this.kv = kv;
        }
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        final List<List<KVCommand<AetherKey>>> batches = new ArrayList<>();

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {
            return emptyTopologyManager();
        }

        @Override public Promise<Unit> start() {return Promise.success(Unit.unit());}

        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            batches.add(List.copyOf(commands));
            return (Promise) Promise.success(List.of());
        }
    }

    /// Minimal SWIM/NTT-free topology stub. Membership-v2 finale: the bootstrap cold-start
    /// fallback seeds initial core members from `topologyManager().coreNodes()`; these tests
    /// exercise DHT/config/callback paths only and need an empty topology (no core members).
    private static TopologyManager emptyTopologyManager() {
        return new TopologyManager() {
            @Override public NodeInfo self() {return NodeInfo.nodeInfo(SELF, new NodeAddress("localhost", 9000));}

            @Override public Option<NodeInfo> get(NodeId id) {return Option.none();}

            @Override public int clusterSize() {return 0;}

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.none();}

            @Override public Promise<Unit> start() {return Promise.unitPromise();}

            @Override public Promise<Unit> stop() {return Promise.unitPromise();}

            @Override public TimeSpan pingInterval() {return timeSpan(5).seconds();}

            @Override public TimeSpan helloTimeout() {return timeSpan(5).seconds();}

            @Override public Option<NodeState> getState(NodeId id) {return Option.none();}

            @Override public List<NodeId> topology() {return List.of();}
        };
    }
}
