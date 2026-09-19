// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.utils.Causes;


/// A publish whose outcome is UNKNOWN, as distinct from a publish that failed (#1236).
///
/// A failed publish is not in the log. This cause says the opposite is possible: the event was
/// appended on the owner — ring, WAL, replication already fired — and only the durability barrier
/// after the append did not confirm, most commonly because peer acks timed out. The event MAY be in
/// the log and visible to consumers. No abort or tombstone protocol exists to take it back, so the
/// system does not pretend to.
///
/// The one safe reaction is to retry with the SAME message identity, so downstream message-ID dedup
/// collapses the two copies. A retry that mints a fresh identity writes a duplicate that dedup cannot
/// recognise. This is why the cause is deliberately not [Cause.Transient]: the default retry policy
/// (`RetryOn.TRANSIENT`) retries only transient causes, so it leaves this one alone. It is not
/// [Cause.Terminal] either, because a retry with the same key is legitimate — which means a facility
/// configured with `RetryOn.NON_TERMINAL` (or [org.pragmatica.lang.utils.Retry], which retries every
/// non-terminal cause) DOES retry it: a keyed publish re-sent that way is safe, a keyless one writes a
/// duplicate.
///
/// It lives in `slice-api` so a slice can discriminate it (`cause instanceof PublishOutcomeUnknown`)
/// without depending on the stream runtime, the same placement as [ResourceCapacityExhausted].
public record PublishOutcomeUnknown(Cause origin, String message) implements Cause.Wrapped {
    public static final Fn1<PublishOutcomeUnknown, Cause> FACTORY = Causes.forOneValue("Publish outcome unknown: the event may already be in the log; retry only with the same message ID (%s)",
                                                                                       PublishOutcomeUnknown::new);
}
