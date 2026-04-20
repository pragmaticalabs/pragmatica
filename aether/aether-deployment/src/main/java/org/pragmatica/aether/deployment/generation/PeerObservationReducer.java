// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/// Leader-side reducer that folds per-peer health observations from multiple
/// followers into a single resolved `HealthHint`.
///
/// Majority rule: a peer is resolved to `FAULTY` only when strictly more than
/// half of the cluster's observers report it FAULTY — specifically when the
/// count reaches `(totalObservers / 2) + 1`. Otherwise, a single `SUSPECTED`
/// report softens the peer to `SUSPECTED`; absent any SUSPECTED/FAULTY reports
/// the peer resolves to `HEALTHY`.
///
/// Epoch handling: each `(observer, peer)` tuple keeps only the most recent
/// observation by `observedAt`; older reports from the same observer are
/// overwritten. `prune(before)` drops entries whose `observedAt` is strictly
/// before the supplied epoch; called at reconcile time to bound memory use.
///
/// See `aether/docs/specs/clustersync-refactor-spec.md` commit 1.
public interface PeerObservationReducer {
    @Contract void recordHint(NodeId observer, NodeId peer, HealthHint hint, Epoch observedAt);
    HealthHint resolvedHint(NodeId peer, int totalObservers);
    @Contract void prune(Epoch before);

    static PeerObservationReducer peerObservationReducer() {
        return new PeerObservationReducerRecord(new ConcurrentHashMap<>());
    }
}

record PeerObservationReducerRecord(Map<NodeId, Map<NodeId, HintEntry>> observations) implements PeerObservationReducer {

    @Contract @Override public void recordHint(NodeId observer, NodeId peer, HealthHint hint, Epoch observedAt) {
        var perPeer = observations.computeIfAbsent(peer, _ -> new ConcurrentHashMap<>());
        perPeer.merge(observer, new HintEntry(hint, observedAt), PeerObservationReducerRecord::mostRecent);
    }

    @Override public HealthHint resolvedHint(NodeId peer, int totalObservers) {
        var perPeer = observations.get(peer);
        if (perPeer == null || perPeer.isEmpty()) {return HealthHint.HEALTHY;}
        var faultyCount = countByHint(perPeer, HealthHint.FAULTY);
        if (faultyCount >= faultyThreshold(totalObservers)) {return HealthHint.FAULTY;}
        var suspectedCount = countByHint(perPeer, HealthHint.SUSPECTED);
        if (suspectedCount > 0 || faultyCount > 0) {return HealthHint.SUSPECTED;}
        return HealthHint.HEALTHY;
    }

    @Contract @Override public void prune(Epoch before) {
        observations.values().forEach(perPeer -> pruneOlderThan(perPeer, before));
        observations.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    private static HintEntry mostRecent(HintEntry existing, HintEntry incoming) {
        return incoming.observedAt().isStrictlyAfter(existing.observedAt())
              ? incoming
              : existing;
    }

    private static int faultyThreshold(int totalObservers) {
        return (totalObservers / 2) + 1;
    }

    private static long countByHint(Map<NodeId, HintEntry> perPeer, HealthHint target) {
        return perPeer.values().stream().filter(entry -> entry.hint() == target).count();
    }

    private static void pruneOlderThan(Map<NodeId, HintEntry> perPeer, Epoch before) {
        perPeer.entrySet().removeIf(e -> before.isStrictlyAfter(e.getValue().observedAt()));
    }

    record HintEntry(HealthHint hint, Epoch observedAt) {}
}
