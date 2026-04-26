// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSource;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.pragmatica.consensus.NodeId.nodeId;
import static org.assertj.core.api.Assertions.assertThat;


/// Theme K #4 — verifies that the leader-only periodic GC removes
/// `NodeLifecycleValue(DECOMMISSIONED)` atoms whose `updatedAt` is older than the
/// configured retention, while preserving recent atoms and atoms in non-DECOMMISSIONED
/// states.
class DecommissionedAtomGcTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId OLD_DECOMMISSIONED = nodeId("node-old").unwrap();
    private static final NodeId RECENT_DECOMMISSIONED = nodeId("node-recent").unwrap();
    private static final NodeId LIVE_ON_DUTY = nodeId("node-live").unwrap();
    private static final NodeId DRAINING = nodeId("node-draining").unwrap();

    private static final TimeSpan RETENTION = TimeSpan.timeSpan(1).hours();

    private final RecordingClusterNode cluster = new RecordingClusterNode();
    private final Map<AetherKey, AetherValue> snapshot = new HashMap<>();
    private final AtomicBoolean isLeader = new AtomicBoolean(true);
    private final AtomicLong nowMs = new AtomicLong(System.currentTimeMillis());

    /// Old DECOMMISSIONED atoms past retention are removed; recent ones preserved.
    @Test
    void tick_removesExpiredDecommissionedAtoms_preservesRecent() {
        seedDecommissioned(OLD_DECOMMISSIONED, nowMs.get() - RETENTION.millis() - 60_000L);
        seedDecommissioned(RECENT_DECOMMISSIONED, nowMs.get() - 30_000L);

        var gc = newGc();
        var result = gc.tick().await();

        assertThat(result.unwrap()).isEqualTo(1L);
        assertThat(cluster.batches).hasSize(1);
        var batch = cluster.batches.get(0);
        assertThat(batch).hasSize(1);
        assertThat(batch.get(0)).isInstanceOf(KVCommand.Remove.class);
        var removed = (KVCommand.Remove<AetherKey>) batch.get(0);
        assertThat(removed.key()).isEqualTo(NodeLifecycleKey.nodeLifecycleKey(OLD_DECOMMISSIONED));
    }

    /// Atoms in non-DECOMMISSIONED states (ON_DUTY, DRAINING, ...) are never touched
    /// regardless of age — only DECOMMISSIONED is a tombstone.
    @Test
    void tick_ignoresNonDecommissionedAtomsRegardlessOfAge() {
        var ancientPast = nowMs.get() - RETENTION.millis() * 10L;
        seedAtom(LIVE_ON_DUTY, NodeLifecycleState.ON_DUTY, ancientPast);
        seedAtom(DRAINING, NodeLifecycleState.DRAINING, ancientPast);

        var gc = newGc();
        var result = gc.tick().await();

        assertThat(result.unwrap()).isEqualTo(0L);
        assertThat(cluster.batches).isEmpty();
    }

    /// Non-leader: GC must be a no-op even when expired atoms are present. This protects
    /// against stale-leader writes during a leader transition.
    @Test
    void tick_isNoOpOnNonLeader() {
        seedDecommissioned(OLD_DECOMMISSIONED, nowMs.get() - RETENTION.millis() - 60_000L);
        isLeader.set(false);

        var gc = newGc();
        var result = gc.tick().await();

        assertThat(result.unwrap()).isEqualTo(0L);
        assertThat(cluster.batches).isEmpty();
    }

    private void seedDecommissioned(NodeId nodeId, long updatedAtMs) {
        seedAtom(nodeId, NodeLifecycleState.DECOMMISSIONED, updatedAtMs);
    }

    private void seedAtom(NodeId nodeId, NodeLifecycleState state, long updatedAtMs) {
        var value = NodeLifecycleValue.nodeLifecycleValue(state,
                                                           updatedAtMs,
                                                           nodeId.id(),
                                                           5000,
                                                           org.pragmatica.aether.slice.generation.Epoch.ZERO,
                                                           org.pragmatica.hlc.HlcTimestamp.ZERO,
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
