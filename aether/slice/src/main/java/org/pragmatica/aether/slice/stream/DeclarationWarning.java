// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

/// A non-blocking advisory the stream parser raises while it resolves a storage declaration (#3333, S2a1-6c): the
/// declaration is accepted and the warning states what was committed and which exits the operator has. Each text follows
/// the operator-text rule of the plan (RP-4): the committed fact, the named exits, no prediction. `code` is the rule id the
/// deploy response reports; these are not `OperatorWarningCode` codes.
public record DeclarationWarning(String code, String message) {
    /// `hot.max_bytes` above the replay-window share was lowered to it.
    public static final String HOT_WINDOW_CLAMPED = "hot-window-clamped";
    /// `mode = "all"` with `max_count`: the count bound's byte share is only `max_count / P` records of `max_event_size`.
    public static final String RETENTION_ALL_COUNT_SIZED_BY_MAX_EVENT = "retention-all-count-sized-by-max-event";

    public static DeclarationWarning declarationWarning(String code, String message) {
        return new DeclarationWarning(code, message);
    }

    /// `declared` is the `hot.max_bytes` written (or defaulted), `share` the per-partition share that `setBy` sets.
    static DeclarationWarning hotWindowClamped(String alias, long declared, long share, String setBy) {
        return declarationWarning(HOT_WINDOW_CLAMPED,
                                  "stream '" + alias
                                 + "': hot.max_bytes = " + declared
                                 + " is above the per-partition replay-window share of " + share
                                 + " bytes set by " + setBy
                                 + "; the stream runs with hot.max_bytes = " + share
                                 + ". To choose the hot window yourself, declare hot.max_bytes at or below " + share
                                 + ", or raise " + setBy
                                 + ".");
    }

    /// `perPartitionCount` is `max_count / P`, `maxEventSize` the stream's, `share` their product in bytes (saturated).
    static DeclarationWarning allCountSizedByMaxEvent(String alias,
                                                      long maxCount,
                                                      long perPartitionCount,
                                                      long maxEventSize,
                                                      long share) {
        return declarationWarning(RETENTION_ALL_COUNT_SIZED_BY_MAX_EVENT,
                                  "stream '" + alias
                                 + "': retention.mode = \"all\" with retention.max_count = " + maxCount
                                 + " sizes the count bound's byte share as (max_count / P) x max_event_size = " + perPartitionCount
                                 + " x " + maxEventSize
                                 + " = " + share
                                 + " bytes per partition. To change it, use mode = \"any\", lower max_count or max_event_size,"
                                 + " or remove max_count.");
    }
}
