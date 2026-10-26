// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.ToIntFunction;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The commit ring of the worker replica feed: the effects of recently committed batches, in apply order, byte-bounded, with the position
/// bookkeeping a subscriber needs to resume (floor, frontier, incarnation tag, the last [#ANCHOR_PAIRS] effect pairs).
///
/// Owned by the store and appended inside the same mutation as the apply. Lock order is `mutationLock` < store monitor < ring lock, and
/// nothing reachable with the ring lock held may call the store: this class has NO reference to `KVStore` and receives the incarnation
/// tag as a parameter of every call, so the heartbeat and the tag-equality check of [#subscribeFrom] read only the cached tag.
///
/// An entry is offered to every subscriber inside the same critical section that appends it and moves the frontier, and a heartbeat is
/// enqueued under that lock, so a heartbeat is always enqueued after every entry for batches up to its `nextSlot`. Closing a subscriber under the
/// lock is a flag; the transport close runs after the lock is released, on the close executor.
public final class CommitRing {
    private static final Logger log = LoggerFactory.getLogger(CommitRing.class);
    /// The number of `(slot, batchId)` pairs a position carries.
    public static final int ANCHOR_PAIRS = 8;
    public static final long DEFAULT_BUDGET_BYTES = 8L * 1024 * 1024;
    public static final long DEFAULT_QUEUE_BYTES = 8L * 1024 * 1024;
    static final int ENTRY_OVERHEAD_BYTES = 64;
    static final int EFFECT_OVERHEAD_BYTES = 16;
    /// What a queued heartbeat counts against its subscriber's byte bound: a stalled subscriber on an idle cluster still overflows.
    static final int HEARTBEAT_BYTES = 64;

    public enum RefusalReason {
        EVICTED,
        INCARNATION_MISMATCH,
        AHEAD,
        BUSY,
        NOT_SERVING
    }

    public enum CloseReason {
        EVICTED,
        INCARNATION_MISMATCH,
        OVERFLOW,
        /// A writer appended a slot behind the frontier; the ring did not record it and raised its floor to the frontier, so every position it
        /// could have granted before is refused `EVICTED` and the subscriber takes a snapshot.
        OUT_OF_ORDER
    }

    public sealed interface Subscribe permits Granted, Refused {}

    /// The entries after the anchor, in apply order, and the live subscription registered in the same step.
    public record Granted(List<ReplicaEntry> entries, long latest, Subscription subscription) implements Subscribe {}

    public record Refused(RefusalReason reason, long floor, IncarnationTag tag) implements Subscribe {}

    /// What a subscriber receives, in order, tagged with a per-subscription sequence assigned at enqueue under the ring lock.
    public sealed interface Message permits QueuedEntry, QueuedHeartbeat {}

    public record QueuedEntry(long subSeq, ReplicaEntry entry) implements Message {}

    public record QueuedHeartbeat(long subSeq, IncarnationTag tag, long nextSlot) implements Message {}

    /// One subscriber's bounded outbound queue. Offers never block and never do I/O; an overflow closes the subscription.
    public static final class Subscription {
        private final Object queueLock = new Object();
        private final Deque<Message> queue = new ArrayDeque<>();
        private final Deque<Integer> sizes = new ArrayDeque<>();
        private final long limitBytes;
        private final Consumer<CloseReason> onClose;
        private long queuedBytes;
        private long nextSubSeq = 1;
        private CloseReason closed;

        Subscription(long limitBytes, Consumer<CloseReason> onClose) {
            this.limitBytes = limitBytes;
            this.onClose = onClose;
        }

        /// The next message, if any. Consumer side: takes only the queue lock.
        public Option<Message> poll() {
            synchronized (queueLock) {
                var message = Option.option(queue.pollFirst());

                message.onPresent(_ -> releaseHead());

                return message;
            }
        }

        /// The consumer took the head message: its bytes no longer count against the bound.
        private void releaseHead() {
            queuedBytes -= sizes.removeFirst();
        }

        public boolean isClosed() {
            synchronized (queueLock) {
                return closed != null;
            }
        }

        public CloseReason closeReason() {
            synchronized (queueLock) {
                return closed;
            }
        }

        /// Ring side, under the ring lock. Returns false when the subscription is (now) closed.
        boolean offerEntry(ReplicaEntry entry, int bytes) {
            return offer(bytes, subSeq -> new QueuedEntry(subSeq, entry));
        }

        /// A heartbeat counts against the same byte bound as an entry, so a subscriber that stopped reading is closed even when no batch commits.
        boolean offerHeartbeat(IncarnationTag tag, long nextSlot) {
            return offer(HEARTBEAT_BYTES, subSeq -> new QueuedHeartbeat(subSeq, tag, nextSlot));
        }

        private boolean offer(int bytes, LongFunction<Message> message) {
            synchronized (queueLock) {
                if (closed != null) {
                    return false;
                }

                if (queuedBytes + bytes > limitBytes) {
                    closed = CloseReason.OVERFLOW;

                    return false;
                }

                queuedBytes += bytes;
                sizes.addLast(bytes);
                queue.addLast(message.apply(nextSubSeq++));

                return true;
            }
        }

        boolean markClosed(CloseReason reason) {
            synchronized (queueLock) {
                if (closed != null) {
                    return false;
                }

                closed = reason;

                return true;
            }
        }

        @Contract
        void fireClose(CloseReason reason) {
            onClose.accept(reason);
        }
    }

    private final ReentrantLock lock;
    private final long budgetBytes;
    private final ToIntFunction<Object> valueSizer;
    private final Executor closer;
    private final Deque<ReplicaEntry> entries = new ArrayDeque<>();
    private final TreeMap<Long, ReplicaEntry> effectEntriesBySlot = new TreeMap<>();
    private final Deque<AnchorPair> anchor = new ArrayDeque<>();
    private final List<Subscription> subscriptions = new ArrayList<>();
    private IncarnationTag tag = IncarnationTag.NONE;
    private long bytes;
    private long floor;
    private long nextSlot;
    private long coveredTo;

    private Runnable betweenBumpAndOffer = () -> {};

    private CommitRing(long budgetBytes, ToIntFunction<Object> valueSizer, Executor closer, ReentrantLock lock) {
        this.budgetBytes = budgetBytes;
        this.valueSizer = valueSizer;
        this.closer = closer;
        this.lock = lock;
    }

    public static CommitRing commitRing() {
        return commitRing(DEFAULT_BUDGET_BYTES);
    }

    public static CommitRing commitRing(long budgetBytes) {
        return commitRing(budgetBytes, ValueSizeTable.valueSizeTable()::estimate, asyncCloser());
    }

    /// `valueSizer` estimates a value's heap bytes (a per-value-class table; never an encode inside the store's mutation); `closer` runs the
    /// transport close of a subscription after the ring lock is released.
    public static CommitRing commitRing(long budgetBytes, ToIntFunction<Object> valueSizer, Executor closer) {
        return commitRing(budgetBytes, valueSizer, closer, new ReentrantLock());
    }

    /// Test seam: the ring lock is supplied, so a test build can wrap it in a ranked lock that throws on an out-of-order acquire (I-W13).
    static CommitRing commitRing(long budgetBytes,
                                 ToIntFunction<Object> valueSizer,
                                 Executor closer,
                                 ReentrantLock lock) {
        return new CommitRing(budgetBytes, valueSizer, closer, lock);
    }

    /// The production close executor: every transport close runs on its own virtual thread, so it holds neither the ring lock nor the store's
    /// monitor and `mutationLock` that the writer which decided the close still holds (a close listener may read the KV).
    public static Executor asyncCloser() {
        return close -> Thread.ofVirtual()
                              .name("replica-ring-close")
                              .start(close);
    }

    /// A new subscription queue with the default bound. Not registered until a [#subscribeFrom] grants it.
    public static Subscription subscription(Consumer<CloseReason> onClose) {
        return new Subscription(DEFAULT_QUEUE_BYTES, onClose);
    }

    public static Subscription subscription(long limitBytes, Consumer<CloseReason> onClose) {
        return new Subscription(limitBytes, onClose);
    }

    // ---- writer side (called by the store inside its mutation; store monitor held, ring lock taken here) ----
    /// Appends the effects of one committed batch at `slot` and moves the frontier to `nextSlot`; offers the entry to every subscriber inside the
    /// same critical section. A `NONE` tag records nothing. A tag different from the cached one clears the ring and the batch is NOT delivered
    /// (the bump batch: every subscriber re-snapshots under the new tag). An empty effect list only extends the open trailing range.
    @Contract
    public void append(IncarnationTag current,
                       long slot,
                       String batchId,
                       List<KVCommand<?>> effects,
                       long newNextSlot) {
        underLockDo(closes -> appendLocked(closes, current, slot, batchId, effects, newNextSlot));
    }

    /// The store's frontier moved without an effect (a V0 or empty slot, or a batch whose effects were all filtered out).
    @Contract
    public void advance(IncarnationTag current, long newNextSlot) {
        underLockDo(closes -> advanceLocked(closes, current, newNextSlot));
    }

    /// Any non-batch replacement of the store (snapshot install, reset, an uncommitted write): the ring is cleared, the floor jumps to
    /// `newFloor` and every subscriber is closed.
    @Contract
    public void invalidate(IncarnationTag current, long newFloor) {
        underLockDo(closes -> resetLocked(closes, current, newFloor));
    }

    /// Closes the open trailing range into a marker entry (offered to every subscriber), then enqueues a heartbeat carrying `nextSlot` to every
    /// subscriber, all under the ring lock. Only entries move a replica's position; a heartbeat never does.
    @Contract
    public void flush() {
        underLockDo(this::flushLocked);
    }

    // ---- reader side ----
    /// One call under the ring lock: verifies the position, returns the entries after it, registers the subscription. Returns exactly one of
    /// the entries or one explicit refusal.
    public Subscribe subscribeFrom(IncarnationTag replicaTag,
                                   long applied,
                                   List<AnchorPair> pairs,
                                   Subscription subscription) {
        return underLock(closes -> subscribeLocked(closes, replicaTag, applied, pairs, subscription));
    }

    /// The last pairs of effect entries applied, oldest first, as of now.
    public List<AnchorPair> anchor() {
        return underLock(_ -> List.copyOf(anchor));
    }

    public IncarnationTag tag() {
        return underLock(_ -> tag);
    }

    public long floor() {
        return underLock(_ -> floor);
    }

    public long nextSlot() {
        return underLock(_ -> nextSlot);
    }

    public long bytes() {
        return underLock(_ -> bytes);
    }

    public int entryCount() {
        return underLock(_ -> entries.size());
    }

    public List<ReplicaEntry> entries() {
        return underLock(_ -> List.copyOf(entries));
    }

    /// How many effect entries the slot index holds. Lets a test assert that eviction drops an entry from the index too, not only from the deque.
    int indexedEffectCount() {
        return underLock(_ -> effectEntriesBySlot.size());
    }

    /// True while the CALLING thread holds the ring lock. Lets a test assert that no transport or store code runs under it.
    public boolean isLockHeldByCurrentThread() {
        return lock.isHeldByCurrentThread();
    }

    /// Test hook: runs inside the append critical section between the frontier bump and the subscriber offers.
    @Contract
    void betweenBumpAndOffer(Runnable hook) {
        betweenBumpAndOffer = hook;
    }

    // ---- locked bodies ----
    private void appendLocked(Closes closes,
                              IncarnationTag current,
                              long slot,
                              String batchId,
                              List<KVCommand<?>> effects,
                              long newNextSlot) {
        if (!current.equals(tag)) {
            resetLocked(closes, current, newNextSlot);

            return;
        }

        if (tag.isNone()) {
            nextSlot = Math.max(nextSlot, newNextSlot);

            return;
        }
        // Guard: never append behind the frontier, so an older slot cannot reorder the chain or move `coveredTo` back. The store's
        // `applyEffects` is the single enforcer of the `slot < applied` skip; this keeps the ring itself in apply order, and never in silence.
        if (slot < nextSlot) {
            refuseBehindFrontierLocked(closes, slot);

            return;
        }

        if (effects.isEmpty()) {
            advanceLocked(closes, current, newNextSlot);

            return;
        }

        var entry = new ReplicaEntry(tag, coveredTo, slot, batchId, effects);

        nextSlot = Math.max(nextSlot, newNextSlot);
        betweenBumpAndOffer.run();
        entries.addLast(entry);
        effectEntriesBySlot.put(slot, entry);
        bytes += estimatedBytes(entry, valueSizer);
        coveredTo = slot + 1;
        pushAnchor(new AnchorPair(slot, batchId));
        offerToSubscribers(closes, entry);
        evict();
    }

    /// I-W1 for a batch the ring cannot record in order: the floor rises to the frontier (which stays) and every live sink is closed
    /// `OUT_OF_ORDER`. The ring is cleared, so every anchor pair a position can carry lies below the new floor: a resubscribe from any position the
    /// ring could have granted before, a fully caught-up one (`applied` = the frontier) included, has no verifiable pair and is refused `EVICTED`.
    private void refuseBehindFrontierLocked(Closes closes, long slot) {
        log.warn("Commit ring refused an append at slot {} behind its frontier {}; floor raised to the frontier, closing {} live subscription(s)",
                 slot,
                 nextSlot,
                 subscriptions.size());
        clearTo(nextSlot);
        closeAll(closes, CloseReason.OUT_OF_ORDER);
    }

    private void advanceLocked(Closes closes, IncarnationTag current, long newNextSlot) {
        if (!current.equals(tag)) {
            resetLocked(closes, current, newNextSlot);

            return;
        }

        nextSlot = Math.max(nextSlot, newNextSlot);
    }

    private void resetLocked(Closes closes, IncarnationTag current, long newFloor) {
        var reason = current.equals(tag)
                     ? CloseReason.EVICTED
                     : CloseReason.INCARNATION_MISMATCH;

        tag = current;
        clearTo(newFloor);
        closeAll(closes, reason);
    }

    /// Empties the ring and moves the floor, the frontier and the covered range to `newFloor`.
    private void clearTo(long newFloor) {
        entries.clear();
        effectEntriesBySlot.clear();
        anchor.clear();
        bytes = 0;
        floor = newFloor;
        nextSlot = newFloor;
        coveredTo = newFloor;
    }

    private void flushLocked(Closes closes) {
        if (tag.isNone()) {
            return;
        }

        closeOpenRange(closes);
        for (var subscription : List.copyOf(subscriptions)) {
            if (!subscription.offerHeartbeat(tag, nextSlot)) {
                closeOne(closes, subscription, CloseReason.OVERFLOW);
            }
        }
    }

    /// Closes the open trailing range into a marker entry: no effects, no batch id, `slot = to - 1`.
    private void closeOpenRange(Closes closes) {
        if (nextSlot <= coveredTo) {
            return;
        }

        var marker = new ReplicaEntry(tag, coveredTo, nextSlot - 1, ReplicaEntry.NONE, List.of());

        entries.addLast(marker);
        bytes += estimatedBytes(marker, valueSizer);
        coveredTo = nextSlot;
        offerToSubscribers(closes, marker);
        evict();
    }

    private Subscribe subscribeLocked(Closes closes,
                                      IncarnationTag replicaTag,
                                      long applied,
                                      List<AnchorPair> pairs,
                                      Subscription subscription) {
        if (tag.isNone()) {
            return new Refused(RefusalReason.NOT_SERVING, floor, tag);
        }

        if (!tag.matches(replicaTag)) {
            return new Refused(RefusalReason.INCARNATION_MISMATCH, floor, tag);
        }

        if (applied > nextSlot) {
            return new Refused(RefusalReason.AHEAD, floor, tag);
        }

        if (applied < floor) {
            return new Refused(RefusalReason.EVICTED, floor, tag);
        }

        return verifyAnchor(applied, pairs).<Subscribe> map(refusal -> refusal)
                           .or(() -> grant(closes, applied, subscription));
    }

    private Subscribe grant(Closes closes, long applied, Subscription subscription) {
        closeOpenRange(closes);
        subscriptions.add(subscription);

        return new Granted(entriesFrom(applied), nextSlot, subscription);
    }

    /// Called with `applied >= floor`. An unanchored position is `AHEAD` (section 2.4: anchor absent while `applied >= floor`). Otherwise every
    /// pair at or above the floor must be present with an equal batch id, at least one pair must be verifiable (none: `EVICTED`), and the ring
    /// must hold no effect entry between the newest pair and `applied`, which the replica would never receive. Empty means verified.
    private Option<Refused> verifyAnchor(long applied, List<AnchorPair> pairs) {
        if (pairs.isEmpty()) {
            return Option.some(new Refused(RefusalReason.AHEAD, floor, tag));
        }

        var verifiable = pairs.stream().filter(pair -> pair.slot() >= floor).toList();

        if (verifiable.isEmpty()) {
            return Option.some(new Refused(RefusalReason.EVICTED, floor, tag));
        }

        return verifiable.stream()
                         .allMatch(this::presentWithEqualId) && !missesAnEffect(applied, pairs)
               ? Option.none()
               : Option.some(new Refused(RefusalReason.AHEAD, floor, tag));
    }

    /// True when the ring holds an effect entry strictly between the position's newest anchor pair and its `applied`: the position claims slots
    /// whose effects it never applied. One ordered-index lookup.
    private boolean missesAnEffect(long applied, List<AnchorPair> pairs) {
        var newest = pairs.stream().mapToLong(AnchorPair::slot).max().orElse(applied);

        return newest < applied && !effectEntriesBySlot.subMap(newest, false, applied, false)
                                                       .isEmpty();
    }

    private boolean presentWithEqualId(AnchorPair pair) {
        var present = effectEntriesBySlot.get(pair.slot());

        return present != null && present.batchId()
                                         .equals(pair.batchId());
    }

    private List<ReplicaEntry> entriesFrom(long applied) {
        var result = new ArrayList<ReplicaEntry>();

        for (var entry : entries) {
            if (entry.slot() >= applied) {
                result.add(entry);
            }
        }

        return List.copyOf(result);
    }

    private void pushAnchor(AnchorPair pair) {
        anchor.addLast(pair);
        while (anchor.size() > ANCHOR_PAIRS) {
            anchor.removeFirst();
        }
    }

    private void offerToSubscribers(Closes closes, ReplicaEntry entry) {
        var size = estimatedBytes(entry, valueSizer);

        for (var subscription : List.copyOf(subscriptions)) {
            if (!subscription.offerEntry(entry, size)) {
                closeOne(closes, subscription, CloseReason.OVERFLOW);
            }
        }
    }

    /// Overflow eviction: the oldest entry goes first and the floor rises past it. Never blocks the apply.
    private void evict() {
        while (bytes > budgetBytes && !entries.isEmpty()) {
            var oldest = entries.removeFirst();

            bytes -= estimatedBytes(oldest, valueSizer);
            floor = oldest.slot() + 1;
            if (!oldest.isMarker()) {
                effectEntriesBySlot.remove(oldest.slot());
            }
        }
    }

    private void closeAll(Closes closes, CloseReason reason) {
        for (var subscription : List.copyOf(subscriptions)) {
            closeOne(closes, subscription, reason);
        }
    }

    private void closeOne(Closes closes, Subscription subscription, CloseReason reason) {
        subscriptions.remove(subscription);
        subscription.markClosed(reason);
        closes.add(() -> subscription.fireClose(subscription.closeReason()));
    }

    // ---- estimate ----
    /// Heap-byte estimate of an entry (R-07): a fixed overhead, then per effect the length of the key's `asString()`, a fixed overhead and the
    /// value estimate. Never an encode.
    static int estimatedBytes(ReplicaEntry entry, ToIntFunction<Object> valueSizer) {
        var total = ENTRY_OVERHEAD_BYTES;

        for (var effect : entry.effects()) {
            total += effectBytes(effect, valueSizer);
        }

        return total;
    }

    private static int effectBytes(KVCommand<?> effect, ToIntFunction<Object> valueSizer) {
        return switch (effect) {
            case KVCommand.Put<?, ?> put -> put.key().asString().length() + EFFECT_OVERHEAD_BYTES + valueSizer.applyAsInt(put.value());
            case KVCommand.Remove<?> remove -> remove.key().asString().length() + EFFECT_OVERHEAD_BYTES;
            default -> EFFECT_OVERHEAD_BYTES;
        };
    }

    // ---- locking ----
    /// Collects the transport closes decided under the lock; they run after it is released.
    private static final class Closes {
        private final List<Runnable> pending = new ArrayList<>();

        @Contract
        void add(Runnable close) {
            pending.add(close);
        }
    }

    private <T> T underLock(java.util.function.Function<Closes, T> body) {
        var closes = new Closes();

        lock.lock();
        T result;

        try {
            result = body.apply(closes);
        } finally {
            lock.unlock();
        }

        closes.pending.forEach(closer::execute);

        return result;
    }

    private void underLockDo(Consumer<Closes> body) {
        underLock(closes -> unitAfter(body, closes));
    }

    private static Unit unitAfter(Consumer<Closes> body, Closes closes) {
        body.accept(closes);

        return Unit.unit();
    }
}
