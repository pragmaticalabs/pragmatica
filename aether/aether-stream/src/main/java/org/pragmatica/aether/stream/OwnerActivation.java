// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
import org.pragmatica.lang.io.TimeSpan;

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
/// Each probe attempt itself times out (the forward-read timeout) and the next demand retries, but there is no
/// bound independent of DEAD: **a member that keeps its transport handshaking while answering nothing never
/// reaches DEAD, and promotion then blocks for as long as it stays in the live set — an outage of that partition
/// with no recovery but removing the member (#1563: a black-holed peer that re-handshakes; the production form
/// would be a wedged JVM whose network stack still completes handshakes).**
///
/// **Overlap verification (#1555 item 7, the KIP-101 interim).** No ring or WAL record carries an owner epoch,
/// so a returning ex-owner's never-acked tail (the same offsets, written under an older ownership) is
/// indistinguishable by HEAD from the acked history that replaced it. Before any peer is used as catch-up source,
/// and for every other responder, the last [#OVERLAP_WINDOW] offsets both hold are compared record by record
/// (offset, timestamp, payload — the #1505 notion of "the same event"); the catch-up source is also compared
/// PAIRWISE with every other responder, because the local log covers only offsets up to the candidate's own head
/// and a candidate lagging two lineages agrees with both. Any disagreement REFUSES activation,
/// whichever side is higher: without an epoch nothing here can tell which of the two lineages was acknowledged,
/// so neither is served and neither is pulled. The refusal is reported once through the [BlockAlarm] and on
/// [#blockOf] (the partition status read); the partition then waits for an operator to pick the source
/// (#1569's pick-source surface, AD14). The detect-and-flag follow-up over a durable per-log epoch history is
/// #1596; the cluster never auto-truncates.
///
/// **The window is a named constant, not a derived bound, and the check is incomplete beyond it.** A divergent
/// tail is at most what an owner appended beyond its last acknowledged offset, and nothing caps that per
/// partition: the pre-append floor (`ReplicationManager.ensureReplicaFloor`) requires in-sync peers to EXIST,
/// and `ReplicaRegistry.freshPeersFor` bounds a peer's lag against the freshest PEER, not against the owner's
/// head — when every peer stops acking together all lags stay 0 and the owner keeps appending. A divergence
/// that starts more than [#OVERLAP_WINDOW] offsets below the lower of the two heads is therefore not detected.
///
/// **An unreachable member that stays unreachable is reported, not bypassed (#1555 item 8).** After
/// `unreachableAlarmAfter` of continuous probe failure the partition stays blocked and the block is reported
/// once through the [BlockAlarm] and on [#blockOf], naming the unreachable members and the responders; the
/// operator path is #1569's surface. An automatic bound is post-GA (#1579): it needs the ack-time replica set
/// to be durable, which it is not — replica sets are HRW over live members, recomputed as membership changes.
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
        Unit raise(ActivationBlock block);
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

    /// The committed record each partition was activated for; [Option#none] marks a first-owner activation.
    private final Map<PartitionKey, Option<StreamPartitionOwnershipValue>> activated = new ConcurrentHashMap<>();

    private final Set<PartitionKey> inFlight = ConcurrentHashMap.newKeySet();
    /// The block currently reported for each partition; the alarm fires when it first appears or changes.
    private final Map<PartitionKey, ActivationBlock> blocks = new ConcurrentHashMap<>();
    /// When the current run of probe failures started (`System.nanoTime`), per partition.
    private final Map<PartitionKey, Long> unreachableSince = new ConcurrentHashMap<>();

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
                            TimeSpan unreachableAlarmAfter) {
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
                                   unreachableAlarmAfter);
    }

    /// Whether this node may act as owner of `(stream, partition)` right now: its consensus engine is active,
    /// it is the owner (the committed record names it, or no record exists and placement names it), and it was
    /// activated for exactly the record it currently holds.
    public boolean isActivated(String stream, int partition) {
        var current = records.committed(stream, partition);

        return consensusActive.getAsBoolean()
               && claimsOwnership(stream, partition, current)
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

    /// The block reported for `(stream, partition)`, if its promotion currently waits for an operator.
    public Option<ActivationBlock> blockOf(String stream, int partition) {
        return Option.option(blocks.get(PartitionKey.partitionKey(stream, partition)));
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

    private Unit clearBlock(PartitionKey key) {
        blocks.remove(key);
        unreachableSince.remove(key);

        return Unit.unit();
    }

    /// A committed record decides ownership; only without one does placement decide.
    private boolean claimsOwnership(String stream, int partition, Option<StreamPartitionOwnershipValue> record) {
        return record.fold(() -> placementOwner.isPlacementOwner(stream, partition),
                           value -> value.owner()
                                         .equals(self));
    }

    /// Step 2: probe every other live placement member; any unreachable member blocks, every responder's overlap
    /// with the local log is verified, and a higher watermark is pulled from its holder.
    private Promise<Unit> catchUpToLiveHolders(String stream, int partition) {
        var peers = liveMembers.get().stream().filter(member -> !member.equals(self)).toList();

        if (peers.isEmpty()) {
            return Promise.success(Unit.unit());
        }

        return Promise.allOf(peers.stream()
                                  .map(peer -> probe.probe(peer, stream, partition)
                                                    .map(watermark -> new PeerWatermark(peer, watermark)))
                                  .toList()).flatMap(results -> reconcileWithPeers(stream, partition, peers, results));
    }

    private Promise<Unit> reconcileWithPeers(String stream,
                                             int partition,
                                             List<NodeId> peers,
                                             List<Result<PeerWatermark>> results) {
        var answered = results.stream().flatMap(result -> result.option()
                                                                .stream()).toList();

        return answered.size() < peers.size()
               ? holdersUnreachable(stream, partition, peers, answered)
               : catchUpFromHighest(stream, partition, answered);
    }

    /// A member did not answer: refuse, and once the failures have run continuously for longer than
    /// `unreachableAlarmAfter`, report the block.
    private Promise<Unit> holdersUnreachable(String stream,
                                             int partition,
                                             List<NodeId> peers,
                                             List<PeerWatermark> answered) {
        var key = PartitionKey.partitionKey(stream, partition);
        var since = unreachableSince.computeIfAbsent(key, _ -> System.nanoTime());

        if (System.nanoTime() - since > unreachableAlarmAfter.nanos()) {
            var responders = answered.stream().map(PeerWatermark::node).toList();

            report(key,
                   new ActivationBlock.HoldersUnreachable(stream,
                                                          partition,
                                                          peers.stream()
                                                               .filter(peer -> !responders.contains(peer))
                                                               .toList(),
                                                          responders,
                                                          unreachableAlarmAfter));
        }

        return ActivationError.HOLDER_UNREACHABLE.promise();
    }

    private Promise<Unit> catchUpFromHighest(String stream, int partition, List<PeerWatermark> answered) {
        clearUnreachable(PartitionKey.partitionKey(stream, partition));
        var local = selfWatermark.localWatermark(stream, partition);
        var source = Option.from(answered.stream()
                                         .filter(peer -> peer.watermark() > local)
                                         .max(Comparator.comparingLong(PeerWatermark::watermark)));
        var others = answered.stream().filter(peer -> source.filter(peer::equals)
                                                            .isEmpty()).toList();

        return verifyAgreement(stream, partition, local, others).flatMap(_ -> catchUpFrom(stream,
                                                                                          partition,
                                                                                          local,
                                                                                          source,
                                                                                          others));
    }

    /// Every member answered: the unreachable run is over, and a report of it no longer describes the partition.
    private Unit clearUnreachable(PartitionKey key) {
        unreachableSince.remove(key);
        Option.option(blocks.get(key))
              .filter(ActivationBlock.HoldersUnreachable.class::isInstance)
              .onPresent(block -> blocks.remove(key, block));

        return Unit.unit();
    }

    /// Every responder that is not the catch-up source must agree with the local log where both hold records:
    /// a lower (or equal) peer that disagrees means the local tail, or the peer's, belongs to another lineage.
    private Promise<Unit> verifyAgreement(String stream, int partition, long local, List<PeerWatermark> others) {
        return Promise.allOf(others.stream().map(peer -> verifyOverlap(stream, partition, local, peer)).toList())
                      .flatMap(results -> Result.allOf(results).async())
                      .mapToUnit();
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
        return verifyOverlap(stream, partition, local, highest).flatMap(_ -> verifySourceAgainstPeers(stream,
                                                                                                        partition,
                                                                                                        highest,
                                                                                                        others))
                            .flatMap(_ -> pullSuffix(stream, partition, highest));
    }

    /// Pairwise agreement of the source with every other responder (v1555 on #1555). The local log covers only
    /// offsets up to the candidate's own head, so a candidate that lags both lineages — a lagging or freshly
    /// joined node HRW may pick — agrees with each of them and would pull the source's suffix over records
    /// another peer acknowledged. Two prefixes of ONE lineage never disagree: only offsets both hold are compared.
    private Promise<Unit> verifySourceAgainstPeers(String stream,
                                                   int partition,
                                                   PeerWatermark source,
                                                   List<PeerWatermark> others) {
        return Promise.allOf(others.stream().map(peer -> verifyPair(stream, partition, source, peer)).toList())
                      .flatMap(results -> Result.allOf(results).async())
                      .mapToUnit();
    }

    /// Compare the last [#OVERLAP_WINDOW] offsets both peers' heads cover.
    private Promise<Unit> verifyPair(String stream, int partition, PeerWatermark source, PeerWatermark peer) {
        var to = Math.min(source.watermark(), peer.watermark());

        if (to < 0) {
            return Promise.success(Unit.unit());
        }

        var from = Math.max(0L, to - OVERLAP_WINDOW + 1);

        return Promise.all(ranges.read(source.node(), stream, partition, from, to),
                           ranges.read(peer.node(), stream, partition, from, to))
                      .flatMap((sourceRange, peerRange) -> pairAgreesOrRefuse(stream,
                                                                              partition,
                                                                              source,
                                                                              peer,
                                                                              sourceRange,
                                                                              peerRange));
    }

    private Promise<Unit> pairAgreesOrRefuse(String stream,
                                             int partition,
                                             PeerWatermark source,
                                             PeerWatermark peer,
                                             List<OffHeapRingBuffer.RawEvent> sourceRange,
                                             List<OffHeapRingBuffer.RawEvent> peerRange) {
        return agree(sourceRange, peerRange)
               ? Promise.success(Unit.unit())
               : refuseDivergent(new ActivationBlock.DivergentPeers(stream,
                                                                    partition,
                                                                    source.node(),
                                                                    source.watermark(),
                                                                    peer.node(),
                                                                    peer.watermark()));
    }

    /// Compare the last [#OVERLAP_WINDOW] offsets both heads cover; nothing overlaps when either side is empty.
    private Promise<Unit> verifyOverlap(String stream, int partition, long local, PeerWatermark peer) {
        var to = Math.min(local, peer.watermark());

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
    private Unit report(PartitionKey key, ActivationBlock block) {
        var previous = Option.option(blocks.put(key, block));

        return previous.filter(block::equals)
                       .fold(() -> alarm.raise(block),
                             _ -> Unit.unit());
    }

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
                                                  highest.watermark()));
    }

    private Promise<Unit> verifyReached(String stream, int partition, long target) {
        return selfWatermark.localWatermark(stream, partition) >= target
               ? Promise.success(Unit.unit())
               : ActivationError.CATCH_UP_SHORT.promise();
    }
}
