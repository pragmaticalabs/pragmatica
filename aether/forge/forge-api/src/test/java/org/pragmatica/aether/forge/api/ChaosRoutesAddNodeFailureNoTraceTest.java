// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge.api;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.ember.EmberCluster.EventLogEntry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpError;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #3794: the Forge event feed is read by the browser, so the `ADD_NODE_FAILED` entry carries the failure's client text, never
/// its `message()`, which for a caught throwable is the printed stack trace. Mutation: writing `cause.message()` at the
/// `ADD_NODE_FAILED` site turns this red.
class ChaosRoutesAddNodeFailureNoTraceTest {
    private static final String SENTINEL = "SENTINEL-3794-add-node";

    @Test
    void addNodeFailingWithAThrowable_logsTheClientText_notTheTrace() {
        var feed = new ArrayList<EventLogEntry>();
        var cause = Causes.fromThrowable(new IllegalStateException(SENTINEL));

        ChaosRoutes.addNode(() -> cause.<NodeId> promise(), feed::add);

        assertThat(cause.message()).as("control: the cause's own message carries the sentinel").contains(SENTINEL);
        assertThat(types(feed)).containsExactly("ADD_NODE", "ADD_NODE_FAILED");

        var failed = feed.getLast().message();

        assertThat(failed).isEqualTo("Failed to add node: " + HttpError.clientMessage(cause))
                          .doesNotContain(SENTINEL)
                          .doesNotContain("IllegalStateException")
                          .doesNotContain("\tat ")
                          .doesNotContain("\\tat ");
    }

    private static List<String> types(List<EventLogEntry> feed) {
        return feed.stream().map(EventLogEntry::type).toList();
    }
}
