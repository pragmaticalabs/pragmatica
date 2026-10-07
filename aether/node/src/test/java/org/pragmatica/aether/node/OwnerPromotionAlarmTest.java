// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.stream.OwnerActivation.ActivationBlock;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #1937 (owner rule: every operator-facing condition raises on the transition AND recovers on the opposite one): the owner-promotion
/// block alarm maps the oversized-event refusal and the unreachable-members wait to their codes, and each block's end to the code that
/// recovers it. Subjects match, so the event layer publishes the recovery only after the raise (#752).
class OwnerPromotionAlarmTest {
    private static final NodeId PEER = NodeId.randomNodeId();
    private final List<OperatorWarning> published = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(published::add);

    @Test
    void oversizedEventBlock_raisesItsCode_andItsEndRaisesTheRecovery_onTheSameSubject() {
        var alarm = AetherNode.ownerPromotionAlarm(sink);
        var block = new ActivationBlock.PeerEventExceedsReadCap("orders", 3, PEER, 7L);

        alarm.raise(block);
        alarm.resolved(block);

        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> published.size() == 2);
        assertThat(published).extracting(OperatorWarning::code)
                             .containsExactly(OperatorWarningCode.STREAM_EVENT_EXCEEDS_READ_CAP, OperatorWarningCode.STREAM_EVENT_EXCEEDS_READ_CAP_RESOLVED);
        assertThat(published).extracting(OperatorWarning::subject).containsOnly("orders[3]");
        assertThat(OperatorWarningCode.STREAM_EVENT_EXCEEDS_READ_CAP_RESOLVED.recoveryOf()).isEqualTo(org.pragmatica.lang.Option.some(OperatorWarningCode.STREAM_EVENT_EXCEEDS_READ_CAP));
    }

    @Test
    void holdersUnreachableBlock_raisesAnOperatorEvent_notOnlyALog_andItsEndRaisesTheRecovery() {
        var alarm = AetherNode.ownerPromotionAlarm(sink);
        var block = new ActivationBlock.HoldersUnreachable("orders", 3, List.of(PEER), List.of(), TimeSpan.timeSpan(30).seconds());

        alarm.raise(block);
        alarm.resolved(block);

        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> published.size() == 2);
        assertThat(published).extracting(OperatorWarning::code)
                             .containsExactly(OperatorWarningCode.STREAM_OWNER_PROMOTION_HOLDERS_UNREACHABLE,
                                              OperatorWarningCode.STREAM_OWNER_PROMOTION_HOLDERS_ANSWERING);
        assertThat(published).extracting(OperatorWarning::subject).containsOnly("orders[3]");
        assertThat(OperatorWarningCode.STREAM_OWNER_PROMOTION_HOLDERS_ANSWERING.recoveryOf())
            .isEqualTo(org.pragmatica.lang.Option.some(OperatorWarningCode.STREAM_OWNER_PROMOTION_HOLDERS_UNREACHABLE));
    }

    /// The blocks that still have no code stay a log line: ending one raises no event, there is nothing to recover.
    @Test
    void otherBlock_endingRaisesNoEvent() throws InterruptedException {
        var alarm = AetherNode.ownerPromotionAlarm(sink);

        alarm.resolved(new ActivationBlock.DivergentPeer("orders", 3, PEER, 5L, 6L));
        Thread.sleep(200);

        assertThat(published).isEmpty();
    }
}
