// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.pragmatica.lang.Option;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;


/// The last observed write submitted and not yet seen committed. It outlives the exhaustion of the write's attempts, so
/// a re-issued identical goal whose committed row lags the local phase can run R-6.0 and rebuild the write
/// (spec R-3.4.2). Cleared when the row at or past `version` is seen.
public record Unconfirmed(SliceObservedState state, long version, Option<FailureNote> failure) {
    public static Unconfirmed unconfirmed(SliceObservedState state, long version, Option<FailureNote> failure) {
        return new Unconfirmed(state, version, failure);
    }
}
