// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;

import org.pragmatica.aether.api.ClusterEvent.Severity;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterEventView;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.json.JsonMapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Pins the wire contract behind #304 (node-mode live events never key correctly): the server's
/// `ClusterEventView` has always carried its HLC time under `at` — never `timestamp` — per its own
/// doc comment, which predates the `type` discriminator. The bug was a client's wrong assumption (the
/// removed dashboard read `event.timestamp`, a field this shape has never sent); the shape itself is
/// what any client, including the next dashboard, depends on, so it stays pinned.
class ClusterEventKeyContractTest {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Test
    void clusterEventView_serializesAtField_neverTimestamp() {
        var at = new HlcTimestamp(HlcTimestamp.pack(1_756_800_000_000L, 0), new NodeId("node-1"));
        var view = new ClusterEventView("NODE_FAILED", at, Severity.WARNING, "node-1 failed", Map.of());
        var written = MAPPER.writeAsString(view);

        assertThat(written.isSuccess()).as("ClusterEventView must serialize").isTrue();
        written.onSuccess(json -> {
            var parsed = MAPPER.readTree(json);

            parsed.onSuccess(tree -> {
                assertThat(tree.has("at")).as("the HLC time field is named 'at' on the wire")
                          .isTrue();
                assertThat(tree.has("timestamp")).as("'timestamp' has never been a field of this shape")
                          .isFalse();
            });
            assertThat(parsed.isSuccess()).isTrue();
        });
    }

    /// Reflection guard against a future accidental rename in either direction — a compile-time
    /// fact this record's shape has held since before the `type` discriminator was added.
    @Test
    void clusterEventView_recordComponents_nameTheTimeFieldAt_notTimestamp() {
        var componentNames = Arrays.stream(ClusterEventView.class.getRecordComponents())
                                   .map(RecordComponent::getName)
                                   .toList();

        assertThat(componentNames).contains("at").doesNotContain("timestamp");
    }
}
