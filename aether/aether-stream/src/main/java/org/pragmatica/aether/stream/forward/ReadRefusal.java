// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.stream.RemovalCause;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.segment.SegmentError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// A stream read refusal as it crosses the wire (#1967): a typed code and the values the cause is built from, never the
/// cause chain and never a message to parse. The serving node answers a failed forwarded read with the refusal a local
/// read of the same partition would have produced; the calling node rebuilds that cause, so the route that maps a local
/// refusal to a status maps the forwarded one identically.
///
/// The slots are positional and each [Kind] fixes which it uses; the others stay at their defaults (`""`, `0`, `false`,
/// [Epoch#ZERO], [RemovalCause#NOT_APPLICABLE]). `cause` is the removal's cause: only `CURSOR_EXPIRED` carries one, and the
/// compact constructor is the ONE table by kind that a constructed or decoded value passes through (never throwing, so a
/// decoded value always builds): `CURSOR_EXPIRED` keeps a cause that names a removal and turns `NOT_APPLICABLE` into
/// [RemovalCause#UNRECORDED] (a removal whose cause nobody stated); every other kind carries `NOT_APPLICABLE`.
/// [RemovalCause#UNKNOWN], which only the decoding of an unrecognised code writes, is kept for `CURSOR_EXPIRED`.
/// `causes` is the SET of removal causes behind the refused range, one bit per [RemovalCause] ordinal: only `CURSOR_EXPIRED`
/// carries one, and the same table turns it into the mask of [StreamError.CursorExpired#causesOf] (a bit at or above
/// `UNKNOWN`'s becomes the `UNKNOWN` bit, the `NOT_APPLICABLE` bit is dropped); every other kind carries `0`. The forms that
/// predate `causes` derive it from the `cause` they name (`{cause}`, and none for `UNRECORDED`). Every helper below that
/// builds a non-cursor refusal reaches the canonical constructor through the ONE private [#refusal] default
/// (`NOT_APPLICABLE`, `0`), which is equivalent to the 11-argument form by construction; the cursor refusal is built once,
/// canonically, from the [StreamError.CursorExpired] it carries. The set of refusals carried is the set a read can raise on the serving node -- the owner role gate, the
/// ring read, the verified-copy gate, the linearizable pipeline, the held-partition naming, the sealed tier -- and
/// [#readRefusal(Cause)] is an exhaustive switch over [StreamError] with no default branch, so a new stream error fails
/// compilation here until someone decides whether a read can raise it. A cause that is not carried (a failed tier read, a
/// consensus failure, an engine integrity error no read raises) travels as its text and is rebuilt as
/// [StreamForwardError.ReadForwardFailed], as before.
///
/// `epochDiverged` is not here: it already travels typed in its own response slots.
@Codec
public record ReadRefusal(Kind kind,
                          String stream,
                          int partition,
                          long first,
                          long second,
                          boolean flag,
                          String detail,
                          String peerFirst,
                          String peerSecond,
                          Epoch epochFirst,
                          Epoch epochSecond,
                          RemovalCause cause,
                          int causes) {
    public ReadRefusal {
        cause = causeOf(kind, cause);
        causes = causesOf(kind, causes);
    }

    /// The form that predates `causes`: it derives the mask from the `cause` it names (`{cause}`; none for
    /// [RemovalCause#UNRECORDED] and [RemovalCause#NOT_APPLICABLE]), the way every pre-`causes` constructor does.
    public ReadRefusal(Kind kind,
                       String stream,
                       int partition,
                       long first,
                       long second,
                       boolean flag,
                       String detail,
                       String peerFirst,
                       String peerSecond,
                       Epoch epochFirst,
                       Epoch epochSecond,
                       RemovalCause cause) {
        this(kind,
             stream,
             partition,
             first,
             second,
             flag,
             detail,
             peerFirst,
             peerSecond,
             epochFirst,
             epochSecond,
             cause,
             StreamError.CursorExpired.maskOf(StreamError.CursorExpired.causesNamedBy(cause)));
    }

    /// The form that predates `cause`: [RemovalCause#NOT_APPLICABLE], which a `CURSOR_EXPIRED` turns into
    /// [RemovalCause#UNRECORDED] because it names no removal cause, and no `causes`.
    public ReadRefusal(Kind kind,
                       String stream,
                       int partition,
                       long first,
                       long second,
                       boolean flag,
                       String detail,
                       String peerFirst,
                       String peerSecond,
                       Epoch epochFirst,
                       Epoch epochSecond) {
        this(kind,
             stream,
             partition,
             first,
             second,
             flag,
             detail,
             peerFirst,
             peerSecond,
             epochFirst,
             epochSecond,
             RemovalCause.NOT_APPLICABLE,
             0);
    }

    private static int causesOf(Kind kind, int causes) {
        return kind == Kind.CURSOR_EXPIRED
               ? StreamError.CursorExpired.maskOf(StreamError.CursorExpired.causesOf(causes))
               : 0;
    }

    private static RemovalCause causeOf(Kind kind, RemovalCause cause) {
        return kind == Kind.CURSOR_EXPIRED
               ? removalCauseOf(cause)
               : RemovalCause.NOT_APPLICABLE;
    }

    private static RemovalCause removalCauseOf(RemovalCause cause) {
        return cause == RemovalCause.NOT_APPLICABLE
               ? RemovalCause.UNRECORDED
               : cause;
    }

    /// The wire form of a carried refusal. The ordinal is the encoding: append only, never reorder.
    @Codec
    public enum Kind {
        STREAM_NOT_FOUND,
        PARTITION_OUT_OF_RANGE,
        PARTITION_NOT_LOCAL,
        BUFFER_CLOSED,
        CURSOR_EXPIRED,
        OWNER_NOT_ACTIVATED,
        REPLICA_NOT_VERIFIED,
        STALE_EPOCH_READ,
        NOT_CURRENT_OWNER,
        OWNER_CATCHUP_PENDING,
        LINEARIZABLE_ROUND_TIMEOUT,
        PARTITION_HELD_NOT_MATERIALIZED,
        RING_INDEX_CORRUPTED,
        SEAL_IN_FLIGHT,
        SEALED_RANGE_MISSING,
        /// The stream's existence is confirmed but this node's engine does not hold it yet (503, a repeat may succeed).
        STREAM_NOT_MATERIALIZED,
        /// A read below the first offset this copy ever held, by a caller that is not the owner (503, the owner answers it).
        BELOW_COPY_TAIL,
        /// A code this node does not know, sent by a newer one (#964): rebuilt as the owner's text.
        UNKNOWN;
        /// The row this kind answers with: the ONE switch from a kind to what a node does with a failed read. A switch
        /// EXPRESSION with no default branch, so a kind added to the enum does not compile until it has a row.
        public Answer answer() {
            return switch (this) {
                case BUFFER_CLOSED, OWNER_NOT_ACTIVATED, REPLICA_NOT_VERIFIED, STALE_EPOCH_READ, NOT_CURRENT_OWNER, OWNER_CATCHUP_PENDING, LINEARIZABLE_ROUND_TIMEOUT, PARTITION_HELD_NOT_MATERIALIZED, SEAL_IN_FLIGHT, SEALED_RANGE_MISSING, STREAM_NOT_MATERIALIZED -> Answer.NN_SURFACE;
                case UNKNOWN -> Answer.NN_DEGRADE;
                case PARTITION_NOT_LOCAL, BELOW_COPY_TAIL -> Answer.NN_RETRY;
                case PARTITION_OUT_OF_RANGE -> Answer.RQ_INVALID;
                case STREAM_NOT_FOUND -> Answer.RQ_NOT_FOUND;
                case CURSOR_EXPIRED -> Answer.CE_L;
                case RING_INDEX_CORRUPTED -> Answer.FAULT;
            };
        }
    }

    /// What a client is told, as a class of answer: not ready (retry later or elsewhere), request wrong, cursor expired or
    /// lost, lost life, or the engine's own fault.
    public enum AnswerClass {
        /// Not ready: a repeat or another copy may succeed.
        NN,
        /// The request itself is wrong or names nothing.
        RQ,
        /// The cursor expired or its data was lost; the row's reason splits the two.
        CE_OR_L,
        /// A lost life (the cursor belongs to a replaced lineage).
        L,
        /// The fault: the engine's own integrity.
        FAULT
    }

    /// What a router does with a failed read: [#SURFACE] returns it, [#DEGRADE] asks another copy when one is left, and
    /// [#RETRY] asks the owner once for a partition this node does not hold. Nothing reads the behaviour yet.
    public enum Behaviour {
        SURFACE,
        DEGRADE,
        RETRY
    }

    /// The rows of the ONE answer switch: a failed read's class as an answer, and the behaviour of the node that holds it. A
    /// row holds no HTTP status, because this module has no HTTP type; the node module maps a row to its status in one
    /// exhaustive switch, `StreamErrorStatus.statusOf(Answer)`, so a row added here does not compile there until it has one.
    public enum Answer {
        NN_SURFACE(AnswerClass.NN, Behaviour.SURFACE),
        NN_DEGRADE(AnswerClass.NN, Behaviour.DEGRADE),
        NN_RETRY(AnswerClass.NN, Behaviour.RETRY),
        RQ_INVALID(AnswerClass.RQ, Behaviour.SURFACE),
        RQ_NOT_FOUND(AnswerClass.RQ, Behaviour.SURFACE),
        CE_L(AnswerClass.CE_OR_L, Behaviour.SURFACE),
        L_EPOCH(AnswerClass.L, Behaviour.SURFACE),
        FAULT(AnswerClass.FAULT, Behaviour.SURFACE),
        /// The fault where this node could not ask and asking again the same way cannot succeed.
        FAULT_DEGRADE(AnswerClass.FAULT, Behaviour.DEGRADE);
        private final AnswerClass answerClass;
        private final Behaviour behaviour;
        Answer(AnswerClass answerClass, Behaviour behaviour) {
            this.answerClass = answerClass;
            this.behaviour = behaviour;
        }
        public AnswerClass answerClass() {
            return answerClass;
        }
        public Behaviour behaviour() {
            return behaviour;
        }
    }

    /// The row for a read's cause, for EVERY cause, in four steps: an [StreamError.EpochDiverged] is `L_EPOCH`; a cause
    /// [#readRefusal(Cause)] carries takes its kind's row; a [StreamForwardError] takes [#ofForwardError]'s; every other
    /// cause takes `UNKNOWN`'s row, which is what the wire does with it (it leaves the serving node as text and is rebuilt
    /// as [StreamForwardError.ReadForwardFailed]). The node that raised a cause and the node that received it over a
    /// forward therefore read the same row.
    public static Answer answerOf(Cause cause) {
        return cause instanceof StreamError.EpochDiverged
               ? Answer.L_EPOCH
               : readRefusal(cause).fold(() -> beyondKinds(cause),
                                         refusal -> refusal.kind()
                                                           .answer());
    }

    private static Answer beyondKinds(Cause cause) {
        return switch (cause) {
            case StreamForwardError error -> ofForwardError(error);
            default -> Kind.UNKNOWN.answer();
        };
    }

    /// Exhaustive over the transport family and over its [StreamForwardError.General] constants, no default branch: a
    /// transport cause added there does not compile until it is placed. `FAULT_DEGRADE` for the members the family marks as
    /// never succeeding on a retry (`Cause.Terminal`, or a `General` constant with `isTransient()` false); `NN_DEGRADE` for
    /// every other member.
    private static Answer ofForwardError(StreamForwardError error) {
        return switch (error) {
            case StreamForwardError.General general -> ofForwardGeneral(general);
            case StreamForwardError.ForwardNotEncoded _, StreamForwardError.PublishNotEncoded _ -> Answer.FAULT_DEGRADE;
            case StreamForwardError.RemotePublishFailed _, StreamForwardError.RemotePublishRetryable _, StreamForwardError.ForwardNotSent _, StreamForwardError.PublishNotSent _, StreamForwardError.ReadForwardFailed _ -> Answer.NN_DEGRADE;
        };
    }

    private static Answer ofForwardGeneral(StreamForwardError.General general) {
        return switch (general) {
            case READ_RESPONSE_OVERSIZED -> Answer.FAULT_DEGRADE;
            case FORWARD_TIMEOUT, GOVERNOR_UNAVAILABLE, READ_FORWARD_TIMEOUT, STREAM_FORWARD_UNAVAILABLE -> Answer.NN_DEGRADE;
        };
    }

    /// The refusal that carries `cause`, or none when `cause` is not one a read is known to raise.
    public static Option<ReadRefusal> readRefusal(Cause cause) {
        return switch (cause) {
            case StreamError error -> ofStreamError(error);
            case SegmentError error -> ofSegmentError(error);
            default -> none();
        };
    }

    /// Exhaustive over [SegmentError], no default branch: a segment error added there does not compile until someone decides
    /// whether a read can raise it. The two a read raises travel typed; the rest have no kind and travel as text.
    private static Option<ReadRefusal> ofSegmentError(SegmentError error) {
        return switch (error) {
            case SegmentError.SealInFlight(var stream, var partition, var from) -> some(readRefusal(Kind.SEAL_IN_FLIGHT,
                                                                                                    stream,
                                                                                                    partition).withFirst(from));
            case SegmentError.SealedRangeMissing(var stream, var partition, var from, var nextSealed) -> some(readRefusal(Kind.SEALED_RANGE_MISSING,
                                                                                                                          stream,
                                                                                                                          partition).withFirst(from).withSecond(nextSealed));
            case SegmentError.General _, SegmentError.WalRangeMissing _, SegmentError.CorruptRecord _ -> none();
        };
    }

    /// The cause a local read of the serving node would have produced; `errorMessage` is the owner's text, which only a
    /// code this node does not know falls back to.
    public Cause toCause(String errorMessage) {
        return switch (kind) {
            case STREAM_NOT_FOUND -> new StreamError.StreamNotFound(stream);
            case PARTITION_OUT_OF_RANGE -> new StreamError.PartitionOutOfRange(stream, partition, (int) first);
            case PARTITION_NOT_LOCAL -> StreamError.General.PARTITION_NOT_LOCAL;
            case BUFFER_CLOSED -> StreamError.General.BUFFER_CLOSED;
            case CURSOR_EXPIRED -> new StreamError.CursorExpired(first,
                                                                 second,
                                                                 reasonOfCursor(),
                                                                 cause,
                                                                 StreamError.CursorExpired.causesOf(causes));
            case STREAM_NOT_MATERIALIZED -> new StreamError.StreamNotMaterialized(stream);
            case BELOW_COPY_TAIL -> new StreamError.BelowCopyTail(first, second);
            case OWNER_NOT_ACTIVATED -> new StreamError.OwnerNotActivated(stream, partition);
            case REPLICA_NOT_VERIFIED -> new StreamError.ReplicaNotVerified(stream, partition, first);
            case STALE_EPOCH_READ -> new StreamError.StaleEpochRead(stream, partition, epochFirst, epochSecond);
            case NOT_CURRENT_OWNER -> new StreamError.NotCurrentOwner(stream,
                                                                      partition,
                                                                      new NodeId(peerFirst),
                                                                      new NodeId(peerSecond));
            case OWNER_CATCHUP_PENDING -> new StreamError.OwnerCatchupPending(stream, partition);
            case LINEARIZABLE_ROUND_TIMEOUT -> new StreamError.LinearizableRoundTimeout(stream, partition);
            case PARTITION_HELD_NOT_MATERIALIZED -> new StreamError.PartitionHeldNotMaterialized(stream,
                                                                                                 partition,
                                                                                                 first,
                                                                                                 flag);
            case RING_INDEX_CORRUPTED -> new StreamError.RingIndexCorrupted(stream, partition, detail);
            case SEAL_IN_FLIGHT -> new SegmentError.SealInFlight(stream, partition, first);
            case SEALED_RANGE_MISSING -> new SegmentError.SealedRangeMissing(stream, partition, first, second);
            case UNKNOWN -> new StreamForwardError.ReadForwardFailed(errorMessage);
        };
    }

    /// The reason a cursor refusal carries: the cause's own when it fixes one (the cause wins over `flag`), else the flag's.
    private StreamError.CursorExpired.Reason reasonOfCursor() {
        return StreamError.CursorExpired.reasonOf(cause).fold(this::flagReason, reason -> reason);
    }

    private StreamError.CursorExpired.Reason flagReason() {
        return flag
               ? StreamError.CursorExpired.Reason.LOST
               : StreamError.CursorExpired.Reason.EXPIRED;
    }

    /// Exhaustive over [StreamError]: every constant and record is either carried or named in the second group as a
    /// refusal no read raises. No default branch.
    private static Option<ReadRefusal> ofStreamError(StreamError error) {
        return switch (error) {
            case StreamError.General general -> ofGeneral(general);
            case StreamError.StreamNotFound(var stream) -> some(readRefusal(Kind.STREAM_NOT_FOUND, stream, 0));
            case StreamError.PartitionOutOfRange(var stream, var partition, var count) -> some(readRefusal(Kind.PARTITION_OUT_OF_RANGE,
                                                                                                           stream,
                                                                                                           partition).withFirst(count));
            case StreamError.CursorExpired(var requested, var tail, var reason, var cause, var causes) -> some(cursorRefusal(requested,
                                                                                                                             tail,
                                                                                                                             reason == StreamError.CursorExpired.Reason.LOST,
                                                                                                                             cause,
                                                                                                                             StreamError.CursorExpired.maskOf(causes)));
            case StreamError.StreamNotMaterialized(var stream) -> some(readRefusal(Kind.STREAM_NOT_MATERIALIZED,
                                                                                   stream,
                                                                                   0));
            case StreamError.BelowCopyTail(var firstHeld, var tail) -> some(readRefusal(Kind.BELOW_COPY_TAIL, "", 0).withFirst(firstHeld).withSecond(tail));
            case StreamError.OwnerNotActivated(var stream, var partition) -> some(readRefusal(Kind.OWNER_NOT_ACTIVATED,
                                                                                              stream,
                                                                                              partition));
            // #2981 step 2: read routing treats a lapsed lease as not-activated (design §0 row 3); no new wire kind.
            case StreamError.OwnerLeaseLapsed(var stream, var partition) -> some(readRefusal(Kind.OWNER_NOT_ACTIVATED,
                                                                                             stream,
                                                                                             partition));
            case StreamError.ReplicaNotVerified(var stream, var partition, var start) -> some(readRefusal(Kind.REPLICA_NOT_VERIFIED,
                                                                                                          stream,
                                                                                                          partition).withFirst(start));
            case StreamError.StaleEpochRead(var stream, var partition, var presented, var current) -> some(readRefusal(Kind.STALE_EPOCH_READ,
                                                                                                                       stream,
                                                                                                                       partition).withEpochs(presented,
                                                                                                                                             current));
            case StreamError.NotCurrentOwner(var stream, var partition, var expected, var actual) -> some(readRefusal(Kind.NOT_CURRENT_OWNER,
                                                                                                                      stream,
                                                                                                                      partition).withPeers(expected.id(),
                                                                                                                                           actual.id()));
            case StreamError.OwnerCatchupPending(var stream, var partition) -> some(readRefusal(Kind.OWNER_CATCHUP_PENDING,
                                                                                                stream,
                                                                                                partition));
            case StreamError.LinearizableRoundTimeout(var stream, var partition) -> some(readRefusal(Kind.LINEARIZABLE_ROUND_TIMEOUT,
                                                                                                     stream,
                                                                                                     partition));
            case StreamError.PartitionHeldNotMaterialized(var stream, var partition, var watermark, var budget) -> some(readRefusal(Kind.PARTITION_HELD_NOT_MATERIALIZED,
                                                                                                                                    stream,
                                                                                                                                    partition).withFirst(watermark).withFlag(budget));
            case StreamError.RingIndexCorrupted(var stream, var partition, var detail) -> some(readRefusal(Kind.RING_INDEX_CORRUPTED,
                                                                                                           stream,
                                                                                                           partition).withDetail(detail));
            case StreamError.EpochDiverged _, StreamError.EventTooLarge _, StreamError.DivergenceNotEstablished _, StreamError.RepairNotAuthorized _, StreamError.RepairWitnessFailed _, StreamError.RepairPreserveFailed _, StreamError.TruncateBelowRetained _, StreamError.TruncateBelowVisible _, StreamError.SeedRejected _, StreamError.WalReplayMismatch _, StreamError.WalHeadLost _, StreamError.StreamConfigNotYetVisible _, StreamError.MaterializeBudgetExceeded _, StreamError.ReshufflePaced _, StreamError.EventProcessingFailed _, StreamError.PartitionCeilingExceeded _, StreamError.RetentionCountUnindexable _, StreamError.RetentionBoundInvalid _, StreamError.ReplicationRefused _, StreamError.PartitionCapExceeded _, StreamError.StaleEpochAppend _, StreamError.ProvenanceRegression _, StreamError.ProvenanceMismatch _, StreamError.ReplicaOffsetGap _, StreamError.ReplicaEntryConflict _, StreamError.ReplicaQuarantined _, StreamError.NotOwnerAppend _, StreamError.SystemStreamWriteRefused _, StreamError.PartitionFailStopped _, StreamError.OwnershipMovedBeforeAck _, StreamError.OwnerLeaseLapsedBeforeAck _ -> none();
        };
    }

    private static Option<ReadRefusal> ofGeneral(StreamError.General general) {
        return switch (general) {
            case PARTITION_NOT_LOCAL -> some(readRefusal(Kind.PARTITION_NOT_LOCAL, "", 0));
            case BUFFER_CLOSED -> some(readRefusal(Kind.BUFFER_CLOSED, "", 0));
            // BUFFER_EMPTY (500 in StreamErrorStatus) is reachable only through U2, PartitionedStreamAccess.readSlice's local
            // slice read (the ring's readSlice); a forwarded read does not raise it, so it has no kind, and answerOf gives it
            // UNKNOWN's row (step 4).
            case BUFFER_EMPTY, STREAM_ALREADY_EXISTS, STREAM_CLOSED, CONSUMER_ALREADY_SUBSCRIBED, CONSUMER_NOT_FOUND, CONSUMER_STALLED, CONSUMER_RUNTIME_CLOSED, STREAM_MEMORY_EXCEEDED, CONSENSUS_PATH_UNAVAILABLE, UNREADABLE_CONSISTENCY_MODE, BUFFER_FULL, EVENT_DROPPED, AHSE_REQUIRED_FOR_STRONG, STREAM_CONFIG_COMMIT_FAILED, SEALING_BEHIND, RUN_DOES_NOT_FIT, SEGMENT_TIER_FULL, REPLICATION_BEHIND -> none();
        };
    }

    /// The cursor refusal, built once and canonically: the slots a cursor uses and the defaults of every other.
    private static ReadRefusal cursorRefusal(long requested, long tail, boolean lost, RemovalCause cause, int causes) {
        return new ReadRefusal(Kind.CURSOR_EXPIRED,
                               "",
                               0,
                               requested,
                               tail,
                               lost,
                               "",
                               "",
                               "",
                               Epoch.ZERO,
                               Epoch.ZERO,
                               cause,
                               causes);
    }

    private static ReadRefusal readRefusal(Kind kind, String stream, int partition) {
        return refusal(kind, stream, partition, 0L, 0L, false, "", "", "", Epoch.ZERO, Epoch.ZERO);
    }

    /// The ONE default for the refusals that are not cursor refusals: `NOT_APPLICABLE` and `0`, which is what the
    /// compact constructor gives every such kind, so the `with...` helpers need not pass `cause` and `causes` through.
    private static ReadRefusal refusal(Kind kind,
                                       String stream,
                                       int partition,
                                       long first,
                                       long second,
                                       boolean flag,
                                       String detail,
                                       String peerFirst,
                                       String peerSecond,
                                       Epoch epochFirst,
                                       Epoch epochSecond) {
        return new ReadRefusal(kind,
                               stream,
                               partition,
                               first,
                               second,
                               flag,
                               detail,
                               peerFirst,
                               peerSecond,
                               epochFirst,
                               epochSecond,
                               RemovalCause.NOT_APPLICABLE,
                               0);
    }

    private ReadRefusal withFirst(long value) {
        return refusal(kind,
                       stream,
                       partition,
                       value,
                       second,
                       flag,
                       detail,
                       peerFirst,
                       peerSecond,
                       epochFirst,
                       epochSecond);
    }

    private ReadRefusal withSecond(long value) {
        return refusal(kind,
                       stream,
                       partition,
                       first,
                       value,
                       flag,
                       detail,
                       peerFirst,
                       peerSecond,
                       epochFirst,
                       epochSecond);
    }

    private ReadRefusal withFlag(boolean value) {
        return refusal(kind,
                       stream,
                       partition,
                       first,
                       second,
                       value,
                       detail,
                       peerFirst,
                       peerSecond,
                       epochFirst,
                       epochSecond);
    }

    private ReadRefusal withDetail(String value) {
        return refusal(kind,
                       stream,
                       partition,
                       first,
                       second,
                       flag,
                       value,
                       peerFirst,
                       peerSecond,
                       epochFirst,
                       epochSecond);
    }

    private ReadRefusal withPeers(String peer1, String peer2) {
        return refusal(kind, stream, partition, first, second, flag, detail, peer1, peer2, epochFirst, epochSecond);
    }

    private ReadRefusal withEpochs(Epoch epoch1, Epoch epoch2) {
        return refusal(kind, stream, partition, first, second, flag, detail, peerFirst, peerSecond, epoch1, epoch2);
    }
}
