// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;

import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2721: a stopped node must leave no `MembershipFsm` timer armed on the JVM-wide `SharedScheduler`. `MembershipFsmCloseTest`
/// pins what `MembershipFsm.close()` cancels; `StopSequencePinTest` pins the call as source text. This boots a REAL node,
/// gives its own FSM a member (the join grace is armed on first observation, 15 s by default, far longer than the stop),
/// stops the node and reads the FSM's armed-timer count. Mutant: delete `membershipFsm.close();` from the node's stop path
/// and the count stays at 1 (the timer keeps re-arming for as long as the JVM lives).
class AetherNodeStopCancelsMembershipTimersBootTest {
    @TempDir
    Path tempDir;

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds()).onFailure(cause -> fail("stop failed in teardown: " + cause.message()));
        }
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void stop_leavesNoMembershipFsmTimerArmed() {
        node = AetherNode.aetherNode(AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(),
                                                                                        Option.none(),
                                                                                        ConfigurationProvider.builder().build(),
                                                                                        tempDir),
                                     () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();

        var fsm = node.membershipFsm();
        var armedBefore = fsm.pendingTimerCount();

        fsm.onPeerConnected(NodeId.nodeId("ghost-peer-2721").unwrap());

        assertThat(fsm.pendingTimerCount()).as("control: the running node's FSM arms the new member's join grace")
                                           .isEqualTo(armedBefore + 1);

        node.stop().await(timeSpan(30).seconds()).onFailure(cause -> fail("stop must succeed: " + cause.message()));
        node = null;

        assertThat(fsm.pendingTimerCount()).as("#2721: node stop must cancel every membership timer").isZero();
    }
}
