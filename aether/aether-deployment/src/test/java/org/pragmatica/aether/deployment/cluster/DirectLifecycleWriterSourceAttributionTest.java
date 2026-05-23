// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent;
import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent.CommandApplied;
import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent.CommandReceived;
import org.pragmatica.aether.deployment.audit.RecentCommandsBuffer;
import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDecommission;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDrain;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceOnDuty;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.RecordJoining;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.RequestReJoin;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/// Phase 3 PR-C — exercises the source-tagged `applyCommand` overload on
/// `DirectLifecycleWriter`. Two failure modes are covered:
///   - happy path: source flows onto both `CommandReceived` and `CommandApplied`;
///   - failure path: source flows onto `CommandApplied` with `accepted=false`.
class DirectLifecycleWriterSourceAttributionTest {
    private List<CommandLifecycleEvent> emitted;
    private RecentCommandsBuffer buffer;
    private AtomicReference<NodeLifecycleValue> lastWritten;
    private boolean failNextWrite;

    @BeforeEach
    void setUp() {
        emitted = new ArrayList<>();
        buffer = RecentCommandsBuffer.recentCommandsBuffer(16);
        lastWritten = new AtomicReference<>();
        failNextWrite = false;
    }

    @Nested
    class HappyPath {
        @Test
        void applyCommand_threadsOperatorSourceToBothEvents() {
            var writer = buildWriter();
            var cmd = forceDecommission();
            writer.applyCommand(cmd, CommandLifecycleEvent.SOURCE_OPERATOR)
                  .await();

            assertEquals(2, emitted.size());
            var received = (CommandReceived) emitted.get(0);
            var applied = (CommandApplied) emitted.get(1);
            assertEquals("OPERATOR", received.source());
            assertEquals("OPERATOR", applied.source());
            assertEquals("ForceDecommission", received.commandType());
            assertEquals("ForceDecommission", applied.commandType());
            assertEquals("FORCED", received.reasonTag());
            assertTrue(applied.accepted());
        }

        @Test
        void applyCommand_defaultsToUnknownSourceWhenLegacyOverloadUsed() {
            var writer = buildWriter();
            writer.applyCommand(forceDecommission())
                  .await();

            assertEquals(2, emitted.size());
            assertEquals("UNKNOWN", emitted.get(0).source());
            assertEquals("UNKNOWN", emitted.get(1).source());
        }

        @Test
        void applyCommand_threadsReconcilerSourceForForceOnDuty() {
            var writer = buildWriter();
            var cmd = new ForceOnDuty(NodeId.nodeId("node-2").unwrap(),
                                       Causes.cause("reconciler-test"),
                                       HlcTimestamp.ZERO);
            writer.applyCommand(cmd, CommandLifecycleEvent.SOURCE_RECONCILER)
                  .await();
            assertEquals("RECONCILER", emitted.get(0).source());
            assertEquals("RECONCILER", emitted.get(1).source());
        }

        @Test
        void applyCommand_threadsCtmSourceForForceDrain() {
            var writer = buildWriter();
            var cmd = new ForceDrain(NodeId.nodeId("node-2").unwrap(),
                                      DrainReason.OPERATOR_DRAIN,
                                      Causes.cause("ctm-test"),
                                      HlcTimestamp.ZERO);
            writer.applyCommand(cmd, CommandLifecycleEvent.SOURCE_CTM)
                  .await();
            assertEquals("CTM", emitted.get(0).source());
            assertEquals("OPERATOR_DRAIN", emitted.get(0).reasonTag());
        }

        @Test
        void applyCommand_threadsBootstrapSourceForRecordJoining() {
            var writer = buildWriter();
            var cmd = new RecordJoining(NodeId.nodeId("node-2").unwrap(),
                                         Option.some("slot-a"),
                                         Causes.cause("bootstrap-test"),
                                         HlcTimestamp.ZERO);
            writer.applyCommand(cmd, CommandLifecycleEvent.SOURCE_BOOTSTRAP)
                  .await();
            assertEquals("BOOTSTRAP", emitted.get(0).source());
        }

        @Test
        void applyCommand_threadsOperatorSourceForRequestReJoin() {
            var writer = buildWriter();
            var cmd = new RequestReJoin(NodeId.nodeId("node-2").unwrap(),
                                         Causes.cause("operator-rejoin"),
                                         HlcTimestamp.ZERO);
            writer.applyCommand(cmd, CommandLifecycleEvent.SOURCE_OPERATOR)
                  .await();
            assertEquals("OPERATOR", emitted.get(0).source());
        }

        @Test
        void applyCommand_alsoLandsInTeedBuffer() {
            var writer = buildWriter();
            writer.applyCommand(forceDecommission(), CommandLifecycleEvent.SOURCE_OPERATOR)
                  .await();
            assertEquals(2, buffer.size());
            assertEquals("OPERATOR", buffer.snapshotAll().get(0).source());
        }
    }

    @Nested
    class FailurePath {
        @Test
        void applyCommand_emitsAppliedWithFalseOnWriteFailure() {
            failNextWrite = true;
            var writer = buildWriter();
            writer.applyCommand(forceDecommission(), CommandLifecycleEvent.SOURCE_OPERATOR)
                  .await();

            assertEquals(2, emitted.size());
            var applied = (CommandApplied) emitted.get(1);
            assertFalse(applied.accepted());
            assertEquals("OPERATOR", applied.source());
        }
    }

    private LifecycleWriter buildWriter() {
        return LifecycleWriter.directLifecycleWriter(_ -> Option.none(),
                                                      this::applyCommandsToFakeKv,
                                                      buffer.teeOn(event -> {
                                                          emitted.add(event);
                                                          return Promise.unitPromise();
                                                      }));
    }

    @SuppressWarnings("unchecked")
    private Promise<List<Object>> applyCommandsToFakeKv(List<KVCommand<AetherKey>> commands) {
        if (failNextWrite) {
            return Causes.cause("fake-kv: simulated failure").promise();
        }
        for (var raw : commands) {
            if (raw instanceof KVCommand.Put<?, ?> put && put.key() instanceof NodeLifecycleKey
                && put.value() instanceof NodeLifecycleValue value) {
                lastWritten.set(value);
            }
        }
        return Promise.success(List.of());
    }

    private static ForceDecommission forceDecommission() {
        return new ForceDecommission(NodeId.nodeId("node-2").unwrap(),
                                      StopReason.FORCED,
                                      Causes.cause("operator decommission"),
                                      HlcTimestamp.ZERO);
    }
}
