// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.stream.replication.BackfillError.General.INCOMPLETE_BACKFILL;
import static org.pragmatica.aether.stream.replication.BackfillError.General.MALFORMED_RESPONSE;
import static org.pragmatica.aether.stream.replication.BackfillError.General.NOT_HIGHEST_WATERMARK;
import static org.pragmatica.aether.stream.replication.BackfillError.General.NO_SOURCE_REPLICA;
import static org.pragmatica.aether.stream.replication.BackfillError.General.UNREACHABLE_REPLICA_BLOCKS_PROMOTION;
import static org.pragmatica.aether.stream.replication.PartitionKey.partitionKey;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupRequest.catchupRequest;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;


/// A4 backfill orchestrator. When a node newly becomes a replica for a non-empty `(stream, partition)`
/// it must pull the partition's existing events from an up-to-date source and reach `CAUGHT_UP`
/// before it is eligible to serve reads.
///
/// ## Source selection (#333)
/// The backfill source is the DETERMINISTIC HRW owner of the partition (computed locally from the
/// member view — every node agrees), NOT the local registry's `CAUGHT_UP` set. Peer `CAUGHT_UP`/
/// watermark state is never propagated cross-node (the production {@link ReplicaRegistry} uses
/// {@link WatermarkStore#NOOP}), so a registry-selected source can report a stale/behind watermark and
/// drive a false `CAUGHT_UP`-with-a-hole. The owner holds the complete partition history, so its
/// reported tail is the true watermark — the same owner-forward principle the read path uses
/// ({@link org.pragmatica.aether.stream.ForwardingReadRouter}). The one exception (#445) is an EMPTY owner
/// read: during owner failover a freshly-provisioned/re-elected HRW owner can hold an empty ring while the
/// acked history survives on a replica, so an empty catch-up response is NOT trusted as the owner's true
/// tail — it routes to the probe-gated no-source path instead of promoting a false `CAUGHT_UP@-1`. The
/// owner itself self-promotes at its
/// local watermark (it is authoritative for its own data). Only when the owner is unknown (empty member
/// view / bootstrap window) does it fall back to the registry `CAUGHT_UP` source with the highest
/// `confirmedOffset`, then the cold-start deadlock-break.
///
/// ## Cold-start deadlock break (owner-immediate, else bounded-wait then data-safe self-promote)
/// After a SIMULTANEOUS full-cluster restart EVERY replica is `SYNCING`, so no caught-up source can
/// ever exist and each replica waits forever for one — a cluster-wide deadlock that prevents the node
/// from ever reaching lifecycle READY. To break it WITHOUT serving stale state:
///   0. the HRW OWNER of the partition ({@link ReplicaPlacement#rank} index 0 over the current core
///      members) is authoritative for its own data, so when self IS the owner it self-promotes
///      IMMEDIATELY at its local watermark — it never waits on a peer source, because no peer can be a
///      more authoritative source than the owner itself. This collapses the cold-start latency for the
///      single-partition system streams (e.g. `system:cluster-events`) where the owner staying SYNCING
///      until the bound elapses is the visible `GET /api/events → 200 []` deadlock,
///   1. for a NON-owner replica the FIRST no-source observation for a partition is timestamped; while
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
    private final ReplicationTransport replicationTransport;
    private final ReplicaWatermarkProbe probe;
    private final SelfWatermark selfWatermark;
    private final NodeId self;
    private final TimeSpan sourceWaitBound;

    private final LongSupplier clock;

    /// Current core members, used ONLY by the owner-immediate cold-start path to compute the HRW owner
    /// ({@link ReplicaPlacement#rank} index 0). Defaults to {@link List#of()} in the backward-compat
    /// factory, where an empty member view makes the owner check always false — so that orchestrator is
    /// byte-identical to the original (no owner-immediate promotion).
    private final Supplier<List<NodeId>> membersSupplier;

    /// #491 F4: the COMMITTED `StreamPartitionOwnershipValue.owner` source. Gates HRW self-election
    /// ({@link #isSelfOwner}) so a node with a diverged/empty ring that HRW-ranks ITSELF owner does NOT
    /// self-promote while a DIFFERENT node is the committed owner (the m1 empty-ring self-election loop).
    /// PERMISSIVE: {@link Option#none} (no record committed yet — cold start / legacy / unowned) preserves
    /// the pure-HRW behavior, keeping the cold-start deadlock-break intact; only a committed owner naming
    /// ANOTHER node blocks self-election. Strict equality would starve a just-promoted owner by one commit.
    /// Defaults to {@link CommittedStreamOwnerSource#none} in the legacy/test factories (behavior unchanged).
    private final CommittedStreamOwnerSource committedOwnerSource;

    /// First wall-clock instant (ms) at which each partition was observed to have NO caught-up source.
    /// `backfill` is invoked one-shot and retried by the reconcile / on-gap seams, so the bounded wait
    /// must persist across calls — this map is that cross-call memory.
    private final ConcurrentHashMap<PartitionKey, Long> firstNoSourceMs = new ConcurrentHashMap<>();

    /// Per-partition `confirmedOffset` at which a CAUGHT_UP non-owner replica was last re-verified against
    /// the HRW owner (#333 write-idle residual). It quiesces {@link #redriveCandidates}: a stale CAUGHT_UP
    /// non-owner is re-included in the redrive only while its current `confirmedOffset` differs from the
    /// value recorded here, so the re-verify backfill (one owner pull) fires once per genuinely-new stale
    /// offset and becomes a no-op once the replica reaches the owner's true tail — never a per-tick probe.
    /// A failed re-verify leaves `confirmedOffset` unchanged AND unrecorded, so the next tick retries.
    private final ConcurrentHashMap<PartitionKey, Long> reverifiedAtOffset = new ConcurrentHashMap<>();

    /// Per-partition wall-clock instant (ms) of the last owner re-verify for a CAUGHT_UP non-owner replica
    /// (#333 write-idle residual). Sibling of {@link #firstNoSourceMs}: it quiesces the periodic re-verify so
    /// {@link #staleCaughtUpNonOwner} re-includes a genuinely-complete, offset-stable replica only once
    /// `sourceWaitBound` has elapsed since the last re-verify — never every tick. Stamped at BOTH the probe
    /// DISPATCH ({@link #reverifyFromOwner}, outcome-independent so a slow/failed probe still starts the next
    /// interval and the re-verify cannot spin) AND a successful pull-promotion ({@link #promote}) — a pull IS a
    /// re-verify by definition, so a freshly-promoted CAUGHT_UP replica is quiesced for a full interval. The
    /// null ⇒ elapsed default therefore fires only for rows that became CAUGHT_UP WITHOUT a pull (restart-
    /// loaded / cold-start self-promote), which earn one re-verify.
    private final ConcurrentHashMap<PartitionKey, Long> lastReverifyMs = new ConcurrentHashMap<>();

    private PartitionBackfill(ReplicaRegistry registry,
                              StreamPartitionRecovery partitionRecovery,
                              CatchupTransport transport,
                              ReplicationTransport replicationTransport,
                              ReplicaWatermarkProbe probe,
                              SelfWatermark selfWatermark,
                              NodeId self,
                              TimeSpan sourceWaitBound,
                              LongSupplier clock,
                              Supplier<List<NodeId>> membersSupplier,
                              CommittedStreamOwnerSource committedOwnerSource) {
        this.registry = registry;
        this.partitionRecovery = partitionRecovery;
        this.transport = transport;
        this.replicationTransport = replicationTransport;
        this.probe = probe;
        this.selfWatermark = selfWatermark;
        this.self = self;
        this.sourceWaitBound = sourceWaitBound;
        this.clock = clock;
        this.membersSupplier = membersSupplier;
        this.committedOwnerSource = committedOwnerSource;
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
                                     ReplicationTransport.NOOP,
                                     (_, _, _) -> NO_SOURCE_REPLICA.promise(),
                                     (_, _) -> - 1L,
                                     self,
                                     TimeSpan.timeSpan(Long.MAX_VALUE).nanos(),
                                     System::currentTimeMillis,
                                     List::of,
                                     CommittedStreamOwnerSource.none());
    }

    /// Cold-start-aware factory: after `sourceWaitBound` elapses with no caught-up source, the
    /// highest-watermark replica self-promotes (data-safe; see class doc). `probe` reports each peer's
    /// current watermark (success) or its unreachability (failure); `selfWatermark` reports self's local
    /// tail offset for the promotion contest. `membersSupplier` reports the current core members so the
    /// HRW OWNER can self-promote immediately (owner-immediate path) without waiting for a peer source.
    /// `replicationTransport` carries a completed NON-owner backfill's {@link ReplicationMessage.ReplicateAck}
    /// back to the current HRW owner (#336), so the owner's replicas-view shows the replica CAUGHT_UP at the
    /// backfilled tail before the next live write.
    public static PartitionBackfill partitionBackfill(ReplicaRegistry registry,
                                                      StreamPartitionRecovery partitionRecovery,
                                                      CatchupTransport transport,
                                                      ReplicationTransport replicationTransport,
                                                      ReplicaWatermarkProbe probe,
                                                      SelfWatermark selfWatermark,
                                                      NodeId self,
                                                      TimeSpan sourceWaitBound,
                                                      Supplier<List<NodeId>> membersSupplier,
                                                      CommittedStreamOwnerSource committedOwnerSource) {
        return new PartitionBackfill(registry,
                                     partitionRecovery,
                                     transport,
                                     replicationTransport,
                                     probe,
                                     selfWatermark,
                                     self,
                                     sourceWaitBound,
                                     System::currentTimeMillis,
                                     membersSupplier,
                                     committedOwnerSource);
    }

    /// Test factory: injects a deterministic clock so the bounded wait can be exercised without sleeping.
    /// Defaults the member view to {@link List#of()} so existing tests exercise the non-owner bounded-wait
    /// path unchanged (empty members ⇒ owner check is false).
    static PartitionBackfill partitionBackfill(ReplicaRegistry registry,
                                               StreamPartitionRecovery partitionRecovery,
                                               CatchupTransport transport,
                                               ReplicaWatermarkProbe probe,
                                               SelfWatermark selfWatermark,
                                               NodeId self,
                                               TimeSpan sourceWaitBound,
                                               LongSupplier clock) {
        return partitionBackfill(registry,
                                 partitionRecovery,
                                 transport,
                                 probe,
                                 selfWatermark,
                                 self,
                                 sourceWaitBound,
                                 clock,
                                 List::of);
    }

    /// Test factory with an explicit member view, for exercising the owner-immediate cold-start path
    /// with a deterministic clock. Uses a no-op replication transport (the backfill-completion ack to the
    /// owner is exercised through the production factory / a capturing transport).
    static PartitionBackfill partitionBackfill(ReplicaRegistry registry,
                                               StreamPartitionRecovery partitionRecovery,
                                               CatchupTransport transport,
                                               ReplicaWatermarkProbe probe,
                                               SelfWatermark selfWatermark,
                                               NodeId self,
                                               TimeSpan sourceWaitBound,
                                               LongSupplier clock,
                                               Supplier<List<NodeId>> membersSupplier) {
        return partitionBackfill(registry,
                                 partitionRecovery,
                                 transport,
                                 probe,
                                 selfWatermark,
                                 self,
                                 sourceWaitBound,
                                 clock,
                                 membersSupplier,
                                 CommittedStreamOwnerSource.none());
    }

    /// Test factory with an explicit committed-owner source, for the #491 F4 divergence-guard repro (HRW
    /// ranks self owner while the committed record names ANOTHER node — self must NOT self-promote). Uses a
    /// no-op replication transport like the sibling member-view factory.
    static PartitionBackfill partitionBackfill(ReplicaRegistry registry,
                                               StreamPartitionRecovery partitionRecovery,
                                               CatchupTransport transport,
                                               ReplicaWatermarkProbe probe,
                                               SelfWatermark selfWatermark,
                                               NodeId self,
                                               TimeSpan sourceWaitBound,
                                               LongSupplier clock,
                                               Supplier<List<NodeId>> membersSupplier,
                                               CommittedStreamOwnerSource committedOwnerSource) {
        return new PartitionBackfill(registry,
                                     partitionRecovery,
                                     transport,
                                     ReplicationTransport.NOOP,
                                     probe,
                                     selfWatermark,
                                     self,
                                     sourceWaitBound,
                                     clock,
                                     membersSupplier,
                                     committedOwnerSource);
    }

    /// Backfill `(streamName, partition)` onto self. Resolves with the number of events applied on
    /// success; fails (leaving self SYNCING) when no source is available or the source/apply path fails.
    ///
    /// ## Source precedence (#333)
    ///   1. self IS the HRW owner → owner-immediate self-promote at the local watermark (authoritative),
    ///   2. self is a NON-owner and the HRW owner is known → pull catch-up from the DETERMINISTIC HRW
    ///      OWNER (computed locally from the member view), NOT the blind registry `CAUGHT_UP` set. Peer
    ///      `CAUGHT_UP`/watermark state is never propagated cross-node (the production registry uses
    ///      {@link WatermarkStore#NOOP}), so a registry-selected source can report a stale/behind
    ///      watermark and drive a FALSE `CAUGHT_UP`-with-a-hole, after which every live batch is rejected
    ///      as a gap forever. The owner holds the complete partition history, so its reported tail
    ///      ({@link ReplicationMessage.CatchupResponse#toOffset}) is the TRUE watermark — this mirrors the
    ///      read path's owner-forward ({@link org.pragmatica.aether.stream.ForwardingReadRouter}). The one
    ///      exception is an EMPTY owner read: during owner failover a fresh/re-elected HRW owner can hold an
    ///      empty ring while the acked history survives on a replica (#445), so an empty catch-up response
    ///      is NOT trusted as the true tail — it falls back to the probe-gated no-source path rather than a
    ///      false `CAUGHT_UP@-1`,
    ///   3. owner unknown (bootstrap window, empty member view) → the registry `CAUGHT_UP` source / the
    ///      cold-start deadlock-break path, unchanged.
    public Promise<Long> backfill(String streamName, int partition) {
        var replicas = registry.replicasFor(streamName, partition);

        if (isSelfOwner(streamName, partition)) {
            return promoteOwner(streamName, partition, replicas);
        }

        return hrwOwner(streamName, partition).filter(owner -> !owner.equals(self))
                       .fold(() -> backfillViaRegistryOrColdStart(streamName, partition, replicas),
                             owner -> backfillOrReverify(streamName, partition, owner, replicas));
    }

    /// Dispatch a NON-owner replica with a known HRW owner on self's current replication state (#333
    /// write-idle residual). A SYNCING replica takes the direct {@link #backfillFromOwner} pull — it has a
    /// real hole to fill. An already-CAUGHT_UP replica takes the cheaper probe-first {@link #reverifyFromOwner}:
    /// it re-checks the owner's head and pulls ONLY when the owner is genuinely ahead, so a periodic
    /// re-verify of a complete replica costs one probe, not a full catch-up request.
    private Promise<Long> backfillOrReverify(String streamName,
                                             int partition,
                                             NodeId owner,
                                             List<ReplicaDescriptor> replicas) {
        return selfIsCaughtUp(replicas)
               ? reverifyFromOwner(streamName, partition, owner, replicas)
               : backfillFromOwner(streamName, partition, owner, replicas);
    }

    /// True when self's registered replica descriptor for `(streamName, partition)` is CAUGHT_UP. A non-owner
    /// CAUGHT_UP replica is the #333 write-idle residual (the probe-first re-verify path); a SYNCING or absent
    /// self descriptor has a genuine hole and takes the direct {@link #backfillFromOwner} pull.
    private boolean selfIsCaughtUp(List<ReplicaDescriptor> replicas) {
        return replicas.stream()
                       .filter(descriptor -> descriptor.nodeId()
                                                       .equals(self))
                       .anyMatch(descriptor -> descriptor.state() == ReplicationState.CAUGHT_UP);
    }

    /// Partitions the periodic re-drive ({@code AetherNode.redriveIncompleteBackfills}) should re-attempt
    /// `backfill` for: every partition where self is a registered, not-yet-CAUGHT_UP replica, PLUS the
    /// #333 write-idle residual case — a CAUGHT_UP replica that is NOT the HRW owner and whose
    /// `confirmedOffset` has moved since, or which has not been re-verified against the owner within the
    /// re-verify interval. The plain registry filter (`state != CAUGHT_UP`) can never catch the residual
    /// case: a node that self-promoted to CAUGHT_UP@0 under an empty/partial member view (cold-start
    /// owner-self-promote) may turn out to be a NON-owner once the member view populates, holding none of the
    /// real owner's history; on a write-idle partition nothing re-arms the gap loop, so it would serve
    /// stale/empty data forever. Re-arming `backfill` routes a CAUGHT_UP non-owner through the probe-first
    /// {@link #reverifyFromOwner}, which pulls the missing suffix and promotes only at the owner's true tail
    /// when the owner is ahead. The re-include is owner-aware (HRW computed locally from the member view) and
    /// offset- and interval-quiesced (see {@link #reverifiedAtOffset} and {@link #lastReverifyMs}), so it
    /// costs at most one owner probe per interval and never a per-tick probe once the replica is genuinely
    /// complete.
    public List<PartitionKey> redriveCandidates() {
        return registry.incompletePartitionsFor(self, this::caughtUpNeedsCatchup);
    }

    /// A (prematurely) CAUGHT_UP self descriptor that still needs a re-drive before it is genuinely
    /// complete, dispatched on self's role:
    ///   - self IS the HRW owner and its row needs a re-drive ({@link #ownerRowNeedsRedrive}): a surviving
    ///     replica holds a HIGHER `confirmedOffset` — a promoted owner that is BEHIND (the #336 lossless-
    ///     failover case) — OR the owner's OWN registry `confirmedOffset` lags its authoritative ring tail
    ///     (the #336 post-failover replicas-view staleness). Either way the redrive routes through
    ///     {@link #promoteOwner} and reseats the owner row, even though it is already CAUGHT_UP.
    ///   - self is NOT the HRW owner — the #333 write-idle residual ({@link #staleCaughtUpNonOwner}).
    /// The genuine single-node / already-covered owner-self-promote stays CAUGHT_UP untouched (no survivor
    /// is ahead and the row already equals the ring tail ⇒ no re-drive).
    private boolean caughtUpNeedsCatchup(ReplicaDescriptor descriptor) {
        return isSelfOwner(descriptor.streamName(), descriptor.partition())
               ? ownerRowNeedsRedrive(descriptor)
               : staleCaughtUpNonOwner(descriptor);
    }

    /// Owner-branch redrive predicate (#336): re-drive a CAUGHT_UP owner row when EITHER a surviving
    /// replica is strictly ahead ({@link #ownerBehindSurvivor} — the lossless-failover catch-up) OR the
    /// owner's own registry `confirmedOffset` lags its authoritative ring tail
    /// ({@link #ownerRegistryBelowRingTail} — the post-failover replicas-view reseat). The scheduled
    /// redrive then routes through {@link #promoteOwner}, which self-promotes at the ring tail via
    /// {@link #ownerSelfPromote}, so the operator replicas-view reports the true watermark without a live
    /// write. Idempotent: once the row equals the ring tail and no survivor is ahead it is not a candidate.
    private boolean ownerRowNeedsRedrive(ReplicaDescriptor descriptor) {
        return ownerBehindSurvivor(descriptor) || ownerRegistryBelowRingTail(descriptor);
    }

    /// True when self is the HRW owner but a surviving non-self replica holds a strictly higher
    /// `confirmedOffset` than self's — a promoted owner that must catch up before becoming authoritative
    /// (#336). Compared against self's registry `confirmedOffset` (the owner's contiguous watermark), so
    /// the redrive decision needs no local-ring read.
    private boolean ownerBehindSurvivor(ReplicaDescriptor descriptor) {
        return aheadSurvivor(registry.replicasFor(descriptor.streamName(), descriptor.partition()),
                             descriptor.confirmedOffset()).isPresent();
    }

    /// True when self is the HRW owner and its OWN registry `confirmedOffset` trails its authoritative
    /// ring tail (`selfWatermark.localWatermark`) — the #336 post-promotion self-watermark staleness. A
    /// replica→owner promotion never re-runs {@link #ownerSelfPromote}, and the live replication path acks
    /// only peers (never the owner's own row), so the promoted owner's row stays frozen at its last
    /// backfill value while its ring tail is correct. Keyed off the RING tail (authoritative), never a peer
    /// watermark, so the reseat converges to real, locally-held data.
    private boolean ownerRegistryBelowRingTail(ReplicaDescriptor descriptor) {
        return descriptor.confirmedOffset() < selfWatermark.localWatermark(descriptor.streamName(),
                                                                           descriptor.partition());
    }

    /// True when `descriptor` (self's CAUGHT_UP descriptor) needs an owner re-verify: self is NOT the HRW
    /// owner of the partition AND EITHER its `confirmedOffset` has moved since the last re-verify
    /// ({@link #offsetMoved}) OR the re-verify interval has elapsed ({@link #reverifyIntervalElapsed}). The
    /// owner check is skipped (no re-arm) when self IS the HRW owner — the genuine single-node /
    /// owner-self-promote case stays CAUGHT_UP untouched. The interval arm periodically re-checks an
    /// offset-stable replica against the current owner (the #445 owner-divergence residual on a write-idle
    /// partition), while the {@link #lastReverifyMs} stamp (at both probe dispatch and a successful pull)
    /// quiesces a genuinely-complete replica to at most one re-verify per interval — never a per-tick probe.
    private boolean staleCaughtUpNonOwner(ReplicaDescriptor descriptor) {
        if (isSelfOwner(descriptor.streamName(), descriptor.partition())) {
            return false;
        }

        return offsetMoved(descriptor) || reverifyIntervalElapsed(descriptor);
    }

    /// True when self's CAUGHT_UP `confirmedOffset` differs from the offset last re-verified against the HRW
    /// owner ({@link #reverifiedAtOffset}) — the replica moved (a live ack, or a promotion not yet re-verified)
    /// since the last owner re-verify. Absent bookkeeping ({@link Option#none}) reads as moved (`.or(true)`)
    /// so a never-verified CAUGHT_UP replica is re-verified at least once.
    private boolean offsetMoved(ReplicaDescriptor descriptor) {
        return Option.option(reverifiedAtOffset.get(partitionKey(descriptor.streamName(),
                                                                 descriptor.partition())))
                     .map(recorded -> recorded != descriptor.confirmedOffset())
                     .or(true);
    }

    /// True when at least `sourceWaitBound` has elapsed since the last owner re-verify probe for this
    /// partition ({@link #lastReverifyMs}), so an offset-stable CAUGHT_UP non-owner is periodically re-checked
    /// against the current HRW owner — the #445 owner-divergence residual on a WRITE-IDLE partition, where no
    /// live ack re-arms the gap loop. Absent bookkeeping ({@link Option#none}) reads as elapsed (null ⇒
    /// elapsed) so the FIRST re-verify is never gated.
    private boolean reverifyIntervalElapsed(ReplicaDescriptor descriptor) {
        return Option.option(lastReverifyMs.get(partitionKey(descriptor.streamName(),
                                                             descriptor.partition())))
                     .map(last -> clock.getAsLong() - last >= sourceWaitBound.millis())
                     .or(true);
    }

    /// Owner unknown (bootstrap) or no authoritative owner source: fall back to the registry `CAUGHT_UP`
    /// source, else the cold-start no-source path. This is the pre-#333 source-selection behavior.
    private Promise<Long> backfillViaRegistryOrColdStart(String streamName,
                                                         int partition,
                                                         List<ReplicaDescriptor> replicas) {
        return selectSource(replicas).fold(() -> handleNoSource(streamName, partition, replicas),
                                           source -> backfillFromCaughtUpSource(streamName, partition, replicas, source));
    }

    /// Probe-first owner re-verify for a CAUGHT_UP non-owner replica (#333 write-idle residual). Stamps
    /// {@link #lastReverifyMs} AT DISPATCH (outcome-independent, so a slow/failed probe still starts the next
    /// interval and the re-verify cannot spin) and probes the CURRENT HRW owner's head via
    /// {@link ReplicaWatermarkProbe}. When the owner is genuinely ahead (`ownerHead > selfConfirmed`) it pulls
    /// the missing suffix through the usual {@link #backfillFromOwner} gate (apply → promote → re-quiesce
    /// {@link #reverifiedAtOffset}). When the owner is NOT ahead (`ownerHead <= selfConfirmed`) it is a
    /// no-op apart from the idempotent completion re-ack (#499, see {@link #reverifyNoOp}) — no catch-up
    /// request, no {@link #handleNoSource}, no {@link #firstNoSourceMs} touch — so an
    /// already-complete replica is never demoted and the periodic re-verify stays a single cheap probe.
    private Promise<Long> reverifyFromOwner(String streamName,
                                            int partition,
                                            NodeId owner,
                                            List<ReplicaDescriptor> replicas) {
        lastReverifyMs.put(partitionKey(streamName, partition), clock.getAsLong());
        var selfConfirmed = selfConfirmedOffset(replicas);

        return probe.probe(owner, streamName, partition)
                    .flatMap(ownerHead -> decideReverify(streamName,
                                                         partition,
                                                         owner,
                                                         replicas,
                                                         selfConfirmed,
                                                         ownerHead));
    }

    /// Route the probed owner head: pull via {@link #backfillFromOwner} when the owner is strictly ahead, else
    /// the {@link #reverifyNoOp} pure no-op (owner not ahead ⇒ self already complete).
    private Promise<Long> decideReverify(String streamName,
                                         int partition,
                                         NodeId owner,
                                         List<ReplicaDescriptor> replicas,
                                         long selfConfirmed,
                                         long ownerHead) {
        return ownerHead > selfConfirmed
               ? backfillFromOwner(streamName, partition, owner, replicas)
               : reverifyNoOp(streamName, partition, selfConfirmed, ownerHead);
    }

    /// No-op arm of the probe-first re-verify: the HRW owner's head is not ahead of self, so the CAUGHT_UP
    /// replica is already complete. Nothing is pulled and self stays CAUGHT_UP. Stamps {@link #reverifiedAtOffset}
    /// at `selfConfirmed` (alongside the dispatch-time {@link #lastReverifyMs}): a replica that reached CAUGHT_UP
    /// with NO prior pull (cold-start self-promote) has no {@link #reverifiedAtOffset} record, so WITHOUT this
    /// stamp {@link #offsetMoved} reads the absent record as moved (`.or(true)`) forever and
    /// {@link #staleCaughtUpNonOwner} would redrive an owner probe EVERY tick. With the stamp `offsetMoved` reads
    /// false for the offset-stable replica, so the re-verify quiesces to at most once per interval
    /// ({@link #reverifyIntervalElapsed}) — honoring the "never a per-tick probe once genuinely complete" contract.
    ///
    /// #499: additionally RE-SENDS the idempotent backfill-completion ack ({@link #ackBackfillToOwner}).
    /// The #336 completion ack is otherwise fired exactly ONCE, inside {@link #promote} — and a replica
    /// that promotes while its member view is still populating (a fresh replacement backfills within
    /// milliseconds of its transport Hello) resolves {@link #hrwOwner} to none and silently skips it.
    /// With no live writes on the partition nothing else ever advances the owner's row, so the owner's
    /// replicas-view stays SYNCING@-1 forever over a fully-replicated partition and RF-restoration never
    /// reads as converged. Re-acking here is quiesced to once per re-verify interval, resolves the
    /// CURRENT (now-populated) owner, and is idempotent on the owner (`handleAck` re-applying the same
    /// tail is a no-op) — a lost one-shot ack self-heals instead of freezing the operator view.
    private Promise<Long> reverifyNoOp(String streamName, int partition, long selfConfirmed, long ownerHead) {
        log.debug("Reverify {}[{}]: HRW owner head {} not ahead of self {} — already complete, no-op",
                  streamName,
                  partition,
                  ownerHead,
                  selfConfirmed);
        reverifiedAtOffset.put(partitionKey(streamName, partition), selfConfirmed);
        ackBackfillToOwner(streamName, partition, selfConfirmed);

        return Promise.success(0L);
    }

    /// Non-owner catch-up from the authoritative HRW owner (#333). `fromOffset` is the LOCAL ring head + 1
    /// ({@link SelfWatermark#localWatermark} + 1) so the owner-frame offsets land CONTIGUOUSLY in the
    /// local ring — {@link StreamPartitionRecovery#appendRecoveredEvent} assigns sequential offsets, so a
    /// `fromOffset` below the local head would shift every subsequent offset. The promotion watermark is
    /// the owner's true tail (`response.toOffset()`); no local descriptor watermark is held for the owner,
    /// so `sourceConfirmedOffset` is `-1` (it must not inflate the watermark past what the owner returned).
    ///
    /// The owner's completeness assumption (#333) HOLDS only when the owner actually returned history. A
    /// freshly-provisioned/re-elected HRW owner can hold an EMPTY ring during failover while the acked
    /// history lives on a surviving replica (#445 run-13), so an EMPTY response is NOT proof the partition is
    /// empty and must not promote at the owner's `-1` tail. The response is dispatched by
    /// {@link #applyOwnerResponse}: empty routes to the probe-gated no-source path; non-empty is genuine
    /// owner history and promotes as before.
    private Promise<Long> backfillFromOwner(String streamName,
                                            int partition,
                                            NodeId owner,
                                            List<ReplicaDescriptor> replicas) {
        var fromOffset = selfWatermark.localWatermark(streamName, partition) + 1;
        var request = catchupRequest(owner, streamName, partition, fromOffset);

        log.debug("Backfill {}[{}]: owner source={} from offset {} [authoritative HRW owner]",
                  streamName,
                  partition,
                  owner,
                  fromOffset);

        return transport.requestCatchup(owner, request)
                        .flatMap(response -> applyOwnerResponse(streamName,
                                                                partition,
                                                                owner,
                                                                fromOffset - 1,
                                                                replicas,
                                                                response));
    }

    /// Dispatch the owner's catch-up response (#445). An EMPTY response (`payloads().isEmpty()` — the robust
    /// predicate, independent of `toOffset` arithmetic) is NOT trusted as the owner's true tail: during
    /// failover a fresh/re-elected HRW owner has an empty ring while the acked history survives on a replica,
    /// so promoting off it would false-flip `CAUGHT_UP@-1` and truncate a stream whose watermark lives on
    /// that survivor. An empty response therefore takes the SAME probe-gated no-source path a cold-start
    /// non-owner uses ({@link #handleNoSource} → {@link #waitThenPromote}): the bounded wait, then a
    /// self-promote ONLY when every peer is reachable and none is probed strictly ahead of self — so a
    /// survivor still ahead keeps self SYNCING (no truncation) while a genuinely-empty partition still
    /// promotes at its watermark. A NON-empty response is genuine owner history: clear the cold-start wait
    /// memory (a data-bearing authoritative source exists, so a later transient no-source observation re-arms
    /// the bound from scratch) and promote via the usual {@link #applyAndPromote} watermark gate.
    ///
    /// #559: an empty response is first disambiguated by {@link #promoteIfAtOwnerTail}. "The owner is
    /// empty" and "I already hold everything the owner holds" are indistinguishable in the response
    /// itself, and conflating them sent a fully caught-up replica into the cold-start contest forever.
    /// Gated on `selfConfirmed >= 0`: a replica holding NOTHING cannot be at a non-empty owner's tail,
    /// so the #445 empty-owner path keeps its exact previous behaviour and costs no extra probe.
    private Promise<Long> applyOwnerResponse(String streamName,
                                             int partition,
                                             NodeId owner,
                                             long selfConfirmed,
                                             List<ReplicaDescriptor> replicas,
                                             ReplicationMessage.CatchupResponse response) {
        if (response.payloads().isEmpty()) {
            return selfConfirmed >= 0
                   ? promoteIfAtOwnerTail(streamName, partition, owner, selfConfirmed, replicas)
                   : handleNoSource(streamName, partition, replicas);
        }

        firstNoSourceMs.remove(partitionKey(streamName, partition));

        return applyAndPromote(streamName, partition, -1L, response);
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
                        .flatMap(response -> applyAndPromote(streamName,
                                                             partition,
                                                             source.confirmedOffset(),
                                                             response));
    }

    private Promise<Long> applyAndPromote(String streamName,
                                          int partition,
                                          long sourceConfirmedOffset,
                                          ReplicationMessage.CatchupResponse response) {
        var fromOffset = response.fromOffset();
        var watermark = Math.max(response.toOffset(), sourceConfirmedOffset);

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
    /// rather than declare a false-ready CAUGHT_UP with holes (B2). A backfill from the HRW owner never
    /// reaches here with an EMPTY response — {@link #applyOwnerResponse} intercepts an empty owner read and
    /// routes it to the probe-gated no-source path (#445) rather than through this offset gate. On a genuine
    /// promotion a NON-owner acks the current HRW owner ({@link #ackBackfillToOwner}) so the owner's
    /// replicas-view reflects the completed backfill before the next live write (#336).
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
        ackBackfillToOwner(streamName, partition, watermark);
        reverifiedAtOffset.put(partitionKey(streamName, partition), watermark);
        // A successful owner/source pull IS a re-verify by definition: the replica now holds the source's
        // history up to `watermark`. Stamp the re-verify clock so a freshly-promoted CAUGHT_UP non-owner is
        // quiesced for a full interval rather than probed again on the very next redrive tick. The null ⇒
        // elapsed rule then applies only to rows that became CAUGHT_UP WITHOUT a pull (restart-loaded /
        // cold-start self-promote), which still earn one re-verify.
        lastReverifyMs.put(partitionKey(streamName, partition), clock.getAsLong());
        firstNoSourceMs.remove(partitionKey(streamName, partition));
        log.info("Backfill {}[{}] complete: applied {} events, self CAUGHT_UP at offset {}",
                 streamName,
                 partition,
                 applied,
                 watermark);

        return Promise.success(applied);
    }

    /// Ack a completed NON-owner backfill back to the CURRENT HRW owner so the owner's replicas-view shows
    /// this replica CAUGHT_UP at the backfilled tail BEFORE (and without waiting for) the next live write
    /// (#336). Reuses the live {@link ReplicationMessage.ReplicateAck} path — the SAME message
    /// {@link ReplicationReceiveHandler} sends per replicated batch — so no new message type is introduced
    /// and the owner's existing {@link DefaultReplicationManager#handleAck} advances the descriptor. When
    /// self IS the owner the resolved owner is self and the `filter` drops it (the owner reseats its own
    /// row via {@link #ownerSelfPromote}); when the owner is unknown (empty member view / bootstrap window)
    /// nothing is sent. Idempotent under redrive: a re-completed backfill re-acks the same tail, a no-op on
    /// the owner.
    private void ackBackfillToOwner(String streamName, int partition, long watermark) {
        hrwOwner(streamName, partition).filter(owner -> !owner.equals(self))
                .onPresent(owner -> replicationTransport.send(owner,
                                                              replicateAck(self, streamName, partition, watermark)));
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

    /// No caught-up source exists. The HRW owner is authoritative for its own data, so it self-promotes
    /// IMMEDIATELY (owner-immediate path) rather than waiting on a peer that cannot be a more
    /// authoritative source. A non-owner stays SYNCING until the bounded wait elapses; once it does, it
    /// attempts a data-safe cold-start self-promotion (see class doc).
    private Promise<Long> handleNoSource(String streamName, int partition, List<ReplicaDescriptor> replicas) {
        if (isSelfOwner(streamName, partition)) {
            return ownerSelfPromote(streamName, partition);
        }

        return waitThenPromote(streamName, partition, replicas);
    }

    /// True when self is the HRW owner of `(streamName, partition)` under the current member view AND the
    /// committed ownership record does not name a DIFFERENT node (#491 F4). `ReplicaPlacement.rank` is
    /// owner-first (index 0 is the owner); an empty member view (e.g. the backward-compat factory's
    /// `List::of`) yields no owner and reports false. The committed-owner gate ({@link #committedOwnerIsOther})
    /// is PERMISSIVE — with no committed record (cold start / legacy) it is a no-op, so pure-HRW behavior and
    /// the cold-start deadlock-break are preserved; only a divergent committed owner blocks self-election.
    private boolean isSelfOwner(String streamName, int partition) {
        return hrwOwner(streamName, partition).map(self::equals)
                       .or(false) && !committedOwnerIsOther(streamName, partition);
    }

    /// #491 F4: PERMISSIVE committed-ownership guard on HRW self-election. Returns true ONLY when a committed
    /// `StreamPartitionOwnershipValue.owner` names a node OTHER than self — the case where a diverged/empty
    /// local ring HRW-ranks self owner while the authoritative committed record names someone else (the m1
    /// self-election loop). {@link Option#none} (no record committed yet — cold start / legacy / unowned)
    /// returns FALSE so pure-HRW self-election is preserved and the cold-start deadlock-break stays intact.
    /// This is the INVERSE of `AetherNode.committedOwnerElsewhere`'s release semantics: that guard treats
    /// none() as releasable (`.or(true)`); this one treats none() as still-HRW-eligible (`.or(false)`).
    /// Strict equality (blocking whenever committed != self) would starve a just-promoted owner by one commit.
    private boolean committedOwnerIsOther(String streamName, int partition) {
        return committedOwnerSource.committedOwner(streamName, partition)
                                   .map(committed -> !committed.owner()
                                                               .equals(self))
                                   .or(false);
    }

    /// The deterministic HRW owner of `(streamName, partition)` under the current member view — index 0
    /// of {@link ReplicaPlacement#rank}, the SAME owner the read path forwards to and the publish path
    /// routes to. {@link Option#none()} when the member view is empty (backward-compat factory / bootstrap
    /// window before members are visible), where the registry / cold-start source path takes over.
    private Option<NodeId> hrwOwner(String streamName, int partition) {
        return Option.from(ReplicaPlacement.rank(streamName, partition, membersSupplier.get()).stream().findFirst());
    }

    /// Owner promotion is LOSSLESS (#336 phase-2). A freshly HRW-elected owner can be BEHIND a surviving
    /// replica when `replicas > minSyncReplicas`: different client-acked writes were confirmed by different
    /// peers, so the promoted owner's local watermark may trail the highest survivor. Self-promoting at the
    /// local watermark would then serve a SHORT log (silent data loss on failover — the empirically-observed
    /// ownerHeadOffset=5 while 20 were acked). The catch-up target is the MAX `confirmedOffset` among the
    /// SURVIVING non-self replicas — a CONTIGUOUS watermark, so with `minSync >= 2` the max-confirmed
    /// survivor holds every client-acked offset after a single owner loss. When self already covers that
    /// target it is authoritative immediately (the owner-immediate cold-start path, unchanged); otherwise it
    /// is held NON-authoritative (SYNCING — the existing read gate then forwards reads to the caught-up
    /// survivor rather than serving self's short log) while it pulls the missing suffix, becoming CAUGHT_UP
    /// only once it reaches the survivor's tail. HRW ownership stays DETERMINISTIC — no other node is elected;
    /// the HRW-elected owner is caught up before it is authoritative.
    ///
    /// When the local registry shows NO survivor ahead the answer may be a FALSE negative (peer watermarks
    /// are never propagated — {@link WatermarkStore#NOOP} — so a caught-up survivor reads as the `-1`
    /// registration default). A FRESH HRW owner (empty ring, `localWatermark == -1`) therefore falls back to
    /// {@link #probeThenPromoteOwner}, which probes the blind survivors' REAL tails before it may self-promote.
    private Promise<Long> promoteOwner(String streamName, int partition, List<ReplicaDescriptor> replicas) {
        var localWatermark = selfWatermark.localWatermark(streamName, partition);

        return aheadSurvivor(replicas, localWatermark).fold(() -> probeThenPromoteOwner(streamName,
                                                                                        partition,
                                                                                        replicas,
                                                                                        localWatermark),
                                                            survivor -> catchupOwnerFromSurvivor(streamName,
                                                                                                 partition,
                                                                                                 survivor,
                                                                                                 localWatermark));
    }

    /// The surviving NON-SELF replica holding the highest `confirmedOffset`, but only when it is STRICTLY
    /// ahead of `localWatermark` — the offset the promoted owner must catch up to before it may serve as
    /// owner. {@link Option#none()} when self already covers every survivor (authoritative immediately) or
    /// there is no survivor at all (cold-start owner-immediate). Taking the MAX across ALL survivors (not a
    /// single best-effort pick) is what makes the target the true watermark, since any one survivor may hold
    /// a different acked subset.
    private Option<ReplicaDescriptor> aheadSurvivor(List<ReplicaDescriptor> replicas, long localWatermark) {
        return Option.from(replicas.stream()
                                   .filter(descriptor -> !descriptor.nodeId()
                                                                    .equals(self))
                                   .max(Comparator.comparingLong(ReplicaDescriptor::confirmedOffset))).filter(survivor -> survivor.confirmedOffset() > localWatermark);
    }

    /// Hold self NON-authoritative (SYNCING at the local watermark, so the read path forwards to the
    /// caught-up survivor instead of serving self's short log) and pull the missing suffix
    /// (`localWatermark + 1 ..` survivor tail) FROM the max-confirmed survivor. Promotion to CAUGHT_UP is
    /// gated by {@link #applyAndPromote}/{@link #promote} — the SAME catch-up-then-promote gate the non-owner
    /// owner-source path uses — so self becomes authoritative only once the applied offset actually reaches
    /// the target. A catch-up failure (survivor transiently unreachable/silent) leaves self SYNCING and
    /// propagates so the redrive retries — never a bounded-wait degrade to the local watermark, which would
    /// truncate below the survivor's confirmed tail (#445).
    private Promise<Long> catchupOwnerFromSurvivor(String streamName,
                                                   int partition,
                                                   ReplicaDescriptor survivor,
                                                   long localWatermark) {
        return catchupOwnerFromSurvivor(streamName,
                                        partition,
                                        survivor.nodeId(),
                                        survivor.confirmedOffset(),
                                        localWatermark);
    }

    /// Core promoted-owner catch-up against an explicit `(survivorNode, survivorTail)`. The local-registry
    /// HIT path passes the survivor's locally-known descriptor; the peer-watermark-blind fallback
    /// ({@link #probeThenPromoteOwner}) passes a survivor whose REAL tail was learned by PROBE (the local
    /// registry reads it as the `-1` registration default because peer watermarks are never propagated —
    /// production {@link ReplicaRegistry} uses {@link WatermarkStore#NOOP}). Both hold self SYNCING at the
    /// local watermark and promote to CAUGHT_UP only once the applied offset reaches the target.
    ///
    /// The survivor here is CONFIRMED strictly ahead (registry-known or probed), so a FAILED catch-up must
    /// NOT degrade to a local-watermark self-promote — that would truncate the acked suffix
    /// (`localWatermark + 1 .. survivorTail`) the survivor holds (#445). The failure simply propagates: self
    /// stays SYNCING (already written above) and the redrive/backfill loop retries. A transiently-unavailable
    /// survivor answers on a later tick; a genuinely-dead survivor leaves the member view, after which
    /// {@link #promoteOwner} re-evaluates — {@link #aheadSurvivor}/the probe no longer see it ahead — and self
    /// self-promotes safely at the local watermark. The bounded-wait {@link #escapeOwnerCatchup} is reserved
    /// for the UNREACHABLE-peer branch of {@link #decideOwnerCatchup}, where NO peer was probed ahead.
    private Promise<Long> catchupOwnerFromSurvivor(String streamName,
                                                   int partition,
                                                   NodeId survivorNode,
                                                   long survivorTail,
                                                   long localWatermark) {
        registry.updateWatermark(streamName, partition, self, localWatermark, ReplicationState.SYNCING);
        var fromOffset = localWatermark + 1;
        var request = catchupRequest(survivorNode, streamName, partition, fromOffset);

        log.warn("Backfill {}[{}]: promoted owner BEHIND survivor {} (self watermark {}, target {}) — holding "
                + "non-authoritative and catching up before serving [#336 lossless failover]",
                 streamName,
                 partition,
                 survivorNode,
                 localWatermark,
                 survivorTail);

        return transport.requestCatchup(survivorNode, request)
                        .flatMap(response -> applyAndPromote(streamName, partition, survivorTail, response))
                        .onSuccess(_ -> recordSurvivorConfirmed(streamName, partition, survivorNode, survivorTail));
    }

    /// After a SUCCESSFUL owner catch-up the owner has just read `localWatermark + 1 .. survivorTail` FROM
    /// `survivorNode`, so it holds DIRECT EVIDENCE that `survivorNode` is confirmed at least to `survivorTail`
    /// — this is a TRUTHFUL operator-surface update (the owner literally pulled those offsets), NOT a
    /// fabrication. Reflecting it in the owner's registry row for the survivor (otherwise blind — peer
    /// watermarks are never propagated, {@link WatermarkStore#NOOP}) makes the replicas-view and any
    /// "a CAUGHT_UP non-owner exists" check see the survivor CAUGHT_UP@survivorTail immediately, without
    /// waiting for the survivor's next live ack or the #333 redrive. Fires ONLY on a successful pull
    /// ({@link Promise#onSuccess}) and only for the survivor actually pulled from, at the tail actually
    /// applied. Guarded MONOTONIC-UP: {@link ReplicaRegistry#updateWatermark} overwrites unconditionally, so
    /// it is invoked only when `survivorTail` exceeds the survivor's currently-recorded offset — a concurrent
    /// higher live ack is never clobbered down. On the local-registry HIT path this is a no-op (the survivor's
    /// row already carries `survivorTail`).
    private void recordSurvivorConfirmed(String streamName, int partition, NodeId survivorNode, long survivorTail) {
        if (survivorTail > recordedOffset(streamName, partition, survivorNode)) {
            registry.updateWatermark(streamName, partition, survivorNode, survivorTail);
        }
    }

    /// The survivor's currently-recorded `confirmedOffset` in the owner's registry, or `-1` when it has no
    /// row yet (blind). Used by {@link #recordSurvivorConfirmed} to keep the survivor-row update monotonic-up.
    private long recordedOffset(String streamName, int partition, NodeId nodeId) {
        return registry.replicasFor(streamName, partition)
                       .stream()
                       .filter(descriptor -> descriptor.nodeId()
                                                       .equals(nodeId))
                       .mapToLong(ReplicaDescriptor::confirmedOffset)
                       .findFirst()
                       .orElse(-1L);
    }

    /// Peer-watermark-blind fallback (#336): reached when the local-registry {@link #aheadSurvivor} finds NO
    /// survivor ahead, but the answer may be a FALSE negative — production {@link ReplicaRegistry} uses
    /// {@link WatermarkStore#NOOP}, so a caught-up survivor's real tail reads as the `-1` registration
    /// default. Without this, a FRESH HRW owner (empty ring, `localWatermark == -1`) would self-promote a
    /// false-ready `CAUGHT_UP@-1` on an empty ring while a survivor holds the whole partition — an effective
    /// RF=1 empty owner. Only survivors whose local watermark is UNKNOWN ({@link #blindPeers}, offset `< 0`)
    /// are probed for their REAL tail; a survivor with a known local offset was already weighed by
    /// {@link #aheadSurvivor}, so the local-registry HIT path is untouched. With no blind survivor there is
    /// genuinely nothing ahead → immediate self-promote. `probe` carries its own transport timeout (the same
    /// one the cold-start path uses), and the chain is async, so the redrive executor thread never blocks.
    private Promise<Long> probeThenPromoteOwner(String streamName,
                                                int partition,
                                                List<ReplicaDescriptor> replicas,
                                                long localWatermark) {
        var blind = blindPeers(replicas);

        if (blind.isEmpty()) {
            return ownerSelfPromote(streamName, partition);
        }

        return Promise.allOf(blind.stream().map(peer -> probe.probe(peer, streamName, partition)).toList()).flatMap(results -> decideOwnerCatchup(streamName,
                                                                                                                                                  partition,
                                                                                                                                                  blind,
                                                                                                                                                  localWatermark,
                                                                                                                                                  results));
    }

    /// The non-self survivors whose LOCAL watermark is unknown (registration default `-1`, i.e. blind under
    /// {@link WatermarkStore#NOOP}) — the only peers worth probing when {@link #aheadSurvivor} found none. A
    /// survivor with a known local offset was already weighed by {@link #aheadSurvivor} and is excluded, so
    /// no redundant probe is issued and the local-registry HIT path stays authoritative.
    private List<NodeId> blindPeers(List<ReplicaDescriptor> replicas) {
        return replicas.stream()
                       .filter(descriptor -> !descriptor.nodeId()
                                                        .equals(self))
                       .filter(descriptor -> descriptor.confirmedOffset() < 0)
                       .map(ReplicaDescriptor::nodeId)
                       .sorted()
                       .toList();
    }

    /// Decide the promoted owner's next move from the probed REAL tails of the blind survivors:
    ///   - the highest REACHABLE tail strictly exceeds `localWatermark` → catch up from that survivor (the
    ///     fresh-owner fix: pull the partition the local registry could not see),
    ///   - no reachable tail is ahead but some peer is UNREACHABLE → its watermark is unknown and might be
    ///     ahead, so route to the bounded-wait {@link #escapeOwnerCatchup} (never self-promote empty past a
    ///     possibly-ahead peer; never wedge — self stays SYNCING so the redrive retries, then degrades),
    ///   - every peer reachable and none ahead → genuinely nothing ahead → {@link #ownerSelfPromote}.
    private Promise<Long> decideOwnerCatchup(String streamName,
                                             int partition,
                                             List<NodeId> peers,
                                             long localWatermark,
                                             List<Result<Long>> results) {
        var bestTail = results.stream().mapToLong(result -> result.or(-1L)).max().orElse(-1L);

        if (bestTail > localWatermark) {
            return catchupOwnerFromSurvivor(streamName,
                                            partition,
                                            peerWithTail(peers, results, bestTail),
                                            bestTail,
                                            localWatermark);
        }

        if (results.stream().anyMatch(Result::isFailure)) {
            return escapeOwnerCatchup(streamName, partition, localWatermark, UNREACHABLE_REPLICA_BLOCKS_PROMOTION);
        }

        return ownerSelfPromote(streamName, partition);
    }

    /// The reachable peer whose probed tail equals `tail` (index-aligned with `results`, since
    /// {@link Promise#allOf} preserves order). `tail` is a strictly-positive reachable maximum here, so an
    /// unreachable peer's `-1` can never match; the final fallback is unreachable in practice.
    private NodeId peerWithTail(List<NodeId> peers, List<Result<Long>> results, long tail) {
        for (var i = 0; i < peers.size(); i++) {
            if (results.get(i).or(-1L) == tail) {
                return peers.get(i);
            }
        }

        return peers.getFirst();
    }

    /// Liveness escape for a promoted owner blocked ONLY by an UNREACHABLE blind peer — the
    /// {@link #decideOwnerCatchup} branch where no peer was probed strictly ahead of self, so its watermark
    /// is UNKNOWN rather than known-higher. Until {@link #sourceWaitBound} elapses self stays behind (the
    /// failure propagates and the redrive retries — the peer may become reachable). Once the bound elapses
    /// self promotes at its LOCAL watermark with a LOUD warning: a logged degraded recovery beats a wedged
    /// partition, and since NO peer was proven ahead this cannot truncate below a known-ahead watermark. A
    /// survivor CONFIRMED strictly ahead whose catch-up fails does NOT come here — it stays SYNCING (#445,
    /// {@link #catchupOwnerFromSurvivor}). The bound is cross-call memory ({@link #firstNoSourceMs}), armed on
    /// the FIRST failed attempt, so successive redrive ticks measure one continuous wait.
    private Promise<Long> escapeOwnerCatchup(String streamName, int partition, long localWatermark, Cause cause) {
        var key = partitionKey(streamName, partition);
        var firstObserved = firstNoSourceMs.computeIfAbsent(key, _ -> clock.getAsLong());
        var waited = clock.getAsLong() - firstObserved;

        if (waited < sourceWaitBound.millis()) {
            log.warn("Backfill {}[{}]: promoted-owner catch-up from survivor failed ({}) — staying "
                    + "non-authoritative ({}ms of {}ms wait elapsed), will retry",
                     streamName,
                     partition,
                     cause.message(),
                     waited,
                     sourceWaitBound.millis());

            return cause.promise();
        }

        log.warn("Backfill {}[{}]: promoted-owner catch-up UNAVAILABLE after {}ms — promoting at LOCAL watermark "
                + "{} with POSSIBLE DATA LOSS [#336 liveness escape: degraded recovery, not a wedge]",
                 streamName,
                 partition,
                 waited,
                 localWatermark);

        return ownerSelfPromote(streamName, partition);
    }

    /// Owner self-promotion: self IS the HRW owner and no REACHABLE source proves more history exists ahead
    /// of self's local watermark, so promote to CAUGHT_UP at that watermark. Reached on the genuine
    /// cold-start (no peer source at all), when every probed peer is reachable and none is ahead, AND — via
    /// the bounded {@link #escapeOwnerCatchup} liveness escape — once the wait elapses with a blind peer
    /// still unreachable (a logged degraded recovery). The owner is authoritative for its own partition, so
    /// promoting at the local watermark is data-safe whenever no reachable source is proven ahead.
    private Promise<Long> ownerSelfPromote(String streamName, int partition) {
        var watermark = selfWatermark.localWatermark(streamName, partition);

        log.warn("Backfill {}[{}]: owner self-promoting to CAUGHT_UP at watermark {} (no reachable source ahead) "
                + "[authoritative owner]",
                 streamName,
                 partition,
                 watermark);
        registry.updateWatermark(streamName, partition, self, watermark);
        firstNoSourceMs.remove(partitionKey(streamName, partition));

        return Promise.success(0L);
    }

    /// Non-owner cold-start path: stay SYNCING until the bounded wait elapses, then attempt a data-safe
    /// promotion via the highest-watermark/all-reachable contest.
    private Promise<Long> waitThenPromote(String streamName, int partition, List<ReplicaDescriptor> replicas) {
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
        // #491 m2: the cold-start self-promote is a DEADLOCK-BREAK for a GENUINE cold start where NO owner
        // can exist (every replica SYNCING). When a committed owner EXISTS this is a FAILOVER, not a
        // deadlock — the owner is authoritative and the redrive's owner re-pull will catch self up once the
        // owner's ring fills (owner-side #491 F1/m3 land the true tail this same batch). Self-promoting off
        // the probe contest here would falsely flip CAUGHT_UP at self's (possibly -1) local watermark, past
        // the #445 empty-owner distrust gate. Stay SYNCING and keep re-pulling — a safety gate, not a new
        // loop. Trade-off: SYNCING-forever under a wedged owner, acceptable because the owner-side fixes
        // land together. Reaching here already implies isSelfOwner() is false, so a present committed owner
        // is a genuine owner-elsewhere case, never a self-promote we are starving.
        if (committedOwnerSource.committedOwner(streamName, partition).isPresent()) {
            log.warn("Backfill {}[{}]: bound elapsed with no source but a committed owner exists — staying "
                    + "SYNCING (failover, not cold-start; cold-start self-promote suppressed to preserve the "
                    + "#445 empty-owner distrust gate)",
                     streamName,
                     partition);

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

    /// #559 — "the owner is empty" vs "I am at the owner's tail". Both produce an EMPTY
    /// `CatchupResponse`, so both used to route into [`#handleNoSource`] and the cold-start promotion
    /// contest. A replica that is genuinely caught up then competes in a contest it should never have
    /// entered, and when it loses the lowest-NodeId tie-break to the owner it returns to SYNCING and
    /// repeats every redrive interval, indefinitely. Observed in the wild: two replicas holding exactly
    /// the owner's tail declined promotion 336 times each over 28 minutes, correctly losing the
    /// tie-break every time while fully caught up.
    ///
    /// The response cannot discriminate. `ForwardCatchupTransport.toResponse` stamps
    /// `toOffset = fromOffset - 1` for EVERY empty response, so comparing them is an identity that
    /// carries no information about the owner's true tail. The owner's watermark must be asked for, so
    /// this probes the owner we just pulled from.
    ///
    /// Self is at the owner's tail iff the owner reports a REAL watermark (`>= 0`) that self is not
    /// behind. `ownerWm >= 0` preserves #445: a fresh/re-elected owner whose ring is empty reports
    /// `-1`, is NOT trusted as the true tail, and still falls through to the probe-gated no-source path
    /// rather than flipping a false `CAUGHT_UP` at self's watermark.
    ///
    /// Deliberately scoped to the OWNER-PULL path. The cold-start contest in [`#decidePromotion`] must
    /// keep its lowest-NodeId tie-break: there the HRW-ranked node is a candidate, not an authority —
    /// nobody has been confirmed to hold authoritative history — so "self matches that node's
    /// watermark" does not establish that self is caught up. Here it does, because the owner answered
    /// a real catch-up request.
    private Promise<Long> promoteIfAtOwnerTail(String streamName,
                                               int partition,
                                               NodeId owner,
                                               long selfConfirmed,
                                               List<ReplicaDescriptor> replicas) {
        return Promise.allOf(List.of(probe.probe(owner, streamName, partition)))
                      .flatMap(results -> results.getFirst()
                                                 .fold(_ -> handleNoSource(streamName, partition, replicas),
                                                       ownerWatermark -> atTail(selfConfirmed, ownerWatermark)
                                                                         ? promoteAtOwnerTail(streamName,
                                                                                              partition,
                                                                                              selfConfirmed,
                                                                                              ownerWatermark)
                                                                         : handleNoSource(streamName, partition, replicas)));
    }

    /// An owner reporting `-1` is an EMPTY owner, never a true tail (#445) — self must not promote off
    /// it however much history self holds, because a further-ahead survivor may exist.
    private static boolean atTail(long selfConfirmed, long ownerWatermark) {
        return ownerWatermark >= 0 && selfConfirmed >= ownerWatermark;
    }

    private Promise<Long> promoteAtOwnerTail(String streamName, int partition, long selfConfirmed, long ownerWatermark) {
        log.debug("Backfill {}[{}]: CAUGHT_UP at owner tail — owner watermark {}, self {} — empty response "
                + "means nothing to fetch, not an empty owner; skipping the cold-start contest",
                  streamName,
                  partition,
                  ownerWatermark,
                  selfConfirmed);
        registry.updateWatermark(streamName, partition, self, selfConfirmed);
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
