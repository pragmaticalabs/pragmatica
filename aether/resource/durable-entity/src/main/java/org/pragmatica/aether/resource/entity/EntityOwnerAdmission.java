// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.aether.dht.CommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource.CommittedOwner;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.fence.OwnershipDomain.StreamPartition;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Owner ADMISSION for the durable-entity write path (#345 I1) — "may this node write this key at all?",
/// asked before every [DurableEntity#create], [DurableEntity#update] and [DurableEntity#delete].
///
/// ## Why the epoch fence is not enough
/// The per-`(keyspace, partition)` epoch fence
/// ([org.pragmatica.aether.dht.PartitionOwnerEpochGate]) answers a different question: *is this writer's
/// view of ownership current?* It rejects a DEPOSED owner, whose stamp is strictly older than the arc's
/// advanced high-water. It cannot reject a live NON-owner, because every node reads the SAME committed
/// record and therefore stamps the SAME current epoch — so all of them commit. Each node also owns its
/// own `StorageEngine`, so there is no `PARTITION_NOT_LOCAL`-style admission the way streams get one from
/// `StreamPartitionManager`. The measured consequence was five nodes each accepting a create for one key,
/// each believing it held the only copy. Staleness rejection and admission control are orthogonal; the
/// entity needs both, and this supplies the second.
///
/// ## The two refusals, and why they are different causes
///   - a REMOTE committed owner → [EntityError.NotCurrentOwner]: stable, the caller re-resolves
///     and retries THERE;
///   - NO committed owner → [EntityError.OwnershipNotYetCommitted]: transient, the caller retries
///     HERE once the leader-only ownership reconcile has minted a record for the arc.
///
/// The absent-record case refuses rather than admits, deliberately. Ownership records are minted
/// asynchronously, so a freshly provisioned keyspace has a window with no owner on any arc — and at this
/// check that window is indistinguishable from an arc that will never have an owner. Admitting on absence
/// would admit both, which is precisely the unfenced behaviour this class exists to end, and it would do
/// so silently. Refusing costs early writes until the reconcile converges, and says so in the cause.
///
/// The read counterpart is [LinearizableEntityServe], which asks the same ownership question of the SAME
/// [CommittedPartitionOwnerSource] over the SAME [EntityPartitionArc] — so a key's reads and writes can
/// never disagree about who owns it. Reads at [ReadConsistency#BOUNDED_STALE] are deliberately NOT
/// admitted: a bounded-stale read is a promise about THIS node's committed prefix, which a non-owner can
/// answer honestly.
final class EntityOwnerAdmission {
    private final NodeId selfNodeId;
    private final EntityPartitionArc arc;
    private final CommittedPartitionOwnerSource committedOwnerSource;

    private EntityOwnerAdmission(NodeId selfNodeId,
                                 EntityPartitionArc arc,
                                 CommittedPartitionOwnerSource committedOwnerSource) {
        this.selfNodeId = selfNodeId;
        this.arc = arc;
        this.committedOwnerSource = committedOwnerSource;
    }

    static EntityOwnerAdmission entityOwnerAdmission(NodeId selfNodeId,
                                                     EntityPartitionArc arc,
                                                     CommittedPartitionOwnerSource committedOwnerSource) {
        return new EntityOwnerAdmission(selfNodeId, arc, committedOwnerSource);
    }

    /// Admit a write to `key` iff this node is the committed owner of the key's arc.
    Result<Unit> admit(Object key) {
        var domain = arc.arcOf(String.valueOf(key));

        return committedOwnerSource.committedOwner(domain.stream(),
                                                   domain.partition())
                                   .fold(() -> ownershipNotYetCommitted(key, domain),
                                         committed -> admitIfSelf(committed, key));
    }

    /// The committed owner for `key` when it is a REMOTE node, or empty when this node owns the arc or
    /// no ownership is committed yet.
    ///
    /// Deliberately a POSITIVE reading rather than an interpretation of [EntityError.NotCurrentOwner]:
    /// a forward must be aimed at an owner the code actually observed, and "no committed owner yet" is
    /// NOT a forwarding target — it is the pre-ownership window, where the caller retries here rather
    /// than going elsewhere. Both absences collapse to `empty`, so an unwired or undecided arc keeps
    /// the existing admission behaviour instead of inventing a destination.
    Option<NodeId> remoteOwner(Object key) {
        var domain = arc.arcOf(String.valueOf(key));

        return committedOwnerSource.committedOwner(domain.stream(),
                                                   domain.partition())
                                   .map(CommittedOwner::owner)
                                   .filter(owner -> !owner.equals(selfNodeId));
    }

    /// Whether this node is the committed owner of `partition` — the SAME question [#admit] answers, asked
    /// without a key.
    ///
    /// The timer tick (#345 I4) needs exactly this shape. It iterates PARTITIONS, not keys, and the only
    /// keys it could ask about are the ones inside a fold it has not built yet — so a key-based check
    /// would force every replica to rebuild and catch-up-poll each partition's fold on every tick just to
    /// discover it must not fire. Asking the arc directly makes a replica's tick cost one map lookup per
    /// partition and nothing else.
    ///
    /// An arc with NO committed owner reads as false, matching [#admit]'s refusal in the pre-ownership
    /// window: nobody may fire a timer on an arc nobody owns.
    boolean isPartitionOwner(int partition) {
        return committedOwnerSource.committedOwner(arc.arcName(),
                                                   partition)
                                   .map(CommittedOwner::owner)
                                   .filter(selfNodeId::equals)
                                   .isPresent();
    }

    private Result<Unit> admitIfSelf(CommittedOwner committed, Object key) {
        return committed.owner()
                        .equals(selfNodeId)
               ? Result.unitResult()
               : notCurrentOwner(key, committed.owner());
    }

    private static Result<Unit> notCurrentOwner(Object key, NodeId owner) {
        return new EntityError.NotCurrentOwner(String.valueOf(key), owner.id()).result();
    }

    private static Result<Unit> ownershipNotYetCommitted(Object key, StreamPartition domain) {
        return new EntityError.OwnershipNotYetCommitted(String.valueOf(key), domain.stream(), domain.partition()).result();
    }
}
