// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.invoke;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// C9 tripwire, timer kind (KVStore dispatcher design §3.5.1 row 25, D1-react entry criterion; it stands in for
/// row 44, whose CDM row moved to the new deployment FSM, know bee940f92f).
///
/// Pins TODAY's behaviour: a ScheduledTask put delivered after an engine-only stop arms the task's timer inline from
/// the absorb path. Nothing gates it: the engine stop has no input into the registry, and that absence is exactly
/// what this test pins (the stop is not simulated: no seam exists to simulate it through).
///
/// When the D1-react engine-alive gate lands this test goes red. Then delete it and enable the (ab) timer arm.
class ScheduledTaskEngineStopTripwireTest {
    private static final String ENABLE = "D1-react engine-alive gate landed: delete ScheduledTaskEngineStopTripwireTest "
                                         + "and enable the exit (ab) timer arm";

    private final NodeId self = new NodeId("node-tripwire");
    private final ScheduledTaskRegistry registry = ScheduledTaskRegistry.scheduledTaskRegistry();
    private final ScheduledTaskManager manager = ScheduledTaskManager.scheduledTaskManager(
        registry,
        new ScheduledTaskManagerTest.StubSliceInvoker(new CopyOnWriteArrayList<>(), Option.none()),
        self,
        _ -> {},
        _ -> Option.none(),
        new ScheduledTaskManagerTest.TestLeaderManager(self));

    @AfterEach
    void tearDown() {
        manager.stop();
    }

    @Test
    void tripwire_taskPutAfterEngineStop_armsTimerInline() {
        manager.onQuorumStateChange(ClusterStateNotification.active());

        registry.onScheduledTaskPut(put());

        assertThat(manager.activeTimerCount()).as(ENABLE).isEqualTo(1);
    }

    private ValuePut<ScheduledTaskKey, ScheduledTaskValue> put() {
        var key = ScheduledTaskKey.scheduledTaskKey("cache",
                                                    Artifact.artifact("org.example:my-slice:1.0.0").unwrap(),
                                                    MethodName.methodName("cleanup").unwrap());

        return new ValuePut<>(new KVCommand.Put<>(key, ScheduledTaskValue.intervalTask(self, "30s", ExecutionMode.ALL)),
                              Option.none());
    }
}
