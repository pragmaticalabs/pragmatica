// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardTransport;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1333, CTO ruling 4 (a): a node that holds no ring for a partition learns its consumer-visible bounds
/// through the SAME forward a consumer read takes — the owner answers a `ReadForward` and the response
/// carries `earliestRetained`/`visibleHead`. Two nodes here: OWNER holds the ring and serves through the
/// real `StreamForwardHandler`; ASSIGNEE holds nothing and asks through the real `StreamForwardClient` over
/// a transport that is two method calls. The pin: the forwarded bounds EQUAL the owner's local bounds.
class StreamReadRouterBoundsTest {
    private static final NodeId OWNER = NodeId.nodeId("owner").unwrap();
    private static final NodeId ASSIGNEE = NodeId.nodeId("assignee").unwrap();
    private static final String STREAM = "topic:ns:orders:1.0.0";
    private static final int PARTITION = 0;

    private StreamPartitionManager ownerPartitions;
    private StreamPartitionManager assigneePartitions;
    private StreamReadRouter ownerRouter;
    private StreamReadRouter assigneeRouter;
    private final AtomicInteger forwardedReads = new AtomicInteger();

    @BeforeEach
    void setUp() {
        ownerPartitions = StreamPartitionManager.streamPartitionManager();
        ownerPartitions.createStream(StreamConfig.streamConfig(STREAM,
                                                               1,
                                                               RetentionPolicy.retentionPolicy(10_000,
                                                                                               1024 * 1024,
                                                                                               600_000),
                                                               "earliest"))
                       .onFailure(cause -> fail(cause.message()));
        assigneePartitions = StreamPartitionManager.streamPartitionManager();
        // The transport is the wire: a ReadForward from the assignee lands on the owner's handler, whose
        // response lands on the assignee's client. Nothing else is between them.
        var clientRef = new StreamForwardClient[1];
        StreamForwardTransport ownerToAssignee = (_, message) -> clientRef[0].onReadForwardResponse((ReadForwardResponse) message);
        var ownerHandler = StreamForwardHandler.streamForwardHandler(OWNER, ownerPartitions, ownerToAssignee);
        StreamForwardTransport assigneeToOwner = (_, message) -> {
            forwardedReads.incrementAndGet();
            ownerHandler.onReadForward((ReadForward) message);
        };

        clientRef[0] = StreamForwardClient.streamForwardClient(ASSIGNEE,
                                                               assigneeToOwner,
                                                               TimeSpan.timeSpan(5).seconds());
        ownerRouter = StreamReadRouter.localOnly(ownerPartitions);
        assigneeRouter = StreamReadRouter.streamReadRouter(assigneePartitions,
                                                           Option.none(),
                                                           Option.some(clientRef[0]),
                                                           ASSIGNEE,
                                                           (_, _) -> Option.some(OWNER),
                                                           StreamReadForwardMetrics.NOOP);
    }

    @AfterEach
    void tearDown() throws Exception {
        ownerPartitions.close();
        assigneePartitions.close();
    }

    @Test
    void bounds_forwardedFromANodeWithoutTheRing_equalTheOwnersLocalBounds() {
        for (var i = 0; i < 5; i++) {
            ownerPartitions.publishLocal(STREAM,
                                         PARTITION,
                                         new byte[]{(byte) i},
                                         1_000L + i)
                           .onFailure(cause -> fail(cause.message()));
        }

        var local = ownerRouter.bounds(STREAM, PARTITION).await().unwrap();
        var forwarded = assigneeRouter.bounds(STREAM, PARTITION).await().unwrap();

        assertThat(local).as("control: the owner's own view").isEqualTo(VisibleBounds.visibleBounds(0L, 4L));
        assertThat(forwarded).as("the assignee learns exactly the owner's bounds").isEqualTo(local);
        assertThat(forwardedReads.get()).as("answered through ONE forwarded read").isEqualTo(1);
        assertThat(assigneePartitions.visibleBounds(STREAM, PARTITION)).as("control: the assignee holds no ring")
                  .isEqualTo(Option.none());
    }

    @Test
    void bounds_local_neverForward() {
        ownerPartitions.publishLocal(STREAM, PARTITION, new byte[]{7}, 1_000L).onFailure(cause -> fail(cause.message()));
        var local = ownerRouter.bounds(STREAM, PARTITION).await().unwrap();

        assertThat(local).isEqualTo(VisibleBounds.visibleBounds(0L, 0L));
        assertThat(forwardedReads.get()).isZero();
    }

    /// An empty ring is a real answer (`0 / -1`, [VisibleBounds#isEmpty]), distinct from "no ring", which is
    /// what the assignee's OWN partition manager says and what a rebuild must refuse on.
    @Test
    void bounds_ofAnEmptyRing_areAnswered_andAMissingRingIsRefused() {
        var forwarded = assigneeRouter.bounds(STREAM, PARTITION).await().unwrap();

        assertThat(forwarded.isEmpty()).isTrue();
        assertThat(StreamReadRouter.localOnly(assigneePartitions).bounds(STREAM, PARTITION).await().isFailure()).as("no ring here and no forward client: refused, not guessed")
                  .isTrue();
    }
}
