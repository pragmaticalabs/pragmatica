// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.audit.RecentCommandsBuffer;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceOnDuty;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StopReason;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;


/// Characterization of the S01 re-projection bug (issue #230): the bypass writer
/// `DirectLifecycleWriter` re-promotes a force-decommissioned (`STOPPED + FORCED`) peer
/// straight back to `ON_DUTY` on a `ForceOnDuty` command, WITHOUT consulting the membership
/// reducer (whose `applyForceOnDuty(Stopped) -> nop` cell would block it). `forceLifecycleWrite`
/// reads the prior value only to preserve metadata, never to gate the transition — an
/// unconditional `Put(NodeLifecycleKey, ON_DUTY)`.
///
/// This test MUST pass against the current `DirectLifecycleWriter` (the bug reproduces). It is
/// the motivation for routing all lifecycle commands through the sovereign FSM
/// (`FsmRoutedLifecycleWriter`) — see `FsmRoutedLifecycleWriterTombstoneTest` for the fixed
/// behaviour. `DirectLifecycleWriter` is retained only as a test fixture after the migration.
class DirectLifecycleWriterForcedTombstoneTest {
    private static final NodeId PEER = NodeId.nodeId("node-2").unwrap();

    @Test
    void directWriter_forceOnDutyOnForciblyStoppedPeer_unconditionallyRepromotesToOnDuty() {
        var written = new ArrayList<NodeLifecycleValue>();
        var tombstoned = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.STOPPED, 1L)
                                           .withStopReason(Option.some(StopReason.FORCED));

        var writer = LifecycleWriter.directLifecycleWriter(
            _ -> Option.some(tombstoned),
            commands -> recordLifecycleWrites(commands, written),
            RecentCommandsBuffer.recentCommandsBuffer(8)
                                .teeOn(_ -> Promise.unitPromise()));

        writer.applyCommand(new ForceOnDuty(PEER, Causes.cause("stale readyCandidate"), HlcTimestamp.ZERO))
              .await();

        var repromoted = written.stream()
                                .anyMatch(value -> value.state() == NodeLifecycleState.ON_DUTY);
        assertTrue(repromoted,
                   "DirectLifecycleWriter must (buggily) re-promote a STOPPED+FORCED peer to ON_DUTY — "
                   + "if this fails, the S01 re-projection mechanism model is wrong");
    }

    private static Promise<List<Object>> recordLifecycleWrites(List<KVCommand<AetherKey>> commands,
                                                               List<NodeLifecycleValue> sink) {
        for (var command : commands) {
            if (command instanceof KVCommand.Put<?, ?> put
                && put.key() instanceof NodeLifecycleKey
                && put.value() instanceof NodeLifecycleValue value) {
                sink.add(value);
            }
        }
        return Promise.success(List.of());
    }
}
