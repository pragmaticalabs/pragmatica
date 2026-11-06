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
class EmberClusterDefaultEngineTest {
    @Test
    void fallbackEngine_whenTheSelectorNamesNoEngine_isLegacy() throws Exception {
        var previous = System.getProperty(EngineSelector.PROPERTY);

        System.setProperty(EngineSelector.PROPERTY, "no-such-engine");
        try {
            var cluster = emberCluster(3, 41000, 41040, 41080, "eng-fallback");

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
