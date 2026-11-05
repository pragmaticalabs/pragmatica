// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// The global engine selector (FSM plan F1 item 7). The FSM2 CI job is only evidence if a typo cannot run LEGACY: an unknown value fails,
/// and a cluster built under it refuses to start.
class EngineSelectorTest {
    @Test
    void engine_isLegacy_whenThePropertyIsAbsentOrBlank() {
        assertThat(EngineSelector.engine(Option.none())).isEqualTo(Result.success(DeploymentEngine.LEGACY));
        assertThat(EngineSelector.engine(Option.some("  "))).isEqualTo(Result.success(DeploymentEngine.LEGACY));
    }

    @Test
    void engine_namesEachEngine_inAnyCase() {
        assertThat(EngineSelector.engine(Option.some("fsm2")).unwrap()).isEqualTo(DeploymentEngine.FSM2);
        assertThat(EngineSelector.engine(Option.some(" FSM2 ")).unwrap()).isEqualTo(DeploymentEngine.FSM2);
        assertThat(EngineSelector.engine(Option.some("Legacy")).unwrap()).isEqualTo(DeploymentEngine.LEGACY);
    }

    @Test
    void engine_fails_onAValueThatNamesNoEngine() {
        var selected = EngineSelector.engine(Option.some("fms2"));

        assertThat(selected.isFailure()).as("a misspelt engine never runs LEGACY").isTrue();
        selected.onFailure(cause -> assertThat(cause.message()).contains("fms2")
                                                               .contains(EngineSelector.PROPERTY));
    }

    @Test
    void cluster_refusesToStart_whenThePropertyNamesNoEngine() {
        var previous = System.getProperty(EngineSelector.PROPERTY);

        System.setProperty(EngineSelector.PROPERTY, "fms2");
        try {
            var cluster = EmberCluster.emberCluster(3);

            assertThat(cluster.start()
                              .await()
                              .isFailure()).as("start refused before any node is created")
                                           .isTrue();
            assertThat(cluster.status()
                              .nodes()).isEmpty();
        } finally {
            Option.option(previous)
                  .onPresent(value -> System.setProperty(EngineSelector.PROPERTY, value))
                  .onEmpty(() -> System.clearProperty(EngineSelector.PROPERTY));
        }
    }
}
