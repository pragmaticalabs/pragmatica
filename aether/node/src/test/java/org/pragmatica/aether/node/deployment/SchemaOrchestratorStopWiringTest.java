// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// #3754 n2: the node's stop reaches the orchestrator THROUGH the wiring. `StopSequencePinTest` pins the source text of the call and of the
/// wiring body; this runs the wiring method, so a body that no longer ends the orchestrator's lifetime is red by behaviour, not only by text.
class SchemaOrchestratorStopWiringTest {
    @Test
    void stopSchemaOrchestrator_endsTheOrchestratorsLifetime_exactlyOnce_throughTheWiring() {
        try (var harness = Fsm2Harness.production(DeploymentEngine.FSM2).assembled()) {
            var orchestrator = new LifetimeRecordingOrchestrator();

            var result = harness.wiring.stopSchemaOrchestrator(orchestrator);

            assertThat(orchestrator.stops.get()).as("the wiring's stop ended this orchestrator's lifetime").isEqualTo(1);
            assertThat(result).isEqualTo(Unit.unit());
        }
    }

    private static final class LifetimeRecordingOrchestrator implements SchemaOrchestratorService {
        final AtomicInteger stops = new AtomicInteger();

        @Override
        public Unit stop() {
            stops.incrementAndGet();
            return Unit.unit();
        }

        @Override
        public Promise<Unit> migrateIfNeeded(String datasourceName) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> undoTo(String datasourceName, int targetVersion) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> baseline(String datasourceName, int version) {
            return Promise.unitPromise();
        }
    }
}
