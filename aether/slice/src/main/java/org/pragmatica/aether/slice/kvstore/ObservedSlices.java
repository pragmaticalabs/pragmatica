// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// How a reader outside the two deployment FSMs reads one observed row under FSM2 (deployment state machine spec 1.6.1, R-1.6.2, C8).
///
/// Every such reader uses **current rows only**. A row is stale only on positive evidence: both boot tokens known (non-zero) and different.
/// An unknown token on either side reads current, so staleness fails open and nothing is dropped on an unknown token alone (C8). This is the
/// cluster FSM's own rule (`fsm2.Rows.isStale`), evaluated here with the reader's own registry token for the node.
///
/// The state a reader acts on is expressed in the legacy [SliceState] vocabulary, so a reader keeps one state model under both engines. The
/// UNLOADED tombstone (R-1.6.3) has no counterpart and reads as absent.
public sealed interface ObservedSlices {
    /// Stale iff both tokens are known (non-zero) and differ (R-1.6.2). `admittedToken` is the reader's registry token for the row's node, `0`
    /// while unknown.
    static boolean isStale(SliceObservedValue row, long admittedToken) {
        var rowToken = row.incarnation()
                          .bootToken();

        return rowToken != 0L && admittedToken != 0L && rowToken != admittedToken;
    }

    /// The observed state in the legacy vocabulary; none for the UNLOADED tombstone, which reads as absent (R-1.6.3).
    static Option<SliceState> sliceState(SliceObservedState state) {
        return switch (state) {
            case LOADING -> some(SliceState.LOADING);
            case LOADED -> some(SliceState.LOADED);
            case ACTIVATING -> some(SliceState.ACTIVATING);
            case ACTIVE -> some(SliceState.ACTIVE);
            case DEACTIVATING -> some(SliceState.DEACTIVATING);
            case FAILED -> some(SliceState.FAILED);
            case UNLOADING -> some(SliceState.UNLOADING);
            case UNKNOWN -> some(SliceState.UNKNOWN);
            case UNLOADED -> none();
        };
    }

    /// The state a reader acts on: none when the row is the UNLOADED tombstone or stale on positive evidence (R-1.6.2), which both read as
    /// absent.
    static Option<SliceState> currentState(SliceObservedValue row, long admittedToken) {
        return isStale(row, admittedToken)
               ? none()
               : sliceState(row.state());
    }

    record unused() implements ObservedSlices {}
}
