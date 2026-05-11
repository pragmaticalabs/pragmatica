// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.serialization.Codec;

@Codec public record TierAwareRetention(long postSealBufferMs, long postSealMaxCount) {
    private static final long DEFAULT_POST_SEAL_BUFFER_MS = 60_000L;

    private static final long DEFAULT_POST_SEAL_MAX_COUNT = 10_000L;

    public static TierAwareRetention tierAwareRetention(long postSealBufferMs, long postSealMaxCount) {
        return new TierAwareRetention(postSealBufferMs, postSealMaxCount);
    }

    public static TierAwareRetention tierAwareRetention() {
        return new TierAwareRetention(DEFAULT_POST_SEAL_BUFFER_MS, DEFAULT_POST_SEAL_MAX_COUNT);
    }
}
