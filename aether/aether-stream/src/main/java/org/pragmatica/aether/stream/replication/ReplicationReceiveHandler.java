// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.stream.replication.DefaultReplicationManager.DEFAULT_ACK_TIMEOUT;
import static org.pragmatica.aether.stream.replication.DefaultReplicationManager.FIRST_RESEND_BACKOFF;
import static org.pragmatica.aether.stream.replication.DefaultReplicationManager.nextBackoff;
import static org.pragmatica.aether.stream.replication.PartitionKey.partitionKey;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.utility.warning.OperatorWarningCode.REPLICA_FSYNC_FAILED;


/// Replica-side receive/apply for the A6 replication path.
///
/// An owner of `(stream, partition)` replicates each published event to its registered replica set
/// via {@link DefaultReplicationManager#replicateEvent} → {@link ReplicationTransport#send} carrying a
/// {@link ReplicationMessage.ReplicateEvents}. THIS handler runs on each replica: it lands the
/// replicated payloads into the local partition ring and acks the highest applied offset back to the
/// owner.
///
/// ## Offset verification against the local head (S1 / #260)
/// A replicated batch carries the OWNER-frame `fromOffset` it was published at. Before applying, the
/// handler compares it to the replica's own next-expected offset ({@link LocalHead#nextExpectedOffset}
/// = local ring head + 1, or 0 for an empty partition) so the owner-frame and replica-frame offsets
/// stay in lock-step — the apply is NEVER a blind "append at whatever the local head happens to be":
///   - `fromOffset == localNext` — contiguous: apply the batch (the normal path).
///   - `fromOffset  > localNext` — a GAP: one or more earlier batches were dropped/reordered, so
///     applying this batch now would land owner-offset-N events at the wrong local offsets and
///     permanently diverge. The batch is REJECTED (nothing applied) and `onGap` fires so the replica
///     re-enters SYNCING/backfill and pulls the missing prefix from a caught-up source.
///   - `fromOffset  < localNext` — a stale/duplicate re-delivery: the overlapping prefix is already
///     present locally. It is VERIFIED, not assumed (#1505): each held event must equal the offered one,
///     and only then is it skipped. The tail at-or-beyond `localNext` is appended. A batch fully below
///     `localNext` is acked at its end only when every one of its events is verified held.
///
/// `localNext` is read before the apply, so it only routes. The offset authority is the apply itself:
/// every event is offered at its OWN owner offset `fromOffset + i` through
/// {@link RecoveredAppender#appendRecovered}, backed by `StreamPartitionManager::appendRecovered`'s
/// offset-addressed overload. That overload decides append-here, verify-held or refuse inside the
/// partition's ordered append section, the same section the catch-up apply ({@link PartitionBackfill}) lands
/// through. A catch-up response racing a live batch for the same offsets therefore cannot shift either one.
/// The append does not re-invoke the replication manager, which is what stops an infinite
/// replicate→apply→replicate loop.
///
/// ## Divergent held event: quarantine (#1505 F2)
/// An offset `N` that holds a DIFFERENT event than the owner offers means this replica's log has diverged
/// from the owner's there. The partition manager then QUARANTINES the partition on this replica, inside the
/// same ordered section that found the divergence: every later offer at an offset `>= N`, in this batch or any
/// later one, live or duplicate, is refused as [StreamError.ReplicaQuarantined]. Nothing at or past `N` is
/// therefore ever acked, and an ack is cumulative on the owner, so no ack can cover `N`. Offsets below `N`
/// still verify and ack. The owner's confirmation barrier stops counting this replica at `N - 1` for as long as the
/// quarantine lasts.
///
/// The refusal still fires `onGap`. In production that runs the backfill orchestrator, which refuses every
/// self-promotion of a quarantined partition and demotes a CAUGHT_UP one to SYNCING ({@link PartitionBackfill}).
/// That is how a replica that was already CAUGHT_UP stops being one. The same orchestrator then REPAIRS the copy when
/// its owner is known and it is not the owner (#1730 phase 2, KIP-101): the tail is cut back to offset `N - 1`
/// (WAL, epoch history and ring, in the ring's ordered section) and refetched from the owner, which lifts the
/// quarantine. A copy that cannot be repaired stays quarantined until the manager is gone. The quarantine is logged
/// at ERROR once, by the partition manager. Persistence and owner-election exclusion are #1513.
///
/// ## Sender validation (#1230)
/// Before anything else, a batch whose sender cannot be the committed owner of the partition at the batch's
/// epoch is refused: nothing applied, nothing acked (not even a stale-duplicate re-ack) — see
/// `senderMayBeCommittedOwner`. The factories without a {@link CommittedStreamOwnerSource} validate
/// nothing (the no-owner source), exactly as before.
///
/// ## Ack
/// After applying the verified portion of a batch up to highest offset `H`, `H` is acked back so
/// {@link DefaultReplicationManager#handleAck} can advance the watermark and resolve any pending
/// `awaitReplication(minAcks)` promise. The ack always reflects the replica's VERIFIED state — never a
/// nominal batch end the replica did not actually reach contiguously.
///
/// ## Ack gate (B7, #2599)
/// A copy not yet compared with the committed owner of the current epoch acknowledges nothing ([AckGate]). The gate is read
/// BEFORE the batch is applied when the batch carries the committed epoch: the copy's trust for an epoch is fixed at its first
/// reading, from whether its head is still below the epoch's start, and reading it after the apply made a fresh replica doubt
/// the owner's own first batch. An ack the gate holds back is deferred, not dropped, and sent when a compare verifies the copy
/// ([#releaseDeferredAck]).
///
/// ## Partial-apply repair (M5)
/// A mid-batch append failure applies only the contiguous prefix `[applyFrom, applyFrom+applied-1]`
/// and leaves a gap for the tail of the batch. The handler acks ONLY that contiguous prefix (never the
/// batch's nominal end), so the owner's watermark view never over-states what landed. A partial apply
/// is NOT treated as success: the `onGap` repair seam is fired for `(streamName, partition)` so the
/// replica re-enters SYNCING/backfill and pulls the missing tail from a caught-up source. The default
/// seam is a no-op; production wires it to the partition-backfill executor (same seam the
/// {@link ReplicaSetController} uses for newly-added replicas).
public final class ReplicationReceiveHandler {
    private static final Logger log = LoggerFactory.getLogger(ReplicationReceiveHandler.class);

    /// Non-replicating, OFFSET-ADDRESSED append seam carrying the SENDING owner's `ownerEpoch` fencing token
    /// (#345 item 1d-ii). In production this is `StreamPartitionManager::appendRecovered`'s offset-addressed
    /// overload, which fences the append against the replica's own partition high-water before landing it, so a
    /// deposed owner's batch is rejected at the replica's commit point. It succeeds with `offset` exactly when the
    /// replica now holds the offered event at `offset` (#1505). That covers two cases: appended there now, or
    /// already held with identical content. It refuses without appending when offsets below `offset` are
    /// missing, or when a DIFFERENT event is held at `offset` ([StreamError.ReplicaEntryConflict]).
    @FunctionalInterface
    public interface RecoveredAppender {
        Result<Long> appendRecovered(String streamName,
                                     int partition,
                                     long offset,
                                     byte[] payload,
                                     long timestamp,
                                     Epoch ownerEpoch);
    }

    /// Local next-expected-offset seam: the offset the NEXT contiguous append would be assigned for
    /// `(streamName, partition)` — local ring head + 1, or 0 for an empty/absent partition. In
    /// production this is `StreamPartitionManager::nextExpectedOffset`. Used to verify an incoming
    /// batch's owner-frame `fromOffset` before applying (S1 / #260).
    @FunctionalInterface
    public interface LocalHead {
        long nextExpectedOffset(String streamName, int partition);
    }

    /// #634 item 1: the durability barrier awaited BETWEEN applying a batch and acking it, so an ack
    /// means "fsynced here", not "in my RAM". Production wires `StreamPartitionManager::syncReplicated`;
    /// the default resolves immediately, which is byte-identical to the pre-barrier behaviour — unwired
    /// deployments (Forge, legacy tests, the explicit non-durable opt-in) keep their exact semantics.
    @FunctionalInterface
    public interface ReplicaDurability {
        Promise<Unit> sync(String streamName, int partition);
    }

    public static final ReplicaDurability NO_DURABILITY_BARRIER = (_, _) -> Promise.unitPromise();

    /// B7 (#1730 phase 2): whether this node may acknowledge what it holds to the owner. A copy that has not been compared
    /// with the committed owner of the current epoch (a demoted owner, a replica whose epoch advanced while it was away) holds
    /// records the owner may not hold, and an acknowledgement of a LATER offset would be counted for those too. Production wires
    /// `StreamPartitionManager::replicaVerified`; the default always acknowledges.
    @FunctionalInterface
    public interface AckGate {
        boolean mayAck(String streamName, int partition);
    }

    private volatile AckGate ackGate = (_, _) -> true;

    /// #2599: the ack a batch could not send because the [AckGate] held it: the batch's sender, the highest offset of its
    /// verified prefix, and its owner epoch. One per partition ([#deferredAcks]).
    private record DeferredAck(NodeId owner, long through, Epoch ownerEpoch) {
        static DeferredAck deferredAck(NodeId owner, long through, Epoch ownerEpoch) {
            return new DeferredAck(owner, through, ownerEpoch);
        }

        /// The value kept when `fresh` is deferred over this one: the fresh one, unless it is of an older epoch, which a
        /// release drops and so would lose this one's ack, or a lower re-delivery under the same epoch, which would lower what is
        /// released.
        DeferredAck mergedWith(DeferredAck fresh) {
            return ownerEpoch.isStrictlyAfter(fresh.ownerEpoch()) || fresh.ownerEpoch()
                                                                          .equals(ownerEpoch) && fresh.through() < through
                   ? this
                   : fresh;
        }

        /// Whether a live ack of `acked` under `epoch` leaves this one still to send: only a higher offset in the same epoch
        /// is not covered by it. One of another epoch is dropped, as a release would drop it.
        boolean outlives(long acked, Epoch epoch) {
            return ownerEpoch.equals(epoch) && through > acked;
        }

        /// Whether a backfill's ack of `acked` under `epoch` settles this one: an offset not above it in the same epoch. One of
        /// another epoch is kept for its own release.
        boolean settledBy(long acked, Epoch epoch) {
            return ownerEpoch.equals(epoch) && through <= acked;
        }
    }

    /// #2599: the deferred acks ([DeferredAck]), at most one per partition. Every ack this handler sends goes out inside
    /// this map's per-key section, and so does the backfill's ack of its watermark once wired ([#ackBackfill]), so a deferred
    /// ack is never released after a live ack that covers it or a backfill ack of its epoch at or above it.
    private final ConcurrentHashMap<PartitionKey, DeferredAck> deferredAcks = new ConcurrentHashMap<>();

    /// #2847: an ack sent to `owner`, re-sendable until `deadlineNanos` (the owner's ack timeout from its first send).
    private record SentAck(NodeId owner, ReplicationMessage.ReplicateAck ack, long deadlineNanos) {
        static SentAck sentAck(NodeId owner, ReplicationMessage.ReplicateAck ack, long deadlineNanos) {
            return new SentAck(owner, ack, deadlineNanos);
        }
    }

    /// #2847: the latest ack sent per partition. Every ack is sent inside this map's per-key section, and a refused one is
    /// re-sent only while it is still its partition's latest: the owner takes an ack's offset as given
    /// (`ReplicaRegistry#updateWatermark` overwrites), so a re-send landing after a newer ack would lower the replica's
    /// confirmed offset there.
    private final ConcurrentHashMap<PartitionKey, SentAck> latestAcks = new ConcurrentHashMap<>();
    private volatile Fn2<ScheduledFuture<?>, Runnable, TimeSpan> resendScheduler = SharedScheduler::schedule;
    private volatile LongSupplier nanoClock = System::nanoTime;

    /// Test seam (#2847): `scheduler` schedules the re-sends of an ack the transport refused to write, and `clock` is the clock
    /// their deadline is measured on, so a test drives both attempts and deadline by hand. Set once, before the first ack.
    @Contract
    void ackResendTimer(Fn2<ScheduledFuture<?>, Runnable, TimeSpan> scheduler, LongSupplier clock) {
        this.resendScheduler = scheduler;
        this.nanoClock = clock;
    }

    /// Late-bind the [AckGate]. Set once at wiring.
    @Contract
    public void ackGate(AckGate gate) {
        this.ackGate = gate;
    }

    private final NodeId self;
    private final RecoveredAppender appender;
    private final LocalHead localHead;
    private final ReplicationTransport transport;
    private final BiConsumer<String, Integer> onGap;
    private final ReplicaDurability durability;
    private final CommittedStreamOwnerSource committedOwners;
    private final OperatorWarningSink operatorWarnings;
    private final LongSupplier bootToken;

    private ReplicationReceiveHandler(NodeId self,
                                      RecoveredAppender appender,
                                      LocalHead localHead,
                                      ReplicationTransport transport,
                                      BiConsumer<String, Integer> onGap,
                                      ReplicaDurability durability,
                                      CommittedStreamOwnerSource committedOwners,
                                      OperatorWarningSink operatorWarnings,
                                      LongSupplier bootToken) {
        this.self = self;
        this.appender = appender;
        this.localHead = localHead;
        this.transport = transport;
        this.onGap = onGap;
        this.durability = durability;
        this.committedOwners = committedOwners;
        this.operatorWarnings = operatorWarnings;
        this.bootToken = bootToken;
    }

    /// The boot token of a handler wired without a process identity: its acks name no process (#1697), so the owner only ever
    /// raises its view of this replica on them.
    private static final LongSupplier NO_BOOT_TOKEN = () -> ReplicationMessage.ReplicateAck.NO_BOOT_TOKEN;

    /// Backward-compatible factory with no local-head verification: the incoming `fromOffset` is
    /// trusted to be contiguous (a fresh/empty replica fed strictly in order). Retained for callers
    /// that cannot supply a local-head view; new wiring should prefer the verifying factory.
    public static ReplicationReceiveHandler replicationReceiveHandler(NodeId self,
                                                                      RecoveredAppender appender,
                                                                      ReplicationTransport transport) {
        return new ReplicationReceiveHandler(self,
                                             appender,
                                             NO_LOCAL_HEAD,
                                             transport,
                                             (_, _) -> {},
                                             NO_DURABILITY_BARRIER,
                                             CommittedStreamOwnerSource.none(),
                                             OperatorWarningSink.logOnly(),
                                             NO_BOOT_TOKEN);
    }

    /// Factory with an explicit `onGap` repair seam, fired `(streamName, partition)` whenever a batch
    /// cannot apply in full so the replica re-enters SYNCING/backfill (M5). No local-head verification.
    public static ReplicationReceiveHandler replicationReceiveHandler(NodeId self,
                                                                      RecoveredAppender appender,
                                                                      ReplicationTransport transport,
                                                                      BiConsumer<String, Integer> onGap) {
        return new ReplicationReceiveHandler(self,
                                             appender,
                                             NO_LOCAL_HEAD,
                                             transport,
                                             onGap,
                                             NO_DURABILITY_BARRIER,
                                             CommittedStreamOwnerSource.none(),
                                             OperatorWarningSink.logOnly(),
                                             NO_BOOT_TOKEN);
    }

    /// Verifying factory (S1 / #260): `localHead` reports the replica's next-expected offset so an
    /// incoming batch's owner-frame `fromOffset` is checked for gaps/duplicates before applying.
    public static ReplicationReceiveHandler replicationReceiveHandler(NodeId self,
                                                                      RecoveredAppender appender,
                                                                      LocalHead localHead,
                                                                      ReplicationTransport transport,
                                                                      BiConsumer<String, Integer> onGap) {
        return new ReplicationReceiveHandler(self,
                                             appender,
                                             localHead,
                                             transport,
                                             onGap,
                                             NO_DURABILITY_BARRIER,
                                             CommittedStreamOwnerSource.none(),
                                             OperatorWarningSink.logOnly(),
                                             NO_BOOT_TOKEN);
    }

    /// Verifying factory WITH the replica durability barrier (#634 item 1). No sender validation.
    public static ReplicationReceiveHandler replicationReceiveHandler(NodeId self,
                                                                      RecoveredAppender appender,
                                                                      LocalHead localHead,
                                                                      ReplicationTransport transport,
                                                                      BiConsumer<String, Integer> onGap,
                                                                      ReplicaDurability durability) {
        return new ReplicationReceiveHandler(self,
                                             appender,
                                             localHead,
                                             transport,
                                             onGap,
                                             durability,
                                             CommittedStreamOwnerSource.none(),
                                             OperatorWarningSink.logOnly(),
                                             NO_BOOT_TOKEN);
    }

    /// Verifying factory WITH the replica durability barrier (#634 item 1) AND sender validation against
    /// the committed partition owner (#1230). Operator warnings are logged only.
    public static ReplicationReceiveHandler replicationReceiveHandler(NodeId self,
                                                                      RecoveredAppender appender,
                                                                      LocalHead localHead,
                                                                      ReplicationTransport transport,
                                                                      BiConsumer<String, Integer> onGap,
                                                                      ReplicaDurability durability,
                                                                      CommittedStreamOwnerSource committedOwners) {
        return replicationReceiveHandler(self,
                                         appender,
                                         localHead,
                                         transport,
                                         onGap,
                                         durability,
                                         committedOwners,
                                         OperatorWarningSink.logOnly(),
                                         NO_BOOT_TOKEN);
    }

    /// Full factory: the durability barrier, sender validation, the operator-warning sink that
    /// carries a withheld-ack fsync failure to the cluster event log (#1574), and the replica process's
    /// boot token every ack carries (#1697), so the owner keeps the highest ack of one process and lets a
    /// restarted process (which may hold less) replace it. This is the production wiring.
    public static ReplicationReceiveHandler replicationReceiveHandler(NodeId self,
                                                                      RecoveredAppender appender,
                                                                      LocalHead localHead,
                                                                      ReplicationTransport transport,
                                                                      BiConsumer<String, Integer> onGap,
                                                                      ReplicaDurability durability,
                                                                      CommittedStreamOwnerSource committedOwners,
                                                                      OperatorWarningSink operatorWarnings,
                                                                      LongSupplier bootToken) {
        return new ReplicationReceiveHandler(self,
                                             appender,
                                             localHead,
                                             transport,
                                             onGap,
                                             durability,
                                             committedOwners,
                                             operatorWarnings,
                                             bootToken);
    }

    @Contract
    @MessageReceiver
    public void onReplicateEvents(ReplicationMessage.ReplicateEvents message) {
        var streamName = message.streamName();
        var partition = message.partition();
        var payloads = message.payloads();
        var timestamps = message.timestamps();
        var fromOffset = message.fromOffset();

        if (!senderMayBeCommittedOwner(message)) {
            refuseUnauthorizedSender(message);

            return;
        }

        var localNext = resolveLocalNext(streamName, partition, fromOffset);

        if (fromOffset > localNext) {
            handleGapAhead(message, fromOffset, localNext);

            return;
        }

        applyAligned(message, streamName, partition, fromOffset, payloads, timestamps);
    }

    /// #1230: a batch is landed and acked only when its sender can be the committed owner of the partition
    /// at the batch's epoch. The owner for an epoch is known only when this replica's committed record is AT
    /// that epoch; there the sender must equal the recorded owner. A batch OLDER than the record comes from a
    /// deposed owner. A batch NEWER than the record means this replica's view lags a commit the sender has
    /// already observed — the owner-handoff flow — so it is not judged here. No record (cold start) leaves
    /// nothing to judge against. Production wires the RAW committed record, not the #568 liveness-filtered
    /// view: an owner this node's SWIM view has marked DEPARTED, or has not yet seen, is still the fenced
    /// writer until the leader commits a new owner. (The filter never drops a merely SUSPECT owner.)
    private boolean senderMayBeCommittedOwner(ReplicationMessage.ReplicateEvents message) {
        return senderMayBeCommittedOwner(message.streamName(),
                                         message.partition(),
                                         message.governorId(),
                                         message.ownerEpoch());
    }

    private boolean senderMayBeCommittedOwner(String streamName, int partition, NodeId sender, Epoch batchEpoch) {
        return committedOwners.committedOwner(streamName, partition)
                              .map(committed -> senderMatches(committed, sender, batchEpoch))
                              .or(true);
    }

    private static boolean senderMatches(CommittedStreamOwnerSource.CommittedOwner committed,
                                         NodeId sender,
                                         Epoch batchEpoch) {
        var committedEpoch = committed.ownerEpoch();

        return batchEpoch.isStrictlyAfter(committedEpoch) || batchEpoch.equals(committedEpoch) && committed.owner()
                                                                                                           .equals(sender);
    }

    /// What a replica does with the owner's visible position (#2087). Production wires
    /// `StreamPartitionManager::commitAdvanced`; the default drops it.
    @FunctionalInterface
    public interface CommitSink {
        @Contract
        void committed(String streamName, int partition, long committedThrough, Epoch ownerEpoch);
    }

    private volatile CommitSink commitSink = (_, _, _, _) -> {};

    /// Late-bind the [CommitSink]. Set once at wiring.
    @Contract
    public void commitSink(CommitSink sink) {
        this.commitSink = sink;
    }

    /// The owner's visible position (#2087), validated like a batch: from a sender that cannot be the committed owner
    /// at that epoch it is dropped, because a deposed owner's position describes a lineage this replica no longer follows.
    @Contract
    @MessageReceiver
    public void onCommitAdvance(ReplicationMessage.CommitAdvance message) {
        if (senderMayBeCommittedOwner(message.streamName(),
                                      message.partition(),
                                      message.governorId(),
                                      message.ownerEpoch())) {
            commitSink.committed(message.streamName(),
                                 message.partition(),
                                 message.committedThrough(),
                                 message.ownerEpoch());
        }
    }

    /// A batch from a node that cannot be the committed owner: nothing is applied and nothing is acked —
    /// not even the stale-duplicate re-ack, which would otherwise count this replica toward a non-owner's
    /// confirmation barrier for an offset holding a DIFFERENT event here. No gap repair either: this node's
    /// log is intact; the batch is simply not authoritative.
    private void refuseUnauthorizedSender(ReplicationMessage.ReplicateEvents message) {
        log.warn("ReplicationReceiveHandler: refusing batch for {}[{}] from {} at epoch {} — sender is not the committed owner "
                + "(#1230), nothing applied or acked",
                 message.streamName(),
                 message.partition(),
                 message.governorId(),
                 message.ownerEpoch());
    }

    /// `fromOffset > localNext`: an earlier batch is missing. Applying now would diverge — reject the
    /// whole batch and trigger backfill repair. No ack: the replica's verified offset is unchanged.
    private void handleGapAhead(ReplicationMessage.ReplicateEvents message, long fromOffset, long localNext) {
        log.warn("ReplicationReceiveHandler: gap for {}[{}] — batch starts at offset {} but next expected is {} "
                + "(missing {} earlier offsets) — rejecting batch, triggering backfill repair",
                 message.streamName(),
                 message.partition(),
                 fromOffset,
                 localNext,
                 fromOffset - localNext);
        onGap.accept(message.streamName(), message.partition());
    }

    /// `fromOffset <= localNext`: every event is offered at its own owner offset `fromOffset + i` (#1505). An
    /// offset already held is verified identical and skipped, and an offset at the head is appended. So an
    /// overlapping or wholly-duplicate batch is idempotent. The ack is the highest offset of the VERIFIED
    /// contiguous prefix of this batch, sent only after the durability barrier. A duplicate's ack therefore
    /// also waits for any catch-up write of the same offsets to be fsynced.
    ///
    /// The ack is this BATCH's verified prefix, not the replica's head, and the owner stores the last ack it
    /// receives (#1505 F4). So an old duplicate, or a batch that stops at a divergence, can lower the owner's
    /// view of this replica. After a divergence at `N` that lowering is the point: the old ack for `N` covered an
    /// entry now known to differ. For an old duplicate it is conservative, and the next live ack restores it.
    private void applyAligned(ReplicationMessage.ReplicateEvents message,
                              String streamName,
                              int partition,
                              long fromOffset,
                              List<byte[]> payloads,
                              List<Long> timestamps) {
        var trustedBeforeApply = trustedBeforeApply(message);
        var outcome = applyBatch(streamName, partition, fromOffset, payloads, timestamps, message.ownerEpoch());

        outcome.refusal().onPresent(cause -> reportShortApply(message, fromOffset + outcome.held(), cause));
        if (outcome.held() <= 0) {
            return;
        }

        var highestHeld = fromOffset + outcome.held() - 1;

        if (aheadOfCommittedView(message) || !trustedBeforeApply && !ackGate.mayAck(streamName, partition)) {
            deferAck(message, streamName, partition, highestHeld);

            return;
        }
        // Ack ONLY after the batch is fsynced here (#634 item 1): the owner's confirmation barrier counts
        // this ack as a durable copy, so acking from RAM would let correlated power loss inside the
        // unsealed window erase writes the caller was told reached RF. A failed sync WITHHOLDS the ack —
        // the record is applied and serveable, but this replica must not be counted toward durability;
        // the owner's barrier degrades honestly instead of over-counting.
        durability.sync(streamName, partition)
                  .onSuccess(_ -> sendLiveAck(message, streamName, partition, highestHeld))
                  .onFailure(cause -> reportWithheldAck(streamName, partition, highestHeld, cause));
    }

    /// #2599: the [AckGate] read on the head the batch found, before it is applied, and only when the batch carries the
    /// committed epoch. The copy's trust for an epoch is fixed at its first reading ([AckGate]): a head still below the epoch's
    /// start holds nothing of an older lineage at or above it, and every record the batch then adds there is this owner's or
    /// was verified identical to it ([#applyBatch] stops at a divergence). Read after the apply, the same fresh replica doubted
    /// the owner's own first batch. A batch of another epoch reads the gate after the apply only, as before.
    private boolean trustedBeforeApply(ReplicationMessage.ReplicateEvents message) {
        return isCommittedEpoch(message.streamName(), message.partition(), message.ownerEpoch()) && ackGate.mayAck(message.streamName(),
                                                                                                                   message.partition());
    }

    /// #2840: a batch stamped with an epoch NEWER than this replica's committed record is admitted ([#senderMatches]: the view lags
    /// a commit the sender has seen), but the [AckGate] judges the copy for the committed epoch, not for the batch's, so it would
    /// count records of an older lineage below the batch as the batch's own. Such a batch is applied and its ack deferred; the
    /// copy is acked for the new epoch only once the record catches up and a compare verifies the copy for it.
    private boolean aheadOfCommittedView(ReplicationMessage.ReplicateEvents message) {
        return committedOwners.committedOwner(message.streamName(), message.partition())
                              .filter(committed -> message.ownerEpoch()
                                                          .isStrictlyAfter(committed.ownerEpoch()))
                              .isPresent();
    }

    private boolean isCommittedEpoch(String streamName, int partition, Epoch epoch) {
        return committedOwners.committedOwner(streamName, partition)
                              .filter(committed -> committed.ownerEpoch()
                                                            .equals(epoch))
                              .isPresent();
    }

    /// The batch's own ack, sent inside the partition's [#deferredAcks] section. A deferred ack it covers ([DeferredAck#outlives])
    /// is settled by it and dropped, so it is never released afterwards, lower.
    @Contract
    private void sendLiveAck(ReplicationMessage.ReplicateEvents message,
                             String streamName,
                             int partition,
                             long highestHeld) {
        deferredAcks.compute(partitionKey(streamName, partition),
                             (_, deferred) -> sendCovering(message, streamName, partition, highestHeld, deferred));
    }

    @NullReturn
    private DeferredAck sendCovering(ReplicationMessage.ReplicateEvents message,
                                     String streamName,
                                     int partition,
                                     long highestHeld,
                                     DeferredAck deferred) {
        sendAck(message.governorId(), streamName, partition, highestHeld, message.ownerEpoch());

        return option(deferred).filter(held -> held.outlives(highestHeld,
                                                             message.ownerEpoch()))
                     .or((DeferredAck) null);
    }

    /// #2599 G3: a backfill's ack of its watermark (`PartitionBackfill.ackBackfillToOwner`), sent inside the partition's
    /// [#deferredAcks] section. A deferred ack of the same epoch at or below the watermark is settled by it ([DeferredAck#settledBy])
    /// and dropped, so it is never released afterwards, lower. Production wires it as the backfill's
    /// `PartitionBackfill.BackfillAckSink`.
    @Contract
    public void ackBackfill(NodeId owner, String streamName, int partition, long watermark, Epoch ownerEpoch) {
        deferredAcks.compute(partitionKey(streamName, partition),
                             (_, deferred) -> sendSettling(owner, streamName, partition, watermark, ownerEpoch, deferred));
    }

    @NullReturn
    private DeferredAck sendSettling(NodeId owner,
                                     String streamName,
                                     int partition,
                                     long watermark,
                                     Epoch ownerEpoch,
                                     DeferredAck deferred) {
        sendAck(owner, streamName, partition, watermark, ownerEpoch);

        return option(deferred).filter(held -> !held.settledBy(watermark, ownerEpoch))
                     .or((DeferredAck) null);
    }

    /// #2599: the gate holds the ack back; it does not drop it. The ack is kept ([#deferredAcks]) and the gap repair asked to
    /// compare the copy; [#releaseDeferredAck] sends it once the compare has verified the copy. Dropped, the ack of a batch that
    /// landed after the compare acked its own watermark and before it marked the copy verified waited for a further backfill
    /// run or a later batch, and the owner's await failed at its 5 s bound when neither came.
    ///
    /// The release is tried again once the ack is kept: a verification that landed between the gate read and the keep ran its
    /// own release and found nothing.
    @Contract
    private void deferAck(ReplicationMessage.ReplicateEvents message,
                          String streamName,
                          int partition,
                          long highestHeld) {
        log.debug("ReplicationReceiveHandler: {}[{}] up to {} applied but NOT yet acked: this copy is not yet verified against the "
                 + "committed owner's epoch; backfill compares it first, then the ack is sent",
                  streamName,
                  partition,
                  highestHeld);
        deferredAcks.merge(partitionKey(streamName, partition),
                           DeferredAck.deferredAck(message.governorId(), highestHeld, message.ownerEpoch()),
                           DeferredAck::mergedWith);
        onGap.accept(streamName, partition);
        releaseDeferredAck(streamName, partition);
    }

    /// #2599: sends the ack a batch deferred while this copy was unverified ([#deferAck]). Production calls it when a compare
    /// verifies the copy (`StreamPartitionManager::onEpochVerified`). The caller's word is not taken for it: the [AckGate] is read
    /// again here, and while it still holds, or when nothing was deferred, nothing is sent. Otherwise the ack goes through the
    /// durability barrier first, like a live one, and is then sent only when its epoch is still the committed one, and no
    /// higher than the head the copy holds then: a repair between the deferral and the release can have cut the deferred
    /// offsets. A live ack sent meanwhile that covers it has already dropped it ([#sendLiveAck]).
    ///
    /// The release acks the deferred offset, never above it: a record past it was not covered by the batch that was deferred,
    /// and a verified copy can hold, past it, records of a newer epoch's owner that this replica's committed view accepted.
    /// The head read before the barrier is a further ceiling: a repair can have cut the deferred offsets before it, and a record
    /// written again after the reading is not covered by the barrier. A backfill ack of the same epoch at or above the deferred
    /// offset settles it first ([#ackBackfill]).
    @Contract
    public void releaseDeferredAck(String streamName, int partition) {
        option(deferredAcks.get(partitionKey(streamName, partition))).filter(_ -> ackGate.mayAck(streamName, partition))
              .onPresent(deferred -> releaseDurably(streamName,
                                                    partition,
                                                    deferred,
                                                    resolveLocalNext(streamName,
                                                                     partition,
                                                                     deferred.through() + 1) - 1));
    }

    @Contract
    private void releaseDurably(String streamName, int partition, DeferredAck gated, long headBeforeSync) {
        durability.sync(streamName, partition)
                  .onSuccess(_ -> deferredAcks.computeIfPresent(partitionKey(streamName, partition),
                                                                (_, held) -> sendIfStillGated(streamName,
                                                                                              partition,
                                                                                              gated,
                                                                                              held,
                                                                                              headBeforeSync)))
                  .onFailure(cause -> reportWithheldAck(streamName,
                                                        partition,
                                                        gated.through(),
                                                        cause));
    }

    /// The send half of a release, inside the partition's [#deferredAcks] section. It sends only the deferral this release
    /// gated and synced, and reads the gate again in the same section. A deferral that replaced it while the barrier was in
    /// flight (a batch of a newly committed epoch on a copy not yet verified for it) is neither verified nor covered by this
    /// barrier: it is kept for its own release.
    @NullReturn
    private DeferredAck sendIfStillGated(String streamName,
                                         int partition,
                                         DeferredAck gated,
                                         DeferredAck held,
                                         long headBeforeSync) {
        return held == gated && ackGate.mayAck(streamName, partition)
               ? sendIfCommitted(streamName, partition, held, headBeforeSync)
               : held;
    }

    /// Sends a released deferred ack under its epoch when that is still the committed one, at its offset capped at
    /// `headBeforeSync` and at the current head; in every case the deferred ack is consumed. One of an older epoch is dropped:
    /// the next live batch acks under the new one.
    @NullReturn
    private DeferredAck sendIfCommitted(String streamName, int partition, DeferredAck deferred, long headBeforeSync) {
        var upTo = Math.min(deferred.through(), headBeforeSync);
        var through = Math.min(upTo, resolveLocalNext(streamName, partition, upTo + 1) - 1);

        option(deferred).filter(held -> isCommittedEpoch(streamName,
                                                         partition,
                                                         held.ownerEpoch()))
              .filter(_ -> through >= 0)
              .onPresent(held -> sendAck(held.owner(),
                                         streamName,
                                         partition,
                                         through,
                                         held.ownerEpoch()));

        return null;
    }

    /// #2599: the stream was removed; the acks deferred for its partitions are dropped with it, so none is released into a
    /// stream recreated under the same name. Production calls it from `StreamPartitionManager::onStreamRemoved`. #2847: so are
    /// its latest sent acks, so no pending re-send reaches the owner of a stream recreated under the same name.
    @Contract
    public void forgetDeferredAcks(String streamName) {
        deferredAcks.keySet().removeIf(key -> key.streamName()
                                                 .equals(streamName));
        latestAcks.keySet().removeIf(key -> key.streamName()
                                               .equals(streamName));
    }

    /// Fire-and-forget, as every ack here, but for one outcome (#2847): an ack the transport proves it never wrote
    /// ([WriteOutcome#provablyNotSent], a FORWARD-lane backpressure refusal) is sent again, the same message, after
    /// [DefaultReplicationManager#FIRST_RESEND_BACKOFF] doubling, until it is written, a newer ack of the partition supersedes
    /// it, or [DefaultReplicationManager#DEFAULT_ACK_TIMEOUT] from its first send has passed. Nothing else would re-send it:
    /// the owner's await of a publish needs an ack from every in-sync replica (`DefaultReplicationManager#awaitReplication`),
    /// so one refused ack held that publish for the full ack timeout (5 s), past the entry node's 2.5 s management forward,
    /// and the client got 503 "outcome unknown" for a publish every replica held. The owner cannot take it twice: an ack
    /// carries a confirmed offset, not an increment. An ack that was written and then lost is still the owner's to time out.
    @Contract
    private void sendAck(NodeId owner, String streamName, int partition, long through, Epoch ownerEpoch) {
        var sent = SentAck.sentAck(owner,
                                   replicateAck(self, streamName, partition, through, ownerEpoch, bootToken.getAsLong()),
                                   nanoClock.getAsLong() + DEFAULT_ACK_TIMEOUT.nanos());

        latestAcks.compute(partitionKey(streamName, partition),
                           (key, _) -> sentWithResend(key, sent, FIRST_RESEND_BACKOFF));
    }

    private SentAck sentWithResend(PartitionKey key, SentAck sent, TimeSpan backoff) {
        writeAck(key, sent, backoff);

        return sent;
    }

    @Contract
    private void writeAck(PartitionKey key, SentAck sent, TimeSpan backoff) {
        transport.send(sent.owner(), sent.ack()).onSuccess(outcome -> resendIfNotSent(key, sent, outcome, backoff));
    }

    @Contract
    private void resendIfNotSent(PartitionKey key, SentAck sent, WriteOutcome outcome, TimeSpan backoff) {
        if (outcome.provablyNotSent()) {
            scheduleResend(key, sent, outcome, backoff);
        }
    }

    /// A re-send goes out only if it would still leave before the deadline; otherwise one WARN, and the owner's await fails on
    /// its own timer, as it did before #2847.
    @Contract
    private void scheduleResend(PartitionKey key, SentAck sent, WriteOutcome outcome, TimeSpan backoff) {
        if (backoff.nanos() >= sent.deadlineNanos() - nanoClock.getAsLong()) {
            log.warn("Ack of {}[{}]@{} to owner {} not written before the ack deadline, last outcome {}; giving up re-sending",
                     key.streamName(),
                     key.partition(),
                     sent.ack().confirmedOffset(),
                     sent.owner(),
                     outcome);

            return;
        }

        resendScheduler.apply(() -> resend(key, sent, backoff), backoff);
    }

    /// Re-sends `sent` only while it is still the partition's latest ack; a superseded one is dropped, the newer ack covers it.
    @Contract
    private void resend(PartitionKey key, SentAck sent, TimeSpan backoff) {
        latestAcks.computeIfPresent(key,
                                    (_, latest) -> latest == sent
                                                   ? sentWithResend(key, sent, nextBackoff(backoff))
                                                   : latest);
    }

    private Unit reportWithheldAck(String streamName, int partition, long highestHeld, Cause cause) {
        return OperatorWarnings.raise(log,
                                      operatorWarnings,
                                      REPLICA_FSYNC_FAILED,
                                      streamName + "[" + partition + "]",
                                      "ReplicationReceiveHandler: durability sync failed for {}[{}] "
                                     + "up to {} — WITHHOLDING ack (applied but not fsynced): {}",
                                      streamName,
                                      partition,
                                      highestHeld,
                                      cause.message());
    }

    /// The batch stopped at `refusedAt`, and `onGap` fires for every stop. After an append failure, or a gap opened
    /// by a concurrent change of the local head, it pulls the missing tail (M5). After a divergence it runs the
    /// backfill orchestrator, which refuses and demotes a quarantined partition (class doc, "quarantine"). A
    /// quarantine refusal repeats on every batch, and the partition manager already logged the quarantine at
    /// ERROR once, so it is logged here at DEBUG only.
    private void reportShortApply(ReplicationMessage.ReplicateEvents message, long refusedAt, Cause cause) {
        if (isDivergence(cause)) {
            log.debug("ReplicationReceiveHandler: refusing {}[{}] from offset {} (owner {}): {} — nothing acked at or past it",
                      message.streamName(),
                      message.partition(),
                      refusedAt,
                      message.governorId(),
                      cause.message());
            onGap.accept(message.streamName(), message.partition());

            return;
        }

        log.warn("ReplicationReceiveHandler: applied up to offset {} of [{}, {}] for {}[{}] (owner {}): {} "
                + "— triggering backfill repair",
                 refusedAt - 1,
                 message.fromOffset(),
                 message.fromOffset() + message.payloads().size() - 1,
                 message.streamName(),
                 message.partition(),
                 message.governorId(),
                 cause.message());
        onGap.accept(message.streamName(), message.partition());
    }

    private static boolean isDivergence(Cause cause) {
        return cause instanceof StreamError.ReplicaEntryConflict || cause instanceof StreamError.ReplicaQuarantined;
    }

    /// #1505 F3: an offset of an overlapping prefix that the ring has already EVICTED cannot be compared, but it
    /// was landed at its own offset by this same authority when it arrived, and a divergence found then would have
    /// quarantined the partition. So it is passed over, and the batch goes on to the offsets still held and to its
    /// new tail. Stopping there stalled a batch whose tail was genuinely new, and nothing re-sent that tail.
    private static boolean isEvicted(Result<Long> result) {
        return result.fold(cause -> cause instanceof StreamError.CursorExpired, _ -> false);
    }

    /// How far a batch landed: `held` events of its prefix are held at their owner offsets, and `refusal` is the
    /// cause that stopped it, if anything did.
    private record BatchOutcome(int held, Option<Cause> refusal) {
        static BatchOutcome batchOutcome(int held, Option<Cause> refusal) {
            return new BatchOutcome(held, refusal);
        }
    }

    private BatchOutcome applyBatch(String streamName,
                                    int partition,
                                    long fromOffset,
                                    List<byte[]> payloads,
                                    List<Long> timestamps,
                                    Epoch ownerEpoch) {
        for (var i = 0; i < payloads.size(); i++) {
            var result = appender.appendRecovered(streamName,
                                                  partition,
                                                  fromOffset + i,
                                                  payloads.get(i),
                                                  timestamps.get(i),
                                                  ownerEpoch);

            if (result.isFailure() && !isEvicted(result)) {
                return BatchOutcome.batchOutcome(i, refusalOf(result));
            }
        }

        return BatchOutcome.batchOutcome(payloads.size(), none());
    }

    private static Option<Cause> refusalOf(Result<Long> result) {
        return result.fold(Option::some, _ -> none());
    }

    /// The replica's next-expected offset for `(streamName, partition)`, or `fromOffset` itself when no
    /// real local-head view is wired ({@link #NO_LOCAL_HEAD}), so no gap is pre-detected and every event is
    /// offered to the appender. The appender's offset check still applies (#1505).
    private long resolveLocalNext(String streamName, int partition, long fromOffset) {
        return localHead == NO_LOCAL_HEAD
               ? fromOffset
               : localHead.nextExpectedOffset(streamName, partition);
    }

    /// Sentinel seam for the non-verifying factories: {@link #resolveLocalNext} short-circuits it to
    /// `fromOffset` so every batch is treated as contiguous (no gap detection, no skip). Never invoked.
    private static final LocalHead NO_LOCAL_HEAD = (_, _) -> 0L;
}
