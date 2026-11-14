// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.FenceTarget.Probe;
import org.pragmatica.aether.stream.replication.PartitionKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;


/// The move fence's deadline base (#2981 step 3, design §2.3 H2, NEW-4): captured ONCE per `(partition, ownershipTerm)`, at the
/// first delivery of a record that names this node owner for that term, and never moved by a later delivery of the same term.
///
/// The base is `renewalApplied[L]` (when this node last delivered a renewal of the fence target `L`) if present, else the
/// record's own delivery time. In delivery order that renewal is `L`'s newest one decided BEFORE the record; a renewal decided
/// after it cannot extend `L`'s ownership, because `L` applies the record first. Re-reading `renewalApplied[L]` at a later
/// record of the same term (an ISR change, a re-drive) could pick up such a later renewal and postpone the deadline without
/// bound under ISR churn, so the first capture stands. The deadline is `base + W` (`StreamingConfig.ownerLeaseWait()`).
///
/// The entry ends only with its term: a record of a new term replaces it (or drops it when another node owns that term). A
/// same-term re-gate (quorum loss cleared the activation) reuses it. A node with no entry for the term (restarted, joined
/// later, loaded the record from a snapshot) uses the current time at its gate's first read, which only waits longer, and keeps
/// it for the term like a delivered base.
///
/// Monotonic times are compared by difference only, as [NodeLease] does.
public final class FenceDeadlines {
    private record Base(long ownershipTerm, long nanos) {
        static Base base(long ownershipTerm, long nanos) {
            return new Base(ownershipTerm, nanos);
        }
    }

    private final NodeId self;
    private final Function<NodeId, Option<Long>> renewalApplied;
    private final LongSupplier nanoTime;
    private final long waitNanos;
    private final Map<PartitionKey, Base> bases = new ConcurrentHashMap<>();

    private FenceDeadlines(NodeId self,
                           Function<NodeId, Option<Long>> renewalApplied,
                           LongSupplier nanoTime,
                           long waitNanos) {
        this.self = self;
        this.renewalApplied = renewalApplied;
        this.nanoTime = nanoTime;
        this.waitNanos = waitNanos;
    }

    /// Deadlines of `self`'s gates, reading `renewalApplied` ([NodeLease#renewalApplied]) and `nanoTime` (the lease's clock),
    /// each `wait` (`W`) after its base.
    public static FenceDeadlines fenceDeadlines(NodeId self,
                                                Function<NodeId, Option<Long>> renewalApplied,
                                                LongSupplier nanoTime,
                                                TimeSpan wait) {
        return new FenceDeadlines(self, renewalApplied, nanoTime, wait.nanos());
    }

    /// The ownership listener's hook; must run on the store's ordered notification delivery thread, so that `renewalApplied`
    /// holds exactly the renewals delivered before `record`.
    @Contract
    public void onOwnershipDelivered(PartitionKey key, StreamPartitionOwnershipValue record) {
        if (record.owner().equals(self)) {
            captureOnce(key, record);
        } else {
            bases.remove(key);
        }
    }

    /// The monotonic instant at which the gate of `key` in `ownershipTerm` may skip the fence target: the captured base plus
    /// `W`. With no base for that term (restart, late join, snapshot load, or a gate that read the record before its delivery
    /// reached the listener) the FIRST read captures now as the base (#2981 step 3b, v3186 F1): a re-reading gate (a same-term
    /// re-gate after quorum loss) then finds the same deadline, which therefore passes, where an uncaptured now + `W` would move
    /// with every read and never expire. A base of a NEWER term is kept, and a read for an older term (a stale gate, which its
    /// guarded lineage write refuses anyway) captures nothing and gets now + `W`.
    public long deadline(PartitionKey key, long ownershipTerm) {
        var now = nanoTime.getAsLong();

        return Option.option(bases.compute(key, (_, known) -> keptOrNow(known, ownershipTerm, now)))
                     .filter(base -> base.ownershipTerm() == ownershipTerm)
                     .map(Base::nanos)
                     .or(now) + waitNanos;
    }

    /// How long the gate of `key` in `ownershipTerm` still waits before it may skip the fence target; zero or negative once the
    /// deadline passed. Reads (and on the first read captures) the deadline as [#deadline] does.
    public long nanosLeft(PartitionKey key, long ownershipTerm) {
        return deadline(key, ownershipTerm) - nanoTime.getAsLong();
    }

    /// `known` is the map's current entry, absent as `null` (the [Map#compute] callback contract).
    private static Base keptOrNow(Base known, long ownershipTerm, long now) {
        return Option.option(known)
                     .filter(base -> base.ownershipTerm() >= ownershipTerm)
                     .or(() -> Base.base(ownershipTerm, now));
    }

    private void captureOnce(PartitionKey key, StreamPartitionOwnershipValue record) {
        if (Option.option(bases.get(key)).filter(base -> base.ownershipTerm() == record.ownershipTerm()).isEmpty()) {
            bases.put(key,
                      Base.base(record.ownershipTerm(), baseOf(record)));
        }
    }

    private long baseOf(StreamPartitionOwnershipValue record) {
        return switch (FenceTarget.fenceTarget(record, self)) {
            case Probe(var owner) -> renewalApplied.apply(owner).or(nanoTime::getAsLong);
            case FenceTarget.NoFence _, FenceTarget.Unknown _ -> nanoTime.getAsLong();
        };
    }
}
