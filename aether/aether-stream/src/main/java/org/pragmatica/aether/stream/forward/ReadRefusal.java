// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import org.pragmatica.aether.slice.generation.Epoch;
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
/// [Epoch#ZERO]). The set of refusals carried is the set a read can raise on the serving node -- the owner role gate, the
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
                          Epoch epochSecond) {
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
        /// A code this node does not know, sent by a newer one (#964): rebuilt as the owner's text.
        UNKNOWN
    }

    /// The refusal that carries `cause`, or none when `cause` is not one a read is known to raise.
    public static Option<ReadRefusal> readRefusal(Cause cause) {
        return switch (cause) {
            case StreamError error -> ofStreamError(error);
            case SegmentError.SealInFlight(var stream, var partition, var from) -> some(readRefusal(Kind.SEAL_IN_FLIGHT,
                                                                                                    stream,
                                                                                                    partition).withFirst(from));
            case SegmentError.SealedRangeMissing(var stream, var partition, var from, var nextSealed) -> some(readRefusal(Kind.SEALED_RANGE_MISSING,
                                                                                                                          stream,
                                                                                                                          partition).withFirst(from).withSecond(nextSealed));
            default -> none();
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
            case CURSOR_EXPIRED -> new StreamError.CursorExpired(first, second);
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

    /// Exhaustive over [StreamError]: every constant and record is either carried or named in the second group as a
    /// refusal no read raises. No default branch.
    private static Option<ReadRefusal> ofStreamError(StreamError error) {
        return switch (error) {
            case StreamError.General general -> ofGeneral(general);
            case StreamError.StreamNotFound(var stream) -> some(readRefusal(Kind.STREAM_NOT_FOUND, stream, 0));
            case StreamError.PartitionOutOfRange(var stream, var partition, var count) -> some(readRefusal(Kind.PARTITION_OUT_OF_RANGE,
                                                                                                           stream,
                                                                                                           partition).withFirst(count));
            case StreamError.CursorExpired(var requested, var tail) -> some(readRefusal(Kind.CURSOR_EXPIRED, "", 0).withFirst(requested).withSecond(tail));
            case StreamError.OwnerNotActivated(var stream, var partition) -> some(readRefusal(Kind.OWNER_NOT_ACTIVATED,
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
            case StreamError.EpochDiverged _, StreamError.EventTooLarge _, StreamError.DivergenceNotEstablished _, StreamError.RepairNotAuthorized _, StreamError.RepairWitnessFailed _, StreamError.RepairPreserveFailed _, StreamError.TruncateBelowRetained _, StreamError.SeedRejected _, StreamError.WalReplayMismatch _, StreamError.WalHeadLost _, StreamError.StreamConfigNotYetVisible _, StreamError.MaterializeBudgetExceeded _, StreamError.ReshufflePaced _, StreamError.EventProcessingFailed _, StreamError.PartitionCeilingExceeded _, StreamError.RetentionCountUnindexable _, StreamError.RetentionBoundInvalid _, StreamError.ReplicationRefused _, StreamError.PartitionCapExceeded _, StreamError.StaleEpochAppend _, StreamError.ProvenanceRegression _, StreamError.ProvenanceMismatch _, StreamError.ReplicaOffsetGap _, StreamError.ReplicaEntryConflict _, StreamError.ReplicaQuarantined _, StreamError.NotOwnerAppend _ -> none();
        };
    }

    private static Option<ReadRefusal> ofGeneral(StreamError.General general) {
        return switch (general) {
            case PARTITION_NOT_LOCAL -> some(readRefusal(Kind.PARTITION_NOT_LOCAL, "", 0));
            case BUFFER_CLOSED -> some(readRefusal(Kind.BUFFER_CLOSED, "", 0));
            case BUFFER_EMPTY, STREAM_ALREADY_EXISTS, STREAM_CLOSED, CONSUMER_ALREADY_SUBSCRIBED, CONSUMER_NOT_FOUND, CONSUMER_STALLED, CONSUMER_RUNTIME_CLOSED, STREAM_MEMORY_EXCEEDED, CONSENSUS_PATH_UNAVAILABLE, UNREADABLE_CONSISTENCY_MODE, BUFFER_FULL, EVENT_DROPPED, AHSE_REQUIRED_FOR_STRONG, STREAM_CONFIG_COMMIT_FAILED, SEALING_BEHIND, RUN_DOES_NOT_FIT, SEGMENT_TIER_FULL -> none();
        };
    }

    private static ReadRefusal readRefusal(Kind kind, String stream, int partition) {
        return new ReadRefusal(kind, stream, partition, 0L, 0L, false, "", "", "", Epoch.ZERO, Epoch.ZERO);
    }

    private ReadRefusal withFirst(long value) {
        return new ReadRefusal(kind,
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
        return new ReadRefusal(kind,
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
        return new ReadRefusal(kind,
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
        return new ReadRefusal(kind,
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
        return new ReadRefusal(kind,
                               stream,
                               partition,
                               first,
                               second,
                               flag,
                               detail,
                               peer1,
                               peer2,
                               epochFirst,
                               epochSecond);
    }

    private ReadRefusal withEpochs(Epoch epoch1, Epoch epoch2) {
        return new ReadRefusal(kind,
                               stream,
                               partition,
                               first,
                               second,
                               flag,
                               detail,
                               peerFirst,
                               peerSecond,
                               epoch1,
                               epoch2);
    }
}
