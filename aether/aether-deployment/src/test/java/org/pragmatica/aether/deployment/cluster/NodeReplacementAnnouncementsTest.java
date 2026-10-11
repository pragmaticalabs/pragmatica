// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.NodeReplacementAnnouncements.Announcement;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;

/// One committed transition, one event; every condition has its recovery under the same subject (the original node).
class NodeReplacementAnnouncementsTest {
    private static final NodeId OLD = new NodeId("core-OLD");
    private static final NodeId NEW = new NodeId("core-NEW");

    private static NodeReplacementValue record(NodeReplacementPhase phase, String reason) {
        return new NodeReplacementValue(NEW, "core", phase, 0L, "", "", "CTM", 0, reason, 0L);
    }

    private static List<OperatorWarningCode> codes(Option<NodeReplacementValue> before, NodeReplacementValue after) {
        return NodeReplacementAnnouncements.of(OLD, before, after).stream().map(Announcement::code).toList();
    }

    @Test
    void begin_isStarted_forTheOriginalAsSubject() {
        var out = NodeReplacementAnnouncements.of(OLD, Option.none(), record(NodeReplacementPhase.PROVISIONING, ""));

        assertThat(out).extracting(Announcement::code).containsExactly(OperatorWarningCode.NODE_REPLACEMENT_STARTED);
        assertThat(out.getFirst().subject()).isEqualTo("core-OLD");
    }

    @Test
    void doneAndRolledBack_areBothRecoveriesOfStarted() {
        assertThat(codes(Option.some(record(NodeReplacementPhase.RETIRING_OLD, "")), record(NodeReplacementPhase.DONE, "")))
            .containsExactly(OperatorWarningCode.NODE_REPLACEMENT_COMPLETED);
        assertThat(codes(Option.some(record(NodeReplacementPhase.JOINING, "")), record(NodeReplacementPhase.ROLLED_BACK, "join deadline")))
            .containsExactly(OperatorWarningCode.NODE_REPLACEMENT_ROLLED_BACK);
        assertThat(OperatorWarningCode.NODE_REPLACEMENT_COMPLETED.recoveryOf().unwrap()).isEqualTo(OperatorWarningCode.NODE_REPLACEMENT_STARTED);
        assertThat(OperatorWarningCode.NODE_REPLACEMENT_ROLLED_BACK.recoveryOf().unwrap()).isEqualTo(OperatorWarningCode.NODE_REPLACEMENT_STARTED);
    }

    @Test
    void joinOverdue_thenJoined_isAPair_onceEach() {
        var overdue = record(NodeReplacementPhase.JOINING, NodeReplacementPlanner.JOIN_OVERDUE);

        assertThat(codes(Option.some(record(NodeReplacementPhase.JOINING, "")), overdue)).containsExactly(OperatorWarningCode.NODE_REPLACEMENT_JOIN_OVERDUE);
        assertThat(codes(Option.some(overdue), record(NodeReplacementPhase.SWAPPING, ""))).containsExactly(OperatorWarningCode.NODE_REPLACEMENT_JOINED);
        assertThat(codes(Option.some(overdue), overdue)).as("an unchanged condition is not re-announced").isEmpty();
    }

    @Test
    void drainBlocked_thenUnblocked_isAPair() {
        var blocked = record(NodeReplacementPhase.DRAINING_OLD, NodeReplacementPlanner.DRAIN_BLOCKED + "slice a below floor");

        assertThat(codes(Option.some(record(NodeReplacementPhase.DRAINING_OLD, "")), blocked)).containsExactly(OperatorWarningCode.NODE_REPLACEMENT_DRAIN_BLOCKED);
        assertThat(codes(Option.some(blocked), record(NodeReplacementPhase.DRAINING_OLD, ""))).containsExactly(OperatorWarningCode.NODE_REPLACEMENT_DRAIN_UNBLOCKED);
    }

    @Test
    void keptBoth_thenSettled_isAPair_andSettlingToDoneAlsoCompletes() {
        var kept = record(NodeReplacementPhase.FAILED_KEPT_BOTH, "drain did not complete");

        assertThat(codes(Option.some(record(NodeReplacementPhase.DRAINING_OLD, "")), kept)).containsExactly(OperatorWarningCode.NODE_REPLACEMENT_FAILED_KEPT_BOTH);
        assertThat(codes(Option.some(kept), record(NodeReplacementPhase.DRAINING_OLD, ""))).containsExactly(OperatorWarningCode.NODE_REPLACEMENT_SETTLED);
    }

    @Test
    void everyConditionCodeHasARecovery() {
        assertThat(OperatorWarningCode.NODE_REPLACEMENT_STARTED.hasRecovery()).isTrue();
        assertThat(OperatorWarningCode.NODE_REPLACEMENT_JOIN_OVERDUE.hasRecovery()).isTrue();
        assertThat(OperatorWarningCode.NODE_REPLACEMENT_DRAIN_BLOCKED.hasRecovery()).isTrue();
        assertThat(OperatorWarningCode.NODE_REPLACEMENT_FAILED_KEPT_BOTH.hasRecovery()).isTrue();
    }
}
