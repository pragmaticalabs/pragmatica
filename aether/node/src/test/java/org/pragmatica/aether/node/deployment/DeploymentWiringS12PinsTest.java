// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// F1 mutation survivor pins (run s1, `nodedep-DeploymentWiring-12` and `-27`): under FSM2 the B3 activation driver and the B13 stop take the FSM2
/// path only once a system is attached. Before that (and on an FSM2 worker, where `attach` is refused) the driver is the dropped legacy gate and
/// the stop runs the legacy deactivation it is handed. Red when either guard's `&&` is widened to `||`, which takes the FSM2 path with no system
/// behind it: the driver is recorded as the FSM2 target and does nothing, and the stop does nothing. Every existing FSM2 test attaches a system
/// first, so none of them reaches this branch.
class DeploymentWiringS12PinsTest {
    @Test
    void activationDriver_isTheDroppedLegacyGate_underFsm2_whileNoSystemIsAttached() {
        try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false)) {
            var legacy = new AtomicInteger();

            harness.wiring.activationDriver(() -> true, legacy::incrementAndGet).run();

            assertThat(harness.wiring.system().isPresent()).as("nothing attached").isFalse();
            assertThat(harness.wiring.recorded()).contains("B3.ActivationDriver -> dropped");
            assertThat(harness.registry.counter("aether_deployment_fsm2_dropped_total", "point", "B3.ActivationDriver").count()).isEqualTo(1.0);
            assertThat(legacy).as("the legacy driver never runs under FSM2").hasValue(0);
        }
    }

    @Test
    void deactivateCluster_runsTheLegacyDeactivation_underFsm2_whileNoSystemIsAttached() {
        try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false)) {
            var legacy = new AtomicInteger();

            harness.wiring.deactivateCluster(() -> legacyDeactivation(legacy)).await();

            assertThat(harness.wiring.system().isPresent()).as("nothing attached").isFalse();
            assertThat(legacy).hasValue(1);
        }
    }

    private static Promise<Unit> legacyDeactivation(AtomicInteger legacy) {
        legacy.incrementAndGet();

        return Promise.unitPromise();
    }
}
