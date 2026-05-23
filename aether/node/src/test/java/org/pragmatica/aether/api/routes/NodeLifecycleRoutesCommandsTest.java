// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.routes.NodeLifecycleRoutes.LifecycleCommandRequest;
import org.pragmatica.aether.api.routes.NodeLifecycleRoutes.LifecycleCommandResponse;
import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent;
import org.pragmatica.aether.deployment.audit.RecentCommandsBuffer;
import org.pragmatica.aether.deployment.cluster.LifecycleWriter;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Phase 3 PR-C — `POST /api/nodes/lifecycle/commands` request parsing + dispatch.
/// Covers:
///   - body parsing for each of the 5 `LifecycleCommand` variants;
///   - validation of malformed bodies (missing type/nodeId, unknown variant, unknown
///     stop/drain reason);
///   - source attribution (`OPERATOR` flows onto the audit publisher);
///   - command-builder fidelity (peer / reason / stopReason / drainReason / slotId).
class NodeLifecycleRoutesCommandsTest {
    private List<LifecycleCommand> capturedCommands;
    private List<String> capturedSources;
    private RecentCommandsBuffer buffer;
    private NodeLifecycleRoutes routes;
    private AtomicReference<LifecycleWriter> writerRef;

    @BeforeEach
    void setUp() {
        capturedCommands = new ArrayList<>();
        capturedSources = new ArrayList<>();
        buffer = RecentCommandsBuffer.recentCommandsBuffer(8);
        writerRef = new AtomicReference<>(captureWriter());
        routes = NodeLifecycleRoutes.nodeLifecycleRoutes(this::nodeProxy);
    }

    @Nested
    class Validation {
        @Test
        void handleLifecycleCommand_nullBody_fails() {
            var result = routes.handleLifecycleCommandForTesting(null).await();
            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message().toLowerCase()).contains("body"));
        }

        @Test
        void handleLifecycleCommand_missingType_fails() {
            var request = new LifecycleCommandRequest(null, "node-2", "reason", null, null, null);
            var result = routes.handleLifecycleCommandForTesting(request).await();
            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message().toLowerCase()).contains("type"));
        }

        @Test
        void handleLifecycleCommand_missingNodeId_fails() {
            var request = new LifecycleCommandRequest("FORCE_DECOMMISSION", "", "reason", null, null, null);
            var result = routes.handleLifecycleCommandForTesting(request).await();
            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message().toLowerCase()).contains("nodeid"));
        }

        @Test
        void handleLifecycleCommand_unknownType_fails() {
            var request = new LifecycleCommandRequest("FORCE_UNKNOWN", "node-2", "reason", null, null, null);
            var result = routes.handleLifecycleCommandForTesting(request).await();
            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message().toUpperCase()).contains("FORCE_DECOMMISSION"));
        }

        @Test
        void handleLifecycleCommand_unknownStopReason_fails() {
            var request = new LifecycleCommandRequest("FORCE_DECOMMISSION", "node-2", "reason", "INVALID", null, null);
            var result = routes.handleLifecycleCommandForTesting(request).await();
            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message().toUpperCase()).contains("FORCED"));
        }

        @Test
        void handleLifecycleCommand_unknownDrainReason_fails() {
            var request = new LifecycleCommandRequest("FORCE_DRAIN", "node-2", "reason", null, "INVALID", null);
            var result = routes.handleLifecycleCommandForTesting(request).await();
            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message().toUpperCase()).contains("DRAINREASON"));
        }
    }

    @Nested
    class HappyPath {
        @Test
        void handleLifecycleCommand_forceDecommissionWithDefaults_succeeds() {
            var request = new LifecycleCommandRequest("FORCE_DECOMMISSION", "node-2", "stuck JOINING", null, null, null);
            var response = expectSuccess(request);

            assertThat(response.accepted()).isTrue();
            assertThat(response.commandType()).isEqualTo("ForceDecommission");
            assertThat(response.nodeId()).isEqualTo("node-2");
            assertThat(capturedCommands).hasSize(1);
            assertThat(capturedSources).containsExactly(CommandLifecycleEvent.SOURCE_OPERATOR);
            var cmd = (LifecycleCommand.ForceDecommission) capturedCommands.get(0);
            assertThat(cmd.peer().id()).isEqualTo("node-2");
            assertThat(cmd.reason().name()).isEqualTo("FORCED");
            assertThat(cmd.justification().message()).contains("stuck JOINING");
        }

        @Test
        void handleLifecycleCommand_forceDecommissionWithGracefulStopReason_succeeds() {
            var request = new LifecycleCommandRequest("FORCE_DECOMMISSION", "node-2", "drain success", "GRACEFUL", null, null);
            expectSuccess(request);
            var cmd = (LifecycleCommand.ForceDecommission) capturedCommands.get(0);
            assertThat(cmd.reason().name()).isEqualTo("GRACEFUL");
        }

        @Test
        void handleLifecycleCommand_forceDrainWithDefaults_succeeds() {
            var request = new LifecycleCommandRequest("FORCE_DRAIN", "node-2", "operator drain", null, null, null);
            expectSuccess(request);
            var cmd = (LifecycleCommand.ForceDrain) capturedCommands.get(0);
            assertThat(cmd.reason().name()).isEqualTo("OPERATOR_DRAIN");
        }

        @Test
        void handleLifecycleCommand_forceOnDuty_succeeds() {
            var request = new LifecycleCommandRequest("FORCE_ON_DUTY", "node-2", "stuck JOINING", null, null, null);
            expectSuccess(request);
            assertThat(capturedCommands.get(0)).isInstanceOf(LifecycleCommand.ForceOnDuty.class);
        }

        @Test
        void handleLifecycleCommand_recordJoiningWithSlot_succeeds() {
            var request = new LifecycleCommandRequest("RECORD_JOINING", "node-2", "gap rule", null, null, "slot-a");
            expectSuccess(request);
            var cmd = (LifecycleCommand.RecordJoining) capturedCommands.get(0);
            assertThat(cmd.slotId().or((String) null)).isEqualTo("slot-a");
        }

        @Test
        void handleLifecycleCommand_recordJoiningWithoutSlot_succeeds() {
            var request = new LifecycleCommandRequest("RECORD_JOINING", "node-2", "no slot", null, null, null);
            expectSuccess(request);
            var cmd = (LifecycleCommand.RecordJoining) capturedCommands.get(0);
            assertThat(cmd.slotId().isEmpty()).isTrue();
        }

        @Test
        void handleLifecycleCommand_requestReJoin_succeeds() {
            var request = new LifecycleCommandRequest("REQUEST_REJOIN", "node-2", "stuck DRAINING", null, null, null);
            expectSuccess(request);
            assertThat(capturedCommands.get(0)).isInstanceOf(LifecycleCommand.RequestReJoin.class);
        }

        @Test
        void handleLifecycleCommand_lowercaseTypeIsAccepted() {
            var request = new LifecycleCommandRequest("force_decommission", "node-2", "lowercase", null, null, null);
            expectSuccess(request);
            assertThat(capturedCommands.get(0)).isInstanceOf(LifecycleCommand.ForceDecommission.class);
        }
    }

    private LifecycleCommandResponse expectSuccess(LifecycleCommandRequest request) {
        var result = routes.handleLifecycleCommandForTesting(request)
                            .onFailure(cause -> fail("expected success but got: " + cause.message()))
                            .await();
        return result.unwrap();
    }

    @SuppressWarnings("unchecked")
    private ManageableNode nodeProxy() {
        return (ManageableNode) Proxy.newProxyInstance(
            ManageableNode.class.getClassLoader(),
            new Class[]{ManageableNode.class},
            (_, method, args) -> switch (method.getName()) {
                case "hlcClock" -> stubHlcClock();
                case "lifecycleWriter" -> writerRef.get();
                case "recentCommandsBuffer" -> buffer;
                case "route" -> null;
                default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
            }
        );
    }

    private LifecycleWriter captureWriter() {
        return new LifecycleWriter() {
            @Override public Promise<Unit> requestDrain(org.pragmatica.consensus.NodeId target) { return Promise.unitPromise(); }
            @Override public Promise<Unit> requestDecommission(org.pragmatica.consensus.NodeId target) { return Promise.unitPromise(); }
            @Override public Promise<Unit> requestActivate(org.pragmatica.consensus.NodeId target) { return Promise.unitPromise(); }
            @Override public Promise<Unit> requestFailedDrain(org.pragmatica.consensus.NodeId target) { return Promise.unitPromise(); }
            @Override public Promise<Unit> applyCommand(LifecycleCommand command) { return applyCommand(command, CommandLifecycleEvent.SOURCE_UNKNOWN); }
            @Override public Promise<Unit> applyCommand(LifecycleCommand command, String source) {
                capturedCommands.add(command);
                capturedSources.add(source);
                return Promise.unitPromise();
            }
        };
    }

    private static HlcClock stubHlcClock() {
        return HlcClock.hlcClock("test-node").unwrap();
    }
}
