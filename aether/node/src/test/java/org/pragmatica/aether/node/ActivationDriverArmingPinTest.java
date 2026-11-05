// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// F1 #2643 kill pin for mutant u04 (AetherNode `periodicTasks.defer(scheduleAtFixedRate(activationDriver, ...))` deleted, which left the
/// 4,892-case `aether/node` run green): the activation level-heal driver built from `deploymentWiring.activationDriver(..)` is handed to the node's
/// periodic tasks at the reconcile interval, so it is armed in `start()` and cancelled in `stop()` with the rest of the node's recurring work.
/// Same technique and limit as `NodeWiringSurvivorPinsTest`: the assembly source is read with comments stripped and whitespace removed, so this pins
/// that the arming statement is present and ordered before `subsystemsReady()`, not that a 5 s tick fires. No boot test can observe the tick today:
/// the driver's effect (an NDM / FSM2 entry request) has no externally readable counter.
class ActivationDriverArmingPinTest {
    @Test
    void node_armsTheActivationDriverOnThePeriodicTasks_atTheReconcileInterval() {
        assertThat(AssemblySource.stripped())
            .containsOnlyOnce("periodicTasks.defer(()->SharedScheduler.scheduleAtFixedRate(activationDriver,NDM_ACTIVATION_RECONCILE_INTERVAL));nodeLifecycle.subsystemsReady();");
    }
}
