// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.forward.RefusalBackoff;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.stream.replication.PartitionKey;
import org.pragmatica.aether.stream.replication.ReplicaWatermarkProbe;
import org.pragmatica.aether.stream.replication.SelfWatermark;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

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
/// **An unreachable live member blocks activation** (it may hold a higher watermark). This is bounded by detection: the
/// member set is the live placement projection (`StreamAssembly.livePlacementMembers`), from which a member leaves
/// once the membership FSM declares it DEAD, shrinking the probe set and unblocking activation within
/// failure-detection time. Activation never proceeds while a reachable member holds a higher watermark.
/// Each probe attempt itself times out (the forward-read timeout) and the next demand retries. A member that keeps its
/// transport handshaking while answering nothing never reaches DEAD (#1563: a black-holed peer that re-handshakes; the
/// production form would be a wedged JVM whose network stack still completes handshakes), so detection alone does not bound
/// the wait; the bounded escape below does, for the candidates it covers.
///
/// **The bounded escape (#2080).** After `promotionEscapeAfter` (`[streaming] promotion_escape_after`, 120 s by default, never below the alarm bound) of continuous probe failure of EACH silent member (the bound is per member: a member that just went silent keeps the partition blocked even when another is past the bound, and an answer resets only that member's clock, including an answer in a round refused for another peer's oversized event; a gap between two rounds longer than `unreachableAlarmAfter` is not counted as silence, so the clock restarts at the later observation. THE GAP THRESHOLD IS A GUESS: the alarm bound is the nearest existing measure of "too long to be one continuous look") a candidate named in the
/// partition's COMMITTED in-sync set (`isrVersion > 0` and the set contains this node) stops waiting for the silent members:
/// the responders are caught up from and reconciled exactly as before, and when the activation completes one
/// `stream-promotion-past-unreachable-peers` operator event (through [BlockAlarm#escaped]) names the partition, the candidate and
/// the members it went ahead without.
/// The guarantee, per operation:
///   - an acknowledged write at `confirmation_factor` >= 2 is on EVERY member of the in-sync set in force (the acknowledgement
///     waited for all of them), so an in-sync candidate holds every such record whether or not the silent members answer
///     `[mechanism: DefaultReplicationManager.isrAckSet]`;
///   - after a full-cluster cold restart the in-sync set is minted from placement, not from what disks hold, so a silent node
///     whose disk is AHEAD may hold acknowledged records the new lineage lacks. They leave the live stream when that node returns
///     and is cut back, but the cut first writes them to a recovery segment on its own volume and reports it
///     (`StreamPartitionManager#repairDivergence`); re-injecting them (follow-up tooling) assigns new offsets, so consumers may see
///     duplicates or reordering;
///   - with ephemeral storage there is nothing to retain, and the escape changes nothing there except availability;
///   - `confirmation_factor` 1 acknowledges at the owner alone and keeps its documented loss window.
/// A candidate outside the in-sync set, a record with no committed set (`isrVersion` 0) and a first owner with no record at all
/// keep waiting and raise the unreachable-members block ([ActivationBlock.HoldersUnreachable]) as before.
///
/// **Overlap verification (#1555 item 7, #1730 phase 2).** The ownership epoch is recorded in every WAL frame's
/// attribution (`StreamPartitionManager.attributedWrite`), but this gate compares RECORDS: a returning ex-owner's
/// never-acked tail (the same offsets, written under an older ownership) is indistinguishable by HEAD from the acked
/// history that replaced it. Before any peer is used as catch-up source, and for every other responder, the last
/// [#OVERLAP_WINDOW] offsets both hold are compared record by record (offset, timestamp, payload — the #1505 notion of
/// "the same event"); the catch-up source is also compared PAIRWISE with every responder above the candidate's head,
/// because the local log covers only offsets up to the candidate's own head and a candidate lagging two lineages agrees
/// with both.
///
/// **A candidate elected from the committed ISR** (a record with `isrVersion > 0` whose ISR names this node) holds
/// every acknowledged record, because an acknowledgement waited for every member of the ISR in force. A peer that
/// disagrees with it within the overlap therefore holds a tail nobody acknowledged: it is LEFT OUT of the catch-up
/// (never the source, never compared again) and cuts its own tail back when it backfills from this node (Kafka's
/// KIP-101 truncation). Any other disagreement REFUSES activation, whichever side is higher: for a candidate with no
/// committed ISR to be elected from (a record minted before #1730), and for two peers that disagree with each other
/// above the candidate's head, nothing here can tell which of the two lineages was acknowledged, so neither is served
/// and neither is pulled. The refusal is reported once through the [BlockAlarm] and on [#blockOf] (the partition
/// status read); the partition then waits for an operator to pick the source (#1569's pick-source surface, AD14).
///
/// **The window is a named constant, not a derived bound.** Nothing caps how far an owner may append beyond its
/// last acknowledged offset: the pre-append floor (`ReplicationManager.ensureReplicaFloor`) requires in-sync peers
/// to EXIST, and `ReplicaRegistry.freshPeersFor` bounds a peer's lag against the freshest PEER, not against the
/// owner's head. That does not limit detection: a divergent tail runs up to the diverging copy's head, so the top
/// window of offsets both copies hold lies inside it however deep the divergence starts. What the window bounds is
/// the cost of each check. Offsets either side has evicted are not compared: a peer's window is read from its
/// oldest available offset ([OwnerPeerReads]); a catch-up SOURCE that holds none of the window it is checked over
/// cannot be verified and is refused ([ActivationBlock.OverlapUnverifiable]). The candidate reads its own window
/// ring then tier ([OwnerPeerReads#ownerRange]; a ring-only candidate read compared nothing once its ring was
/// evicted, v1555 R3). A peer answers ring then tier only when the candidate is in that peer's registered replica
/// set (`StreamPartitionManager.isRegisteredReplica`); otherwise it answers from its ring alone, bounded by its
/// VISIBLE position. So a peer whose ring has evicted the window compares nothing: as the catch-up SOURCE it fails
/// closed ([ActivationBlock.OverlapUnverifiable]); as a lower peer it is accepted, since nothing is pulled from it.
///
/// **An unreachable member that stays unreachable is reported, and bypassed only by the bounded escape (#1555 item 8, #2080).**
/// After `unreachableAlarmAfter` of continuous probe failure the block is reported once through the [BlockAlarm] and on
/// [#blockOf], naming the unreachable members and the responders, whoever the candidate is; the operator path is #1569's surface.
/// A candidate the bounded escape does not cover stays blocked from there on. One it covers goes ahead at `promotionEscapeAfter`
/// (later than the alarm on purpose: see the guarantee above) and reports the escape; the block it had reported is told as resolved.
/// #1579's plan for an automatic bound ("it needs the ack-time replica set to be durable") is superseded: the committed in-sync set
/// is that durable
/// record, and the cold-restart residual it leaves is retained and reported by the divergent-tail cut.
///
/// **Quorum loss clears every activation** ([#onQuorumStateChange]) and a node without an active consensus engine is
/// never activated, so an ex-owner that heals back re-runs the whole gate before it serves again.
///
/// Residual (accepted, #1555 decision c): an append admitted on the previous owner in the instant before the
/// ownership flip reaches it is not seen by this catch-up, whose probe is a snapshot. At `confirmation_factor` 1 the
/// acknowledgement is owner-local by definition, so this is part of that documented loss window. At
/// `confirmation_factor` >= 2 such an append is acknowledged only if the new owner holds it, and only because the new
/// owner is a member of the ISR the previous owner acknowledges on: the acknowledgement waits for every member of that
/// ISR (its maximal form, `IsrMonitor`), and a member that has applied the newer record refuses the older owner epoch.
/// That membership is an ELECTION rule, not a property of the confirmation factor: failover elects from the live ISR,
/// and a planned move goes only to a live ISR member (#2981). Without that rule `confirmation_factor` >= 2 did not
/// close it: a planned move to a node outside the ISR, under apply skew, lost an acknowledged record (#2981, TLA+
/// model `Streams.tla`, invariant `AckedNeverLost`). Still open: nothing fences the previous owner itself, so the
/// guarantee holds only while every path that commits an owner elects inside the previous owner's ISR, and the closure
/// assumes the maximal ISR covers every committed ISR member; #2993 (an `IsrMonitor` expansion still in flight is
/// forgotten by the next tick) is a known gap there. Direction B, an ownership check at acknowledgement time on the
/// deposed owner, is the mechanism that closes N2 regardless of both (a separate later change).
/// [mechanism: the election rule above with all-member acknowledgement; model-checked on three nodes, not reproduced at
/// runtime]
public final class OwnerActivation {
    private static final Logger log = LoggerFactory.getLogger(OwnerActivation.class);

    /// The committed `StreamPartitionOwnershipValue` of `(stream, partition)` in this node's applied state.
    @FunctionalInterface
    public interface OwnershipRecordSource {
        Option<StreamPartitionOwnershipValue> committed(String stream, int partition);
        /// #2981 step 2 (design §2.2 "Unwired seams"): the explicit default of a manager whose record seam was never bound, compared
        /// BY IDENTITY. Finds no record.
        OwnershipRecordSource UNWIRED = (_, _) -> Option.none();
    }

    /// Which ring serves `(stream, partition)` on this node ([OffHeapRingBuffer#incarnation]): the activation tells a ring
    /// it has already activated from one that was rebuilt since (#1730 phase 2).
    @FunctionalInterface
    public interface RingIncarnation {
        long of(String stream, int partition);
        /// For the factories without a partition manager behind them: every partition has the same, unknown, incarnation.
        RingIncarnation NONE = (_, _) -> - 1L;
    }

    /// Commits, as the owner, where the current epoch of `(stream, partition)` begins (#1730 phase 2, KIP-320): with
    /// `restarted` the owner first takes the next ownership term, because its ring was rebuilt and it may assign offsets
    /// it assigned before. A guarded write of exactly `current`; a refusal (the record moved on) fails the activation, which
    /// re-runs against the new record. #1976: the refusal is a FAILED promise ([ActivationError#LINEAGE_NOT_COMMITTED]);
    /// success means THIS write was accepted. A read-back cannot decide it: on a restart the record already names a start,
    /// so a refused commit would read as landed.
    @FunctionalInterface
    public interface LineageCommit {
        Promise<Unit> commit(String stream,
                             int partition,
                             StreamPartitionOwnershipValue current,
                             long startOffset,
                             boolean restarted);

        /// Commits nothing: the factories without a consensus applier behind them. A gate with the move fence wired refuses to
        /// run with it ([ActivationError#FENCE_WITHOUT_LINEAGE], #2981 step 3b).
        LineageCommit NONE = (_, _, _, _, _) -> Promise.success(Unit.unit());
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
        NO_RING("The partition's ring is not materialized here, so there is no offset an epoch could begin at"),
        /// #1976: no committed leader authorises the write — a quorum condition with its own event, not a refusal of this
        /// partition's commit, so it is retried but never counted toward the per-partition refusal alarm.
        NO_COMMITTED_LEADER("No committed leader authorises the owner's lineage commit"),
        LINEAGE_NOT_COMMITTED("The owner's epoch start was not committed (the guarded write was refused or did not apply)"),
        /// #2981 step 3b (design §2.3): the fence's induction needs every activated owner's start in the record, so the next
        /// owner can find it as its fence target; a gate that commits no lineage would activate an owner nobody fences.
        FENCE_WITHOUT_LINEAGE("The move fence is wired but the lineage commit is not, so this owner's epoch start would never be recorded"),
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

    /// Read the APPENDED records `from .. to` of `(stream, partition)` held by `node` — self included. A node that
    /// retains only part of the range returns that part; offsets missing on either side are not compared.
    @FunctionalInterface
    public interface RecordRange {
        Promise<List<OffHeapRingBuffer.RawEvent>> read(NodeId node, String stream, int partition, long from, long to);
    }

    /// Where a partition whose promotion is blocked is reported: once per distinct [ActivationBlock]. `AetherNode`
    /// binds the operator-facing warning.
    @FunctionalInterface
    public interface BlockAlarm {
        /// Called while the gate holds its alarm-ordering monitor (#2004): it must neither block nor take a lock, or the monitor
        /// is no longer a leaf lock and a lock-order inversion becomes possible. Log and hand off.
        Unit raise(ActivationBlock block);

        /// `block` no longer holds the partition's promotion (#1937): its condition ended or this node stopped being the owner.
        /// Called once per ended block that was raised; the default does nothing. Same contract as [#raise]: neither blocks nor takes a lock.
        default Unit resolved(ActivationBlock block) {
            return Unit.unit();
        }

        /// A promotion went ahead without members that did not answer (#2080), see [PromotionEscape]. Called once per escape.
        /// The default does nothing: an alarm that does not override it reports no escape, so the production binding must.
        default Unit escaped(PromotionEscape escape) {
            return Unit.unit();
        }
    }

    /// Which gate let a candidate proceed past unreachable members.
    public enum EscapeGate {
        /// The owner activation gate ([OwnerActivation#catchUpToLiveHolders]).
        OWNER_ACTIVATION,
        /// The replica promotion contest (`PartitionBackfill#decidePromotionUnquarantined`).
        REPLICA_CONTEST
    }

    /// The bounded escape (#2080): `candidate`, named in the partition's committed in-sync set, proceeded past `skipped` after
    /// they stayed unreachable for `elapsed`, which passed the configured `bound` (`[streaming] promotion_escape_after`). An acknowledged write (confirmation factor >= 2) is on every in-sync member,
    /// so the candidate holds all of them; the one case that does not hold is a full-cluster cold restart where a skipped node's disk
    /// is ahead, whose records the new lineage lacks. A returning member is re-gated and cut back (the cut preserves what it removes).
    public record PromotionEscape(String streamName,
                                  int partition,
                                  EscapeGate gate,
                                  NodeId candidate,
                                  List<NodeId> skipped,
                                  TimeSpan bound,
                                  TimeSpan elapsed) {
        public String message() {
            return ("Promotion of %s[%d] on %s proceeded WITHOUT %s (%s): they did not answer for %s, longer than the configured bound %s "
                   + "(promotion_escape_after), and %s is named in the partition's committed in-sync set, so it holds every acknowledged record unless a full-cluster cold restart left "
                   + "a skipped node's disk ahead; such records would leave the live stream and are retained in a recovery segment "
                   + "when the node returns and is cut back").formatted(streamName,
                                                                        partition,
                                                                        candidate,
                                                                        skipped,
                                                                        gate == EscapeGate.OWNER_ACTIVATION
                                                                        ? "owner activation"
                                                                        : "replica promotion contest",
                                                                        elapsed,
                                                                        bound,
                                                                        candidate);
        }
    }

    /// A promotion that cannot complete without an operator: the partition stays un-activated, the gate keeps
    /// refusing, and the block is readable on [#blockOf] until an activation succeeds or ownership leaves this node.
    public sealed interface ActivationBlock extends Cause {
        String streamName();
        int partition();

        /// `peer` holds, at an offset both hold, a record different from the local one: one of the two is a
        /// divergent tail, and nothing here can tell which.
        record DivergentPeer(String streamName, int partition, NodeId peer, long localHead, long peerHead) implements ActivationBlock {
            @Override
            public String message() {
                return ("Owner promotion of %s[%d] refused: %s disagrees with the local log where both hold records "
                       + "(local head %d, peer head %d); one of them is a divergent tail and the partition waits "
                       + "for an operator to pick the source").formatted(streamName,
                                                                         partition,
                                                                         peer,
                                                                         localHead,
                                                                         peerHead);
            }
        }

        /// Two peers — the catch-up source and another responder — hold different records at an offset both hold:
        /// the suffix that would be pulled may belong to another lineage than records the other peer acknowledged.
        /// The source component is `origin`: a component named `source` would clash with [Cause#source()].
        record DivergentPeers(String streamName,
                              int partition,
                              NodeId origin,
                              long originHead,
                              NodeId peer,
                              long peerHead) implements ActivationBlock {
            @Override
            public String message() {
                return ("Owner promotion of %s[%d] refused: catch-up source %s (head %d) and %s (head %d) disagree where "
                       + "both hold records; one of them is a divergent tail and the partition waits for an operator "
                       + "to pick the source").formatted(streamName, partition, origin, originHead, peer, peerHead);
            }
        }

        /// The catch-up source `origin` holds none of the records its comparison with `other` would check — its
        /// ring and tier start above the window — so its suffix cannot be verified against `other`'s log.
        record OverlapUnverifiable(String streamName,
                                   int partition,
                                   NodeId origin,
                                   long originHead,
                                   NodeId other,
                                   long otherHead) implements ActivationBlock {
            @Override
            public String message() {
                return ("Owner promotion of %s[%d] refused: catch-up source %s (head %d) holds none of the records to "
                       + "compare with %s (head %d) — its window is evicted — so its suffix cannot be verified; the "
                       + "partition waits for an operator to pick the source").formatted(streamName,
                                                                                         partition,
                                                                                         origin,
                                                                                         originHead,
                                                                                         other,
                                                                                         otherHead);
            }
        }

        /// `peer` HOLDS the partition with durable data (`peerHead`) but has not materialized it because its off-heap
        /// budget is exhausted, so no reshuffle slot will ever free it: the owner cannot verify or pull from it and
        /// refuses to act as owner (`localHead` is what it holds). Safe, but appends stay refused until budget frees
        /// on that node or an operator picks a source.
        record HolderBudgetDeferred(String streamName, int partition, NodeId peer, long localHead, long peerHead) implements ActivationBlock {
            @Override
            public String message() {
                return ("Owner promotion of %s[%d] refused: %s holds durable data (head %d, local head %d) but has not "
                       + "materialized the partition — off-heap budget exhausted on that node; appends stay refused until "
                       + "its budget frees or an operator picks the source").formatted(streamName,
                                                                                       partition,
                                                                                       peer,
                                                                                       peerHead,
                                                                                       localHead);
            }
        }

        /// #1730: the committed owner left the live set and no member of the committed in-sync replica set `isr` is
        /// live, so no node may be elected: every other copy may lack acknowledged records (unclean failover is off).
        /// The partition stays unavailable until an ISR member returns or an operator picks a source.
        record NoInSyncReplica(String streamName, int partition, NodeId owner, List<NodeId> isr) implements ActivationBlock {
            @Override
            public String message() {
                return ("Partition %s[%d] has no owner: committed owner %s is not live and no member of its in-sync "
                       + "replica set %s is live, so no copy is known to hold every acknowledged record; the partition "
                       + "stays unavailable until an in-sync replica returns; there is no operator override yet (#1569)").formatted(streamName,
                                                                                                                                    partition,
                                                                                                                                    owner,
                                                                                                                                    isr);
            }
        }

        /// #1431: `peer` ANSWERED the promotion probe, but the event at `offset` alone exceeds its read cap, so its
        /// watermark cannot be read past it. Not an unreachable peer: the operator raises `maxReadResponseBytes` on
        /// `peer` above that event (or upgrades a peer whose handler cuts before the first event).
        record PeerEventExceedsReadCap(String streamName, int partition, NodeId peer, long offset) implements ActivationBlock {
            @Override
            public String message() {
                return ("Owner promotion of %s[%d] refused: %s answered the watermark probe, but its event at offset %d "
                       + "is larger than its read cap (maxReadResponseBytes), so its log cannot be read past that "
                       + "event; raise the cap on %s above that event's size").formatted(streamName,
                                                                                         partition,
                                                                                         peer,
                                                                                         offset,
                                                                                         peer);
            }
        }

        /// #1976: the owner's guarded lineage commit was refused `refusals` times in a row (the ownership record keeps moving
        /// under it, or no committed leader authorises the write), so the partition stays un-activated. It keeps retrying
        /// with backoff; this is the operator's signal that it has not succeeded. Reported once, cleared on activation.
        record LineageRefused(String streamName, int partition, int refusals) implements ActivationBlock {
            @Override
            public String message() {
                return ("Owner promotion of %s[%d] has been refused %d times in a row: the guarded commit of its epoch start "
                       + "is not accepted (the ownership record keeps changing, or no leader authorises it); the owner "
                       + "retries with backoff and the partition stays un-activated until it lands").formatted(streamName,
                                                                                                               partition,
                                                                                                               refusals);
            }
        }

        /// #2960: every source the promoted owner could catch up from answered short without advancing it for `rounds` consecutive rounds
        /// and was demoted (`PartitionBackfill.MAX_STALLED_ROUNDS`), so the round ends with no source left. The owner stays SYNCING and never
        /// activates below the target (the rule is lossless); the redrive retries every tick. Reported once, cleared on progress or activation.
        record CatchupStalled(String streamName, int partition, int rounds) implements ActivationBlock {
            @Override
            public String message() {
                return ("Owner catch-up of %s[%d] cannot progress: every source answered short without advancing the owner for %d "
                       + "consecutive rounds; the owner stays non-authoritative and never activates below the target, and the "
                       + "redrive retries until a source holding the missing records answers in full").formatted(streamName,
                                                                                                                 partition,
                                                                                                                 rounds);
            }
        }

        /// `unreachable` have not answered the promotion probe for longer than `blockedLongerThan` and may hold a
        /// higher watermark; `responders` answered.
        record HoldersUnreachable(String streamName,
                                  int partition,
                                  List<NodeId> unreachable,
                                  List<NodeId> responders,
                                  TimeSpan blockedLongerThan) implements ActivationBlock {
            @Override
            public String message() {
                return ("Owner promotion of %s[%d] blocked for longer than %s: %s did not answer the watermark probe "
                       + "and may hold acknowledged records (responders: %s); the partition waits for them or for "
                       + "an operator").formatted(streamName, partition, blockedLongerThan, unreachable, responders);
            }
        }
    }

    /// How many of the most recent offsets two nodes both hold are compared before one is trusted or promoted
    /// over the other. A named constant, not a derived bound — see the class comment.
    public static final int OVERLAP_WINDOW = 1024;
    static final TimeSpan REDRIVE_INITIAL_BACKOFF = TimeSpan.timeSpan(250).millis();
    static final TimeSpan REDRIVE_MAX_BACKOFF = TimeSpan.timeSpan(2).seconds();
    /// Consecutive refused lineage commits of one partition before the operator is told (#1976).
    static final int LINEAGE_REFUSAL_ALARM_AFTER = 5;

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
    private final RecordRange ranges;
    private final BlockAlarm alarm;
    private final TimeSpan unreachableAlarmAfter;
    private final RingIncarnation ringIncarnation;
    private final LineageCommit lineage;
    /// #2981 step 2 (design §2.2 check 1): the node lease, and the confirmation factor that decides whether it gates a stream (>= 2).
    private final OwnerLease lease;
    private final ToIntFunction<String> confirmationFactor;
    /// The ring each partition was last activated on, by [RingIncarnation]: an activation on the SAME ring kept its
    /// offsets, one on another ring (a restart, a re-created stream) may assign them again.
    private final Map<PartitionKey, Long> activatedIncarnation = new ConcurrentHashMap<>();
    /// Consecutive refused lineage commits per partition; reset when one lands.
    private final Map<PartitionKey, Integer> lineageRefusals = new ConcurrentHashMap<>();

    /// The committed record each partition was activated for; [Option#none] marks a first-owner activation.
    private final Map<PartitionKey, Option<StreamPartitionOwnershipValue>> activated = new ConcurrentHashMap<>();

    /// The candidate's durable sealed floor, late-bound ([#sealedFloor]); none sealed until wired.
    private volatile LastSealedOffsetSource sealedFloor = LastSealedOffsetSource.none();

    /// The earliest offset a peer still holds in its RING, or none when that is not known. Late-bound ([#peerRingTail]).
    @FunctionalInterface
    public interface PeerRingTail {
        Promise<Option<Long>> of(NodeId peer, String stream, int partition);
    }

    private volatile PeerRingTail peerRingTail = (_, _, _) -> Promise.success(Option.none());

    /// Late-bind where a peer's ring begins. The gate's relaxation for a divergent peer applies only to a divergence at an offset
    /// the peer still holds in its ring: below it the peer's copy was handed to the tier (sealed), where its own repair refuses to
    /// cut. The compare reads the peer's range through its tier too, so a difference found there is NOT a relaxation case. Unknown
    /// (the default) fails safe: no relaxation. Set once at wiring.
    @Contract
    public void peerRingTail(PeerRingTail tail) {
        this.peerRingTail = tail;
    }

    /// Forgets what this node's registry says a peer confirmed. Late-bound ([#peerRows]); the default forgets nothing.
    @FunctionalInterface
    public interface PeerRowReset {
        @Contract
        void reset(String stream, int partition, NodeId peer);
    }

    private volatile PeerRowReset peerRows = (_, _, _) -> {};

    /// Late-bind the registry reset used for a peer the gate leaves out as divergent: its row, confirmed under an earlier
    /// tenure, would otherwise keep seeding this owner's acknowledgements for records the peer holds in another version
    /// (#1890 class). Set once at wiring.
    @Contract
    public void peerRows(PeerRowReset reset) {
        this.peerRows = reset;
    }

    /// The offset up to which `peer`, which answered the probe with `watermark`, is a source for this owner: `watermark` capped at
    /// the committed-lineage end an earlier clipped catch-up learned for it (I-27, #1759), while its tail is still the one observed
    /// then. Late-bound ([#peerLineageEnd]).
    @FunctionalInterface
    public interface PeerLineageEnd {
        long of(String stream, int partition, NodeId peer, long watermark);
    }

    private volatile PeerLineageEnd peerLineageEnd = (_, _, _, watermark) -> watermark;

    /// Re-learns the committed-lineage end entry that caps `peer` (tail `watermark`) before the gate ranks on it (#3142 F3): an entry
    /// is advisory, and a replica refilled to exactly the remembered tail would otherwise keep a stale cap. Late-bound
    /// ([#peerLineageConfirm]).
    @FunctionalInterface
    public interface PeerLineageConfirm {
        Promise<Unit> of(String stream, int partition, NodeId peer, long watermark);
    }

    private volatile PeerLineageConfirm peerLineageConfirm = (_, _, _, _) -> Promise.success(Unit.unit());

    /// Late-bind the confirmation of a learned cap (#3142 F3). Unset (the default) confirms nothing, so the gate ranks on the caps
    /// as learned. Set once at wiring.
    @Contract
    public void peerLineageConfirm(PeerLineageConfirm confirm) {
        this.peerLineageConfirm = confirm;
    }

    /// Late-bind the committed-lineage end of a peer (#3071, ruling 37277a904f). The gate ranks, compares and verifies a peer only up
    /// to it: a deposed epoch's tail the catch-up refuses never counts as ahead, so the gate cannot pull it again on every demand.
    /// The refused tail is the peer's to repair. Unknown (the default) is the raw watermark. Set once at wiring.
    @Contract
    public void peerLineageEnd(PeerLineageEnd end) {
        this.peerLineageEnd = end;
    }

    /// Learns, without installing anything, the committed-lineage end of `peer` (tail `watermark`) from the page it serves at `from`
    /// (#3080). A failure is the hook's to absorb: the gate then judges the peer as it did before. Late-bound ([#peerLineageLearn]).
    @FunctionalInterface
    public interface PeerLineageLearn {
        Promise<Unit> of(String stream, int partition, NodeId peer, long watermark, long from);
    }

    private volatile PeerLineageLearn peerLineageLearn = (_, _, _, _, _) -> Promise.success(Unit.unit());

    /// Late-bind the read-only lineage-end learning (#3080). Two responders that disagree may differ only because one holds a deposed
    /// epoch's tail whose bound no pull has taught this node yet; the bound is learned from the page of each, at the first offset
    /// they differ, and the pair is compared once more over what remains. Unset (the default) learns nothing, so such a pair is
    /// refused as before. Set once at wiring.
    @Contract
    public void peerLineageLearn(PeerLineageLearn learn) {
        this.peerLineageLearn = learn;
    }

    /// Probes the move fence's target `L` (#2981 step 3b, design §2.3 NEW-2) for its watermark, stamped with the record that made this
    /// node owner (`ownerEpoch`, `ownershipTerm`): `L` answers only once it has applied that record, so its answer holds every record it
    /// ever acked. `L` is probed whether or not it is a live placement member. The protocol on `L`'s side is #2981 step 3c.
    @FunctionalInterface
    public interface FenceProbe {
        Promise<Long> probe(NodeId target, String stream, int partition, Epoch ownerEpoch, long ownershipTerm);
    }

    /// The move fence's wiring (#2981 step 3b): the per-term deadlines and the probe of the fence target.
    public record MoveFence(FenceDeadlines deadlines, FenceProbe probe) {
        public static MoveFence moveFence(FenceDeadlines deadlines, FenceProbe probe) {
            return new MoveFence(deadlines, probe);
        }
    }

    private volatile Option<MoveFence> moveFence = Option.none();

    /// Late-bind the move fence (#2981 step 3b, design §2.3). Unset (the default) the gate runs as before #2981 step 3; set, a gate
    /// whose record names a fence target `L` probes it and races its answer against the term's deadline, and a gate without a
    /// lineage commit ([LineageCommit#NONE]) refuses ([ActivationError#FENCE_WITHOUT_LINEAGE]). Set once at wiring.
    @Contract
    public void moveFence(MoveFence fence) {
        this.moveFence = Option.some(fence);
    }

    /// Late-bind the candidate's durable sealed floor, the bound the gate's relaxation for a divergent peer respects. Set once at
    /// wiring.
    @Contract
    public void sealedFloor(LastSealedOffsetSource source) {
        this.sealedFloor = source;
    }

    private final Set<PartitionKey> inFlight = ConcurrentHashMap.newKeySet();
    /// Partitions with a re-drive already scheduled.
    private final Set<PartitionKey> redriving = ConcurrentHashMap.newKeySet();
    /// #2574: per partition, the instant (System.nanoTime) before which no gate may run, because a probe of the last run was
    /// not sent by the transport ([StreamForwardError.ForwardNotSent]). The refusal fails at once where the probe used to
    /// wait out its read timeout; without this pacing every demand ([#admit]) would start a gate, and with it one probe per
    /// live peer, at the request rate.
    private final Map<PartitionKey, Long> refusedUntilNanos = new ConcurrentHashMap<>();
    /// #2574 (CTO, know 89e17399a9): the refused-probe hold, per PEER: the refusal is a property of the lane to that peer,
    /// and every partition's gate probes the same peers, so one peer's backoff paces all of them and a peer that answers
    /// is not held back by another that refuses. A run's wait is the longest hold among its refused peers.
    private final RefusalBackoff<NodeId> peerRefusals = RefusalBackoff.refusalBackoff();
    /// #2682: eager gates ([#onCommittedOwnership]) waiting for a slot, in arrival order, and the slots taken; both guarded
    /// by [#eagerOrder]. One unwired gate runs one eager gate at a time.
    private final Object eagerOrder = new Object();
    private final LinkedHashSet<PartitionKey> eagerBacklog = new LinkedHashSet<>();
    private int eagerRunning;
    private volatile int eagerConcurrency = 1;
    /// The block currently reported for each partition; the alarm fires when it first appears or changes.
    private final Map<PartitionKey, ActivationBlock> blocks = new ConcurrentHashMap<>();
    /// The "members did not answer" block, in its OWN slot (#1937): it is a condition independent of every other block, so a
    /// peer refused for an oversized event ([#blocks]) neither masks it nor is re-raised by it.
    private final Map<PartitionKey, ActivationBlock> unreachableBlocks = new ConcurrentHashMap<>();
    /// The refused-lineage-commit block (#1976), in a slot of its own: it is an independent condition, so a block of another
    /// kind reported for the partition neither replaces it nor ends it.
    private final Map<PartitionKey, ActivationBlock> lineageBlocks = new ConcurrentHashMap<>();
    /// Raise-before-resolve (#2004): recording a block and raising it, and removing a block and resolving it, each happen under
    /// this monitor. A clear that lands while a raise is in flight therefore waits for it and delivers [raise, resolve], and a
    /// clear that lands first leaves nothing to raise; never [resolve, raise], which the aggregator drops as a recovery with no
    /// open warning, leaving the CRITICAL open. Serialised rather than flagged "reported after the raise" because the raise is a
    /// log line plus an asynchronous hand-off, and these events fire on a refusal or a transition, not per request. Reentrant, so a
    /// raise or resolve that calls back into the gate cannot deadlock itself.
    ///
    /// **A leaf lock, which is why it cannot take part in a deadlock (the #1946 shape was a lock taken in opposite orders).**
    /// While it is held the gate runs only map operations and [BlockAlarm#raise] / [BlockAlarm#resolved], and no other lock is taken
    /// beneath it: the node's alarm writes a log line and enqueues onto a bounded hand-off queue (`OperatorWarningSink.HandOff`: an
    /// `ArrayBlockingQueue` offer, a rejection handler that only counts, never caller-runs). Nothing above it holds a lock the
    /// partition manager shares: `admit`, which starts an activation, is called outside every `StreamPartitionManager` monitor.
    /// An alarm that blocked or took a lock would break this, so [BlockAlarm] says it must not.
    ///
    /// **Which pairs race in production, and which sites are ordered defensively only.** An activation of one partition is single-flight, so
    /// every raise happens on that activation's thread. The one pair that crosses threads in production is the lineage-slot raise
    /// (`refused`, on the activation chain) against [#onQuorumStateChange] (the quorum-state notification thread); that is the reproduced
    /// race. `clearBlock` is called only on the activation chain (its visibility is wider than that for the tests), so `report`,
    /// `trackUnreachable`, `clearBlock` and the three resolves issued on the activation thread itself (`noLongerOversized`,
    /// `clearUnreachable`, `proceedWithout`) cannot race a raise today; they take the monitor so that every raise and resolve is ordered the same
    /// way, which keeps the property if a clear is ever called from another thread. The sibling gates with a real cross-thread pair
    /// (`ForwardCatchupTransport`, `StreamPartitionManager.refusedCuts`) and the backfill's superseded flight have their own leaf monitors.
    private final Object alarmOrder = new Object();
    /// When the current run of probe failures started (`System.nanoTime`), per partition.
    private final Map<PartitionKey, Long> unreachableSince = new ConcurrentHashMap<>();
    /// Per silent member, since when it has been continuously unreachable (`System.nanoTime`), per partition (#2080): the escape bound is
    /// PER MEMBER, so a member that just went silent is never skipped because another one is past the bound. A member that answers (or
    /// leaves the live set) loses its entry; the whole map goes with the activation and with ownership.
    private final Map<PartitionKey, Map<NodeId, Silence>> silentSince = new ConcurrentHashMap<>();

    /// A member's current run of silence: when it began and when a round last saw it silent (`System.nanoTime`).
    private record Silence(long since, long last) {}

    /// How long members must stay unreachable before an ISR-named candidate goes ahead without them (#2080, `[streaming]
    /// promotion_escape_after`). Never until wired: a gate that is not told its bound does not escape.
    private volatile TimeSpan promotionEscapeAfter = TimeSpan.timeSpan(Long.MAX_VALUE).nanos();

    /// Late-bind the escape bound (#2080); the configuration validates that it is at least the alarm bound. Set once at wiring.
    @Contract
    public void promotionEscapeAfter(TimeSpan bound) {
        this.promotionEscapeAfter = bound;
    }

    /// The escape the gate went ahead on, held until the activation COMPLETES (#2080): only then is it reported, so an event never
    /// describes a promotion that did not happen. A member answering again, or ownership leaving this node, drops it unreported.
    private final Map<PartitionKey, PromotionEscape> pendingEscapes = new ConcurrentHashMap<>();

    private OwnerActivation(NodeId self,
                            OwnershipRecordSource records,
                            PlacementOwner placementOwner,
                            Option<LinearizableBarrier> barrier,
                            Supplier<List<NodeId>> liveMembers,
                            ReplicaWatermarkProbe probe,
                            SelfWatermark selfWatermark,
                            OwnerCatchUp catchUp,
                            BooleanSupplier consensusActive,
                            RecordRange ranges,
                            BlockAlarm alarm,
                            TimeSpan unreachableAlarmAfter,
                            RingIncarnation ringIncarnation,
                            LineageCommit lineage,
                            OwnerLease lease,
                            ToIntFunction<String> confirmationFactor) {
        this.self = self;
        this.records = records;
        this.placementOwner = placementOwner;
        this.barrier = barrier;
        this.liveMembers = liveMembers;
        this.probe = probe;
        this.selfWatermark = selfWatermark;
        this.catchUp = catchUp;
        this.consensusActive = consensusActive;
        this.ranges = ranges;
        this.alarm = alarm;
        this.unreachableAlarmAfter = unreachableAlarmAfter;
        this.ringIncarnation = ringIncarnation;
        this.lineage = lineage;
        this.lease = lease;
        this.confirmationFactor = confirmationFactor;
    }

    public static OwnerActivation ownerActivation(NodeId self,
                                                  OwnershipRecordSource records,
                                                  PlacementOwner placementOwner,
                                                  Option<LinearizableBarrier> barrier,
                                                  Supplier<List<NodeId>> liveMembers,
                                                  ReplicaWatermarkProbe probe,
                                                  SelfWatermark selfWatermark,
                                                  OwnerCatchUp catchUp,
                                                  BooleanSupplier consensusActive,
                                                  RecordRange ranges,
                                                  BlockAlarm alarm,
                                                  TimeSpan unreachableAlarmAfter) {
        return ownerActivation(self,
                               records,
                               placementOwner,
                               barrier,
                               liveMembers,
                               probe,
                               selfWatermark,
                               catchUp,
                               consensusActive,
                               ranges,
                               alarm,
                               unreachableAlarmAfter,
                               RingIncarnation.NONE,
                               LineageCommit.NONE);
    }

    /// The confirmation factor of the factory without a lease (tests that predate #2981): every stream reads as CF=1, which takes no
    /// lease check (design §0 scope), so those gates admit exactly as before.
    private static final ToIntFunction<String> NO_LEASE_SCOPE = _ -> 1;

    /// The lineage-committing factory (#1730 phase 2) without a node lease (tests that predate #2981): the activation also commits
    /// where the current epoch begins (see [LineageCommit]) before the node is marked activated; no stream takes the lease check.
    public static OwnerActivation ownerActivation(NodeId self,
                                                  OwnershipRecordSource records,
                                                  PlacementOwner placementOwner,
                                                  Option<LinearizableBarrier> barrier,
                                                  Supplier<List<NodeId>> liveMembers,
                                                  ReplicaWatermarkProbe probe,
                                                  SelfWatermark selfWatermark,
                                                  OwnerCatchUp catchUp,
                                                  BooleanSupplier consensusActive,
                                                  RecordRange ranges,
                                                  BlockAlarm alarm,
                                                  TimeSpan unreachableAlarmAfter,
                                                  RingIncarnation ringIncarnation,
                                                  LineageCommit lineage) {
        return ownerActivation(self,
                               records,
                               placementOwner,
                               barrier,
                               liveMembers,
                               probe,
                               selfWatermark,
                               catchUp,
                               consensusActive,
                               ranges,
                               alarm,
                               unreachableAlarmAfter,
                               ringIncarnation,
                               lineage,
                               OwnerLease.UNWIRED,
                               NO_LEASE_SCOPE);
    }

    /// The production factory (#1730 phase 2, #2981 step 2): the activation also commits where the current epoch begins (see
    /// [LineageCommit]) before the node is marked activated, and a stream whose `confirmationFactor` is 2 or more is admitted only
    /// while `lease` is valid ([#admit]).
    public static OwnerActivation ownerActivation(NodeId self,
                                                  OwnershipRecordSource records,
                                                  PlacementOwner placementOwner,
                                                  Option<LinearizableBarrier> barrier,
                                                  Supplier<List<NodeId>> liveMembers,
                                                  ReplicaWatermarkProbe probe,
                                                  SelfWatermark selfWatermark,
                                                  OwnerCatchUp catchUp,
                                                  BooleanSupplier consensusActive,
                                                  RecordRange ranges,
                                                  BlockAlarm alarm,
                                                  TimeSpan unreachableAlarmAfter,
                                                  RingIncarnation ringIncarnation,
                                                  LineageCommit lineage,
                                                  OwnerLease lease,
                                                  ToIntFunction<String> confirmationFactor) {
        return new OwnerActivation(self,
                                   records,
                                   placementOwner,
                                   barrier,
                                   liveMembers,
                                   probe,
                                   selfWatermark,
                                   catchUp,
                                   consensusActive,
                                   ranges,
                                   alarm,
                                   unreachableAlarmAfter,
                                   ringIncarnation,
                                   lineage,
                                   lease,
                                   confirmationFactor);
    }

    /// Whether this node may act as owner of `(stream, partition)` right now: its consensus engine is active,
    /// it is the owner (the committed record names it, or no record exists and placement names it), and it was
    /// activated for exactly the ownership it currently holds. #1730: the ownership is the record without its ISR
    /// (owner, epoch, term, transfer stamp) — an ISR change moves no ownership, and re-running the whole gate on every
    /// ISR shrink or expansion would refuse the partition's writes each time for nothing.
    public boolean isActivated(String stream, int partition) {
        var current = records.committed(stream, partition);

        return consensusActive.getAsBoolean()
               && claimsOwnership(stream, partition, current)
               && Option.option(activated.get(PartitionKey.partitionKey(stream, partition)))
                        .filter(recorded -> sameOwnership(recorded, current))
                        .isPresent()
               && sameRing(stream, partition);
    }

    /// #1873: the activation belongs to the ring it committed the lineage for. A ring rebuilt under an unchanged record (a
    /// re-created stream, a lazy re-materialization) has offsets this activation never saw, so it is NOT activated until the gate
    /// re-runs and bumps the epoch: the record alone cannot tell the two rings apart. A partition whose lineage was never
    /// committed on this node (a first owner with no record) has nothing to compare.
    private boolean sameRing(String stream, int partition) {
        return Option.option(activatedIncarnation.get(PartitionKey.partitionKey(stream, partition)))
                     .map(activatedOn -> activatedOn == ringIncarnation.of(stream, partition))
                     .or(true);
    }

    static boolean sameOwnership(Option<StreamPartitionOwnershipValue> left,
                                 Option<StreamPartitionOwnershipValue> right) {
        return left.map(OwnerActivation::ownershipOf)
                   .equals(right.map(OwnerActivation::ownershipOf));
    }

    private record Ownership(NodeId owner, Epoch ownerEpoch, long ownershipTerm, HlcTimestamp transferredAt) {}

    private static Ownership ownershipOf(StreamPartitionOwnershipValue record) {
        return new Ownership(record.owner(), record.ownerEpoch(), record.ownershipTerm(), record.transferredAt());
    }

    /// The gate every owner action passes: admitted when activated; otherwise an activation is started and the
    /// action is refused with the transient [StreamError.OwnerNotActivated].
    ///
    /// #2981 step 2 (design §2.2 check 1, F-1a-5, F-1a-8): FIRST, a stream with confirmation factor 2 or more whose node lease is
    /// not valid now is refused with the transient [StreamError.OwnerLeaseLapsed], with NO activation side effect: no gate is
    /// started (a lapse must not send consensus rounds and probes at the quorum whose slowness caused it) and `activated` is left
    /// as it was, so the next renewal restores admission without a re-gate. An ADMISSION check, not a read-freshness guarantee: a
    /// read admitted under a valid lease may be served after the lease lapsed.
    public Result<Unit> admit(String stream, int partition) {
        if (leaseLapsed(stream)) {
            return new StreamError.OwnerLeaseLapsed(stream, partition).result();
        }

        if (isActivated(stream, partition)) {
            return Result.unitResult();
        }

        attempt(stream, partition, REDRIVE_INITIAL_BACKOFF);

        return new StreamError.OwnerNotActivated(stream, partition).result();
    }

    private boolean leaseLapsed(String stream) {
        return confirmationFactor.applyAsInt(stream) >= 2 && !lease.valid();
    }

    /// #2682: the committed-record hook. A committed ownership record that newly names this node (no record before, or an
    /// ownership other than the one before: owner, epoch, term, transfer stamp) starts the promotion gate at once, instead of
    /// waiting for the first owner action to call [#admit]. Without it a new owner nothing publishes to or reads from never
    /// runs the gate, so it never pulls the appended tail a live peer holds beyond its own head, and stays behind for good.
    /// An ISR-only change starts nothing, for the reason [#isActivated] gives. The gate itself is unchanged: this only starts
    /// the same attempt a demand starts, with its fresh view, re-drive loop and every safety step.
    ///
    /// Guarantee: a partition this node newly owns runs its gate within one eager slot's wait plus the gate's own run, with no
    /// demand, and a failed run (one during a quorum loss included) is re-run until the partition is activated or this node
    /// is no longer its named owner. Mechanism: the hook below and the re-drive loop of [#attempt]. Not covered: an attempt
    /// that meets a gate already running for the partition ([ActivationError#IN_PROGRESS]) leaves the outcome to that gate.
    /// At most [#eagerConcurrency] eager gates run at once (a mass reshuffle would otherwise probe `RF - 1` peers per moved
    /// partition in one burst); the rest wait in arrival order. A demand ([#admit]) never waits for a slot.
    @Contract
    public void onCommittedOwnership(String stream,
                                     int partition,
                                     Option<StreamPartitionOwnershipValue> before,
                                     StreamPartitionOwnershipValue after) {
        if (after.owner().equals(self) && !sameOwnership(before, Option.some(after))) {
            queueEager(PartitionKey.partitionKey(stream, partition));
        }
    }

    /// Late-bind the eager-gate cap (#2682, `[streaming] reshuffle_concurrency`); the configuration validates it is at least 1.
    /// Set once at wiring. THE VALUE IS A GUESS: it bounds the same per-node fan-out a reshuffle's backfill does, but no
    /// measurement backs it; a reshuffle of more than 100 partitions, timing the probe burst, would settle it.
    @Contract
    public void eagerConcurrency(int cap) {
        this.eagerConcurrency = cap;
        drainEager();
    }

    /// The partitions whose eager gate waits for a slot, in arrival order: a snapshot, for the cap's tests.
    List<PartitionKey> eagerWaiting() {
        synchronized (eagerOrder) {
            return List.copyOf(eagerBacklog);
        }
    }

    @Contract
    private void queueEager(PartitionKey key) {
        synchronized (eagerOrder) {
            eagerBacklog.add(key);
        }

        drainEager();
    }

    @Contract
    private void drainEager() {
        nextEager().onPresent(this::runEager);
    }

    /// The next queued partition, taking a slot for it; none while every slot is taken or nothing waits.
    private Option<PartitionKey> nextEager() {
        synchronized (eagerOrder) {
            if (eagerRunning >= eagerConcurrency || eagerBacklog.isEmpty()) {
                return Option.none();
            }

            eagerRunning++;

            return Option.some(eagerBacklog.removeFirst());
        }
    }

    /// The slot is held for the first gate run only; a failed run continues in the re-drive loop outside the cap, paced by
    /// its backoff. The next queued partition is then tried, so a cap above one fills every free slot. The run starts on the
    /// async executor, never on the thread that applied the committed record: the gate's first step orders a consensus round.
    @Contract
    private void runEager(PartitionKey key) {
        Promise.async(() -> startEager(key));
    }

    @Contract
    private void startEager(PartitionKey key) {
        eagerAttempt(key.streamName(), key.partition()).onResultRun(this::eagerSlotFreed);
        drainEager();
    }

    @Contract
    private void eagerSlotFreed() {
        synchronized (eagerOrder) {
            eagerRunning--;
        }

        drainEager();
    }

    /// One [#attempt] whose completion is visible: an activation already in place runs nothing, a refusal's wait defers
    /// the run (the slot frees at once), otherwise the gate runs and a failure is re-driven exactly as on demand.
    /// FER, as in [#attempt]: nobody waits on this run, so its failure is not surfaced; the re-drive loop makes the next attempt.
    private Promise<Unit> eagerAttempt(String stream, int partition) {
        if (isActivated(stream, partition)) {
            return Promise.unitPromise();
        }

        return refusalWaitLeft(PartitionKey.partitionKey(stream, partition)).fold(() -> activateOrRedrive(stream,
                                                                                                          partition,
                                                                                                          REDRIVE_INITIAL_BACKOFF),
                                                                                  left -> Promise.success(deferPastRefusal(stream,
                                                                                                                           partition,
                                                                                                                           REDRIVE_INITIAL_BACKOFF,
                                                                                                                           left)));
    }

    /// One activation attempt. A failed attempt is re-driven here, with a doubling backoff, while this node still
    /// claims the partition and is not activated: without it a failure (a peer that has not applied the stream's
    /// config yet, a probe that timed out) waited for the next demand, and a partition nobody demanded again stayed
    /// refused until something else happened to probe it (cloud run 1: ~25 s).
    ///
    /// FER: the failure itself is not surfaced to the refused caller, which is told `OwnerNotActivated` and retries;
    /// this loop only guarantees an attempt is made again. Stops when the node is activated or ownership left it
    /// (`NOT_OWNER`); a demand starts a fresh loop, and one loop runs per partition.
    ///
    /// #2682: a quorum loss does not stop the loop. While consensus is inactive the loop runs no gate, so an ex-owner
    /// re-runs the whole gate only after it heals back; it waits one more backoff (bounded by [#REDRIVE_MAX_BACKOFF])
    /// and looks again. An ownership that moved away during the loss is seen by the gate's fresh view once quorum
    /// returns, which ends the loop (`NOT_OWNER`); until then this node cannot learn of it. Before, a run that failed
    /// while quorum was lost ended the loop, and a partition nothing demanded (one the eager hook started) stayed
    /// un-activated after quorum returned, because nothing ran its gate again.
    ///
    /// #2574: an attempt that falls inside a refusal's wait ([#refusedUntilNanos]) runs no gate; it is re-driven at the
    /// wait's end instead. After a refused probe the redrive therefore takes the ruled shape (know 89e17399a9): the
    /// refused peers' hold, 250 ms doubling to 4 s, jittered, reset once the peer's probe is sent.
    private void attempt(String stream, int partition, TimeSpan backoff) {
        refusalWaitLeft(PartitionKey.partitionKey(stream, partition)).onEmpty(() -> activateOrRedrive(stream,
                                                                                                      partition,
                                                                                                      backoff))
                       .onPresent(left -> deferPastRefusal(stream, partition, backoff, left));
    }

    private Promise<Unit> activateOrRedrive(String stream, int partition, TimeSpan backoff) {
        return activateUnlessHeld(stream, partition, backoff).onFailure(cause -> redrive(stream,
                                                                                         partition,
                                                                                         backoff,
                                                                                         cause));
    }

    /// #3140: [#attempt] checks the refusal wait, but a gate run by another thread (an admit, or the redrive timer) can be
    /// refused and park the partition between that check and this thread winning `inFlight`, so the stale check let a second
    /// gate probe INSIDE the hold (a third hold measured 0 ms; the consumer's twin is #2875). The check is repeated here, once
    /// this thread owns `inFlight`: it releases the partition untouched and defers to the wait's end. The result is
    /// [ActivationError#IN_PROGRESS], which [#redrive] ignores, because the deferral is the re-drive.
    private Promise<Unit> activateUnlessHeld(String stream, int partition, TimeSpan backoff) {
        var key = PartitionKey.partitionKey(stream, partition);

        if (!inFlight.add(key)) {
            return ActivationError.IN_PROGRESS.promise();
        }

        return refusalWaitLeft(key).fold(() -> runGate(stream, partition, key).withResult(_ -> inFlight.remove(key)),
                                         left -> yieldToRefusal(stream, partition, backoff, key, left));
    }

    private Promise<Unit> yieldToRefusal(String stream,
                                         int partition,
                                         TimeSpan backoff,
                                         PartitionKey key,
                                         TimeSpan left) {
        inFlight.remove(key);
        deferPastRefusal(stream, partition, backoff, left);

        return ActivationError.IN_PROGRESS.promise();
    }

    /// The part of the partition's refusal wait still to run, none once it has passed.
    private Option<TimeSpan> refusalWaitLeft(PartitionKey key) {
        return Option.option(refusedUntilNanos.get(key))
                     .map(until -> until - System.nanoTime())
                     .filter(left -> left > 0L)
                     .map(left -> TimeSpan.timeSpan(left).nanos());
    }

    private Unit deferPastRefusal(String stream, int partition, TimeSpan backoff, TimeSpan left) {
        if (redriving.add(PartitionKey.partitionKey(stream, partition))) {
            SharedScheduler.schedule(() -> redriveAfterBackoff(stream, partition, backoff), left);
        }

        return Unit.unit();
    }

    /// #2574: each probed peer's hold advances on a refusal and resets once its probe was sent; the longest hold among this
    /// run's refused peers starts the partition's wait (see [#refusedUntilNanos]). `results` are in `peers` order.
    @Contract
    private void noteRefusals(PartitionKey key, List<NodeId> peers, List<Result<PeerWatermark>> results) {
        IntStream.range(0,
                        Math.min(peers.size(),
                                 results.size()))
                 .mapToObj(index -> peerHold(peers.get(index),
                                             results.get(index)))
                 .flatMap(Option::stream)
                 .max(Comparator.naturalOrder())
                 .ifPresent(hold -> refusedUntilNanos.put(key,
                                                          System.nanoTime() + hold.nanos()));
    }

    private Option<TimeSpan> peerHold(NodeId peer, Result<PeerWatermark> result) {
        return result.fold(cause -> refusedHold(peer, cause), _ -> sentProbe(peer));
    }

    private Option<TimeSpan> refusedHold(NodeId peer, Cause cause) {
        return StreamForwardError.isRefusal(cause)
               ? Option.some(peerRefusals.refused(peer))
               : sentProbe(peer);
    }

    private Option<TimeSpan> sentProbe(NodeId peer) {
        peerRefusals.sent(peer);

        return Option.none();
    }

    private void redrive(String stream, int partition, TimeSpan backoff, Cause cause) {
        log.debug("Owner activation of {}[{}] pending: {}", stream, partition, cause.message());
        if (cause == ActivationError.IN_PROGRESS || cause == ActivationError.NOT_OWNER) {
            return;
        }

        scheduleRedrive(stream, partition, backoff);
    }

    @Contract
    private void scheduleRedrive(String stream, int partition, TimeSpan backoff) {
        if (redriving.add(PartitionKey.partitionKey(stream, partition))) {
            SharedScheduler.schedule(() -> redriveAfterBackoff(stream, partition, backoff), backoff);
        }
    }

    /// While consensus is inactive no gate runs: the loop waits one more backoff (see [#attempt]).
    private void redriveAfterBackoff(String stream, int partition, TimeSpan backoff) {
        redriving.remove(PartitionKey.partitionKey(stream, partition));
        if (isActivated(stream, partition)) {
            return;
        }

        if (consensusActive.getAsBoolean()) {
            attempt(stream, partition, nextBackoff(backoff));
        } else {
            scheduleRedrive(stream, partition, nextBackoff(backoff));
        }
    }

    private static TimeSpan nextBackoff(TimeSpan backoff) {
        return backoff.millis() * 2 >= REDRIVE_MAX_BACKOFF.millis()
               ? REDRIVE_MAX_BACKOFF
               : TimeSpan.timeSpan(backoff.millis() * 2).millis();
    }

    /// Run the promotion gate for `(stream, partition)` once; concurrent demands share nothing and are told an
    /// activation is already running.
    public Promise<Unit> activate(String stream, int partition) {
        var key = PartitionKey.partitionKey(stream, partition);

        if (!inFlight.add(key)) {
            return ActivationError.IN_PROGRESS.promise();
        }

        return runGate(stream, partition, key).withResult(_ -> inFlight.remove(key));
    }

    /// Quorum-state hook: on PASSIVE (quorum lost) drop every activation, so no partition is served as owner
    /// until the gate re-runs against a view refreshed after the node rejoins.
    @Contract
    public void onQuorumStateChange(ClusterStateNotification notification) {
        if (notification.state() == ClusterStateNotification.State.PASSIVE) {
            activated.clear();
            lineageRefusals.clear();
            synchronized (alarmOrder) {
                lineageBlocks.keySet().forEach(key -> ended(lineageBlocks.remove(key)));
            }
        }
    }

    /// The block reported for `(stream, partition)`, if its promotion currently waits for an operator.
    public Option<ActivationBlock> blockOf(String stream, int partition) {
        var key = PartitionKey.partitionKey(stream, partition);

        return Option.option(blocks.get(key))
                     .orElse(() -> Option.option(unreachableBlocks.get(key)))
                     .orElse(() -> Option.option(lineageBlocks.get(key)));
    }

    /// The fence (step 2's fenced form) completes BEFORE [#commitLineage] (#2981 step 3b, v3186 F2): a lineage write at the same
    /// offset as `L`'s start drops that start from the record, after which this node's own start is the newest and a re-gate of the
    /// term reads [FenceTarget.NoFence]. That is sound only because `L` was already fenced when the write was submitted.
    private Promise<Unit> runGate(String stream, int partition, PartitionKey key) {
        if (moveFence.isPresent() && lineage == LineageCommit.NONE) {
            return ActivationError.FENCE_WITHOUT_LINEAGE.promise();
        }

        return freshView(stream, partition).flatMap(record -> fencedCatchUp(stream, partition, key, record).map(_ -> record))
                        .flatMap(record -> commitLineage(stream, partition, key, record))
                        .map(record -> recordActivation(stream, partition, key, record));
    }

    /// Step 3 (#1730 phase 2, KIP-320): the owner commits where the current epoch begins, BEFORE it is activated and so
    /// before it assigns or serves a single offset of it. Three cases, decided by the committed record and this
    /// process's own memory of the ring it activated:
    ///   - the record names no start for its epoch: a failover or a first record, the epoch begins at the next offset;
    ///   - it names the start and this process activated THIS ring for it: the offsets were kept, nothing to commit;
    ///   - it names the start and the ring is another one (a restart, a re-created stream, a re-materialization): the
    ///     owner may assign again offsets it assigned before, in the SAME epoch, which no consumer could tell apart. It
    ///     takes the next ownership term, so the new epoch begins where it resumed.
    /// A consumer that read the replaced offsets holds the old epoch, and is told so (`EpochValidation`).
    private Promise<Option<StreamPartitionOwnershipValue>> commitLineage(String stream,
                                                                         int partition,
                                                                         PartitionKey key,
                                                                         Option<StreamPartitionOwnershipValue> record) {
        return record.fold(() -> Promise.success(record), committed -> commitLineage(stream, partition, key, committed));
    }

    private Promise<Option<StreamPartitionOwnershipValue>> commitLineage(String stream,
                                                                         int partition,
                                                                         PartitionKey key,
                                                                         StreamPartitionOwnershipValue committed) {
        var started = committed.lastEpochStart()
                               .filter(start -> start.epoch()
                                                     .equals(committed.ownerEpoch()))
                               .isPresent();
        var incarnation = ringIncarnation.of(stream, partition);

        if (lineage != LineageCommit.NONE && incarnation < 0L) {
            return ActivationError.NO_RING.promise();
        }

        var restarted = started && !Long.valueOf(incarnation).equals(activatedIncarnation.get(key));

        if (lineage == LineageCommit.NONE || started && !restarted) {
            return Promise.success(Option.some(committed));
        }

        return lineage.commit(stream,
                              partition,
                              committed,
                              selfWatermark.localWatermark(stream, partition) + 1L,
                              restarted)
                      .flatMap(_ -> ownedRecord(stream, partition))
                      .flatMap(this::requireCommittedStart)
                      .withSuccess(_ -> landed(key, incarnation))
                      .withFailure(cause -> refused(stream, partition, key, cause));
    }

    private Unit landed(PartitionKey key, long incarnation) {
        activatedIncarnation.put(key, incarnation);
        lineageRefusals.remove(key);

        return Unit.unit();
    }

    /// #1976: a refused commit never latches [#activatedIncarnation], so the re-drive runs the commit again; only the
    /// operator's view needs the count — the Nth refusal in a row is reported once, and [#recordActivation] clears it.
    private Unit refused(String stream, int partition, PartitionKey key, Cause cause) {
        if (cause != ActivationError.LINEAGE_NOT_COMMITTED) {
            return Unit.unit();
        }

        var count = lineageRefusals.merge(key, 1, Integer::sum);

        if (count != LINEAGE_REFUSAL_ALARM_AFTER) {
            return Unit.unit();
        }

        var block = new ActivationBlock.LineageRefused(stream, partition, count);

        synchronized (alarmOrder) {
            lineageBlocks.put(key, block);

            return alarm.raise(block);
        }
    }

    /// A guarded write that was refused is not a failed promise (the applier answers it with a result): the record is
    /// re-read, and an epoch that still has no committed start fails the activation, which re-runs against the record as it
    /// is now. Without this an owner would activate with no start and answer every consumer read `OwnerNotActivated`.
    private Promise<Option<StreamPartitionOwnershipValue>> requireCommittedStart(Option<StreamPartitionOwnershipValue> record) {
        return record.filter(value -> value.lastEpochStart()
                                           .filter(start -> start.epoch()
                                                                 .equals(value.ownerEpoch()))
                                           .isPresent())
                     .isPresent() || record.isEmpty()
               ? Promise.success(record)
               : ActivationError.LINEAGE_NOT_COMMITTED.promise();
    }

    private Unit recordActivation(String stream,
                                  int partition,
                                  PartitionKey key,
                                  Option<StreamPartitionOwnershipValue> record) {
        activated.put(key, record);
        Option.option(pendingEscapes.get(key)).onPresent(alarm::escaped);
        clearBlock(key);
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
                      .fold(() -> firstOwnerView(stream, partition),
                            _ -> refreshedView(stream, partition));
    }

    private Promise<Option<StreamPartitionOwnershipValue>> firstOwnerView(String stream, int partition) {
        return claimsOwnership(stream, partition, Option.none())
               ? Promise.success(Option.none())
               : notOwner(stream, partition);
    }

    private Promise<Option<StreamPartitionOwnershipValue>> refreshedView(String stream, int partition) {
        return barrier.fold(() -> Promise.success(Unit.unit()),
                            round -> round.awaitRound(stream, partition))
                      .flatMap(_ -> ownedRecord(stream, partition));
    }

    private Promise<Option<StreamPartitionOwnershipValue>> ownedRecord(String stream, int partition) {
        var current = records.committed(stream, partition);

        return claimsOwnership(stream, partition, current)
               ? Promise.success(current)
               : notOwner(stream, partition);
    }

    /// Ownership left this node: whatever block it reported is no longer its to report.
    private <T> Promise<T> notOwner(String stream, int partition) {
        clearBlock(PartitionKey.partitionKey(stream, partition));

        return ActivationError.NOT_OWNER.promise();
    }

    /// Also ends a refusal episode (#1976): the count restarts, and a reported [ActivationBlock.LineageRefused] is told as
    /// resolved — the partition activated, or this node stopped claiming it, so the condition no longer holds here. A later
    /// tenure that gets stuck again therefore raises again.
    Unit clearBlock(PartitionKey key) {
        lineageRefusals.remove(key);
        synchronized (alarmOrder) {
            ended(blocks.remove(key));
            ended(unreachableBlocks.remove(key));
            ended(lineageBlocks.remove(key));
        }

        unreachableSince.remove(key);
        silentSince.remove(key);
        pendingEscapes.remove(key);

        return Unit.unit();
    }

    /// A committed record decides ownership; only without one does placement decide.
    private boolean claimsOwnership(String stream, int partition, Option<StreamPartitionOwnershipValue> record) {
        return record.fold(() -> placementOwner.isPlacementOwner(stream, partition),
                           value -> value.owner()
                                         .equals(self));
    }

    /// Step 2 with the move fence (#2981 step 3b, design §2.3): with no fence wired, or no record (a first owner, nobody to
    /// fence), today's catch-up. Otherwise by the record's [FenceTarget]: nobody to fence, today's catch-up; `L` named, `L`
    /// raced against the term's deadline ([#raceFenceTarget]); `L` unknown (a start that names no owner), the full deadline is
    /// waited and nobody is probed before it, which is safe for any `L`.
    private Promise<Unit> fencedCatchUp(String stream,
                                        int partition,
                                        PartitionKey key,
                                        Option<StreamPartitionOwnershipValue> record) {
        return moveFence.flatMap(fence -> fencedRecord(stream, partition, key, fence, record))
                        .or(() -> catchUpToLiveHolders(stream, partition));
    }

    private Option<Promise<Unit>> fencedRecord(String stream,
                                               int partition,
                                               PartitionKey key,
                                               MoveFence fence,
                                               Option<StreamPartitionOwnershipValue> record) {
        return record.map(committed -> fenced(stream, partition, key, fence, committed));
    }

    private Promise<Unit> fenced(String stream,
                                 int partition,
                                 PartitionKey key,
                                 MoveFence fence,
                                 StreamPartitionOwnershipValue committed) {
        var left = fence.deadlines().nanosLeft(key, committed.ownershipTerm());

        return switch (FenceTarget.fenceTarget(committed, self)) {
            case FenceTarget.NoFence _ -> catchUpToLiveHolders(stream, partition);
            case FenceTarget.Probe(var target) -> probeFenceTarget(stream, partition, fence, committed, target, left);
            case FenceTarget.Unknown _ -> afterDeadline(left).flatMap(_ -> catchUpToLiveHolders(stream, partition));
        };
    }

    /// A deadline that already passed (a same-term re-gate reusing the term's base) leaves nothing to race: the others are probed
    /// now, after it, and `L` is not waited for.
    private Promise<Unit> probeFenceTarget(String stream,
                                           int partition,
                                           MoveFence fence,
                                           StreamPartitionOwnershipValue committed,
                                           NodeId target,
                                           long left) {
        return left > 0L
               ? raceFenceTarget(stream, partition, fence, committed, target, left)
               : catchUpWith(stream, partition, othersThan(target));
    }

    /// Design §2.3 H1: today's parallel fan-out to the other members, with `L` added and raced against the deadline. `L`
    /// answered first: it applied the record, so it no longer admits, acks or shows anything, and every answer of this fan-out
    /// counts, `L`'s included. The deadline passed first: the answers collected so far are DISCARDED (one could miss a record `L`
    /// acked between that answer and its expiry, model `L_late`) and every other member is probed again; only those answers
    /// count. A failed probe of `L` is no answer: the deadline still decides.
    private Promise<Unit> raceFenceTarget(String stream,
                                          int partition,
                                          MoveFence fence,
                                          StreamPartitionOwnershipValue committed,
                                          NodeId target,
                                          long left) {
        var others = othersThan(target);
        var firstRound = probeAll(stream, partition, others);
        var answer = fence.probe()
                          .probe(target,
                                 stream,
                                 partition,
                                 committed.ownerEpoch(),
                                 committed.ownershipTerm())
                          .map(Option::some);

        return Promise.any(List.of(answer, afterDeadline(left).map(_ -> Option.<Long> none()))).flatMap(answered -> afterRace(stream,
                                                                                                                              partition,
                                                                                                                              others,
                                                                                                                              firstRound,
                                                                                                                              target,
                                                                                                                              answered));
    }

    private Promise<Unit> afterRace(String stream,
                                    int partition,
                                    List<NodeId> others,
                                    Promise<List<Result<PeerWatermark>>> firstRound,
                                    NodeId target,
                                    Option<Long> answered) {
        return answered.fold(() -> catchUpWith(stream, partition, others),
                             watermark -> withTargetAnswer(stream,
                                                           partition,
                                                           others,
                                                           firstRound,
                                                           new PeerWatermark(target, watermark)));
    }

    private Promise<Unit> withTargetAnswer(String stream,
                                           int partition,
                                           List<NodeId> others,
                                           Promise<List<Result<PeerWatermark>>> firstRound,
                                           PeerWatermark answer) {
        return firstRound.flatMap(results -> reconcileWithPeers(stream,
                                                                partition,
                                                                plus(others, answer.node()),
                                                                plus(results, Result.success(answer))));
    }

    private static <T> List<T> plus(List<T> list, T last) {
        return Stream.concat(list.stream(),
                             Stream.of(last))
                     .toList();
    }

    /// Resolves once `nanos` have passed (at once when none are left).
    private static Promise<Unit> afterDeadline(long nanos) {
        return nanos > 0L
               ? Promise.promise(TimeSpan.timeSpan(nanos).nanos(),
                                 promise -> promise.succeed(Unit.unit()))
               : Promise.success(Unit.unit());
    }

    /// The members the fence's "others" are: every live member but self and `L` (whose probe is the fence's own).
    private List<NodeId> othersThan(NodeId target) {
        return otherLiveMembers().stream()
                               .filter(member -> !member.equals(target))
                               .toList();
    }

    private List<NodeId> otherLiveMembers() {
        return liveMembers.get()
                          .stream()
                          .filter(member -> !member.equals(self))
                          .toList();
    }

    /// Step 2: probe every other live placement member; any unreachable member blocks, every responder's overlap
    /// with the local log is verified, and a higher watermark is pulled from its holder.
    private Promise<Unit> catchUpToLiveHolders(String stream, int partition) {
        return catchUpWith(stream, partition, otherLiveMembers());
    }

    private Promise<Unit> catchUpWith(String stream, int partition, List<NodeId> peers) {
        if (peers.isEmpty()) {
            return Promise.success(Unit.unit());
        }

        return probeAll(stream, partition, peers).flatMap(results -> reconcileWithPeers(stream,
                                                                                        partition,
                                                                                        peers,
                                                                                        results));
    }

    private Promise<List<Result<PeerWatermark>>> probeAll(String stream, int partition, List<NodeId> peers) {
        return Promise.allOf(peers.stream()
                                  .map(peer -> probe.probe(peer, stream, partition)
                                                    .map(watermark -> new PeerWatermark(peer, watermark)))
                                  .toList());
    }

    private Promise<Unit> reconcileWithPeers(String stream,
                                             int partition,
                                             List<NodeId> peers,
                                             List<Result<PeerWatermark>> results) {
        noteRefusals(PartitionKey.partitionKey(stream, partition), peers, results);
        var answered = results.stream().flatMap(result -> result.option()
                                                                .stream()).toList();

        return oversizedAt(peers, results).fold(() -> noLongerOversized(stream, partition, peers, answered),
                                                oversized -> refuseOversizedKeepingUnreachable(stream,
                                                                                               partition,
                                                                                               peers,
                                                                                               results,
                                                                                               answered,
                                                                                               oversized));
    }

    /// No peer is cut before its first event any more (#1937 F5): an oversized block still standing for this partition describes a
    /// condition that ended, whether the peers now answer or one went silent, so it ends here and the alarm is told. The
    /// unreachable wait and the catch-up proceed as before.
    private Promise<Unit> noLongerOversized(String stream,
                                            int partition,
                                            List<NodeId> peers,
                                            List<PeerWatermark> answered) {
        var key = PartitionKey.partitionKey(stream, partition);

        Option.option(blocks.get(key))
              .filter(ActivationBlock.PeerEventExceedsReadCap.class::isInstance)
              .onPresent(block -> {
                  synchronized (alarmOrder) {
                      if (blocks.remove(key, block)) {
                          ended(block);
                      }
                  }
              });

        return answered.size() < peers.size()
               ? holdersUnreachable(stream, partition, peers, answered)
               : catchUpFromHighest(stream, partition, answered);
    }

    private record OversizedPeer(NodeId peer, long offset) {}

    /// #1431: the first peer that answered with a page cut before its first event (index-aligned with `results`).
    private static Option<OversizedPeer> oversizedAt(List<NodeId> peers, List<Result<PeerWatermark>> results) {
        for (var i = 0; i < peers.size(); i++) {
            var peer = peers.get(i);
            var oversized = results.get(i)
                                   .fold(cause -> cause instanceof OwnerPeerReads.EventExceedsReadCap(var offset)
                                                  ? Option.some(new OversizedPeer(peer, offset))
                                                  : Option.<OversizedPeer> none(),
                                         _ -> Option.<OversizedPeer> none());

            if (oversized.isPresent()) {
                return oversized;
            }
        }

        return Option.none();
    }

    /// #1937: a peer refused for an oversized event is one condition; a DIFFERENT peer that did not answer is another, with its
    /// own timer and its own block. The refusal must not return before the unreachable condition is evaluated, or the second
    /// goes unreported for as long as the first stands. Every peer that neither answered nor was the oversized one is
    /// unreachable.
    private Promise<Unit> refuseOversizedKeepingUnreachable(String stream,
                                                            int partition,
                                                            List<NodeId> peers,
                                                            List<Result<PeerWatermark>> results,
                                                            List<PeerWatermark> answered,
                                                            OversizedPeer oversized) {
        var key = PartitionKey.partitionKey(stream, partition);
        var silent = new java.util.ArrayList<NodeId>();

        for (var i = 0; i < peers.size(); i++) {
            var failed = results.get(i)
                                .fold(cause -> !(cause instanceof OwnerPeerReads.EventExceedsReadCap),
                                      _ -> false);

            if (failed) {
                silent.add(peers.get(i));
            }
        }

        if (silent.isEmpty()) {
            clearUnreachable(key);
        } else {
            // the members that DID answer in this round reset their escape clocks here too (#2084 R1), not only on the unrefused path
            shortestSilence(key, silent);
            trackUnreachable(key, stream, partition, silent, answered);
        }

        return refuseOversized(stream, partition, oversized);
    }

    /// #1431: its own refusal, reported at once (once per distinct block) — never counted as an unreachable member.
    private Promise<Unit> refuseOversized(String stream, int partition, OversizedPeer oversized) {
        var block = new ActivationBlock.PeerEventExceedsReadCap(stream, partition, oversized.peer(), oversized.offset());

        report(PartitionKey.partitionKey(stream, partition), block);

        return block.promise();
    }

    /// A member did not answer: refuse, and once the failures have run continuously for longer than
    /// `unreachableAlarmAfter`, report the block.
    private Promise<Unit> holdersUnreachable(String stream,
                                             int partition,
                                             List<NodeId> peers,
                                             List<PeerWatermark> answered) {
        var responders = answered.stream().map(PeerWatermark::node).toList();
        var key = PartitionKey.partitionKey(stream, partition);
        var silent = peers.stream().filter(peer -> !responders.contains(peer)).toList();

        unreachableSince.computeIfAbsent(key, _ -> System.nanoTime());
        var shortest = shortestSilence(key, silent);

        if (shortest.map(this::pastBound).or(false) && isrElected(stream, partition)) {
            return proceedWithout(stream, partition, key, silent, answered, shortest.or(0L));
        }

        trackUnreachable(key, stream, partition, silent, answered);

        return ActivationError.HOLDER_UNREACHABLE.promise();
    }

    /// Updates the per-member clocks of the partition to `silent` (a member that answered or left the live set loses its entry, a newly
    /// silent one starts at now) and returns how long the member that has been silent for the SHORTEST time has been, in nanoseconds:
    /// the escape may go ahead only when even that member is past the bound. None without a silent member.
    private Option<Long> shortestSilence(PartitionKey key, List<NodeId> silent) {
        var clocks = silentSince.computeIfAbsent(key, _ -> new ConcurrentHashMap<>());
        var now = System.nanoTime();

        clocks.keySet().retainAll(silent);
        silent.forEach(peer -> clocks.merge(peer, new Silence(now, now), (known, _) -> continued(known, now)));

        return Option.from(clocks.values().stream().map(silence -> now - silence.since()).min(Long::compare));
    }

    /// Observed continuity (#2084): the gate is demand-driven, so a stretch in which no round looked at the member is not evidence that
    /// it stayed silent. A member last seen silent more than `unreachableAlarmAfter` ago starts a new run at this observation; rounds
    /// closer together than that extend the run. THE THRESHOLD IS A GUESS (the alarm bound is the nearest existing "too long to be one
    /// continuous look" measure; the re-drive backs off to 2 s, so a blocked partition is looked at far more often than 20 s).
    private Silence continued(Silence known, long now) {
        return now - known.last() > unreachableAlarmAfter.nanos()
               ? new Silence(now, now)
               : new Silence(known.since(), now);
    }

    private boolean pastBound(long silentNanos) {
        return silentNanos > promotionEscapeAfter.nanos();
    }

    /// #2080, the bounded escape. `silent` have not answered for longer than the bound and this node is the candidate named in the
    /// committed in-sync set (`isrVersion > 0`), so it holds every acknowledged record the set was acknowledging: the responders
    /// are caught up from and reconciled as always, and the silent members are no longer waited for. The escape is held as pending
    /// when that step passes and reported when the activation completes ([#recordActivation]): a divergent responder that refuses the
    /// activation, or a failed epoch-start commit, means nothing went ahead, and the unreachable-members block is ended instead of
    /// raised. A candidate outside the set, or a record with no set, never gets here: it keeps waiting and reports the block.
    private Promise<Unit> proceedWithout(String stream,
                                         int partition,
                                         PartitionKey key,
                                         List<NodeId> silent,
                                         List<PeerWatermark> answered,
                                         long silentNanos) {
        var escape = new PromotionEscape(stream,
                                         partition,
                                         EscapeGate.OWNER_ACTIVATION,
                                         self,
                                         silent,
                                         promotionEscapeAfter,
                                         TimeSpan.timeSpan(silentNanos).nanos());

        synchronized (alarmOrder) {
            ended(unreachableBlocks.remove(key));
        }

        return catchUpFromResponders(stream, partition, answered).withSuccess(_ -> pendingEscapes.put(key, escape));
    }

    /// The unreachable condition's timer and block, kept apart from every other block (#1937): once the members in `silent`
    /// have failed continuously for longer than `unreachableAlarmAfter`, the block is reported — once per distinct block.
    private void trackUnreachable(PartitionKey key,
                                  String stream,
                                  int partition,
                                  List<NodeId> silent,
                                  List<PeerWatermark> answered) {
        var since = unreachableSince.computeIfAbsent(key, _ -> System.nanoTime());

        if (System.nanoTime() - since > unreachableAlarmAfter.nanos()) {
            var block = new ActivationBlock.HoldersUnreachable(stream,
                                                               partition,
                                                               silent,
                                                               answered.stream().map(PeerWatermark::node).toList(),
                                                               unreachableAlarmAfter);

            synchronized (alarmOrder) {
                var previous = Option.option(unreachableBlocks.put(key, block));

                previous.filter(block::equals).fold(() -> alarm.raise(block), _ -> Unit.unit());
            }
        }
    }

    private Promise<Unit> catchUpFromHighest(String stream, int partition, List<PeerWatermark> answered) {
        clearUnreachable(PartitionKey.partitionKey(stream, partition));

        return catchUpFromResponders(stream, partition, answered);
    }

    private Promise<Unit> catchUpFromResponders(String stream, int partition, List<PeerWatermark> answered) {
        var local = selfWatermark.localWatermark(stream, partition);

        return confirmCaps(stream, partition, answered).flatMap(_ -> withoutDivergentPeers(stream,
                                                                                           partition,
                                                                                           local,
                                                                                           answered))
                          .flatMap(kept -> catchUpFromKept(stream, partition, local, kept));
    }

    /// Every probed peer's learned cap is confirmed after the probe round and before any ranking (#3142 F3).
    /// Recovery: design-out. [Promise#allOf] settles with each confirmation's own result and never fails, so a confirmation that
    /// fails leaves the cap as learned and never blocks the gate; the cap is only an advisory bound, and the catch-up it ranks
    /// still verifies what it installs.
    private Promise<Unit> confirmCaps(String stream, int partition, List<PeerWatermark> answered) {
        return Promise.allOf(answered.stream()
                                     .map(peer -> peerLineageConfirm.of(stream,
                                                                        partition,
                                                                        peer.node(),
                                                                        peer.watermark()))
                                     .toList()).map(_ -> Unit.unit());
    }

    /// #1730 phase 2 (KIP-101): a candidate ELECTED from the committed ISR holds every acknowledged record, because an
    /// acknowledgement needed every member of the ISR in force (phase 1). A peer whose records differ from the
    /// candidate's within the overlap therefore carries a tail nobody acknowledged: an ex-owner that returned, or an ISR
    /// member that lagged an election. That peer is left out of the catch-up (never the source, never compared again) and
    /// truncates itself when it backfills from the new owner. Only a candidate with a committed ISR it belongs to is
    /// exempt; a record minted before #1730 has none, and every other refusal (two peers disagreeing with each other,
    /// nothing comparable) is unchanged.
    private Promise<List<PeerWatermark>> withoutDivergentPeers(String stream,
                                                               int partition,
                                                               long local,
                                                               List<PeerWatermark> answered) {
        if (!isrElected(stream, partition)) {
            return Promise.success(answered);
        }

        return Promise.allOf(answered.stream()
                                     .map(peer -> divergesFromCandidate(stream, partition, local, peer))
                                     .toList()).map(verdicts -> keepAgreeing(stream, partition, answered, verdicts));
    }

    private boolean isrElected(String stream, int partition) {
        return records.committed(stream, partition)
                      .filter(record -> record.committedIsrNames(self))
                      .isPresent();
    }

    /// The first offset, within the overlap, at which `peer` holds a record that differs from the candidate's, or none. A peer
    /// that cannot be read is not judged here; the existing comparisons report it as they did before.
    private Promise<Option<Long>> divergesFromCandidate(String stream, int partition, long local, PeerWatermark peer) {
        var to = Math.min(local, peer.watermark());

        if (to < 0) {
            return Promise.success(Option.none());
        }

        var from = Math.max(0L, to - OVERLAP_WINDOW + 1);

        return Promise.all(ranges.read(self, stream, partition, from, to),
                           ranges.read(peer.node(),
                                       stream,
                                       partition,
                                       from,
                                       to))
                      .map(OwnerActivation::firstDifference)
                      .flatMap(difference -> difference.fold(() -> Promise.success(Option.<Long> none()),
                                                             offset -> inPeersRing(stream, partition, peer, offset)))
                      .recover(_ -> Option.none());
    }

    /// `offset` when the peer holds it in its RING (it is at or above the peer's ring tail); none when it lies below it (sealed
    /// data) or the tail is not known.
    private Promise<Option<Long>> inPeersRing(String stream, int partition, PeerWatermark peer, long offset) {
        return peerRingTail.of(peer.node(),
                               stream,
                               partition)
                           .map(tail -> tail.filter(ringTail -> offset >= ringTail)
                                            .map(_ -> offset));
    }

    /// The lowest offset present in both ranges whose records differ.
    private static Option<Long> firstDifference(List<OffHeapRingBuffer.RawEvent> mine,
                                                List<OffHeapRingBuffer.RawEvent> theirs) {
        var theirRecords = new HashSet<>(theirs);
        var theirOffsets = theirs.stream().map(OffHeapRingBuffer.RawEvent::offset).collect(Collectors.toSet());

        return Option.from(mine.stream()
                               .filter(event -> theirOffsets.contains(event.offset()))
                               .filter(event -> !theirRecords.contains(event))
                               .map(OffHeapRingBuffer.RawEvent::offset)
                               .min(Long::compare));
    }

    /// B6 (the design's Q6): the gate relaxes, excluding a divergent peer, ONLY when the candidate is ISR-elected, the peer is a
    /// replica of the partition, and the divergence lies ABOVE the candidate's durable sealed floor. A divergence at or below it
    /// is in offsets already sealed into segments, which no truncation will remove (the peer's repair refuses below the floor
    /// and the partition stays flagged), so there is nothing to discard and activation stays refused as before. The peer's own
    /// sealed floor is not known here; the candidate's is the bound.
    private static boolean relaxable(Result<Option<Long>> verdict, long floor) {
        return verdict.or(Option.none())
                      .filter(offset -> offset > floor)
                      .isPresent();
    }

    private List<PeerWatermark> keepAgreeing(String stream,
                                             int partition,
                                             List<PeerWatermark> answered,
                                             List<Result<Option<Long>>> verdicts) {
        var kept = new ArrayList<PeerWatermark>();
        var floor = sealedFloor.lastSealedOffset(stream, partition);

        for (var i = 0; i < answered.size(); i++) {
            if (relaxable(verdicts.get(i), floor)) {
                log.warn("Owner activation of {}[{}]: {} holds records that differ from this elected owner's (watermark {}, "
                        + "local {}); it is left out of the catch-up and truncates its divergent tail when it backfills from this node",
                         stream,
                         partition,
                         answered.get(i).node(),
                         answered.get(i).watermark(),
                         selfWatermark.localWatermark(stream, partition));
                peerRows.reset(stream,
                               partition,
                               answered.get(i).node());
            } else {
                kept.add(answered.get(i));
            }
        }

        return List.copyOf(kept);
    }

    private Promise<Unit> catchUpFromKept(String stream, int partition, long local, List<PeerWatermark> answered) {
        var source = Option.from(answered.stream()
                                         .filter(peer -> lineageEnd(stream, partition, peer) > local)
                                         .max(Comparator.comparingLong(peer -> lineageEnd(stream, partition, peer))));
        var sourceEnd = source.map(peer -> lineageEnd(stream, partition, peer));
        var others = answered.stream().filter(peer -> source.filter(peer::equals)
                                                            .isEmpty()).toList();

        return verifyAgreement(stream, partition, local, others).flatMap(_ -> catchUpFrom(stream,
                                                                                          partition,
                                                                                          local,
                                                                                          source,
                                                                                          others))
                              .flatMap(_ -> rerankIfClipped(stream, partition, source, sourceEnd, answered));
    }

    /// #3071: a peer is ranked by its committed-lineage end ([#peerLineageEnd]), never its raw watermark, so a deposed epoch's tail
    /// the catch-up refuses never counts as ahead. The end of a peer is learned by the pull that clips it: when this pull lowered
    /// the source's end, the responders are ranked again against the new local head, since another one may hold more of the
    /// lineage. Bounded: a re-rank needs a source whose end dropped in that very pull, and a peer's end drops at most once for the
    /// tail it holds.
    private Promise<Unit> rerankIfClipped(String stream,
                                          int partition,
                                          Option<PeerWatermark> source,
                                          Option<Long> sourceEnd,
                                          List<PeerWatermark> answered) {
        return source.filter(peer -> sourceEnd.filter(end -> lineageEnd(stream, partition, peer) < end)
                                              .isPresent())
                     .fold(() -> Promise.success(Unit.unit()),
                           _ -> catchUpFromKept(stream,
                                                partition,
                                                selfWatermark.localWatermark(stream, partition),
                                                answered));
    }

    /// The offset up to which `peer` is a source for this owner ([#peerLineageEnd]).
    private long lineageEnd(String stream, int partition, PeerWatermark peer) {
        return peerLineageEnd.of(stream, partition, peer.node(), peer.watermark());
    }

    /// Every member answered: the unreachable run is over, and a report of it no longer describes the partition.
    private Unit clearUnreachable(PartitionKey key) {
        unreachableSince.remove(key);
        silentSince.remove(key);
        pendingEscapes.remove(key);
        synchronized (alarmOrder) {
            ended(unreachableBlocks.remove(key));
        }

        return Unit.unit();
    }

    /// Every responder that is not the catch-up source must agree with the local log where both hold records:
    /// a lower (or equal) peer that disagrees means the local tail, or the peer's, belongs to another lineage.
    private Promise<Unit> verifyAgreement(String stream, int partition, long local, List<PeerWatermark> others) {
        return Promise.allOf(others.stream()
                                   .map(peer -> verifyOverlap(stream, partition, local, peer).withFailure(cause -> flagBudgetHolder(stream,
                                                                                                                                    partition,
                                                                                                                                    local,
                                                                                                                                    peer,
                                                                                                                                    cause)))
                                   .toList())
                      .flatMap(results -> Result.allOf(results).async())
                      .mapToUnit();
    }

    /// The refusal that names a peer deferred for off-heap budget is reported (once per distinct block) rather than
    /// only debug-logged by the re-drive: a paced peer clears when a slot frees, a budget-deferred one does not.
    @Contract
    private void flagBudgetHolder(String stream, int partition, long local, PeerWatermark peer, Cause cause) {
        if (StreamError.PartitionHeldNotMaterialized.isBudgetExhausted(cause)) {
            report(PartitionKey.partitionKey(stream, partition),
                   new ActivationBlock.HolderBudgetDeferred(stream, partition, peer.node(), local, peer.watermark()));
        }
    }

    private Promise<Unit> catchUpFrom(String stream,
                                      int partition,
                                      long local,
                                      Option<PeerWatermark> source,
                                      List<PeerWatermark> others) {
        return source.fold(() -> Promise.success(Unit.unit()),
                           highest -> verifySource(stream, partition, local, highest, others));
    }

    /// The source must agree with the local log, and with every other responder, where each pair holds records
    /// before its suffix is appended on top.
    private Promise<Unit> verifySource(String stream,
                                       int partition,
                                       long local,
                                       PeerWatermark highest,
                                       List<PeerWatermark> others) {
        return verifySourceOverlap(stream, partition, local, highest).flatMap(_ -> verifySourceAgainstPeers(stream,
                                                                                                            partition,
                                                                                                            local,
                                                                                                            highest,
                                                                                                            others))
                                  .flatMap(_ -> pullSuffix(stream, partition, highest))
                                  .withFailure(cause -> flagBudgetHolder(stream, partition, local, highest, cause));
    }

    /// Pairwise agreement of the source with every other responder (v1555 on #1555). The local log covers only
    /// offsets up to the candidate's own head, so a candidate that lags both lineages — a lagging or freshly
    /// joined node HRW may pick — agrees with each of them and would pull the source's suffix over records
    /// another peer acknowledged. Two prefixes of ONE lineage never disagree: only offsets both hold are compared.
    ///
    /// Only peers ABOVE the candidate's head are compared pairwise. The pull appends the source's offsets above the
    /// candidate's head, and a peer at or below that head holds none of them: its records are checked against the
    /// candidate's own (lenient comparison), never overwritten by the suffix. Comparing it with the source too would
    /// only refuse a healthy promotion whenever the source has evicted a lagging replica's range.
    private Promise<Unit> verifySourceAgainstPeers(String stream,
                                                   int partition,
                                                   long local,
                                                   PeerWatermark source,
                                                   List<PeerWatermark> others) {
        return Promise.allOf(others.stream()
                                   .filter(peer -> lineageEnd(stream, partition, peer) > local)
                                   .map(peer -> verifyPair(stream, partition, source, peer))
                                   .toList())
                      .flatMap(results -> Result.allOf(results).async())
                      .mapToUnit();
    }

    /// Compare the last [#OVERLAP_WINDOW] offsets both peers' heads cover.
    private Promise<Unit> verifyPair(String stream, int partition, PeerWatermark source, PeerWatermark peer) {
        return verifyPair(stream, partition, source, peer, false);
    }

    private Promise<Unit> verifyPair(String stream,
                                     int partition,
                                     PeerWatermark source,
                                     PeerWatermark peer,
                                     boolean learned) {
        var to = Math.min(lineageEnd(stream, partition, source), lineageEnd(stream, partition, peer));

        if (to < 0) {
            return Promise.success(Unit.unit());
        }

        var from = Math.max(0L, to - OVERLAP_WINDOW + 1);

        return Promise.all(ranges.read(source.node(),
                                       stream,
                                       partition,
                                       from,
                                       to),
                           ranges.read(peer.node(),
                                       stream,
                                       partition,
                                       from,
                                       to))
                      .flatMap((sourceRange, peerRange) -> pairAgreesOrRefuse(stream,
                                                                              partition,
                                                                              source,
                                                                              peer,
                                                                              sourceRange,
                                                                              peerRange,
                                                                              learned));
    }

    private Promise<Unit> pairAgreesOrRefuse(String stream,
                                             int partition,
                                             PeerWatermark source,
                                             PeerWatermark peer,
                                             List<OffHeapRingBuffer.RawEvent> sourceRange,
                                             List<OffHeapRingBuffer.RawEvent> peerRange,
                                             boolean learned) {
        if (compared(sourceRange, peerRange) == 0) {
            return refuseDivergent(new ActivationBlock.OverlapUnverifiable(stream,
                                                                           partition,
                                                                           source.node(),
                                                                           source.watermark(),
                                                                           peer.node(),
                                                                           peer.watermark()));
        }

        return agree(sourceRange, peerRange)
               ? Promise.success(Unit.unit())
               : learnOrRefuse(stream, partition, source, peer, firstDifference(sourceRange, peerRange).or(0L), learned);
    }

    /// #3080: a pair that differs is refused only once the bound of each side has been learned from its own page. A responder
    /// whose differing records are a deposed epoch's tail is then judged only up to its bound, and the pair compared again agrees;
    /// one whose records differ with no bound to explain it is refused as before.
    private Promise<Unit> learnOrRefuse(String stream,
                                        int partition,
                                        PeerWatermark source,
                                        PeerWatermark peer,
                                        long differsAt,
                                        boolean learned) {
        return learned
               ? refuseDivergent(new ActivationBlock.DivergentPeers(stream,
                                                                    partition,
                                                                    source.node(),
                                                                    source.watermark(),
                                                                    peer.node(),
                                                                    peer.watermark()))
               : Promise.all(learnBound(stream, partition, source, differsAt),
                             learnBound(stream, partition, peer, differsAt)).flatMap((_, _) -> verifyPair(stream,
                                                                                                          partition,
                                                                                                          source,
                                                                                                          peer,
                                                                                                          true));
    }

    /// Recovery: design-out. A page that cannot be read teaches nothing, and the pair is compared again exactly as before, so the
    /// refusal that follows is the one the gate raised without learning.
    private Promise<Unit> learnBound(String stream, int partition, PeerWatermark peer, long from) {
        return peerLineageLearn.of(stream,
                                   partition,
                                   peer.node(),
                                   peer.watermark(),
                                   from)
                               .recover(_ -> Unit.unit());
    }

    /// Compare the last [#OVERLAP_WINDOW] offsets both heads cover; nothing overlaps when either side is empty.
    private Promise<Unit> verifyOverlap(String stream, int partition, long local, PeerWatermark peer) {
        var to = Math.min(local, lineageEnd(stream, partition, peer));

        if (to < 0) {
            return Promise.success(Unit.unit());
        }

        var from = Math.max(0L, to - OVERLAP_WINDOW + 1);

        return Promise.all(ranges.read(self, stream, partition, from, to),
                           ranges.read(peer.node(),
                                       stream,
                                       partition,
                                       from,
                                       to))
                      .flatMap((mine, theirs) -> agreeOrRefuse(stream, partition, local, peer, mine, theirs));
    }

    /// The candidate against the catch-up SOURCE, whose suffix is then pulled on top of the candidate's log: as
    /// [#verifyOverlap], and in addition the comparison must reach at least one record. A source that holds none
    /// of the window (its ring and tier start above the candidate's head) cannot be verified, so it is refused
    /// rather than trusted; a lower peer nobody pulls from stays lenient.
    private Promise<Unit> verifySourceOverlap(String stream, int partition, long local, PeerWatermark source) {
        var to = Math.min(local, source.watermark());

        if (to < 0) {
            return Promise.success(Unit.unit());
        }

        var from = Math.max(0L, to - OVERLAP_WINDOW + 1);

        return Promise.all(ranges.read(self, stream, partition, from, to),
                           ranges.read(source.node(),
                                       stream,
                                       partition,
                                       from,
                                       to))
                      .flatMap((mine, theirs) -> sourceAgreesOrRefuse(stream, partition, local, source, mine, theirs));
    }

    private Promise<Unit> sourceAgreesOrRefuse(String stream,
                                               int partition,
                                               long local,
                                               PeerWatermark source,
                                               List<OffHeapRingBuffer.RawEvent> mine,
                                               List<OffHeapRingBuffer.RawEvent> theirs) {
        return compared(mine, theirs) == 0
               ? refuseDivergent(new ActivationBlock.OverlapUnverifiable(stream,
                                                                         partition,
                                                                         source.node(),
                                                                         source.watermark(),
                                                                         self,
                                                                         local))
               : agreeOrRefuse(stream, partition, local, source, mine, theirs);
    }

    private Promise<Unit> agreeOrRefuse(String stream,
                                        int partition,
                                        long local,
                                        PeerWatermark peer,
                                        List<OffHeapRingBuffer.RawEvent> mine,
                                        List<OffHeapRingBuffer.RawEvent> theirs) {
        return agree(mine, theirs)
               ? Promise.success(Unit.unit())
               : refuseDivergent(new ActivationBlock.DivergentPeer(stream,
                                                                   partition,
                                                                   peer.node(),
                                                                   local,
                                                                   peer.watermark()));
    }

    /// How many offsets both ranges hold.
    private static long compared(List<OffHeapRingBuffer.RawEvent> mine, List<OffHeapRingBuffer.RawEvent> theirs) {
        var theirOffsets = theirs.stream().map(OffHeapRingBuffer.RawEvent::offset).collect(Collectors.toSet());

        return mine.stream()
                   .filter(event -> theirOffsets.contains(event.offset()))
                   .count();
    }

    /// Two ranges agree when every offset present in both holds the same record there.
    private static boolean agree(List<OffHeapRingBuffer.RawEvent> mine, List<OffHeapRingBuffer.RawEvent> theirs) {
        var theirRecords = new HashSet<>(theirs);
        var theirOffsets = theirs.stream().map(OffHeapRingBuffer.RawEvent::offset).collect(Collectors.toSet());

        return mine.stream()
                   .filter(event -> theirOffsets.contains(event.offset()))
                   .allMatch(theirRecords::contains);
    }

    /// Logged by the alarm, once; every refused demand re-detects it silently.
    private Promise<Unit> refuseDivergent(ActivationBlock block) {
        report(PartitionKey.partitionKey(block.streamName(), block.partition()),
               block);

        return block.promise();
    }

    /// Record the block and raise the alarm when it first appears or changes, so a partition that stays blocked
    /// across many demands is reported once.
    /// A block that stood has ended (#1937): the alarm is told, so a recovery reaches whoever was told of the block.
    private void ended(ActivationBlock block) {
        Option.option(block).onPresent(alarm::resolved);
    }

    private Unit report(PartitionKey key, ActivationBlock block) {
        synchronized (alarmOrder) {
            var previous = Option.option(blocks.put(key, block));

            previous.filter(last -> last.getClass() != block.getClass()).onPresent(this::ended);

            return previous.filter(block::equals)
                           .fold(() -> alarm.raise(block),
                                 _ -> Unit.unit());
        }
    }

    /// The catch-up is asked for the source's RAW tail: it clips a deposed epoch's tail itself and learns the source's
    /// committed-lineage end from that page. The run is then verified against that end, never the raw tail (#3071).
    private Promise<Unit> pullSuffix(String stream, int partition, PeerWatermark highest) {
        log.warn("Owner activation of {}[{}]: {} holds watermark {} ahead of local {} — catching up before acting as owner",
                 stream,
                 partition,
                 highest.node(),
                 highest.watermark(),
                 selfWatermark.localWatermark(stream, partition));

        return catchUp.catchUp(stream,
                               partition,
                               highest.node(),
                               highest.watermark())
                      .flatMap(_ -> verifyReached(stream,
                                                  partition,
                                                  lineageEnd(stream, partition, highest)));
    }

    private Promise<Unit> verifyReached(String stream, int partition, long target) {
        return selfWatermark.localWatermark(stream, partition) >= target
               ? Promise.success(Unit.unit())
               : ActivationError.CATCH_UP_SHORT.promise();
    }
}
