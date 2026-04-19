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
import org.pragmatica.cluster.metrics.MetricsMessage.MetricsPing;
import org.pragmatica.cluster.metrics.MetricsMessage.SnapshotPayload;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageReceiver;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Per-node cache of the latest `ClusterGenerationSnapshot` received from the leader.
///
/// Every node (leader and followers alike) receives Tier 1 `MetricsPing`s that carry
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
/// Thread-safe — relies on atomics for all mutable state.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §7.2 (Tier 1 distribution).
public interface NodeSnapshotCache extends GenerationSnapshotSource {
    Option<ClusterGenerationSnapshot> current();
    @Override long observedRabiaTerm();
    Epoch observedEpoch();

    @Override default Option<MembershipView> currentMembershipView() {
        return current().map(NodeSnapshotCache::toMembershipView);
    }

    @MessageReceiver@Contract void onMetricsPing(MetricsPing ping);

    private static MembershipView toMembershipView(ClusterGenerationSnapshot snapshot) {
        var coreMembers = snapshot.coreMembers();
        return new SnapshotMembershipView(coreMembers, snapshot.desiredCoreSize());
    }

    static NodeSnapshotCache nodeSnapshotCache(NodeId self) {
        return nodeSnapshotCache(self, _ -> Option.none());
    }

    static NodeSnapshotCache nodeSnapshotCache(NodeId self,
                                               Function<byte[], Option<ClusterGenerationSnapshot>> snapshotDecoder) {
        return new NodeSnapshotCacheImpl(self, snapshotDecoder);
    }
}

final class NodeSnapshotCacheImpl implements NodeSnapshotCache {
    private static final Logger log = LoggerFactory.getLogger(NodeSnapshotCacheImpl.class);

    private final NodeId self;
    private final Function<byte[], Option<ClusterGenerationSnapshot>> snapshotDecoder;

    private final AtomicLong observedRabiaTerm = new AtomicLong();

    private final AtomicReference<Epoch> observedEpoch = new AtomicReference<>(Epoch.ZERO);

    private final AtomicReference<Option<ClusterGenerationSnapshot>> currentSnapshot = new AtomicReference<>(Option.none());

    NodeSnapshotCacheImpl(NodeId self, Function<byte[], Option<ClusterGenerationSnapshot>> snapshotDecoder) {
        this.self = self;
        this.snapshotDecoder = snapshotDecoder;
    }

    @Override public Option<ClusterGenerationSnapshot> current() {
        return currentSnapshot.get();
    }

    @Override public long observedRabiaTerm() {
        return observedRabiaTerm.get();
    }

    @Override public Epoch observedEpoch() {
        return observedEpoch.get();
    }

    @Override@Contract public void onMetricsPing(MetricsPing ping) {
        var currentTerm = observedRabiaTerm.get();
        if (ping.rabiaTerm() <currentTerm) {
            log.trace("Node {} rejecting stale-term ping from {}: pingTerm={}, observedTerm={}",
                      self,
                      ping.sender(),
                      ping.rabiaTerm(),
                      currentTerm);
            return;
        }
        var incomingEpoch = Epoch.epoch(ping.epochTerm(), ping.epochCounter());
        if (ping.rabiaTerm() > currentTerm) {
            acceptNewTerm(ping, incomingEpoch);
            return;
        }
        acceptSameTerm(ping, incomingEpoch);
    }

    private void acceptNewTerm(MetricsPing ping, Epoch incomingEpoch) {
        observedRabiaTerm.set(ping.rabiaTerm());
        observedEpoch.set(incomingEpoch);
        ping.snapshot().onPresent(this::decodeAndStore);
        log.info("Node {} leader term changed, accepting snapshot at epoch {}", self, incomingEpoch);
    }

    private void acceptSameTerm(MetricsPing ping, Epoch incomingEpoch) {
        if (!incomingEpoch.isStrictlyAfter(observedEpoch.get())) {return;}
        observedEpoch.set(incomingEpoch);
        ping.snapshot().onPresent(this::decodeAndStore);
        log.trace("Node {} advancing observed epoch to {}", self, incomingEpoch);
    }

    private void decodeAndStore(SnapshotPayload payload) {
        snapshotDecoder.apply(payload.bytes()).onPresent(this::storeSnapshot);
    }

    private void storeSnapshot(ClusterGenerationSnapshot snapshot) {
        currentSnapshot.set(Option.some(snapshot));
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
}
