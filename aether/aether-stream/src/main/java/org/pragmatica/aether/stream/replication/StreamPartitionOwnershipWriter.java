// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;


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
/// ## Epoch-advance semantics
/// The `ownerEpoch` is `Epoch.epoch(incarnation, rabiaTerm, ownershipTerm)`: the committed generation
/// epoch — the cluster incarnation (#1529), which ranks first so a new run outranks a restored one, then
/// the generation term — paired with the per-partition `ownershipTerm` as the local counter. Both are a
/// pure function of committed state — the generation term from the committed generation, the
/// `ownershipTerm` read from (and bumped relative to) the committed `StreamPartitionOwnershipValue` —
/// so two replicas presented the same committed state reach the IDENTICAL decision and write the
/// IDENTICAL value; the CP-applier fence then deduplicates / orders them. The epoch advances on BOTH
/// axes of an ownership transfer:
///   - a **leader re-election / governor handover** bumps `rabiaTerm` (the dominant component), and
///   - **every owner change — including a same-term HRW reshuffle (node join, no re-election)** — bumps
///     `ownershipTerm` (the local counter).
/// This closes the same-term-reshuffle gap: a deposed-but-alive owner whose committed epoch carried the
/// OLD `ownershipTerm` is strictly dominated by its successor's `(rabiaTerm, ownershipTerm+1)` epoch and
/// is therefore fenced, even when no leader change occurred. `ownerEpoch.localCounter == ownershipTerm`
/// by construction. (The DHT ownership writer in `BootstrapModule.buildCorePartitionCommand` closes the
/// same same-term gap the same way, via its own `ownerEpoch(committedEpoch, ownershipTerm)` helper —
/// #345 DHT parity.)
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
/// changed (unchanged partitions are no-ops). #265 (increment 6) bounds the reshuffle fan-out on the
/// APPLY axis: [#writeOwnershipChanges] decides a WHOLE reconcile pass at once and returns the moved
/// partitions' Puts as ONE list, which the caller (the AetherNode driver, fired once per
/// [ReplicaSetController] pass) applies as a SINGLE consensus batch — so a mass reshuffle commits one
/// batch per pass instead of N un-batched applies. The per-partition [#decide] is unchanged: it still
/// mints one Put per moved partition; only the transport is batched.
public interface StreamPartitionOwnershipWriter {
    /// Compute the ownership-change command for a single `(stream, partition)`, or [Option#none] when
    /// the HRW owner already matches the committed owner (idempotent). Pure: no side effects, no clock
    /// reads beyond `hlcClock.now()` for the advisory `transferredAt` stamp (which is not part of the
    /// fence). `committedEpoch` supplies the committed generation term (its `rabiaTerm`) that becomes the
    /// dominant component of the new `ownerEpoch`; the epoch's local counter is the `ownershipTerm` this
    /// decision writes, so the epoch advances on every owner change as well as on a generation bump.
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

    /// Leader-only BATCH driver (#265): [#writeOwnershipChange] every `(stream, partition)` in
    /// `partitions` — the pairs reconciled by ONE [ReplicaSetController] pass — and collect the emitted
    /// commands into a single list, the ownership deltas of that pass. The caller applies the whole list
    /// as ONE consensus batch. Each per-pair call self-limits: a follower emits nothing (its
    /// `isLeaderSupplier` gate short-circuits before any committed-state read) and an unchanged owner
    /// contributes nothing (its `decide` returns [Option#none]), so the returned list holds at most one
    /// `Put` per GENUINELY-moved partition. An empty `partitions` list — or a pass with no moved
    /// partitions — yields an empty list and the caller applies nothing.
    default List<KVCommand<AetherKey>> writeOwnershipChanges(List<PartitionKey> partitions) {
        return partitions.stream()
                         .map(partition -> writeOwnershipChange(partition.streamName(),
                                                                partition.partition()))
                         .flatMap(Option::stream)
                         .toList();
    }

    /// The pre-#1730 writer: plain `Put`s of records carrying no committed ISR (`isrVersion` 0), which the ack
    /// path treats as unmanaged. Kept for harnesses that drive ownership by hand; production uses the ISR-aware
    /// factory below.
    static StreamPartitionOwnershipWriter streamPartitionOwnershipWriter(BooleanSupplier isLeaderSupplier,
                                                                         Supplier<Epoch> generationEpochSupplier,
                                                                         HlcClock hlcClock,
                                                                         CommittedOwnership committedOwnership,
                                                                         HrwOwner hrwOwner) {
        return new StreamPartitionOwnershipWriterRecord(isLeaderSupplier,
                                                        generationEpochSupplier,
                                                        hlcClock,
                                                        committedOwnership,
                                                        hrwOwner);
    }

    /// #1730: the ISR-aware writer. Every write is a guarded [KVCommand.LeaderTransaction] whose single mutation
    /// expects the exact committed record, so it can never overwrite an ISR change it did not see, and the records
    /// it mints carry the partition's in-sync replica set:
    ///   - **first record** — the ISR is the partition's placement for the owner ([IsrInputs#initialIsr]): a fresh
    ///     partition holds nothing, so every placed replica is trivially in sync;
    ///   - **owner unchanged** — members that left the live set are dropped from the ISR (never the owner);
    ///   - **planned move** (the committed owner is still live) — the new owner leads, the live ISR stays; the new
    ///     owner's activation catches up from the live holders before it serves;
    ///   - **failover** (the committed owner is not live) — the new owner is chosen ONLY from the live ISR, the
    ///     desired owner if it is a member, else the HRW-first member. With no live ISR member nothing is written:
    ///     the partition stays unavailable rather than lose an acknowledged write (unclean failover is off).
    static StreamPartitionOwnershipWriter streamPartitionOwnershipWriter(BooleanSupplier isLeaderSupplier,
                                                                         Supplier<Epoch> generationEpochSupplier,
                                                                         HlcClock hlcClock,
                                                                         CommittedOwnership committedOwnership,
                                                                         HrwOwner hrwOwner,
                                                                         IsrInputs isrInputs,
                                                                         Supplier<Option<LeaderValue>> committedLeader) {
        return new IsrOwnershipWriter(isLeaderSupplier,
                                      generationEpochSupplier,
                                      hlcClock,
                                      committedOwnership,
                                      hrwOwner,
                                      isrInputs,
                                      committedLeader);
    }

    /// What the ISR-aware writer reads besides the committed record (#1730).
    interface IsrInputs {
        /// The live placement members, as the leader sees them.
        List<NodeId> liveMembers();
        /// The placement of `(stream, partition)` led by `owner`, the owner first.
        List<NodeId> initialIsr(String stream, int partition, NodeId owner);
    }

    /// One guarded mutation of the ownership record of `(stream, partition)`: applied only while the committed record is
    /// exactly `committed` and the committed leader is `leader` (#1730). The owner-side commits (the ISR, the start of an
    /// epoch) use it as the leader's writer does.
    static KVCommand<AetherKey> guardedOwnershipWrite(LeaderValue leader,
                                                      String stream,
                                                      int partition,
                                                      Option<StreamPartitionOwnershipValue> committed,
                                                      StreamPartitionOwnershipValue next) {
        return IsrOwnershipWriter.guarded(leader, stream, partition, committed, next);
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
                                            Supplier<Epoch> generationEpochSupplier,
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
        return current.owner()
                      .equals(owner)
               ? Option.none()
               : Option.some(buildCommand(stream, partition, owner, committedEpoch, current.ownershipTerm() + 1L));
    }

    private Epoch currentEpoch() {
        return generationEpochSupplier.get();
    }

    private KVCommand<AetherKey> buildCommand(String stream,
                                              int partition,
                                              NodeId owner,
                                              Epoch committedEpoch,
                                              long ownershipTerm) {
        var value = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner,
                                                                                ownerEpoch(committedEpoch, ownershipTerm),
                                                                                ownershipTerm,
                                                                                hlcClock.now());

        return new KVCommand.Put<AetherKey, AetherValue>(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,
                                                                                                                 partition),
                                                         value);
    }

    /// The committed `ownerEpoch`: the leader's committed generation term (the dominant component, which
    /// advances on a leader re-election / governor handover) paired with the per-partition `ownershipTerm`
    /// as the local counter (which advances on EVERY owner change, including a same-term HRW reshuffle).
    /// This couples `ownerEpoch.localCounter == ownershipTerm`, so a deposed-but-alive owner — whose
    /// committed epoch carried the OLD `ownershipTerm` — is strictly dominated by its successor's epoch
    /// and is fenced, even when no leader change occurred. Still a pure function of committed state
    /// (committed generation term + the takeover counter read from the committed record), so two replicas
    /// presented identical committed state mint the IDENTICAL value.
    private static Epoch ownerEpoch(Epoch committedEpoch, long ownershipTerm) {
        return committedEpoch.withCounter(ownershipTerm);
    }
}

/// #1730 ISR-aware writer; see [StreamPartitionOwnershipWriter#streamPartitionOwnershipWriter(BooleanSupplier, Supplier,
/// HlcClock, StreamPartitionOwnershipWriter.CommittedOwnership, StreamPartitionOwnershipWriter.HrwOwner,
/// StreamPartitionOwnershipWriter.IsrInputs, Supplier)].
record IsrOwnershipWriter(BooleanSupplier isLeaderSupplier,
                          Supplier<Epoch> generationEpochSupplier,
                          HlcClock hlcClock,
                          StreamPartitionOwnershipWriter.CommittedOwnership committedOwnership,
                          StreamPartitionOwnershipWriter.HrwOwner hrwOwner,
                          StreamPartitionOwnershipWriter.IsrInputs isrInputs,
                          Supplier<Option<LeaderValue>> committedLeader) implements StreamPartitionOwnershipWriter {
    @Override
    public Option<KVCommand<AetherKey>> decide(String stream,
                                               int partition,
                                               Option<StreamPartitionOwnershipValue> committed,
                                               NodeId owner,
                                               Epoch committedEpoch) {
        return committedLeader.get()
                              .flatMap(leader -> next(stream,
                                                      partition,
                                                      committed,
                                                      owner,
                                                      committedEpoch,
                                                      isrInputs.liveMembers()).map(value -> guarded(leader,
                                                                                                    stream,
                                                                                                    partition,
                                                                                                    committed,
                                                                                                    value)));
    }

    @Override
    public Option<KVCommand<AetherKey>> writeOwnershipChange(String stream, int partition) {
        if (!isLeaderSupplier.getAsBoolean()) {
            return Option.none();
        }

        var committed = committedOwnership.ownershipOf(stream, partition);

        return hrwOwner.ownerOf(stream, partition)
                       .orElse(() -> committed.map(StreamPartitionOwnershipValue::owner))
                       .flatMap(owner -> decide(stream,
                                                partition,
                                                committed,
                                                owner,
                                                generationEpochSupplier.get()));
    }

    /// The record the leader should commit next, or none when the committed one stands (or no owner may be chosen).
    Option<StreamPartitionOwnershipValue> next(String stream,
                                               int partition,
                                               Option<StreamPartitionOwnershipValue> committed,
                                               NodeId desired,
                                               Epoch committedEpoch,
                                               List<NodeId> live) {
        return committed.fold(() -> Option.some(minted(desired,
                                                       committedEpoch,
                                                       1L,
                                                       led(desired, isrInputs.initialIsr(stream, partition, desired)),
                                                       1L,
                                                       List.of(),
                                                       List.of())),
                              current -> successor(stream, partition, current, desired, committedEpoch, live));
    }

    private Option<StreamPartitionOwnershipValue> successor(String stream,
                                                            int partition,
                                                            StreamPartitionOwnershipValue current,
                                                            NodeId desired,
                                                            Epoch committedEpoch,
                                                            List<NodeId> live) {
        var liveIsr = current.isr().stream().filter(live::contains).toList();
        var fenced = fencedAfter(current, live);

        if (live.contains(current.owner())) {
            return desired.equals(current.owner())
                   ? shrunk(current, liveIsr, fenced)
                   : Option.some(moved(current, desired, committedEpoch, liveIsr, fenced));
        }

        return failoverOwner(stream, partition, liveIsr, desired).map(owner -> moved(current,
                                                                                     owner,
                                                                                     committedEpoch,
                                                                                     liveIsr,
                                                                                     fenced))
                            .orElse(() -> refused(current));
    }

    /// No live ISR member and the owner dead: elect nobody (unclean failover is off) and COMMIT that verdict once, so
    /// the refusal is one committed transition its committer announces (#1730, owner ruling). Already committed: no
    /// write, so a repeated reconcile of a still-refused partition changes nothing and announces nothing.
    private static Option<StreamPartitionOwnershipValue> refused(StreamPartitionOwnershipValue current) {
        return current.failoverRefused()
               ? Option.none()
               : Option.some(current.withFailoverRefused(true));
    }

    /// Owner unchanged and live: drop the ISR members that left the live set. The owner itself is live, so the
    /// shrunk ISR is never empty.
    private static Option<StreamPartitionOwnershipValue> shrunk(StreamPartitionOwnershipValue current,
                                                                List<NodeId> liveIsr,
                                                                List<NodeId> fenced) {
        var next = liveIsr.equals(current.isr()) && fenced.equals(current.fenced())
                   ? current
                   : current.withIsrAndFenced(liveIsr, fenced);

        if (current.failoverRefused()) {
            // The refused owner is live again: the refusal resolves without an election.
            return Option.some(next.withFailoverRefused(false));
        }

        return next == current
               ? Option.none()
               : Option.some(next);
    }

    /// The members this leader keeps out of the ISR because ITS liveness view does not list them (#1883): the ones
    /// already fenced and the ISR members it drops now, minus every one it lists as live again. This committed set is
    /// the single liveness input of ISR membership: the owner never expands a fenced member, so the owner's and the
    /// leader's views of the same member cannot drive opposite commits (Kafka: an expansion admits only brokers the
    /// controller lists as unfenced).
    private static List<NodeId> fencedAfter(StreamPartitionOwnershipValue current, List<NodeId> live) {
        return Stream.concat(current.fenced().stream(),
                             current.isr().stream())
                     .filter(member -> !live.contains(member))
                     .distinct()
                     .toList();
    }

    /// Failover elects from the live ISR only: the desired owner when it is a member, else the HRW-first member.
    /// None when no member is live — the unclean case, refused.
    private static Option<NodeId> failoverOwner(String stream, int partition, List<NodeId> liveIsr, NodeId desired) {
        if (liveIsr.contains(desired)) {
            return Option.some(desired);
        }

        return Option.from(ReplicaPlacement.rank(stream, partition, liveIsr).stream().findFirst());
    }

    private StreamPartitionOwnershipValue moved(StreamPartitionOwnershipValue current,
                                                NodeId owner,
                                                Epoch committedEpoch,
                                                List<NodeId> liveIsr,
                                                List<NodeId> fenced) {
        var term = current.ownershipTerm() + 1L;

        return minted(owner,
                      committedEpoch,
                      term,
                      led(owner, liveIsr),
                      current.isrVersion() + 1L,
                      fenced,
                      current.epochStarts());
    }

    private StreamPartitionOwnershipValue minted(NodeId owner,
                                                 Epoch committedEpoch,
                                                 long ownershipTerm,
                                                 List<NodeId> isr,
                                                 long isrVersion,
                                                 List<NodeId> fenced,
                                                 List<EpochStart> epochStarts) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner,
                                                                           committedEpoch.withCounter(ownershipTerm),
                                                                           ownershipTerm,
                                                                           hlcClock.now(),
                                                                           isr,
                                                                           isrVersion,
                                                                           fenced,
                                                                           epochStarts);
    }

    /// `owner` first, then the other members in their given order.
    static List<NodeId> led(NodeId owner, List<NodeId> members) {
        var ordered = new ArrayList<NodeId>();

        ordered.add(owner);
        members.stream().filter(member -> !member.equals(owner)).forEach(ordered::add);

        return List.copyOf(ordered);
    }

    /// One guarded mutation: applied only while the committed record is exactly `committed` and the committed
    /// leader is `leader`, so a write decided on a stale record is refused rather than overwriting an ISR change.
    static KVCommand<AetherKey> guarded(LeaderValue leader,
                                        String stream,
                                        int partition,
                                        Option<StreamPartitionOwnershipValue> committed,
                                        StreamPartitionOwnershipValue next) {
        var key = StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream, partition);
        var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                      committed.map(value -> value),
                                                                      Option.some(next));

        return new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                       UUID.randomUUID().toString(),
                                                                       leader,
                                                                       List.of(),
                                                                       List.of(mutation));
    }
}
