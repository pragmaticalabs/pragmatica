// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;


/// Per-event outcome of a batch publish (#1342), stated in #1236's vocabulary.
///
/// A batch is not atomic: partition groups are written concurrently and each group appends in order, so a
/// batch can end with some events durably in the log and others not. `Promise<Unit>` could not say which —
/// it either acknowledged a batch with refused events as success, or failed the batch and hid the events that
/// DID land, so a caller retrying the whole batch duplicated them (#1237: a retry can duplicate unless keyed).
///
/// - [Published]: durably appended at `offset`, min-sync barrier satisfied.
/// - [OutcomeUnknown]: the write was attempted and refused or timed out. Per #1236 the refusal is raised
///   AFTER the local append, so the event MAY be in the log. Retry only with a stable message key (#1237).
/// - [NotAttempted]: the event never reached the write path (no consensus path for a STRONG stream, an earlier
///   event of the same partition group failed, or the request was rejected before writing). It is NOT in the
///   log and can be retried without duplicating.
public sealed interface PublishOutcome {
    record Published(long offset) implements PublishOutcome {}

    record OutcomeUnknown(Cause cause) implements PublishOutcome {}

    record NotAttempted(Cause cause) implements PublishOutcome {}

    /// The outcome of a write that WAS attempted: an offset is [Published]; a failure is [OutcomeUnknown],
    /// never [NotAttempted], because the publisher cannot tell a pre-append refusal from a post-append one
    /// until #1236 classifies them.
    static PublishOutcome attempted(Result<Long> result) {
        return result.fold(OutcomeUnknown::new, Published::new);
    }
}
