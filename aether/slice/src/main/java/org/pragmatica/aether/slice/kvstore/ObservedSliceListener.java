// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.lang.Contract;

/// A reader of the observed slice rows under FSM2 (deployment state machine spec 1.6.1), fed by an [ObservedSliceFeed]: it sees only rows
/// the feed shows, so every row it is handed is current and is not the UNLOADED tombstone.
public interface ObservedSliceListener {
    /// The row of `key` is shown, with its committed value `row`: a first showing or a later write of a shown row.
    @Contract
    void onObservedSlice(SliceObservedKey key, SliceObservedValue row);

    /// The row of `key`, shown before, no longer is: removed, tombstoned, stale on positive evidence, or its node departed.
    @Contract
    void onObservedSliceGone(SliceObservedKey key);
}
