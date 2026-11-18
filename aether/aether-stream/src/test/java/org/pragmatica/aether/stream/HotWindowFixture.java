// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.HotWindowDeclaration;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamDeclaration;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Test helper: a copy of a [StreamConfig] whose declaration carries ONLY a `[hot]` section with the given window, every
/// other component of the config carried over verbatim. A test that sizes a ring from the hot window declares it
/// through this, so it reads the same with and without the ring reading the declaration.
public final class HotWindowFixture {
    private HotWindowFixture() {}

    /// `config` with a declaration holding only `[hot]` = (`count` entries, `bytes` bytes).
    public static StreamConfig declaredHot(StreamConfig config, long count, long bytes) {
        var hot = HotWindowDeclaration.hotWindowDeclaration(some(bytes), some(count));
        var declaration = StreamDeclaration.streamDeclaration(none(), none(), some(hot), none());

        return new StreamConfig(config.name(),
                                config.partitions(),
                                config.retention(),
                                config.autoOffsetReset(),
                                config.maxEventSizeBytes(),
                                config.consistencyMode(),
                                config.replicationFactor(),
                                config.confirmationFactor(),
                                config.compression(),
                                config.encryptionKeyId(),
                                config.incarnation(),
                                some(declaration));
    }
}
