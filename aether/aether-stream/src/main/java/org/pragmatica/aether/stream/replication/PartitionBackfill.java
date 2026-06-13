// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.stream.replication.BackfillError.General.INCOMPLETE_BACKFILL;
import static org.pragmatica.aether.stream.replication.BackfillError.General.MALFORMED_RESPONSE;
import static org.pragmatica.aether.stream.replication.BackfillError.General.NOT_HIGHEST_WATERMARK;
import static org.pragmatica.aether.stream.replication.BackfillError.General.NO_SOURCE_REPLICA;
import static org.pragmatica.aether.stream.replication.BackfillError.General.UNREACHABLE_REPLICA_BLOCKS_PROMOTION;
import static org.pragmatica.aether.stream.replication.PartitionKey.partitionKey;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupRequest.catchupRequest;


/// A4 backfill orchestrator. When a node newly becomes a replica for a non-empty `(stream, partition)`
/// it must pull the partition's existing events from an up-to-date source and reach `CAUGHT_UP`
/// before it is eligible to serve reads.
///
/// ## Source selection
/// From {@link ReplicaRegistry#replicasFor} it picks the `CAUGHT_UP` replica (owner or replica) with
/// the highest `confirmedOffset`, excluding self. If there is no such source (no peer is caught up,
/// or self is the only replica) backfill cannot proceed and the promise fails — self stays SYNCING.
///
/// ## Cold-start deadlock break (bounded-wait then data-safe self-promote)
/// After a SIMULTANEOUS full-cluster restart EVERY replica is `SYNCING`, so no caught-up source can
/// ever exist and each replica waits forever for one — a cluster-wide deadlock that prevents the node
/// from ever reaching lifecycle READY. To break it WITHOUT serving stale state:
///   1. the FIRST no-source observation for a partition is timestamped; while
///      `now - firstObserved < sourceWaitBound` the node simply stays SYNCING (the staggered-restart
///      case where a survivor is merely slow resolves itself here),
///   2. once the bound elapses the node PROBES every other registered replica via
///      {@link ReplicaWatermarkProbe} and self-promotes ONLY when it can prove it is safe:
///        - EVERY other registered replica must be REACHABLE (a probe success). An UNREACHABLE replica
///          might be caught-up with newer state, so promoting past it could serve stale events — the
///          node stays SYNCING instead ({@link BackfillError.General#UNREACHABLE_REPLICA_BLOCKS_PROMOTION}).
///        - self's own watermark must be `>= max(seen peer watermarks)`; on an exact tie the
///          deterministic tie-break (lowest {@link NodeId}) elects exactly ONE promoter, so the cluster
///          cannot promote two divergent replicas.
///   The promotion is logged at WARN with every watermark seen (an operator-visible bootstrap decision).
///
/// ## Flow
///   1. read self's current `confirmedOffset` from the registry (`-1` for a fresh replica),
///   2. issue a single {@link CatchupTransport#requestCatchup} from `confirmedOffset + 1`; the
///      production transport ({@link ForwardCatchupTransport}) pages internally until the source is
///      drained,
///   3. apply every returned event into the local ring via {@link StreamPartitionRecovery}
///      (offset-preserving, non-replicating),
///   4. on full success flip self to `CAUGHT_UP` at the source watermark
///      ({@link ReplicaRegistry#updateWatermark}).
///
/// ## Failure safety
/// If the catch-up request fails (source unreachable / timeout) or any local apply fails, the promise
/// fails and `updateWatermark` is NOT called — self remains `SYNCING` and is excluded from the read
/// path by the existing `selectReplicaAndRead` filter. No partial-success state machine: a fresh
/// replica re-runs backfill on the next reconcile.
public final class PartitionBackfill {
    private static final Logger log = LoggerFactory.getLogger(PartitionBackfill.class);

    private final ReplicaRegistry registry;
    private final StreamPartitionRecovery partitionRecovery;
    private final CatchupTransport transport;
    private final ReplicaWatermarkProbe probe;
    private final SelfWatermark selfWatermark;
    private final NodeId self;
    private final TimeSpan sourceWaitBound;
    private final LongSupplier clock;
    /// First wall-clock instant (ms) at which each partition was observed to have NO caught-up source.
    /// `backfill` is invoked one-shot and retried by the reconcile / on-gap seams, so the bounded wait
    /// must persist across calls — this map is that cross-call memory.
    private final ConcurrentHashMap<PartitionKey, Long> firstNoSourceMs = new ConcurrentHashMap<>();

    private PartitionBackfill(ReplicaRegistry registry,
                              StreamPartitionRecovery partitionRecovery,
                              CatchupTransport transport,
                              ReplicaWatermarkProbe probe,
                              SelfWatermark selfWatermark,
                              NodeId self,
                              TimeSpan sourceWaitBound,
                              LongSupplier clock) {
        this.registry = registry;
        this.partitionRecovery = partitionRecovery;
        this.transport = transport;
        this.probe = probe;
        this.selfWatermark = selfWatermark;
        this.self = self;
        this.sourceWaitBound = sourceWaitBound;
        this.clock = clock;
    }

    /// Backward-compatible factory: no cold-start self-promotion (probe is a no-op that never reports a
    /// reachable peer, so the deadlock-break never fires and behavior is byte-identical to the original
    /// orchestrator). Used where the deadlock-break is not wired.
    public static PartitionBackfill partitionBackfill(ReplicaRegistry registry,
                                                      StreamPartitionRecovery partitionRecovery,
                                                      CatchupTransport transport,
                                                      NodeId self) {
        return new PartitionBackfill(registry,
                                     partitionRecovery,
                                     transport,
                                     (_, _, _) -> NO_SOURCE_REPLICA.promise(),
                                     (_, _) -> - 1L,
                                     self,
                                     TimeSpan.timeSpan(Long.MAX_VALUE).nanos(),
                                     System::currentTimeMillis);
    }

    /// Cold-start-aware factory: after `sourceWaitBound` elapses with no caught-up source, the
    /// highest-watermark replica self-promotes (data-safe; see class doc). `probe` reports each peer's
    /// current watermark (success) or its unreachability (failure); `selfWatermark` reports self's local
    /// tail offset for the promotion contest.
    public static PartitionBackfill partitionBackfill(ReplicaRegistry registry,
                                                      StreamPartitionRecovery partitionRecovery,
                                                      CatchupTransport transport,
                                                      ReplicaWatermarkProbe probe,
                                                      SelfWatermark selfWatermark,
                                                      NodeId self,
                                                      TimeSpan sourceWaitBound) {
        return new PartitionBackfill(registry,
                                     partitionRecovery,
                                     transport,
                                     probe,
                                     selfWatermark,
                                     self,
                                     sourceWaitBound,
                                     System::currentTimeMillis);
    }

    /// Test factory: injects a deterministic clock so the bounded wait can be exercised without sleeping.
    static PartitionBackfill partitionBackfill(ReplicaRegistry registry,
                                               StreamPartitionRecovery partitionRecovery,
                                               CatchupTransport transport,
                                               ReplicaWatermarkProbe probe,
                                               SelfWatermark selfWatermark,
                                               NodeId self,
                                               TimeSpan sourceWaitBound,
                                               LongSupplier clock) {
        return new PartitionBackfill(registry,
                                     partitionRecovery,
                                     transport,
                                     probe,
                                     selfWatermark,
                                     self,
                                     sourceWaitBound,
                                     clock);
    }

    /// Backfill `(streamName, partition)` onto self from the best caught-up peer. Resolves with the
    /// number of events applied on success; fails (leaving self SYNCING) when no source is available
    /// or the source/apply path fails.
    public Promise<Long> backfill(String streamName, int partition) {
        var replicas = registry.replicasFor(streamName, partition);

        return selectSource(replicas).fold(() -> handleNoSource(streamName, partition, replicas),
                                           source -> backfillFromCaughtUpSource(streamName, partition, replicas, source));
    }

    private Promise<Long> backfillFromCaughtUpSource(String streamName,
                                                     int partition,
                                                     List<ReplicaDescriptor> replicas,
                                                     ReplicaDescriptor source) {
        // A caught-up source exists: this is the normal path. Clear any cold-start wait memory so a
        // later transient no-source observation re-arms the bound from scratch (no false fast-promote).
        firstNoSourceMs.remove(partitionKey(streamName, partition));

        return backfillFrom(streamName, partition, source, selfConfirmedOffset(replicas));
    }

    private Promise<Long> backfillFrom(String streamName, int partition, ReplicaDescriptor source, long selfConfirmed) {
        var fromOffset = selfConfirmed + 1;
        var request = catchupRequest(source.nodeId(), streamName, partition, fromOffset);

        log.debug("Backfill {}[{}]: source={} from offset {} (source watermark {})",
                  streamName,
                  partition,
                  source.nodeId(),
                  fromOffset,
                  source.confirmedOffset());

        return transport.requestCatchup(source.nodeId(),
                                        request)
                        .flatMap(response -> applyAndPromote(streamName, partition, source, response));
    }

    private Promise<Long> applyAndPromote(String streamName,
                                          int partition,
                                          ReplicaDescriptor source,
                                          ReplicationMessage.CatchupResponse response) {
        var fromOffset = response.fromOffset();
        var watermark = Math.max(response.toOffset(), source.confirmedOffset());

        return applyEvents(streamName, partition, response).fold(cause -> failApply(streamName, partition, cause),
                                                                 applied -> promote(streamName,
                                                                                    partition,
                                                                                    fromOffset,
                                                                                    watermark,
                                                                                    applied));
    }

    /// Promote self to CAUGHT_UP only when the highest applied offset actually reaches the source
    /// watermark. `fromOffset + applied - 1` is the highest offset landed locally; if that is below
    /// the watermark a gap remains (short/truncated page, source still ahead) → fail and stay SYNCING
    /// rather than declare a false-ready CAUGHT_UP with holes (B2).
    private Promise<Long> promote(String streamName, int partition, long fromOffset, long watermark, long applied) {
        var highestApplied = fromOffset + applied - 1;

        if (highestApplied < watermark) {
            log.warn("Backfill {}[{}] incomplete: applied {} events up to offset {} but watermark is {} — staying SYNCING",
                     streamName,
                     partition,
                     applied,
                     highestApplied,
                     watermark);

            return INCOMPLETE_BACKFILL.promise();
        }

        registry.updateWatermark(streamName, partition, self, watermark);
        log.info("Backfill {}[{}] complete: applied {} events, self CAUGHT_UP at offset {}",
                 streamName,
                 partition,
                 applied,
                 watermark);

        return Promise.success(applied);
    }

    /// Apply every recovered event in order via a sequential fail-fast fold. A truncated response
    /// (`payloads.size() != timestamps.size()`) is treated as a parse failure so the replica stays
    /// SYNCING instead of applying only the shorter prefix (B2). The fold is an explicit loop — never
    /// parallelized — so it cannot silently drop events on a parallel reduce (M4). Returns the number
    /// of events applied, short-circuiting to the first local append failure.
    private Result<Long> applyEvents(String streamName, int partition, ReplicationMessage.CatchupResponse response) {
        var payloads = response.payloads();
        var timestamps = response.timestamps();

        if (payloads.size() != timestamps.size()) {
            log.warn("Backfill {}[{}]: malformed catch-up response — {} payloads vs {} timestamps",
                     streamName,
                     partition,
                     payloads.size(),
                     timestamps.size());

            return MALFORMED_RESPONSE.result();
        }

        var applied = 0L;

        for (var i = 0; i < payloads.size(); i++) {
            var result = partitionRecovery.appendRecoveredEvent(streamName,
                                                                partition,
                                                                payloads.get(i),
                                                                timestamps.get(i));

            if (result.isFailure()) {
                // Propagate the append failure cause; the value channel is empty on a failed Result.
                return result;
            }

            applied++;
        }

        return Result.success(applied);
    }

    private Promise<Long> failApply(String streamName, int partition, Cause cause) {
        log.warn("Backfill {}[{}] failed applying events: {} — staying SYNCING", streamName, partition, cause.message());

        return cause.promise();
    }

    /// No caught-up source exists. Stay SYNCING until the bounded wait elapses; once it does, attempt a
    /// data-safe cold-start self-promotion (see class doc).
    private Promise<Long> handleNoSource(String streamName, int partition, List<ReplicaDescriptor> replicas) {
        var key = partitionKey(streamName, partition);
        var firstObserved = firstNoSourceMs.computeIfAbsent(key, _ -> clock.getAsLong());
        var waited = clock.getAsLong() - firstObserved;

        if (waited < sourceWaitBound.millis()) {
            log.warn("Backfill {}[{}]: no caught-up source available — staying SYNCING ({}ms of {}ms wait elapsed)",
                     streamName,
                     partition,
                     waited,
                     sourceWaitBound.millis());

            return NO_SOURCE_REPLICA.promise();
        }

        return attemptColdStartPromotion(streamName, partition, replicas);
    }

    /// Probe every other registered replica's watermark+reachability, then decide promotion off the
    /// collected view. Self's watermark is its LOCAL tail offset (not the registry's stale SYNCING `-1`).
    /// `Promise.allOf` collects EVERY peer's outcome as a `Result` (Success=watermark for a reachable
    /// peer, Failure for an unreachable one) — it never short-circuits, so a single unreachable peer does
    /// not erase the reachable peers' watermarks from the decision view.
    private Promise<Long> attemptColdStartPromotion(String streamName,
                                                    int partition,
                                                    List<ReplicaDescriptor> replicas) {
        var peers = peerNodeIds(replicas);
        var selfLocal = selfWatermark.localWatermark(streamName, partition);

        return Promise.allOf(peers.stream().map(peer -> probe.probe(peer, streamName, partition)).toList()).flatMap(results -> decidePromotion(streamName,
                                                                                                                                               partition,
                                                                                                                                               peers,
                                                                                                                                               selfLocal,
                                                                                                                                               results));
    }

    /// Promotion predicate. Promote self iff (a) EVERY peer probe succeeded (all reachable) AND (b)
    /// self's watermark wins the highest-watermark contest with the deterministic lowest-NodeId
    /// tie-break. Any unreachable peer, or a peer with a strictly higher watermark, or a tie lost to a
    /// lower NodeId, leaves self SYNCING.
    private Promise<Long> decidePromotion(String streamName,
                                          int partition,
                                          List<NodeId> peers,
                                          long selfWm,
                                          List<Result<Long>> results) {
        if (results.stream().anyMatch(Result::isFailure)) {
            log.warn("Backfill {}[{}]: cold-start self-promotion BLOCKED — a co-replica is unreachable "
                    + "(self watermark {}, peers {}) — staying SYNCING to avoid serving stale state",
                     streamName,
                     partition,
                     selfWm,
                     peers);

            return UNREACHABLE_REPLICA_BLOCKS_PROMOTION.promise();
        }

        var maxPeerWatermark = results.stream().mapToLong(result -> result.or(-1L)).max().orElse(-1L);

        if (selfWm < maxPeerWatermark || losesTieBreak(peers, selfWm, results, maxPeerWatermark)) {
            log.warn("Backfill {}[{}]: cold-start self-promotion declined — self watermark {} does not win "
                    + "(max reachable peer watermark {}, peers {}) — staying SYNCING",
                     streamName,
                     partition,
                     selfWm,
                     maxPeerWatermark,
                     peers);

            return NOT_HIGHEST_WATERMARK.promise();
        }

        log.warn("Backfill {}[{}]: BREAKING cold-start deadlock — self-promoting to CAUGHT_UP at watermark {} "
                + "(all {} co-replicas reachable, max peer watermark {}) [operator bootstrap decision]",
                 streamName,
                 partition,
                 selfWm,
                 peers.size(),
                 maxPeerWatermark);
        registry.updateWatermark(streamName, partition, self, selfWm);
        firstNoSourceMs.remove(partitionKey(streamName, partition));

        return Promise.success(0L);
    }

    /// On an exact tie at the max watermark, exactly ONE replica may promote: the one with the lowest
    /// NodeId across {self} ∪ {peers that are tied at the max}. Self loses the tie-break iff some tied
    /// peer has a strictly lower NodeId than self.
    private boolean losesTieBreak(List<NodeId> peers, long selfWm, List<Result<Long>> results, long maxPeerWatermark) {
        if (selfWm > maxPeerWatermark) {
            return false;
        }

        for (var i = 0; i < peers.size(); i++) {
            var peerWatermark = results.get(i).or(-1L);

            if (peerWatermark == selfWm && peers.get(i).compareTo(self) < 0) {
                return true;
            }
        }

        return false;
    }

    private List<NodeId> peerNodeIds(List<ReplicaDescriptor> replicas) {
        return replicas.stream()
                       .map(ReplicaDescriptor::nodeId)
                       .filter(nodeId -> !nodeId.equals(self))
                       .sorted()
                       .toList();
    }

    private long selfConfirmedOffset(List<ReplicaDescriptor> replicas) {
        return replicas.stream()
                       .filter(descriptor -> descriptor.nodeId()
                                                       .equals(self))
                       .mapToLong(ReplicaDescriptor::confirmedOffset)
                       .max()
                       .orElse(-1L);
    }

    private Option<ReplicaDescriptor> selectSource(List<ReplicaDescriptor> replicas) {
        return Option.from(replicas.stream()
                                   .filter(descriptor -> !descriptor.nodeId()
                                                                    .equals(self))
                                   .filter(descriptor -> descriptor.state() == ReplicationState.CAUGHT_UP)
                                   .max(Comparator.comparingLong(ReplicaDescriptor::confirmedOffset)));
    }
}
