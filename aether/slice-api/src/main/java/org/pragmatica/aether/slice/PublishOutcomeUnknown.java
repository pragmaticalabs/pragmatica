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
/// recognise. This is why the cause is deliberately neither [Cause.Transient] nor [Cause.Terminal]:
/// a retry facility that re-runs the whole operation would re-mint the identity, so it must not be
/// invited to retry by classification.
///
/// It lives in `slice-api` so a slice can discriminate it (`cause instanceof PublishOutcomeUnknown`)
/// without depending on the stream runtime, the same placement as [ResourceCapacityExhausted].
public record PublishOutcomeUnknown(Cause origin, String message) implements Cause.Wrapped {
    public static final Fn1<PublishOutcomeUnknown, Cause> FACTORY = Causes.forOneValue("Publish outcome unknown: the event may already be in the log; retry only with the same message ID (%s)",
                                                                                       PublishOutcomeUnknown::new);
}
