// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.cluster.fsm2.Admission;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.cluster.fsm2.Seams;
import org.pragmatica.aether.deployment.node.machine.PeerRow;
import org.pragmatica.aether.deployment.node.machine.RebuildSnapshot;
import org.pragmatica.aether.deployment.node.machine.RoutingEntry;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Builds the snapshot an FSM starts from, **at a KV revision**, on the consumer thread (phase-3 design 4.4, review C1, H6, M2, L2).
///
/// **Consistent cut (cores).** The store's content and its `committedRevision()` are read inside one `synchronized (store)` block, and
/// every `KVStore` accessor synchronizes on the same monitor, so the pair is one atomic cut: the snapshot holds exactly the batches up to
/// and including revision `r`. The interpreter then discards every KV-sourced input stamped `revision <= r`, still queued or arriving
/// later (see `FsmRuntime`), which closes the duplicate-and-regress window of a snapshot taken on the apply thread: a put-then-remove of a
/// key absent from the snapshot is not re-declared by the replayed put (review M2). The apply thread only stamps and enqueues; no
/// snapshot is ever built there (#2143's `SLOW-APPLY` pattern).
///
/// **What is read.** The node snapshot is the node's own desired and observed rows, its peers' observed rows (each with the boot token the
/// registry knows for that peer *now*), the committed targets and routings, and the slices the local store holds. The cluster snapshot is
/// every `AetherKey`/`AetherValue` entry: the FSM's `Rebuild` indexes the key families it reads and ignores the rest, so a key family
/// added to `F2/Indexer` is covered without a second list here. The seams and the admissions come from the callers' suppliers, read on the
/// same (consumer) thread right after the cut.
///
/// A worker's store has no revision (its `committedRevision` is 0 for its whole life), so this is the **core** snapshot; the worker rule
/// (an install sequence, design 4.4 D-1) is a separate piece that this tracer does not wire.
public final class KvSnapshotter {
    /// A snapshot and the revision it was cut at.
    public record Cut<S>(S snapshot, long revision) {}

    private record Raw(List<Map.Entry<AetherKey, AetherValue>> entries, Option<LeaderValue> leader, long revision) {}

    private final KVStore<AetherKey, AetherValue> store;

    private KvSnapshotter(KVStore<AetherKey, AetherValue> store) {
        this.store = store;
    }

    public static KvSnapshotter kvSnapshotter(KVStore<AetherKey, AetherValue> store) {
        return new KvSnapshotter(store);
    }

    /// The node FSM's `RebuildSnapshot` for `self`, with peers' rows stamped by `tokenOf` and `loaded` the slices the local store holds.
    public Cut<RebuildSnapshot> nodeSnapshot(NodeId self, ToLongFunction<NodeId> tokenOf, Set<Artifact> loaded) {
        var raw = cut();
        var snapshot = new Rows(self, tokenOf);

        raw.entries().forEach(snapshot::add);

        return new Cut<>(snapshot.build(loaded), raw.revision());
    }

    /// The cluster FSM's `Snapshot`: every entry sorted by key (a deterministic `Rebuild`), the seams and admissions the callers read now,
    /// and the committed leader.
    public Cut<ClusterInput.Snapshot> clusterSnapshot(Supplier<Seams> seams,
                                                      Supplier<Map<NodeId, Admission>> admissions) {
        var raw = cut();
        var entries = raw.entries()
                         .stream()
                         .sorted(Comparator.comparing(entry -> entry.getKey()
                                                                    .toString()))
                         .map(entry -> new ClusterInput.KvPut(entry.getKey(),
                                                              entry.getValue()))
                         .toList();

        return new Cut<>(ClusterInput.Snapshot.snapshot(entries, seams.get(), admissions.get(), raw.leader()),
                         raw.revision());
    }

    private Raw cut() {
        var entries = new ArrayList<Map.Entry<AetherKey, AetherValue>>();

        synchronized (store) {
            var revision = store.committedRevision();

            store.forEach(AetherKey.class,
                          AetherValue.class,
                          (key, value) -> entries.add(Map.entry(key, value)));

            return new Raw(entries, store.getTyped(LeaderKey.INSTANCE, LeaderValue.class), revision);
        }
    }

    /// Sorts the entries of a cut into the node snapshot's maps.
    private static final class Rows {
        private final NodeId self;
        private final ToLongFunction<NodeId> tokenOf;
        private final Map<Artifact, SliceDesiredValue> desired = new HashMap<>();
        private final Map<Artifact, SliceObservedValue> observed = new HashMap<>();
        private final Map<Artifact, Map<NodeId, PeerRow>> replicas = new HashMap<>();
        private final Map<ArtifactBase, Version> targets = new HashMap<>();
        private final Map<ArtifactBase, RoutingEntry> routings = new HashMap<>();

        Rows(NodeId self, ToLongFunction<NodeId> tokenOf) {
            this.self = self;
            this.tokenOf = tokenOf;
        }

        Rows add(Map.Entry<AetherKey, AetherValue> entry) {
            switch (entry.getKey()) {
                case SliceDesiredKey key when key.nodeId().equals(self) && entry.getValue() instanceof SliceDesiredValue value -> desired.put(key.artifact(),
                                                                                                                                              value);
                case SliceObservedKey key when entry.getValue() instanceof SliceObservedValue value -> addObserved(key,
                                                                                                                   value);
                case SliceTargetKey key when entry.getValue() instanceof SliceTargetValue value -> targets.put(key.artifactBase(),
                                                                                                               value.currentVersion());
                case VersionRoutingKey key when entry.getValue() instanceof VersionRoutingValue value -> routings.put(key.artifactBase(),
                                                                                                                      RoutingEntry.routingEntry(value.oldVersion(),
                                                                                                                                                value.newVersion()));
                default -> {}
            }

            return this;
        }

        private void addObserved(SliceObservedKey key, SliceObservedValue value) {
            if (key.nodeId().equals(self)) {
                observed.put(key.artifact(), value);
            } else {
                replicas.computeIfAbsent(key.artifact(),
                                         _ -> new HashMap<>())
                        .put(key.nodeId(),
                             PeerRow.peerRow(value,
                                             tokenOf.applyAsLong(key.nodeId())));
            }
        }

        RebuildSnapshot build(Set<Artifact> loaded) {
            return new RebuildSnapshot(desired, observed, replicas, targets, routings, loaded);
        }
    }
}
