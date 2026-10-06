// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.http.HttpStatus;
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
/// - 409, the request conflicts with what exists;
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
            case StreamError.EventTooLarge _, StreamError.PartitionOutOfRange _, StreamError.PartitionCeilingExceeded _,
                 StreamError.RetentionCountUnindexable _, StreamError.RetentionBoundInvalid _,
                 StreamError.PartitionCapExceeded _ -> HttpStatus.BAD_REQUEST;
            case StreamError.StreamNotFound _ -> HttpStatus.NOT_FOUND;
            case StreamError.ReplicationRefused _ -> HttpStatus.CONFLICT;
            case StreamError.CursorExpired _ -> HttpStatus.GONE;
            case StreamError.StreamConfigNotYetVisible _, StreamError.MaterializeBudgetExceeded _, StreamError.ReshufflePaced _,
                 StreamError.PartitionHeldNotMaterialized _, StreamError.StaleEpochAppend _, StreamError.StaleEpochRead _,
                 StreamError.NotCurrentOwner _, StreamError.ReplicaQuarantined _, StreamError.NotOwnerAppend _,
                 StreamError.OwnerNotActivated _, StreamError.OwnerCatchupPending _,
                 StreamError.LinearizableRoundTimeout _ -> HttpStatus.SERVICE_UNAVAILABLE;
            case StreamError.SeedRejected _, StreamError.WalReplayMismatch _, StreamError.WalHeadLost _,
                 StreamError.RingIndexCorrupted _, StreamError.EventProcessingFailed _, StreamError.ProvenanceRegression _,
                 StreamError.ProvenanceMismatch _, StreamError.ReplicaOffsetGap _,
                 StreamError.ReplicaEntryConflict _ -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static HttpStatus general(StreamError.General general) {
        return switch (general) {
            case EVENT_DROPPED, AHSE_REQUIRED_FOR_STRONG -> HttpStatus.BAD_REQUEST;
            case CONSUMER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case STREAM_ALREADY_EXISTS, CONSUMER_ALREADY_SUBSCRIBED -> HttpStatus.CONFLICT;
            case BUFFER_CLOSED, STREAM_CLOSED, CONSUMER_RUNTIME_CLOSED, CONSUMER_STALLED, STREAM_MEMORY_EXCEEDED, SEALING_BEHIND,
                 SEGMENT_TIER_FULL, BUFFER_FULL, CONSENSUS_PATH_UNAVAILABLE, PARTITION_NOT_LOCAL -> HttpStatus.SERVICE_UNAVAILABLE;
            case BUFFER_EMPTY, UNREADABLE_CONSISTENCY_MODE, STREAM_CONFIG_COMMIT_FAILED, RUN_DOES_NOT_FIT -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
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
