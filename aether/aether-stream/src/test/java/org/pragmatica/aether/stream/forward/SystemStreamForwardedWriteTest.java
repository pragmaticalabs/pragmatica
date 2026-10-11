// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.forward.StreamForwardHandler.streamForwardHandler;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward.publishForward;

/// #1338: the system-write refusal also sits at the OWNER, on the append every publish reaches, so a forwarded publish does
/// not depend on the origin having checked. Only a forward marked as sent by the origin's own system publisher
/// (`PublishForward#systemWriter`) is appended to a `system:` stream.
class SystemStreamForwardedWriteTest {
    private static final NodeId OWNER = NodeId.randomNodeId();
    private static final NodeId ORIGIN = NodeId.randomNodeId();
    private static final String SYSTEM = SystemStreams.CLUSTER_EVENTS.asString();
    private static final byte[] PAYLOAD = "e".getBytes();

    private StreamPartitionManager manager;
    private List<StreamForwardMessage> sent;
    private StreamForwardHandler handler;

    @BeforeEach
    void setUp() {
        manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        sent = new ArrayList<>();
        handler = streamForwardHandler(OWNER, manager, (_, message) -> sent.add(message));
        manager.createStream(StreamConfig.streamConfig(SYSTEM));
        manager.createStream(StreamConfig.streamConfig("com.acme:orders:1.0.0"));
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    private long events(String stream) {
        return manager.partitionInfo(stream, 0).unwrap().eventCount();
    }

    @Test
    void forwardedPublish_toASystemStream_fromANonSystemOrigin_isRefusedAtTheOwner() {
        handler.onPublishForward(publishForward(ORIGIN, "c1", SYSTEM, 0, PAYLOAD, 1L));

        var response = (PublishForwardResponse) sent.getFirst();

        assertThat(response.success()).isFalse();
        assertThat(response.retryable()).isFalse();
        assertThat(response.errorMessage()).contains("system stream");
        assertThat(events(SYSTEM)).isZero();
    }

    /// Controls: the instrument sees an append — the same forward lands on a non-system stream, and a forward marked as
    /// the origin's system publisher lands on the system stream.
    @Test
    void controls_nonSystemForwardLands_andASystemPublishersForwardLands() {
        handler.onPublishForward(publishForward(ORIGIN, "c2", "com.acme:orders:1.0.0", 0, PAYLOAD, 1L));
        handler.onPublishForward(publishForward(ORIGIN, "c3", SYSTEM, 0, PAYLOAD, 2L, true));

        assertThat(sent).hasSize(2).allSatisfy(message -> assertThat(((PublishForwardResponse) message).success()).isTrue());
        assertThat(events("com.acme:orders:1.0.0")).isEqualTo(1L);
        assertThat(events(SYSTEM)).isEqualTo(1L);
    }

    /// The owner's one append, reached directly: no caller gets in by forgetting a check.
    @Test
    void ownerAppend_withoutTheSystemMark_isRefused_singleAndBatch() {
        var single = manager.publishLocalAtFloor(SYSTEM, 0, PAYLOAD, 1L, 0);
        var batch = manager.publishLocalBatchAtFloor(SYSTEM, 0, List.of(PAYLOAD), 1L, 0);
        var forwarded = manager.publishForwarded(SYSTEM, 0, PAYLOAD, 1L, 0);

        for (var result : List.of(single, batch, forwarded)) {
            assertThat(result.isFailure()).as("result=%s", result).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.SystemStreamWriteRefused.class));
        }

        assertThat(events(SYSTEM)).isZero();
    }
}
