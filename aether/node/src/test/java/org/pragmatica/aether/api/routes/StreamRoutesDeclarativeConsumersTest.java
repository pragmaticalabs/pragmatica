// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;

import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.node.stream.StreamConsumerManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// #1389: `GET /api/v1/streams/declarative-consumers` carries THREE node-wide numbers, two of them
/// `long` and adjacent in the constructor — `cursorCommitFailureCount` (#654) and
/// `attachSkippedNoLocalSliceCount` (#1389). A swap between those two compiles, and every other test of
/// this endpoint would stay green through it, so each is read here from its own source with its own
/// distinct value. Without this the route hunk that exposes the new counter is pinned by nothing.
class StreamRoutesDeclarativeConsumersTest {
    @Test
    void declarativeConsumers_carriesEachNodeWideCounter_fromItsOwnManagerAccessor() {
        var manager = mock(StreamConsumerManager.class);

        when(manager.statuses()).thenReturn(List.of());
        when(manager.activeSubscriptionCount()).thenReturn(7);
        when(manager.cursorCommitFailureCount()).thenReturn(11L);
        when(manager.attachSkippedNoLocalSliceCount()).thenReturn(23L);

        var node = mock(ManageableNode.class);

        when(node.streamConsumerManager()).thenReturn(manager);

        // The coordinator and the registry are stored and never read by this endpoint — it is a pure
        // snapshot off the consumer manager. Both are SEALED interfaces, so Mockito refuses them, and
        // standing up real ones would add collaborators the method under test cannot reach.
        var response = StreamRoutes.streamRoutes(() -> node, null, null)
                                   .declarativeConsumers();

        assertThat(response.attachedSubscriptions()).isEqualTo(7);
        assertThat(response.cursorCommitFailureCount()).describedAs("#654's counter, not #1389's")
                                                       .isEqualTo(11L);
        assertThat(response.attachSkippedNoLocalSliceCount()).describedAs("#1389's counter, not #654's")
                                                             .isEqualTo(23L);
        assertThat(response.consumers()).describedAs("no declarations, so no rows — it never fabricates any")
                                        .isEmpty();
    }
}
