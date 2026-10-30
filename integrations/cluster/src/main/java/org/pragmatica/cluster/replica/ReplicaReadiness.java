// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;


/// Whether this replica may be read as the cluster's view (spec 2.8, I-W6): ONE computation, [#ready], and one publisher, [#refresh], which hands
/// its result to the sink. Nothing else sets or clears what the sink drives.
///
/// `ready()` is true iff all of: (a) the replica's tag equals the current source's newest counted heartbeat tag and neither is `NONE`; (b) that
/// heartbeat is at most `staleMillis` old; (c) `applied` is at least the `nextSlot` of the newest heartbeat received at least `lagMillis` ago;
/// (d) this node's own activation directive names the WORKER role; (e) the initial catch-up is done; (f) a cluster directory with its DHT
/// replication is installed. A heartbeat from a source that is not serving, or one whose `nextSlot` is behind `applied` under an equal tag
/// (`AHEAD`), is never counted toward (b). While not ready the replica keeps its last state.
public final class ReplicaReadiness {
    private record Beat(long arrivalNanos, long nextSlot) {}

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final LongSupplier nanos;
    private final Supplier<ReplicaPosition> position;
    private final BooleanSupplier workerRole;
    private final BooleanSupplier directoryInstalled;
    private final Consumer<Boolean> sink;
    private final long staleNanos;
    private final long lagNanos;
    private final ArrayDeque<Beat> beats = new ArrayDeque<>();
    private IncarnationTag beatTag = IncarnationTag.NONE;
    private boolean caughtUp;
    private long carriedUntilNanos;
    private boolean published;
    private boolean lastPublished;

    private ReplicaReadiness(LongSupplier nanos,
                             Supplier<ReplicaPosition> position,
                             BooleanSupplier workerRole,
                             BooleanSupplier directoryInstalled,
                             Consumer<Boolean> sink,
                             ReplicaClientLimits limits) {
        this.nanos = nanos;
        this.position = position;
        this.workerRole = workerRole;
        this.directoryInstalled = directoryInstalled;
        this.sink = sink;
        this.staleNanos = limits.staleMillis() * NANOS_PER_MILLI;
        this.lagNanos = limits.lagMillis() * NANOS_PER_MILLI;
    }

    public static ReplicaReadiness replicaReadiness(LongSupplier nanos,
                                                    Supplier<ReplicaPosition> position,
                                                    BooleanSupplier workerRole,
                                                    BooleanSupplier directoryInstalled,
                                                    Consumer<Boolean> sink,
                                                    ReplicaClientLimits limits) {
        return new ReplicaReadiness(nanos, position, workerRole, directoryInstalled, sink, limits);
    }

    /// A heartbeat of the current source. Counted toward (b) and (c) only when the source says it is serving and the heartbeat is not `AHEAD`.
    public synchronized Unit onHeartbeat(IncarnationTag tag, long nextSlot, boolean serving) {
        if (serving && !isAhead(tag, nextSlot)) {
            beatTag = tag;
            carriedUntilNanos = 0L;
            beats.addLast(new Beat(nanos.getAsLong(), nextSlot));
        }

        return Unit.unit();
    }

    /// True iff the heartbeat claims a frontier behind this replica's under an equal tag: the replica is ahead of its source, so its position is
    /// unusable (discard, snapshot).
    public boolean isAhead(IncarnationTag tag, long nextSlot) {
        var held = position.get();

        return held.tag()
                   .matches(tag) && nextSlot < held.applied();
    }

    /// The initial catch-up (e) is done, or (on a new source) not yet.
    public synchronized Unit caughtUp(boolean done) {
        caughtUp = done;

        return Unit.unit();
    }

    /// The replica is re-based on a new source (a snapshot is coming): the old heartbeats say nothing about the new state. A resume from the
    /// replica's own anchor does NOT call this: the heartbeats already counted stay true of the replica and age out under (b), so a re-parent to
    /// another source does not by itself drop readiness.
    public synchronized Unit sourceChanged() {
        beats.clear();
        beatTag = IncarnationTag.NONE;
        caughtUp = false;

        return Unit.unit();
    }

    /// A resume onto another source keeps the counted heartbeats only until `graceNanos` from now, or until the new source counts its own.
    public synchronized Unit carryBeatsFor(long graceNanos) {
        if (carriedUntilNanos == 0L) {
            carriedUntilNanos = nanos.getAsLong() + graceNanos;
        }

        return Unit.unit();
    }

    public synchronized boolean ready() {
        return isSynced(nanos.getAsLong())
               && workerRole.getAsBoolean()
               && directoryInstalled.getAsBoolean();
    }

    /// Whether this replica is current enough to be relayed to members (spec 3.3, I-W5): (a), (b), (c), (e) hold and `applied` has reached the
    /// `nextSlot` of the NEWEST counted heartbeat, not only of the one at least `L` old. The worker role (d) and the directory (f) are not part of it.
    public synchronized boolean servable() {
        var now = nanos.getAsLong();

        return isSynced(now) && counted().map(newest -> position.get()
                                                                .applied() >= newest.nextSlot())
                                       .or(false);
    }

    private boolean isSynced(long now) {
        return counted().filter(newest -> isFresh(newest, now))
                      .filter(_ -> beatTag.matches(position.get().tag()))
                      .filter(_ -> isCaughtUpToLag(now))
                      .isPresent() && caughtUp;
    }

    private Option<Beat> counted() {
        return carriedUntilNanos != 0L && nanos.getAsLong() > carriedUntilNanos
               ? Option.none()
               : Option.option(beats.peekLast());
    }

    private boolean isFresh(Beat newest, long now) {
        return now - newest.arrivalNanos() <= staleNanos;
    }

    /// (c): the newest heartbeat at least `lagNanos` old, and nothing older kept.
    private boolean isCaughtUpToLag(long now) {
        while (beats.size() > 1 && now - secondOldest().arrivalNanos() >= lagNanos) {
            beats.removeFirst();
        }

        return Option.option(beats.peekFirst())
                     .filter(oldest -> now - oldest.arrivalNanos() >= lagNanos)
                     .map(oldest -> position.get()
                                            .applied() >= oldest.nextSlot())
                     .or(true);
    }

    private Beat secondOldest() {
        var iterator = beats.iterator();

        iterator.next();

        return iterator.next();
    }

    /// The only publisher: recomputes [#ready] and tells the sink when it changed (or on first call).
    @Contract
    public synchronized void refresh() {
        var now = ready();

        if (!published || now != lastPublished) {
            published = true;
            lastPublished = now;
            sink.accept(now);
        }
    }
}
