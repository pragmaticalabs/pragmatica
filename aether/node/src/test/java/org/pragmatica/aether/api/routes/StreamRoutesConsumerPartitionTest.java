// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.node.stream.StreamConsumerManager.PartitionCursor;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1266 and rev1272 F7 follow-up: `GET /api/streams/declarative-consumers` renders every way a
/// partition can be attached and not delivering — held behind a dead-letter append, held behind a
/// scheduled retry, or never started because its cursor fetch keeps failing. Each is set on its own, so
/// a dropped or swapped mapping shows.
class StreamRoutesConsumerPartitionTest {
    @Test
    void toConsumerPartition_carriesEachNonDeliveringState_fromItsOwnCursorField() {
        var deadLetterHeld = StreamRoutes.toConsumerPartition(new PartitionCursor(3, 42L, false, Option.none(), true, false, false, RewindEpoch.NONE));
        var retryHeld = StreamRoutes.toConsumerPartition(new PartitionCursor(3, 42L, false, Option.none(), false, true, false, RewindEpoch.NONE));
        var awaitingFetch = StreamRoutes.toConsumerPartition(new PartitionCursor(3, 0L, false, Option.none(), false, false, true, RewindEpoch.NONE));

        assertThat(deadLetterHeld.deadLetterInFlight()).isTrue();
        assertThat(deadLetterHeld.retryInFlight()).isFalse();
        assertThat(deadLetterHeld.awaitingCursorFetch()).isFalse();
        assertThat(retryHeld.deadLetterInFlight()).isFalse();
        assertThat(retryHeld.retryInFlight()).isTrue();
        assertThat(retryHeld.awaitingCursorFetch()).isFalse();
        assertThat(awaitingFetch.deadLetterInFlight()).isFalse();
        assertThat(awaitingFetch.retryInFlight()).isFalse();
        assertThat(awaitingFetch.awaitingCursorFetch()).isTrue();
        assertThat(retryHeld.committedOffset()).isEqualTo(42L);
    }
}
