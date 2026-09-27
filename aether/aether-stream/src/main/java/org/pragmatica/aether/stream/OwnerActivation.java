// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.PartitionKey;
import org.pragmatica.aether.stream.replication.ReplicaWatermarkProbe;
import org.pragmatica.aether.stream.replication.SelfWatermark;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// #1555 owner promotion gate. A node may ACT as owner of `(stream, partition)` — accept application appends,
/// report `servedByOwner`, serve reads as owner — only once it has been ACTIVATED for the ownership record it
/// currently holds. Activation runs three steps:
///
///   1. **Fresh view.** When an ownership record is committed, one no-op consensus round ([LinearizableBarrier])
///      is ordered and applied locally; because Rabia applies decisions in one total order, every ownership
///      change committed before the round has then applied here too. The record is re-read and must still
///      name self. A node partitioned away whose committed view went stale therefore sees the newer record,
///      or cannot complete the round at all, and never activates on the stale one. A partition's FIRST owner
///      (no record committed yet) skips this step: there is no earlier ownership to be stale about.
///   2. **Catch-up.** The watermark of every OTHER live placement member is probed; if any is ahead of self,
///      the missing suffix is pulled from the highest holder before activation. This runs even for a first
///      owner, because stream ownership records are runtime state: after a whole-cluster cold restart every
///      partition looks first-ever while a same-id node may hold on-disk data ahead of the new owner. It is
///      skipped only when no other live member exists.
///   3. **Activate** for exactly that record. Activation is bound to the record VALUE (owner, epoch, term,
///      transfer stamp): any later change to the committed record — including a transfer away and back —
///      no longer matches it, so the node re-runs the gate before acting again (the owner-side epoch fence).
///
/// **An unreachable live member blocks activation** (it may hold a higher watermark). This is bounded: the
/// member set is the live placement projection (`AetherNode.livePlacementMembers`), from which a member leaves
/// once the membership FSM declares it DEAD, shrinking the probe set and unblocking activation within
/// failure-detection time. Activation never proceeds while a reachable member holds a higher watermark.
///
/// **Quorum loss clears every activation** ([#onQuorumStateChange]) and a node without an active consensus engine is
/// never activated, so an ex-owner that heals back re-runs the whole gate before it serves again.
///
/// Residual (accepted, #1555 decision c): an append admitted on the previous owner in the instant before the
/// ownership flip reaches it is not seen by this catch-up. At `min-sync-replicas` 0 the acknowledgement is
/// owner-local by definition, so this is part of that documented loss window; `min-sync-replicas` >= 1 closes it.
public final class OwnerActivation {
    private static final Logger log = LoggerFactory.getLogger(OwnerActivation.class);

    /// The committed `StreamPartitionOwnershipValue` of `(stream, partition)` in this node's applied state.
    @FunctionalInterface
    public interface OwnershipRecordSource {
        Option<StreamPartitionOwnershipValue> committed(String stream, int partition);
    }

    /// Whether this node is the owner of `(stream, partition)` under the current placement.
    @FunctionalInterface
    public interface PlacementOwner {
        boolean isPlacementOwner(String stream, int partition);
    }

    /// Pull `(local watermark + 1) .. sourceTail` of `(stream, partition)` from `source` into the local ring,
    /// resolving once the local ring holds the suffix.
    @FunctionalInterface
    public interface OwnerCatchUp {
        Promise<Long> catchUp(String stream, int partition, NodeId source, long sourceTail);
    }

    /// Why an activation attempt did not complete; every case leaves the partition un-activated and the next
    /// demand retries.
    public enum ActivationError implements Cause {
        NOT_OWNER("This node is not the owner of the partition after refreshing its committed view"),
        HOLDER_UNREACHABLE("A live placement member did not answer the watermark probe and may hold a higher watermark"),
        CATCH_UP_SHORT("The catch-up did not reach the highest live holder's watermark"),
        IN_PROGRESS("An activation of this partition is already running");

        private final String message;

        ActivationError(String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return message;
        }
    }

    private record PeerWatermark(NodeId node, long watermark) {}

    private final NodeId self;
    private final OwnershipRecordSource records;
    private final PlacementOwner placementOwner;
    private final Option<LinearizableBarrier> barrier;
    private final Supplier<List<NodeId>> liveMembers;
    private final ReplicaWatermarkProbe probe;
    private final SelfWatermark selfWatermark;
    private final OwnerCatchUp catchUp;
    private final BooleanSupplier consensusActive;
    /// The committed record each partition was activated for; [Option#none] marks a first-owner activation.
    private final Map<PartitionKey, Option<StreamPartitionOwnershipValue>> activated = new ConcurrentHashMap<>();
    private final Set<PartitionKey> inFlight = ConcurrentHashMap.newKeySet();

    private OwnerActivation(NodeId self,
                            OwnershipRecordSource records,
                            PlacementOwner placementOwner,
                            Option<LinearizableBarrier> barrier,
                            Supplier<List<NodeId>> liveMembers,
                            ReplicaWatermarkProbe probe,
                            SelfWatermark selfWatermark,
                            OwnerCatchUp catchUp,
                            BooleanSupplier consensusActive) {
        this.self = self;
        this.records = records;
        this.placementOwner = placementOwner;
        this.barrier = barrier;
        this.liveMembers = liveMembers;
        this.probe = probe;
        this.selfWatermark = selfWatermark;
        this.catchUp = catchUp;
        this.consensusActive = consensusActive;
    }

    public static OwnerActivation ownerActivation(NodeId self,
                                                  OwnershipRecordSource records,
                                                  PlacementOwner placementOwner,
                                                  Option<LinearizableBarrier> barrier,
                                                  Supplier<List<NodeId>> liveMembers,
                                                  ReplicaWatermarkProbe probe,
                                                  SelfWatermark selfWatermark,
                                                  OwnerCatchUp catchUp,
                                                  BooleanSupplier consensusActive) {
        return new OwnerActivation(self,
                                   records,
                                   placementOwner,
                                   barrier,
                                   liveMembers,
                                   probe,
                                   selfWatermark,
                                   catchUp,
                                   consensusActive);
    }

    /// Whether this node may act as owner of `(stream, partition)` right now: its consensus engine is active,
    /// it is the owner (the committed record names it, or no record exists and placement names it), and it was
    /// activated for exactly the record it currently holds.
    public boolean isActivated(String stream, int partition) {
        var current = records.committed(stream, partition);

        return consensusActive.getAsBoolean() && claimsOwnership(stream, partition, current)
               && Option.option(activated.get(PartitionKey.partitionKey(stream, partition)))
                        .filter(current::equals)
                        .isPresent();
    }

    /// The gate every owner action passes: admitted when activated; otherwise an activation is started and the
    /// action is refused with the transient [StreamError.OwnerNotActivated].
    public Result<Unit> admit(String stream, int partition) {
        if (isActivated(stream, partition)) {
            return Result.unitResult();
        }

        // FER: the refused action is retried by its caller, and every later demand re-runs the gate, so an
        // activation attempt that fails here is logged and superseded, never relied upon.
        activate(stream, partition).onFailure(cause -> log.debug("Owner activation of {}[{}] pending: {}",
                                                                 stream,
                                                                 partition,
                                                                 cause.message()));

        return new StreamError.OwnerNotActivated(stream, partition).result();
    }

    /// Run the promotion gate for `(stream, partition)` once; concurrent demands share nothing and are told an
    /// activation is already running.
    public Promise<Unit> activate(String stream, int partition) {
        var key = PartitionKey.partitionKey(stream, partition);

        if (!inFlight.add(key)) {
            return ActivationError.IN_PROGRESS.promise();
        }

        return runGate(stream, partition, key).onResultRun(() -> inFlight.remove(key));
    }

    /// Quorum-state hook: on PASSIVE (quorum lost) drop every activation, so no partition is served as owner
    /// until the gate re-runs against a view refreshed after the node rejoins.
    @Contract
    public void onQuorumStateChange(ClusterStateNotification notification) {
        if (notification.state() == ClusterStateNotification.State.PASSIVE) {
            activated.clear();
        }
    }

    private Promise<Unit> runGate(String stream, int partition, PartitionKey key) {
        return freshView(stream, partition).flatMap(record -> catchUpToLiveHolders(stream, partition).map(_ -> record))
                                           .map(record -> recordActivation(stream, partition, key, record));
    }

    private Unit recordActivation(String stream,
                                  int partition,
                                  PartitionKey key,
                                  Option<StreamPartitionOwnershipValue> record) {
        activated.put(key, record);
        log.info("Owner activation of {}[{}] complete at watermark {} for ownership record {}",
                 stream,
                 partition,
                 selfWatermark.localWatermark(stream, partition),
                 record);

        return Unit.unit();
    }

    /// Step 1: with a committed record, order one no-op round and re-read the record, which must still make this
    /// node the owner. With no record (a first owner) the step is skipped.
    private Promise<Option<StreamPartitionOwnershipValue>> freshView(String stream, int partition) {
        return records.committed(stream, partition)
                      .fold(() -> firstOwnerView(stream, partition), _ -> refreshedView(stream, partition));
    }

    private Promise<Option<StreamPartitionOwnershipValue>> firstOwnerView(String stream, int partition) {
        return claimsOwnership(stream, partition, Option.none())
               ? Promise.success(Option.none())
               : ActivationError.NOT_OWNER.promise();
    }

    private Promise<Option<StreamPartitionOwnershipValue>> refreshedView(String stream, int partition) {
        return barrier.fold(() -> Promise.success(Unit.unit()), round -> round.awaitRound(stream, partition))
                      .flatMap(_ -> ownedRecord(stream, partition));
    }

    private Promise<Option<StreamPartitionOwnershipValue>> ownedRecord(String stream, int partition) {
        var current = records.committed(stream, partition);

        return claimsOwnership(stream, partition, current)
               ? Promise.success(current)
               : ActivationError.NOT_OWNER.promise();
    }

    /// A committed record decides ownership; only without one does placement decide.
    private boolean claimsOwnership(String stream, int partition, Option<StreamPartitionOwnershipValue> record) {
        return record.fold(() -> placementOwner.isPlacementOwner(stream, partition),
                           value -> value.owner()
                                         .equals(self));
    }

    /// Step 2: probe every other live placement member; any unreachable member blocks, a higher watermark is
    /// pulled from its holder first.
    private Promise<Unit> catchUpToLiveHolders(String stream, int partition) {
        var peers = liveMembers.get()
                               .stream()
                               .filter(member -> !member.equals(self))
                               .toList();

        if (peers.isEmpty()) {
            return Promise.success(Unit.unit());
        }

        return Promise.allOf(peers.stream()
                                  .map(peer -> probe.probe(peer, stream, partition)
                                                    .map(watermark -> new PeerWatermark(peer, watermark)))
                                  .toList())
                      .flatMap(results -> catchUpFromHighest(stream, partition, results));
    }

    private Promise<Unit> catchUpFromHighest(String stream, int partition, List<Result<PeerWatermark>> results) {
        if (results.stream()
                   .anyMatch(Result::isFailure)) {
            return ActivationError.HOLDER_UNREACHABLE.promise();
        }

        var local = selfWatermark.localWatermark(stream, partition);

        return Option.from(results.stream()
                                  .map(result -> result.or(new PeerWatermark(self, -1L)))
                                  .filter(peer -> peer.watermark() > local)
                                  .max(Comparator.comparingLong(PeerWatermark::watermark)))
                     .fold(() -> Promise.success(Unit.unit()), highest -> pullSuffix(stream, partition, highest));
    }

    private Promise<Unit> pullSuffix(String stream, int partition, PeerWatermark highest) {
        log.warn("Owner activation of {}[{}]: {} holds watermark {} ahead of local {} — catching up before acting as owner",
                 stream,
                 partition,
                 highest.node(),
                 highest.watermark(),
                 selfWatermark.localWatermark(stream, partition));

        return catchUp.catchUp(stream, partition, highest.node(), highest.watermark())
                      .flatMap(_ -> verifyReached(stream, partition, highest.watermark()));
    }

    private Promise<Unit> verifyReached(String stream, int partition, long target) {
        return selfWatermark.localWatermark(stream, partition) >= target
               ? Promise.success(Unit.unit())
               : ActivationError.CATCH_UP_SHORT.promise();
    }
}
