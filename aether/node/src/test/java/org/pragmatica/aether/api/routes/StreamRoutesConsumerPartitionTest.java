// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.node.stream.StreamConsumerManager.PartitionCursor;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// rev1272 F7 follow-up: `GET /api/streams/declarative-consumers` renders the "not started, retrying
/// its cursor fetch" state, so a stuck consumer does not read as a quiet one.
class StreamRoutesConsumerPartitionTest {
    @Test
    void toConsumerPartition_carriesAwaitingCursorFetch() {
        var awaiting = StreamRoutes.toConsumerPartition(new PartitionCursor(3, 0L, false, Option.none(), true));
        var started = StreamRoutes.toConsumerPartition(new PartitionCursor(3, 42L, false, Option.none(), false));

        assertThat(awaiting.awaitingCursorFetch()).isTrue();
        assertThat(awaiting.committedOffset()).isZero();
        assertThat(started.awaitingCursorFetch()).isFalse();
        assertThat(started.committedOffset()).isEqualTo(42L);
    }
}
