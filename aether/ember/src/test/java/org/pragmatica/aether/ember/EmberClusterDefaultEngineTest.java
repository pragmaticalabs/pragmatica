// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// F1 #2643 kill test for mutant u09 (`EmberCluster.deploymentEngine` fallback `selectedEngine.or(LEGACY)` -> FSM2).
///
/// An absent selector property is `Result.success(LEGACY)` (see `EngineSelector.engine`), so the fallback is NOT read on any started cluster: a
/// started-cluster assertion on "default engine is LEGACY" stays green under the mutant (measured, `EmberClusterDefaultEngineTest` first form,
/// 1 case green on the mutant). The fallback is read exactly when the selector FAILED (a value naming no engine); `start` then refuses, so the field
/// is observable only on a constructed, never-started cluster. The field is private and has no accessor, so it is read by reflection.
@PortBudget
class EmberClusterDefaultEngineTest {
    private static final int NODES = 3;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;

    @Test
    void fallbackEngine_whenTheSelectorNamesNoEngine_isLegacy() throws Exception {
        var previous = System.getProperty(EngineSelector.PROPERTY);

        System.setProperty(EngineSelector.PROPERTY, "no-such-engine");
        try {
            var base = EmberTestPorts.freeBase(new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                        EmberTestPorts.POOL_LAST,
                                                                        EmberTestPorts.POOL_STEP,
                                                                        NODES,
                                                                        MGMT_OFFSET,
                                                                        APP_HTTP_OFFSET));
            var cluster = emberCluster(NODES, base, base + MGMT_OFFSET, base + APP_HTTP_OFFSET, "eng-fallback");

            assertThat(engineField(cluster).get()).isEqualTo(DeploymentEngine.LEGACY);
        } finally {
            restore(previous);
        }
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<DeploymentEngine> engineField(EmberCluster cluster) throws Exception {
        Field field = EmberCluster.class.getDeclaredField("deploymentEngine");

        field.setAccessible(true);
        return (AtomicReference<DeploymentEngine>) field.get(cluster);
    }

    private static void restore(String previous) {
        if (previous == null) {
            System.clearProperty(EngineSelector.PROPERTY);
        } else {
            System.setProperty(EngineSelector.PROPERTY, previous);
        }
    }
}
