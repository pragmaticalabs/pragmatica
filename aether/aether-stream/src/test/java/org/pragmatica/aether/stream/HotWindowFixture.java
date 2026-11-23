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

/// Test helper: a copy of a [StreamConfig] whose `[hot]` section is the given window, every other component of the config
/// carried over verbatim, and every other section of an existing declaration (`[retention]`, `[truncation]`, expected
/// ingress) kept as the source declared it. Only `[hot]` is replaced. A test that sizes a ring from the hot window declares it
/// through this, so it reads the same with and without the ring reading the declaration.
///
/// A window below the stream's `max_event_size` is not creatable ([StreamError.HotWindowBelowMaxEvent]), so the copy's
/// `max_event_size` is lowered to the window when the window is smaller: the one component this fixture does not carry over
/// verbatim. It is the maximum such a ring always enforced (the ring refused an event past its data region); a test of the
/// refusal itself builds its declaration directly.
public final class HotWindowFixture {
    private HotWindowFixture() {}

    /// `config` with `[hot]` = (`count` entries, `bytes` bytes) and `max_event_size` at most `bytes`; a source without a declaration gets one holding only `[hot]`.
    public static StreamConfig declaredHot(StreamConfig config, long count, long bytes) {
        var hot = HotWindowDeclaration.hotWindowDeclaration(some(bytes), some(count));
        var declaration = config.declaration()
                                .fold(() -> StreamDeclaration.streamDeclaration(none(), none(), some(hot), none()),
                                      existing -> replaceHot(existing, hot));

        return new StreamConfig(config.name(),
                                config.partitions(),
                                config.retention(),
                                config.autoOffsetReset(),
                                Math.min(config.maxEventSizeBytes(), bytes),
                                config.consistencyMode(),
                                config.replicationFactor(),
                                config.confirmationFactor(),
                                config.compression(),
                                config.encryptionKeyId(),
                                config.incarnation(),
                                some(declaration));
    }

    private static StreamDeclaration replaceHot(StreamDeclaration existing, HotWindowDeclaration hot) {
        return StreamDeclaration.streamDeclaration(existing.retention(),
                                                   existing.truncation(),
                                                   some(hot),
                                                   existing.expectedIngressBytesPerSecond());
    }
}
