// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.generation;

import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSource;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.SnapshotPayload;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageReceiver;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Per-node cache of the latest `ClusterGenerationSnapshot` received from the leader.
///
/// Every node (leader and followers alike) receives Tier 1 `ClusterSyncPing`s that carry
/// the leader's current Rabia term, epoch, and — on epoch advance — an opaque
/// `SnapshotPayload` containing a serialized `ClusterGenerationSnapshot`. This cache
/// fences incoming pings against `(observedRabiaTerm, observedEpoch)` and retains the
/// last decoded snapshot so downstream consumers (to be wired in later commits) can
/// observe a coherent cluster view.
///
/// Fencing rules (per spec §4):
///   - Reject pings whose `rabiaTerm < observedRabiaTerm` (stale leader).
///   - Accept pings whose `rabiaTerm > observedRabiaTerm`: advance both the term and
///     epoch, storing the snapshot when present.
///   - Accept pings at the current term only when `epoch` is strictly newer than the
///     last observed epoch; silently ignore reordered/duplicate pings at the same or
///     older epoch.
///   - A heartbeat ping (no snapshot) advances the observed epoch but never clears a
///     previously cached snapshot.
///
/// Thread-safe — (rabiaTerm, epoch, snapshot) collapse into a single `AtomicReference<State>`
/// so readers always observe a coherent tuple.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §7.2 (Tier 1 distribution).
public interface NodeSnapshotCache extends GenerationSnapshotSource {
    Option<ClusterGenerationSnapshot> current();
    @Override long observedRabiaTerm();
    Epoch observedEpoch();

    @Override default Option<MembershipView> currentMembershipView() {
        return current().map(NodeSnapshotCache::toMembershipView);
    }

    @MessageReceiver@Contract void onClusterSyncPing(ClusterSyncPing ping);

    private static MembershipView toMembershipView(ClusterGenerationSnapshot snapshot) {
        var coreMembers = snapshot.coreMembers();
        return new SnapshotMembershipView(coreMembers, snapshot.desiredCoreSize());
    }

    static NodeSnapshotCache nodeSnapshotCache(NodeId self) {
        return nodeSnapshotCache(self, _ -> Option.none());
    }

    static NodeSnapshotCache nodeSnapshotCache(NodeId self,
                                               Function<byte[], Option<ClusterGenerationSnapshot>> snapshotDecoder) {
        return new NodeSnapshotCacheRecord(self, snapshotDecoder, new AtomicReference<>(State.INITIAL));
    }

    record State(long rabiaTerm, Epoch epoch, Option<ClusterGenerationSnapshot> snapshot) {
        static final State INITIAL = new State(0L, Epoch.ZERO, Option.none());
    }
}

record NodeSnapshotCacheRecord(NodeId self,
                               Function<byte[], Option<ClusterGenerationSnapshot>> snapshotDecoder,
                               AtomicReference<NodeSnapshotCache.State> stateRef) implements NodeSnapshotCache {
    private static final Logger log = LoggerFactory.getLogger(NodeSnapshotCacheRecord.class);

    @Override public Option<ClusterGenerationSnapshot> current() {
        return stateRef.get().snapshot();
    }

    @Override public long observedRabiaTerm() {
        return stateRef.get().rabiaTerm();
    }

    @Override public Epoch observedEpoch() {
        return stateRef.get().epoch();
    }

    @Override@Contract public void onClusterSyncPing(ClusterSyncPing ping) {
        var incomingEpoch = Epoch.epoch(ping.epochTerm(), ping.epochCounter());
        var updated = stateRef.updateAndGet(current -> applyPing(current, ping, incomingEpoch));
        logTransition(ping, incomingEpoch, updated);
    }

    private NodeSnapshotCache.State applyPing(NodeSnapshotCache.State current,
                                              ClusterSyncPing ping,
                                              Epoch incomingEpoch) {
        if (ping.rabiaTerm() <current.rabiaTerm()) {return current;}
        if (ping.rabiaTerm() > current.rabiaTerm()) {return acceptNewTerm(ping, incomingEpoch, current);}
        if (!incomingEpoch.isStrictlyAfter(current.epoch())) {return current;}
        return acceptSameTerm(ping, incomingEpoch, current);
    }

    private NodeSnapshotCache.State acceptNewTerm(ClusterSyncPing ping,
                                                  Epoch incomingEpoch,
                                                  NodeSnapshotCache.State current) {
        var nextSnapshot = ping.snapshot().flatMap(this::decodePayload);
        var retained = nextSnapshot.orElse(current.snapshot());
        return new NodeSnapshotCache.State(ping.rabiaTerm(), incomingEpoch, retained);
    }

    private NodeSnapshotCache.State acceptSameTerm(ClusterSyncPing ping,
                                                   Epoch incomingEpoch,
                                                   NodeSnapshotCache.State current) {
        var nextSnapshot = ping.snapshot().flatMap(this::decodePayload);
        var retained = nextSnapshot.orElse(current.snapshot());
        return new NodeSnapshotCache.State(current.rabiaTerm(), incomingEpoch, retained);
    }

    private Option<ClusterGenerationSnapshot> decodePayload(SnapshotPayload payload) {
        return snapshotDecoder.apply(payload.bytes());
    }

    @Contract private void logTransition(ClusterSyncPing ping, Epoch incomingEpoch, NodeSnapshotCache.State updated) {
        if (updated.rabiaTerm() != ping.rabiaTerm() && ping.rabiaTerm() <updated.rabiaTerm()) {
            log.trace("Node {} rejected stale-term ping from {}: pingTerm={}, observedTerm={}",
                      self,
                      ping.sender(),
                      ping.rabiaTerm(),
                      updated.rabiaTerm());
            return;
        }
        if (!incomingEpoch.equals(updated.epoch())) {return;}
        if (ping.rabiaTerm() > 0L && updated.epoch().equals(incomingEpoch)) {log.trace("Node {} observed epoch {} (term {})",
                                                                                       self,
                                                                                       incomingEpoch,
                                                                                       updated.rabiaTerm());}
    }
}

/// Narrow adapter that exposes a `ClusterGenerationSnapshot` as the consensus-layer
/// `MembershipView` contract (package-private on purpose — construction is the cache's
/// responsibility).
record SnapshotMembershipView(Map<NodeId, CoreMember> coreMembers, int desiredCoreSize) implements MembershipView {
    @Override public Set<NodeId> coreMemberIds() {
        return coreMembers.keySet();
    }

    @Override public Set<NodeId> onDutyMemberIds() {
        return coreMembers.entrySet().stream()
                                   .filter(entry -> entry.getValue().lifecycle() == NodeLifecycleState.ON_DUTY)
                                   .map(Map.Entry::getKey)
                                   .collect(Collectors.toUnmodifiableSet());
    }

    @Override public int healthyOnDutyCount() {
        return (int) coreMembers.values().stream()
                                       .filter(member -> member.lifecycle() == NodeLifecycleState.ON_DUTY)
                                       .filter(member -> member.healthHint() == HealthHint.HEALTHY)
                                       .count();
    }

    @Override public Set<NodeId> ctmProvisionedNodeIds() {
        return coreMembers.entrySet().stream()
                                   .filter(entry -> entry.getValue().provisioningSource() == ProvisioningSource.CTM)
                                   .map(Map.Entry::getKey)
                                   .collect(Collectors.toUnmodifiableSet());
    }
}
