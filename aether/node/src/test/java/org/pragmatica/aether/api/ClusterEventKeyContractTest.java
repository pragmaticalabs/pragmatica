// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.pragmatica.aether.api.ClusterEvent.Severity;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterEventView;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the wire contract behind #304 (node-mode live events never key correctly): the server's
/// `ClusterEventView` has always carried its HLC time under `at` — never `timestamp` — per its own
/// doc comment, which predates the `type` discriminator. The client-side bug was an original wrong
/// assumption, not a later rename: `events.js`/`index.html` read `event.timestamp`, a field this
/// shape has never sent.
///
/// Trace-waterfall wiring (`trace-detail` component, loaded but never connected — `index.html:512-524`)
/// is explicitly OUT of this PR per the CTO scope ruling; only the event-key fix is pinned here.
class ClusterEventKeyContractTest {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    private static Result<String> resource(String path) {
        try (InputStream in = ClusterEventKeyContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                return Result.failure(Causes.cause("Dashboard resource not found on classpath: " + path));
            }
            return Result.success(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Result.failure(Causes.fromThrowable(e));
        }
    }

    @Test
    void clusterEventView_serializesAtField_neverTimestamp() {
        var at = new HlcTimestamp(HlcTimestamp.pack(1_756_800_000_000L, 0), new NodeId("node-1"));
        var view = new ClusterEventView("NODE_FAILED", at, Severity.WARNING, "node-1 failed", Map.of());

        var written = MAPPER.writeAsString(view);

        assertThat(written.isSuccess()).as("ClusterEventView must serialize").isTrue();

        written.onSuccess(json -> {
            var parsed = MAPPER.readTree(json);

            parsed.onSuccess(tree -> {
                assertThat(tree.has("at")).as("the HLC time field is named 'at' on the wire").isTrue();
                assertThat(tree.has("timestamp")).as("'timestamp' has never been a field of this shape").isFalse();
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

    /// #304 fix: the dedup/display key must be computed from whichever time field the payload
    /// actually carries (`at` for node-mode `ClusterEventView`, `timestamp` for Forge-mode
    /// `ForgeEvent`) — never assume `timestamp` exists.
    @Test
    void eventsJs_computesKeyFromAtOrTimestamp_notTimestampAlone() {
        var eventsJs = resource("/dashboard/js/stores/events.js").unwrap();

        assertThat(eventsJs)
                .as("events.js must expose an eventKey/eventMillis helper reading either time field")
                .contains("eventKey")
                .contains("eventMillis")
                .as("the old timestamp-only dedup check must be gone")
                .doesNotContain("e.timestamp === event.timestamp && e.type === event.type");
    }

    @Test
    void indexHtml_usesEventKeyHelper_andNeverReadsEventTimestampDirectly() {
        var indexHtml = resource("/dashboard/index.html").unwrap();

        assertThat(indexHtml)
                .as("the event list key must go through the store's key helper")
                .contains(":key=\"$store.events.eventKey(event)\"")
                .as("the event time display must go through the store's millis helper")
                .contains("formatTime($store.events.eventMillis(event))")
                .as("no template binding may read the nonexistent 'event.timestamp' field directly")
                .doesNotContain("event.timestamp");
    }
}
