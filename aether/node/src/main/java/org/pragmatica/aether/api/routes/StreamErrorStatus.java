// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.forward.ReadRefusal;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;


/// The ONE place a stream-engine refusal becomes an HTTP status on a management route (#1921). No [StreamError] carries a status, so
/// every one answered 500. The switch is exhaustive over the sealed hierarchy and has NO default branch: a new error type, or a new
/// `General` constant, fails compilation here until someone decides which class of answer it is.
///
/// The classes, and the question each answers for the caller:
/// - 400, the request itself is wrong (a partition the stream does not have, an event or a bound the engine cannot take);
/// - 404, the stream or consumer group is unknown to this node's engine;
/// - 409, the request conflicts with what exists (an `EpochDiverged` cursor: the partition's owner replaced the lineage it belongs
///   to; marked guess, since the management routes carry no cursor epoch and it should not arise there);
/// - 410, the cursor named an offset retention has already reclaimed [marked guess: 410 over 400/416, because the offset WAS valid];
/// - 503, retry later or elsewhere: a capacity shortage, a partition this node does not hold or has not materialized yet, an
///   ownership or epoch disagreement that membership will resolve;
/// - 500, the engine's own integrity or an internal signal: a WAL that does not replay, a replica entry that conflicts, a failed
///   config commit.
///
/// Applied on the stream READ routes only; the publish path keeps its own refusals (#524).
public final class StreamErrorStatus {
    private StreamErrorStatus() {}

    public static HttpStatus statusOf(StreamError error) {
        return switch (error) {
            case StreamError.General general -> general(general);
            case StreamError.EventTooLarge _, StreamError.PartitionOutOfRange _, StreamError.PartitionCeilingExceeded _, StreamError.RetentionCountUnindexable _, StreamError.RetentionBoundInvalid _, StreamError.HotWindowBelowMaxEvent _, StreamError.PartitionCapExceeded _ -> HttpStatus.BAD_REQUEST;
            case StreamError.StreamNotFound _ -> HttpStatus.NOT_FOUND;
            case StreamError.SystemStreamWriteRefused _ -> HttpStatus.METHOD_NOT_ALLOWED;
            case StreamError.ReplicationRefused _, StreamError.StreamStorageUnknown _, StreamError.EpochDiverged _ -> HttpStatus.CONFLICT;
            case StreamError.CursorExpired _ -> HttpStatus.GONE;
            case StreamError.StreamConfigNotYetVisible _, StreamError.StreamNotMaterialized _, StreamError.BelowCopyTail _, StreamError.MaterializeBudgetExceeded _, StreamError.ReshufflePaced _, StreamError.PartitionHeldNotMaterialized _, StreamError.StaleEpochAppend _, StreamError.StaleEpochRead _, StreamError.NotCurrentOwner _, StreamError.ReplicaQuarantined _, StreamError.NotOwnerAppend _, StreamError.OwnerNotActivated _, StreamError.OwnerLeaseLapsed _, StreamError.OwnershipMovedBeforeAck _, StreamError.OwnerLeaseLapsedBeforeAck _, StreamError.OwnerCatchupPending _, StreamError.LinearizableRoundTimeout _, StreamError.ReplicaNotVerified _, StreamError.RepairNotAuthorized _, StreamError.RepairWitnessFailed _, StreamError.RepairPreserveFailed _, StreamError.PartitionFailStopped _ -> HttpStatus.SERVICE_UNAVAILABLE;
            case StreamError.SeedRejected _, StreamError.WalReplayMismatch _, StreamError.WalHeadLost _, StreamError.RingIndexCorrupted _, StreamError.EventProcessingFailed _, StreamError.ProvenanceRegression _, StreamError.ProvenanceMismatch _, StreamError.ReplicaOffsetGap _, StreamError.ReplicaEntryConflict _, StreamError.DivergenceNotEstablished _, StreamError.TruncateBelowRetained _, StreamError.TruncateBelowVisible _ -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static HttpStatus general(StreamError.General general) {
        return switch (general) {
            case EVENT_DROPPED, AHSE_REQUIRED_FOR_STRONG -> HttpStatus.BAD_REQUEST;
            case CONSUMER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case STREAM_ALREADY_EXISTS, CONSUMER_ALREADY_SUBSCRIBED -> HttpStatus.CONFLICT;
            case BUFFER_CLOSED, STREAM_CLOSED, CONSUMER_RUNTIME_CLOSED, CONSUMER_STALLED, STREAM_MEMORY_EXCEEDED, SEALING_BEHIND, SEGMENT_TIER_FULL, REPLICATION_BEHIND, BUFFER_FULL, CONSENSUS_PATH_UNAVAILABLE, PARTITION_NOT_LOCAL -> HttpStatus.SERVICE_UNAVAILABLE;
            case BUFFER_EMPTY, UNREADABLE_CONSISTENCY_MODE, STREAM_CONFIG_COMMIT_FAILED, RUN_DOES_NOT_FIT -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /// The status of a row of the ONE answer switch ([ReadRefusal.Answer]): a switch EXPRESSION with no default branch, so a row
    /// added in `aether-stream` does not compile here until it has a status. A row holds one status.
    public static HttpStatus statusOf(ReadRefusal.Answer answer) {
        return switch (answer) {
            case NN_SURFACE, NN_DEGRADE, NN_RETRY -> HttpStatus.SERVICE_UNAVAILABLE;
            case RQ_INVALID -> HttpStatus.BAD_REQUEST;
            case RQ_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CE_L -> HttpStatus.GONE;
            case L_EPOCH -> HttpStatus.CONFLICT;
            case FAULT, FAULT_DEGRADE -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /// A failed stream READ keeps a status somebody already decided and classifies only a cause that carries none. The invariant:
    /// `typedRead` classifies ONLY a cause that carries no decided status. A cause with a decided status passes through UNCHANGED:
    /// never re-rowed, never nested. A decided status is (a) an [HttpStatusAware] cause (a [ManagementServerError.StreamRefused] a
    /// route already built, `notMaterializedHere`'s 503 included), or (b) a [StreamError], whose pinned [#statusOf(StreamError)] is
    /// total and is what the raising node always answered; a kindless one pinned at 500 (`WalReplayMismatch`, `WalHeadLost`,
    /// `SeedRejected`, `ProvenanceMismatch`, ...) therefore stays 500. For every kinded [StreamError] the pin and the row of
    /// [ReadRefusal#answerOf(Cause)] agree (`StreamErrorStatusTest`), so the node that raised a cause and the node that received
    /// it over a forward answer the same status. Only a cause outside both takes the row of
    /// [ReadRefusal#answerOf(Cause)]: `UNKNOWN`'s (503) for `StorageError` and the like, which is what the wire does with it, and its
    /// own row for a `SegmentError` or a `StreamForwardError`. The pin's [#typed(Cause)] stays for
    /// what is not a read of records (the metadata lookups); the hop to the STREAMING task group is not a read answer at all.
    public static Cause typedRead(Cause cause) {
        return switch (cause) {
            case HttpStatusAware decided -> decided;
            case StreamError error -> typed(error);
            default -> new ManagementServerError.StreamRefused(statusOf(ReadRefusal.answerOf(cause)), cause);
        };
    }

    public static <T> Promise<T> typedRead(Promise<T> promise) {
        return promise.mapError(StreamErrorStatus::typedRead);
    }

    /// A [StreamError] becomes a [ManagementServerError.StreamRefused] under its class; any other cause passes through unchanged.
    public static Cause typed(Cause cause) {
        return cause instanceof StreamError error
               ? new ManagementServerError.StreamRefused(statusOf(error), error)
               : cause;
    }

    public static <T> Result<T> typed(Result<T> result) {
        return result.mapError(StreamErrorStatus::typed);
    }

    public static <T> Promise<T> typed(Promise<T> promise) {
        return promise.mapError(StreamErrorStatus::typed);
    }
}
