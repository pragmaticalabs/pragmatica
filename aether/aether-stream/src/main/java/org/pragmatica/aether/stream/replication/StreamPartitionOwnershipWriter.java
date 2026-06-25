// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/// Leader-only, idempotent, deterministic writer of per-`(stream, partition)` ownership records
/// (#345 item 1d-i) — the stream-side mirror of the DHT ownership writer in `BootstrapModule`
/// (`decideCoreOwnership` / `rewriteIfOwnerStale` / `buildCorePartitionCommand`).
///
/// ## What it does
/// Stream-partition ownership was previously pure HRW recomputed on the fly with no persisted record
/// and no fencing token (the registry mutation in [ReplicaSetController#reconcilePartition]). This
/// writer gives a moved partition a consensus-committed owner record whose `ownerEpoch` advances on
/// every owner change, so the append fence (1d-ii) — and the [org.pragmatica.aether.slice.fence] CP
/// applier guard (1a), which fences ANY `EpochBearing` value — can reject a deposed owner.
///
/// The leader, on a reconcile tick, computes the HRW owner of `(stream, partition)` from committed
/// membership and compares it to the committed ownership record:
///   - **no record yet** → emit the initial `Put` (`ownershipTerm = 1`),
///   - **HRW owner equals the committed owner** → [Option#none] (idempotent no-op),
///   - **HRW owner differs from the committed owner** → emit a `Put` for the new owner with an
///     advanced `ownerEpoch` and `ownershipTerm + 1`.
///
/// ## Epoch-advance semantics (mirrors the DHT writer)
/// The `ownerEpoch` is sourced PURELY from the committed generation — `Epoch.epoch(rabiaTerm, 0)`,
/// the same `currentEpoch()` the DHT writer uses (`BootstrapModule.currentEpoch`). It is NOT a
/// per-partition free-running counter, so the decision and the epoch are a pure function of committed
/// state (committed ownership + HRW owner + committed generation term). Two replicas presented the
/// same committed state therefore reach the IDENTICAL decision and write the IDENTICAL value — the
/// CP-applier fence then deduplicates / orders them. `ownershipTerm` is the monotonic per-partition
/// takeover counter, bumped on each owner change exactly as the DHT writer bumps it.
///
/// ## Why leader-only / deterministic
/// Only the leader emits (the [#writeOwnershipChange] driver is gated by `isLeaderSupplier`),
/// mirroring the DHT writer being a leader-only module rather than a per-node reconcile. Replicas
/// converge by OBSERVING the committed `Put`, never by writing — so there is no per-node ownership
/// write race. The pure decision in [#decide] is what makes a follower that becomes leader continue
/// the exact same sequence without re-deriving epochs from local clocks.
///
/// ## Load shape under reshuffle
/// One `KVCommand.Put` per MOVED partition — a full ring reshuffle (a membership change that shifts
/// HRW ownership for many partitions) emits one consensus write per partition whose owner actually
/// changed (unchanged partitions are no-ops). This is intentionally NOT batched/optimized for 1d-i;
/// #265's reshuffle-ring work is where the moved-partition fan-out is bounded.
public interface StreamPartitionOwnershipWriter {
    /// Compute the ownership-change command for a single `(stream, partition)`, or [Option#none] when
    /// the HRW owner already matches the committed owner (idempotent). Pure: no side effects, no clock
    /// reads beyond `hlcClock.now()` for the advisory `transferredAt` stamp (which is not part of the
    /// fence). `committedEpoch` is the committed generation epoch the new `ownerEpoch` is sourced from.
    Option<KVCommand<AetherKey>> decide(String stream,
                                        int partition,
                                        Option<StreamPartitionOwnershipValue> committed,
                                        NodeId hrwOwner,
                                        Epoch committedEpoch);

    /// Leader-only driver: when this node is the leader, [#decide] the command for `(stream,
    /// partition)` against committed state and emit it. A non-leader returns [Option#none] without
    /// reading committed state. Returns the emitted command (if any) so the caller can apply it through
    /// the consensus `ClusterNode` and so tests can assert the decision without a live cluster.
    Option<KVCommand<AetherKey>> writeOwnershipChange(String stream, int partition);

    static StreamPartitionOwnershipWriter streamPartitionOwnershipWriter(BooleanSupplier isLeaderSupplier,
                                                                         Supplier<Long> rabiaTermSupplier,
                                                                         HlcClock hlcClock,
                                                                         CommittedOwnership committedOwnership,
                                                                         HrwOwner hrwOwner) {
        return new StreamPartitionOwnershipWriterRecord(isLeaderSupplier,
                                                        rabiaTermSupplier,
                                                        hlcClock,
                                                        committedOwnership,
                                                        hrwOwner);
    }

    /// Reads the committed ownership record for `(stream, partition)` from committed KV — the leader's
    /// source of truth for "the current owner". [Option#none] means no record committed yet.
    interface CommittedOwnership {
        Option<StreamPartitionOwnershipValue> ownershipOf(String stream, int partition);
    }

    /// Computes the HRW owner of `(stream, partition)` from committed membership — the deterministic
    /// desired owner. [Option#none] means no placement can be computed (empty member view).
    interface HrwOwner {
        Option<NodeId> ownerOf(String stream, int partition);
    }
}

record StreamPartitionOwnershipWriterRecord(BooleanSupplier isLeaderSupplier,
                                            Supplier<Long> rabiaTermSupplier,
                                            HlcClock hlcClock,
                                            StreamPartitionOwnershipWriter.CommittedOwnership committedOwnership,
                                            StreamPartitionOwnershipWriter.HrwOwner hrwOwner) implements StreamPartitionOwnershipWriter {
    @Override
    public Option<KVCommand<AetherKey>> decide(String stream,
                                               int partition,
                                               Option<StreamPartitionOwnershipValue> committed,
                                               NodeId owner,
                                               Epoch committedEpoch) {
        return committed.fold(() -> Option.some(buildCommand(stream, partition, owner, committedEpoch, 1L)),
                              current -> rewriteIfOwnerChanged(stream, partition, owner, committedEpoch, current));
    }

    @Override
    public Option<KVCommand<AetherKey>> writeOwnershipChange(String stream, int partition) {
        if (!isLeaderSupplier.getAsBoolean()) {
            return Option.none();
        }

        return hrwOwner.ownerOf(stream, partition)
                       .flatMap(owner -> decide(stream,
                                                partition,
                                                committedOwnership.ownershipOf(stream, partition),
                                                owner,
                                                currentEpoch()));
    }

    private Option<KVCommand<AetherKey>> rewriteIfOwnerChanged(String stream,
                                                               int partition,
                                                               NodeId owner,
                                                               Epoch committedEpoch,
                                                               StreamPartitionOwnershipValue current) {
        return current.owner().equals(owner)
               ? Option.none()
               : Option.some(buildCommand(stream, partition, owner, committedEpoch, current.ownershipTerm() + 1L));
    }

    private Epoch currentEpoch() {
        return Epoch.epoch(rabiaTermSupplier.get(), 0L);
    }

    private KVCommand<AetherKey> buildCommand(String stream,
                                              int partition,
                                              NodeId owner,
                                              Epoch epoch,
                                              long ownershipTerm) {
        var value = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner,
                                                                                epoch,
                                                                                ownershipTerm,
                                                                                hlcClock.now());

        return new KVCommand.Put<AetherKey, AetherValue>(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,
                                                                                                                 partition),
                                                         value);
    }
}
