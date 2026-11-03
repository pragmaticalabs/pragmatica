// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.lang.Cause;


/// What became of one record of a [ControlLog#append] (#2590 P1). Every outcome is a value; nothing is thrown.
///
/// - [Admitted]: the record is at `offset`, fsync-durable on the owner and acknowledged by every peer the confirmation
///   barrier counts. Only this outcome is an acknowledgement.
/// - [Refused]: nothing was appended. The cause is the admission predicate's, the owner's pre-append refusal (a stale
///   epoch, [org.pragmatica.aether.stream.StreamError.NotOwnerAppend], a short replica floor) or a CARRY that lost its
///   condition.
/// - [OutcomeUnknown]: the record was appended and the WAL fsync or the barrier did not confirm. It stays in the owner's
///   in-flight tail, is never acknowledged, and may still commit, e.g. through a replica after a failover.
/// - [IndexUnavailable]: the partition's index is rebuilding (materialization, promotion, truncation, a hole) or its
///   ownership record carries no committed in-sync set; nothing was appended.
public sealed interface ControlOutcome {
    record Admitted(long offset) implements ControlOutcome {}

    record Refused(Cause cause) implements ControlOutcome {}

    record OutcomeUnknown(Cause cause) implements ControlOutcome {}

    enum IndexUnavailable implements ControlOutcome {
        INDEX_UNAVAILABLE
    }

    /// The writer's `LocalOnly` (§5.2): the record is not acknowledged and the writer retries, re-reading what it relies
    /// on. A refusal is `LocalOnly` only when it is a retryable pre-append refusal (the owner moved, its config was not yet
    /// visible, too few replicas); any other refusal is the kind's verdict and final for that record.
    default boolean isLocalOnly() {
        return switch (this) {
            case Admitted _ -> false;
            case Refused(var cause) -> isRetryableRefusal(cause);
            case OutcomeUnknown _ -> true;
            case IndexUnavailable _ -> true;
        };
    }

    private static boolean isRetryableRefusal(Cause cause) {
        return cause instanceof ControlLogError.RemoteRefused(var retryable, _, _)
               ? retryable
               : StreamForwardHandler.isRetryable(cause);
    }
}
