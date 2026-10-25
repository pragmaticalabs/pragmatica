// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaEntries;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaHeartbeat;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaRefused;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotChunk;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotRequest;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSubscribe;
import org.pragmatica.cluster.state.kvstore.CommitRing;
import org.pragmatica.cluster.state.kvstore.CommitRing.CloseReason;
import org.pragmatica.cluster.state.kvstore.CommitRing.Granted;
import org.pragmatica.cluster.state.kvstore.CommitRing.QueuedEntry;
import org.pragmatica.cluster.state.kvstore.CommitRing.QueuedHeartbeat;
import org.pragmatica.cluster.state.kvstore.CommitRing.RefusalReason;
import org.pragmatica.cluster.state.kvstore.CommitRing.Refused;
import org.pragmatica.cluster.state.kvstore.CommitRing.Subscribe;
import org.pragmatica.cluster.state.kvstore.CommitRing.Subscription;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.ReplicaEntry;
import org.pragmatica.cluster.state.kvstore.ReplicaKeyFilter;
import org.pragmatica.cluster.state.kvstore.ReplicaSnapshot;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


/// The core side of the worker replica feed: answers a subscribe from the commit ring, pushes the ring's entries and heartbeats to each
/// subscriber, and serves the filtered snapshot in pulled chunks.
///
/// Threads. A subscribe and a snapshot request are handled on `serveExecutor`, never on the network I/O thread that delivered them: the snapshot
/// capture holds the store monitor and encodes megabytes. A saturated executor answers `BUSY`. [#tick()] (a scheduler thread) moves each
/// subscription's queue to the transport; [#heartbeat()] asks the ring to close its open range and enqueue a heartbeat for every subscriber.
/// The ring offers only to bounded queues and does no I/O, so the apply path never waits for a subscriber (I-W11).
///
/// A source serves iff `serving` says so (`isActive() && !isPendingCatchUp()` on a core); a heartbeat carries the same flag so a subscriber
/// notices a source that stopped serving.
public final class ReplicaFeedServer<K extends StructuredKey, V> {
    private static final Logger log = LoggerFactory.getLogger(ReplicaFeedServer.class);
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final int GRANT_ACK_SEQUENCE = 0;

    private record TransferKey(NodeId peer, long requestId) {}

    /// A subscriber the server pushes to. `ready` flips once the catch-up has been sent, so no live message overtakes it.
    private record Served(NodeId peer, long requestId, Subscription subscription, AtomicBoolean ready) {}

    private static final class Transfer {
        private final SnapshotCache.Cached image;
        private volatile long lastTouchNanos;

        Transfer(SnapshotCache.Cached image, long nowNanos) {
            this.image = image;
            this.lastTouchNanos = nowNanos;
        }
    }

    private final NodeId self;
    private final KVStore<K, V> store;
    private final ReplicaKeyFilter filter;
    private final BiConsumer<NodeId, ProtocolMessage> send;
    private final BooleanSupplier serving;
    private final Predicate<NodeId> eligible;
    private final Predicate<NodeId> connected;
    private final Executor serveExecutor;
    private final LongSupplier nanos;
    private final ReplicaFeedLimits limits;
    private final SnapshotCache cache;
    private final Semaphore serves;
    private final Map<NodeId, Served> subscribers = new ConcurrentHashMap<>();
    private final Map<TransferKey, Transfer> transfers = new ConcurrentHashMap<>();
    private final AtomicLong busyTotal = new AtomicLong();
    private final AtomicLong refusedTotal = new AtomicLong();
    private final AtomicLong subscribedTotal = new AtomicLong();
    private final AtomicLong snapshotsCaptured = new AtomicLong();
    private final AtomicLong snapshotCacheHits = new AtomicLong();

    private ReplicaFeedServer(NodeId self,
                              KVStore<K, V> store,
                              ReplicaKeyFilter filter,
                              BiConsumer<NodeId, ProtocolMessage> send,
                              BooleanSupplier serving,
                              Predicate<NodeId> eligible,
                              Predicate<NodeId> connected,
                              Executor serveExecutor,
                              LongSupplier nanos,
                              ReplicaFeedLimits limits) {
        this.self = self;
        this.store = store;
        this.filter = filter;
        this.send = send;
        this.serving = serving;
        this.eligible = eligible;
        this.connected = connected;
        this.serveExecutor = serveExecutor;
        this.nanos = nanos;
        this.limits = limits;
        this.cache = new SnapshotCache(limits.cacheEntries(), limits.cacheMillis() * NANOS_PER_MILLI, nanos);
        this.serves = new Semaphore(limits.maxServes());
    }

    /// `send` hands a message to the transport (non-blocking); `eligible` admits a subscriber; `connected` says whether its link is up (a
    /// subscription of a gone peer is released); `serveExecutor` is the dedicated serve pool.
    public static <K extends StructuredKey, V> ReplicaFeedServer<K, V> replicaFeedServer(NodeId self,
                                                                                         KVStore<K, V> store,
                                                                                         ReplicaKeyFilter filter,
                                                                                         BiConsumer<NodeId, ProtocolMessage> send,
                                                                                         BooleanSupplier serving,
                                                                                         Predicate<NodeId> eligible,
                                                                                         Predicate<NodeId> connected,
                                                                                         Executor serveExecutor,
                                                                                         LongSupplier nanos,
                                                                                         ReplicaFeedLimits limits) {
        return new ReplicaFeedServer<>(self,
                                       store,
                                       filter,
                                       send,
                                       serving,
                                       eligible,
                                       connected,
                                       serveExecutor,
                                       nanos,
                                       limits);
    }

    // ---- inbound (called on the network thread: hand over at once) ----
    @Contract
    public void onSubscribe(ReplicaSubscribe request) {
        hand(request.sender(), request.requestId(), () -> serveSubscribe(request));
    }

    @Contract
    public void onSnapshotRequest(ReplicaSnapshotRequest request) {
        hand(request.sender(), request.requestId(), () -> serveSnapshot(request));
    }

    /// Hands `work` to the serve executor; a saturated executor answers `BUSY` rather than queueing without bound.
    private void hand(NodeId peer, long requestId, Runnable work) {
        try {
            serveExecutor.execute(work);
        } catch (RejectedExecutionException e) {
            refuseBusy(peer, requestId);
        }
    }

    // ---- periodic (scheduler thread) ----
    /// Releases the subscriptions of peers that went away, pushes each ready subscription's queue to the transport, and abandons idle transfers.
    @Contract
    public void tick() {
        releaseGone();
        subscribers.values().forEach(this::drain);
        abandonIdleTransfers();
    }

    /// Closes the ring's open range into a marker and enqueues a heartbeat for every subscriber (all under the ring lock).
    @Contract
    public void heartbeat() {
        store.replicaRing().flush();
    }

    public Map<String, Long> counters() {
        return Map.of("replica_busy_total",
                      busyTotal.get(),
                      "replica_refused_total",
                      refusedTotal.get(),
                      "replica_subscribed_total",
                      subscribedTotal.get(),
                      "replica_snapshots_captured_total",
                      snapshotsCaptured.get(),
                      "replica_snapshot_cache_hits_total",
                      snapshotCacheHits.get(),
                      "replica_subscribers",
                      (long) subscribers.size(),
                      "replica_ring_subscriptions",
                      (long) store.replicaRing().subscriptionCount(),
                      "replica_transfers",
                      (long) transfers.size());
    }

    // ---- subscribe ----
    private void serveSubscribe(ReplicaSubscribe request) {
        var peer = request.sender();

        if (!eligible.test(peer) || !serving.getAsBoolean()) {
            refuse(peer, request.requestId(), RefusalReason.NOT_SERVING);

            return;
        }

        if (subscribers.size() >= limits.maxSubscribers() && !subscribers.containsKey(peer)) {
            refuseBusy(peer, request.requestId());

            return;
        }

        var subscription = CommitRing.subscription(limits.queueBytes(),
                                                   reason -> onSubscriptionClosed(peer, request.requestId(), reason));

        switch (subscribe(request, subscription)) {
            case Granted granted -> admit(new Served(peer, request.requestId(), subscription, new AtomicBoolean()),
                                          granted);
            case Refused refused -> refuse(peer, request.requestId(), refused.reason(), refused.floor(), refused.tag());
        }
    }

    private Subscribe subscribe(ReplicaSubscribe request, Subscription subscription) {
        var ring = store.replicaRing();

        return switch (request.mode()) {
            case LIVE_ONLY -> ring.subscribeLive(subscription);
            case FROM_ANCHOR -> ring.subscribeFrom(request.tag(), request.applied(), request.anchor(), subscription);
            case UNKNOWN -> new Refused(RefusalReason.NOT_SERVING, ring.floor(), ring.tag());
        };
    }

    private void admit(Served served, Granted granted) {
        var previous = subscribers.put(served.peer(), served);

        if (previous != null) {
            store.replicaRing().unsubscribe(previous.subscription());
        }

        subscribedTotal.incrementAndGet();
        sendCatchUp(served, granted);
        served.ready().set(true);
    }

    /// The entries after the verified position, in messages of bounded weight, all with sequence 0; an empty grant still sends one empty
    /// message, which is the acknowledgement that the subscription is registered.
    private void sendCatchUp(Served served, Granted granted) {
        var batch = new ArrayList<ReplicaEntry>();
        var weight = 0;

        for (var entry : granted.entries()) {
            batch.add(entry);
            weight += entry.effects().size() + 1;
            if (weight >= limits.catchUpWeight()) {
                sendEntries(served, GRANT_ACK_SEQUENCE, batch, granted.latest());
                batch.clear();
                weight = 0;
            }
        }

        if (!batch.isEmpty() || granted.entries().isEmpty()) {
            sendEntries(served, GRANT_ACK_SEQUENCE, batch, granted.latest());
        }
    }

    private void sendEntries(Served served, long subSeq, List<ReplicaEntry> entries, long latest) {
        send.accept(served.peer(), new ReplicaEntries(self, served.requestId(), subSeq, entries, latest));
    }

    /// A subscription the ring closed: it leaves the table and the subscriber is told, so it resubscribes (the position decides whether it must
    /// take a snapshot). A release by the owner fires nothing.
    private void onSubscriptionClosed(NodeId peer, long requestId, CloseReason reason) {
        subscribers.computeIfPresent(peer,
                                     (_, served) -> served.requestId() == requestId
                                                    ? null
                                                    : served);
        if (reason != CloseReason.RELEASED) {
            var ring = store.replicaRing();

            refuse(peer, requestId, refusalFor(reason), ring.floor(), ring.tag());
        }
    }

    private static RefusalReason refusalFor(CloseReason reason) {
        return reason == CloseReason.INCARNATION_MISMATCH
               ? RefusalReason.INCARNATION_MISMATCH
               : RefusalReason.EVICTED;
    }

    // ---- drain ----
    private void releaseGone() {
        subscribers.values()
                   .stream()
                   .filter(served -> served.subscription()
                                           .isClosed() || !connected.test(served.peer()) || !eligible.test(served.peer()))
                   .toList()
                   .forEach(this::release);
    }

    private void release(Served served) {
        subscribers.remove(served.peer(), served);
        store.replicaRing().unsubscribe(served.subscription());
    }

    private void drain(Served served) {
        if (!served.ready().get()) {
            return;
        }

        var sent = 0;
        var next = served.subscription().poll();

        while (next.isPresent() && sent < limits.drainMessagesPerTick()) {
            next.onPresent(message -> push(served, message));
            sent++;
            next = served.subscription().poll();
        }
    }

    private void push(Served served, CommitRing.Message message) {
        switch (message) {
            case QueuedEntry queued -> sendEntries(served,
                                                   queued.subSeq(),
                                                   List.of(queued.entry()),
                                                   queued.entry().slot() + 1);
            case QueuedHeartbeat beat -> send.accept(served.peer(),
                                                     new ReplicaHeartbeat(self,
                                                                          served.requestId(),
                                                                          beat.subSeq(),
                                                                          beat.tag(),
                                                                          beat.nextSlot(),
                                                                          serving.getAsBoolean(),
                                                                          beat.nextSlot()));
        }
    }

    // ---- snapshot ----
    private void serveSnapshot(ReplicaSnapshotRequest request) {
        var peer = request.sender();

        if (!eligible.test(peer) || !serving.getAsBoolean()) {
            refuse(peer, request.requestId(), RefusalReason.NOT_SERVING);

            return;
        }

        if (request.offset() == 0 && request.hash().isEmpty()) {
            beginTransfer(request);
        } else {
            continueTransfer(request);
        }
    }

    private void beginTransfer(ReplicaSnapshotRequest request) {
        var key = new TransferKey(request.sender(), request.requestId());

        option(transfers.get(key)).onPresent(old -> finish(key, old));
        if (!serves.tryAcquire()) {
            refuseBusy(request.sender(), request.requestId());

            return;
        }

        imageOfNow().onSuccess(image -> startTransfer(key, image))
                  .onFailure(cause -> abandonStart(request,
                                                   cause.message()));
    }

    private void startTransfer(TransferKey key, SnapshotCache.Cached image) {
        var transfer = new Transfer(image, nanos.getAsLong());

        transfers.put(key, transfer);
        sendChunk(key, transfer, 0);
    }

    private void abandonStart(ReplicaSnapshotRequest request, String reason) {
        serves.release();
        log.warn("Replica snapshot for {} not served: {}", request.sender(), reason);
        refuse(request.sender(), request.requestId(), RefusalReason.NOT_SERVING);
    }

    /// The cached image of the current `(tag, revision)`, or a fresh capture (cached for the next asker).
    private Result<SnapshotCache.Cached> imageOfNow() {
        return cache.get(store.replicaRing().tag(),
                         store.committedRevision())
                    .map(this::cacheHit)
                    .map(Result::success)
                    .or(this::capture);
    }

    private SnapshotCache.Cached cacheHit(SnapshotCache.Cached hit) {
        snapshotCacheHits.incrementAndGet();

        return hit;
    }

    private Result<SnapshotCache.Cached> capture() {
        return store.captureReplicaSnapshot(filter)
                    .flatMap(this::servable)
                    .flatMap(this::cached);
    }

    private Result<ReplicaSnapshot> servable(ReplicaSnapshot snapshot) {
        return snapshot.tag()
                       .isNone()
               ? cause("no cluster incarnation committed").result()
               : success(snapshot);
    }

    private Result<SnapshotCache.Cached> cached(ReplicaSnapshot snapshot) {
        return sha256Hex(snapshot.bytes()).map(hash -> remember(snapshot, hash));
    }

    private SnapshotCache.Cached remember(ReplicaSnapshot snapshot, String hash) {
        snapshotsCaptured.incrementAndGet();

        return cache.put(new SnapshotCache.Cached(snapshot, hash, nanos.getAsLong()));
    }

    private static Result<String> sha256Hex(byte[] bytes) {
        return Result.lift(Causes::fromThrowable,
                           () -> HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private void continueTransfer(ReplicaSnapshotRequest request) {
        var key = new TransferKey(request.sender(), request.requestId());

        option(transfers.get(key)).filter(transfer -> sameImage(transfer, request))
              .filter(transfer -> request.offset() < transfer.image.snapshot()
                                                                   .bytes().length)
              .onPresent(transfer -> sendChunk(key,
                                               transfer,
                                               (int) request.offset()))
              .onEmpty(() -> refuse(request.sender(),
                                    request.requestId(),
                                    RefusalReason.EVICTED));
    }

    private static boolean sameImage(Transfer transfer, ReplicaSnapshotRequest request) {
        return transfer.image.hash()
                             .equals(request.hash()) && transfer.image.snapshot()
                                                                      .revision() == request.revision();
    }

    private void sendChunk(TransferKey key, Transfer transfer, int offset) {
        var snapshot = transfer.image.snapshot();
        var end = Math.min(snapshot.bytes().length, offset + limits.chunkBytes());

        transfer.lastTouchNanos = nanos.getAsLong();
        send.accept(key.peer(),
                    new ReplicaSnapshotChunk(self,
                                             key.requestId(),
                                             snapshot.tag(),
                                             snapshot.revision(),
                                             snapshot.anchor(),
                                             transfer.image.hash(),
                                             snapshot.bytes().length,
                                             offset,
                                             Arrays.copyOfRange(snapshot.bytes(), offset, end)));
        if (end >= snapshot.bytes().length) {
            finish(key, transfer);
        }
    }

    /// Frees the transfer's serve slot exactly once, whoever finishes it first.
    private void finish(TransferKey key, Transfer transfer) {
        if (transfers.remove(key, transfer)) {
            serves.release();
        }
    }

    private void abandonIdleTransfers() {
        var now = nanos.getAsLong();
        var idleNanos = limits.transferIdleMillis() * NANOS_PER_MILLI;

        transfers.forEach((key, transfer) -> abandonIfIdle(key, transfer, now - transfer.lastTouchNanos > idleNanos));
    }

    private void abandonIfIdle(TransferKey key, Transfer transfer, boolean idle) {
        if (idle) {
            finish(key, transfer);
        }
    }

    // ---- refusals ----
    private void refuseBusy(NodeId peer, long requestId) {
        busyTotal.incrementAndGet();
        refuse(peer, requestId, RefusalReason.BUSY);
    }

    private void refuse(NodeId peer, long requestId, RefusalReason reason) {
        var ring = store.replicaRing();

        refuse(peer, requestId, reason, ring.floor(), ring.tag());
    }

    private void refuse(NodeId peer, long requestId, RefusalReason reason, long floor, IncarnationTag tag) {
        refusedTotal.incrementAndGet();
        send.accept(peer, new ReplicaRefused(self, requestId, reason, floor, tag));
    }
}
