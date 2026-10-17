// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.forward;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Deadline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.forward.StreamForwardClient.streamForwardClient;

/// #1996: a stream publish or read forward must not outlive, in a peer's offline buffer, the wait its caller just
/// started. The transport drops a buffered frame once the wait it was handed has passed, so what is pinned here is the
/// wait the client hands over: the SAME one that arms the caller's timeout. A client that sent the plain frame instead
/// would let a publish whose caller was told "outcome unknown" be applied minutes later.
class StreamForwardOfflineTtlTest {
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId TARGET = NodeId.randomNodeId();
    private static final TimeSpan PUBLISH_TIMEOUT = TimeSpan.timeSpan(7).seconds();
    private static final TimeSpan READ_TIMEOUT = TimeSpan.timeSpan(9).seconds();
    private static final long BUDGET_MILLIS = 300L;

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final List<StreamForwardMessage> plain = new CopyOnWriteArrayList<>();

    private record Sent(StreamForwardMessage message, TimeSpan lifetime) {}

    private final StreamForwardClient client = streamForwardClient(SELF, new StreamForwardTransport() {
        @Override
        public void send(NodeId target, StreamForwardMessage message) {
            plain.add(message);
        }

        @Override
        public void send(NodeId target, StreamForwardMessage message, TimeSpan callerWait) {
            sent.add(new Sent(message, callerWait));
        }
    }, PUBLISH_TIMEOUT, READ_TIMEOUT);

    @Test
    void publish_withNoAmbientBudget_frameLifetimeIsThePublishTimeout() {
        client.publishRemote(TARGET, "events", 0, new byte[]{1}, 1L);

        assertThat(plain).as("a publish forward is never sent without a frame lifetime").isEmpty();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().message()).isInstanceOf(StreamForwardMessage.PublishForward.class);
        assertThat(sent.getFirst().lifetime().millis()).isEqualTo(PUBLISH_TIMEOUT.millis());
    }

    @Test
    void publish_underBoundedBudget_frameLifetimeIsTheRemainingBudget() {
        Deadline.runWith(Deadline.fromWireMillis(BUDGET_MILLIS),
                         () -> client.publishRemote(TARGET, "events", 0, new byte[]{1}, 1L));

        assertThat(plain).isEmpty();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().lifetime().millis())
            .as("the budget that remained (<= 300ms), not the configured 7s publish timeout")
            .isPositive()
            .isLessThanOrEqualTo(BUDGET_MILLIS);
    }

    @Test
    void read_withNoAmbientBudget_frameLifetimeIsTheReadTimeout() {
        client.readRemote(TARGET, "events", 0, 0L, 10);

        assertThat(plain).as("a read forward is never sent without a frame lifetime").isEmpty();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().message()).isInstanceOf(StreamForwardMessage.ReadForward.class);
        assertThat(sent.getFirst().lifetime().millis()).isEqualTo(READ_TIMEOUT.millis());
    }

    @Test
    void read_underBoundedBudget_frameLifetimeIsTheRemainingBudget() {
        Deadline.runWith(Deadline.fromWireMillis(BUDGET_MILLIS), () -> client.readRemote(TARGET, "events", 0, 0L, 10));

        assertThat(plain).isEmpty();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().lifetime().millis()).isPositive().isLessThanOrEqualTo(BUDGET_MILLIS);
    }
}
