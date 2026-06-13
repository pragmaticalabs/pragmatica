// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


/// Pure, deterministic stream-replica placement using HRW (highest-random-weight /
/// rendezvous) hashing.
///
/// Every node computes the IDENTICAL owner + replica set locally from the same membership
/// input, with no coordination. For a given `(streamName, partition)` each member is scored
/// by a stable 64-bit hash of `streamName|partition|nodeId`; members are ranked by score
/// descending (ties broken by node-id string for total determinism). The highest-scoring
/// member is the owner; the top `rf` members form the replica set, owner-first.
///
/// HRW is chosen over a fixed hash ring because it yields minimal churn: when a node leaves,
/// only the `(stream, partition)` placements that actually included that node change; all
/// other placements are byte-identical because relative score ordering among the surviving
/// members is unaffected.
///
/// ## Stable hash
/// No shared stable 64-bit hash utility exists that is reachable from this module
/// ([org.pragmatica.dht.HomeReplicaResolverImpl] has only a private 32-bit FNV-1a helper),
/// so this class implements FNV-1a 64-bit over UTF-8 bytes inline. FNV-1a is fully
/// deterministic across JVMs and nodes with no external library, and matches the existing
/// dht precedent. [Object#hashCode()] and identity hashes are deliberately NOT used — they
/// are not stable across JVMs.
public final class ReplicaPlacement {
    private static final long FNV_64_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_64_PRIME = 0x100000001b3L;

    private ReplicaPlacement() {}

    /// Classifies a stream for replication-factor purposes.
    public enum StreamClass {
        APP,
        SYSTEM
    }

    /// Owner plus the full ordered replica set for a single `(streamName, partition)`.
    /// `replicas` is ordered owner-first and always contains `owner` at index 0.
    public record Placement(NodeId owner, List<NodeId> replicas) {
        public Placement {
            replicas = List.copyOf(replicas);
        }
    }

    /// Place replicas for one `(streamName, partition)` across `members`.
    ///
    /// Returns [Option#none()] when `members` is empty. `requestedRf` is clamped to
    /// `[1, members.size()]` internally.
    public static Option<Placement> place(String streamName, int partition, Iterable<NodeId> members, int requestedRf) {
        var ranked = rank(streamName, partition, members);

        if (ranked.isEmpty()) {
            return Option.none();
        }

        var rf = clamp(requestedRf, 1, ranked.size());
        var replicas = List.copyOf(ranked.subList(0, rf));

        return Option.option(new Placement(ranked.getFirst(), replicas));
    }

    /// Rank all members by HRW score descending, tie-broken by node-id string ascending.
    /// Package-visible to allow direct churn/ordering assertions in tests.
    static List<NodeId> rank(String streamName, int partition, Iterable<NodeId> members) {
        var ranked = new ArrayList<NodeId>();

        members.forEach(ranked::add);
        ranked.sort(Comparator.comparingLong((NodeId node) -> score(streamName, partition, node)).reversed().thenComparing(NodeId::id));

        return ranked;
    }

    /// HRW score for a single member: stable 64-bit hash of `stream|partition|nodeId`.
    static long score(String streamName, int partition, NodeId node) {
        return stableHash64(streamName + "|" + partition + "|" + node.id());
    }

    /// True when `self` is the owner of `(streamName, partition)` under the given membership.
    public static boolean isOwner(String streamName,
                                  int partition,
                                  Iterable<NodeId> members,
                                  int requestedRf,
                                  NodeId self) {
        return place(streamName, partition, members, requestedRf).map(placement -> placement.owner()
                                                                                            .equals(self))
                    .or(false);
    }

    /// Effective replication factor for an APP stream:
    /// `clamp(requested, 1, clusterSize)`. Returns 0 only when the cluster is empty.
    public static int replicationFactor(int requested, int clusterSize) {
        if (clusterSize <= 0) {
            return 0;
        }

        return clamp(requested, 1, clusterSize);
    }

    /// Effective replication factor for a SYSTEM stream:
    /// `min(max(3, N - 2), N)` where `N = clusterSize`. Returns 0 for an empty cluster.
    ///
    /// Examples: N=1→1, N=3→3, N=5→3, N=7→5, N=10→8.
    public static int systemReplicationFactor(int clusterSize) {
        if (clusterSize <= 0) {
            return 0;
        }

        return Math.min(Math.max(3, clusterSize - 2), clusterSize);
    }

    /// Effective replication factor dispatched on [StreamClass]. For [StreamClass#APP] the
    /// `requested` value is honoured (clamped); for [StreamClass#SYSTEM] it is ignored in
    /// favour of the system formula.
    public static int replicationFactor(StreamClass streamClass, int requested, int clusterSize) {
        return switch (streamClass) {
            case APP -> replicationFactor(requested, clusterSize);
            case SYSTEM -> systemReplicationFactor(clusterSize);
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /// FNV-1a 64-bit over the UTF-8 bytes of `value`. Deterministic across JVMs/nodes. Public so
    /// other partition-placement call sites (e.g. partition routing in `PartitionedStreamAccess`) can
    /// reuse the SAME stable hash instead of identity-unstable `Object#hashCode()` (m2).
    public static long stableHash64(String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        var hash = FNV_64_OFFSET_BASIS;

        for (var b : bytes) {
            hash ^= (b & 0xff);
            hash *= FNV_64_PRIME;
        }

        return hash;
    }
}
