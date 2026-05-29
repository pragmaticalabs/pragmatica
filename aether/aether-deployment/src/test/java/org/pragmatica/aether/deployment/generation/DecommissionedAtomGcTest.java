// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.ClusterMode;
import org.pragmatica.aether.slice.generation.ClusterQuiescence;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationReason;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GenerationSnapshotKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GenerationSnapshotValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSource;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.pragmatica.consensus.NodeId.nodeId;
import static org.assertj.core.api.Assertions.assertThat;


/// RC1 membership-v2 step 1 — `DecommissionedAtomGc` is re-sourced off the FSM-written
/// `NodeLifecycleValue.state == STOPPED` + retention cutoff. A node is now "gone" when it is
/// absent from the NTT-derived generation snapshot's `coreMembers` (read from the same KV map
/// under `GenerationSnapshotKey.SINGLETON`); any leftover `NodeLifecycleKey` atom for such a
/// node is GC'd. When no snapshot has been published the GC is a no-op (membership unknown).
class DecommissionedAtomGcTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId GONE = nodeId("node-gone").unwrap();
    private static final NodeId ANOTHER_GONE = nodeId("node-gone-2").unwrap();
    private static final NodeId LIVE_MEMBER = nodeId("node-live").unwrap();

    private static final TimeSpan RETENTION = TimeSpan.timeSpan(1).hours();

    private final RecordingClusterNode cluster = new RecordingClusterNode();
    private final Map<AetherKey, AetherValue> snapshot = new HashMap<>();
    private final AtomicBoolean isLeader = new AtomicBoolean(true);
    private final AtomicLong nowMs = new AtomicLong(System.currentTimeMillis());

    /// Lifecycle atoms for nodes absent from the published membership are removed; atoms for
    /// nodes still in `coreMembers` are preserved.
    @Test
    void tick_removesAtomsForNodesAbsentFromMembership_preservesMembers() {
        seedMembership(LIVE_MEMBER);
        seedAtom(LIVE_MEMBER, NodeLifecycleState.ON_DUTY);
        seedAtom(GONE, NodeLifecycleState.STOPPED);

        var gc = newGc();
        var result = gc.tick().await();

        assertThat(result.unwrap()).isEqualTo(1L);
        assertThat(cluster.batches).hasSize(1);
        var batch = cluster.batches.get(0);
        assertThat(batch).hasSize(1);
        assertThat(batch.get(0)).isInstanceOf(KVCommand.Remove.class);
        var removed = (KVCommand.Remove<AetherKey>) batch.get(0);
        assertThat(removed.key()).isEqualTo(NodeLifecycleKey.nodeLifecycleKey(GONE));
    }

    /// State and age are irrelevant — membership presence is the only criterion. An ON_DUTY
    /// atom for a node no longer in membership is GC'd; a STOPPED atom for a current member is
    /// kept.
    @Test
    void tick_ignoresStateAndAge_usesMembershipPresenceOnly() {
        seedMembership(LIVE_MEMBER);
        seedAtom(LIVE_MEMBER, NodeLifecycleState.STOPPED);
        seedAtom(GONE, NodeLifecycleState.ON_DUTY);
        seedAtom(ANOTHER_GONE, NodeLifecycleState.DRAINING);

        var gc = newGc();
        var result = gc.tick().await();

        assertThat(result.unwrap()).isEqualTo(2L);
        var batch = cluster.batches.get(0);
        var removedKeys = batch.stream()
                               .map(c -> ((KVCommand.Remove<AetherKey>) c).key())
                               .toList();
        assertThat(removedKeys).containsExactlyInAnyOrder(NodeLifecycleKey.nodeLifecycleKey(GONE),
                                                          NodeLifecycleKey.nodeLifecycleKey(ANOTHER_GONE));
    }

    /// No published snapshot ⇒ membership unknown ⇒ GC is a no-op (must never run against an
    /// unknown membership, otherwise it would wipe atoms for live nodes during cold boot).
    @Test
    void tick_isNoOpWhenNoSnapshotPublished() {
        seedAtom(GONE, NodeLifecycleState.STOPPED);

        var gc = newGc();
        var result = gc.tick().await();

        assertThat(result.unwrap()).isEqualTo(0L);
        assertThat(cluster.batches).isEmpty();
    }

    /// Non-leader: GC must be a no-op even when removable atoms are present. This protects
    /// against stale-leader writes during a leader transition.
    @Test
    void tick_isNoOpOnNonLeader() {
        seedMembership(LIVE_MEMBER);
        seedAtom(GONE, NodeLifecycleState.STOPPED);
        isLeader.set(false);

        var gc = newGc();
        var result = gc.tick().await();

        assertThat(result.unwrap()).isEqualTo(0L);
        assertThat(cluster.batches).isEmpty();
    }

    private void seedMembership(NodeId... members) {
        var coreMembers = new LinkedHashMap<NodeId, CoreMember>();
        for (var member : members) {
            coreMembers.put(member,
                            CoreMember.coreMember(member,
                                                  member.id(),
                                                  5000,
                                                  NodeLifecycleState.ON_DUTY,
                                                  HealthHint.HEALTHY,
                                                  Epoch.ZERO,
                                                  Epoch.ZERO));
        }
        var generation = ClusterGenerationSnapshot.clusterGenerationSnapshot(Epoch.ZERO,
                                                                             HlcTimestamp.ZERO,
                                                                             GenerationReason.LEADER_ELECTED,
                                                                             members.length,
                                                                             coreMembers,
                                                                             Map.of(),
                                                                             Map.of(),
                                                                             ClusterMode.CORE_ONLY,
                                                                             ClusterQuiescence.QUIESCED,
                                                                             "");
        snapshot.put(GenerationSnapshotKey.SINGLETON, GenerationSnapshotValue.generationSnapshotValue(generation));
    }

    private void seedAtom(NodeId nodeId, NodeLifecycleState state) {
        var value = NodeLifecycleValue.nodeLifecycleValue(state,
                                                          nowMs.get(),
                                                          nodeId.id(),
                                                          5000,
                                                          Epoch.ZERO,
                                                          HlcTimestamp.ZERO,
                                                          ProvisioningSource.CTM);
        snapshot.put(NodeLifecycleKey.nodeLifecycleKey(nodeId), value);
    }

    private DecommissionedAtomGc newGc() {
        var autoHeal = AutoHealConfig.autoHealConfig(TimeSpan.timeSpan(60).seconds(),
                                                      TimeSpan.timeSpan(15).seconds(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT,
                                                      AutoHealConfig.DEFAULT_PROVISION_STABILITY_WINDOW,
                                                      RETENTION)
                                            .unwrap();
        return DecommissionedAtomGc.decommissionedAtomGc(cluster,
                                                          () -> snapshot,
                                                          isLeader::get,
                                                          autoHeal,
                                                          nowMs::get);
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        final List<List<KVCommand<AetherKey>>> batches = new ArrayList<>();

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {
            throw new UnsupportedOperationException("not used");
        }

        @Override public Promise<Unit> start() {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            batches.add(List.copyOf(commands));
            return (Promise) Promise.success(List.of());
        }
    }
}
