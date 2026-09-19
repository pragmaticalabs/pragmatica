// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.node.stream.StreamConsumerManager.PartitionCursor;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1266: `GET /api/streams/declarative-consumers` renders each partition's delivery holds from the
/// status cursor. The holds are set asymmetrically so a dropped or swapped mapping shows.
class StreamRoutesConsumerPartitionTest {
    @Test
    void toConsumerPartition_carriesEachDeliveryHold_fromItsOwnCursorField() {
        var deadLetterHeld = StreamRoutes.toConsumerPartition(new PartitionCursor(3, 42L, false, Option.none(), true, false));
        var retryHeld = StreamRoutes.toConsumerPartition(new PartitionCursor(3, 42L, false, Option.none(), false, true));

        assertThat(deadLetterHeld.deadLetterInFlight()).isTrue();
        assertThat(deadLetterHeld.retryInFlight()).isFalse();
        assertThat(retryHeld.deadLetterInFlight()).isFalse();
        assertThat(retryHeld.retryInFlight()).isTrue();
        assertThat(retryHeld.committedOffset()).isEqualTo(42L);
    }
}
