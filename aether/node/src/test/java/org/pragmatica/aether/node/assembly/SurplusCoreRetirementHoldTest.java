// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node.assembly;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// #3324: the voter-excluded core retirement (the second core surplus drain, beside the membership reconciler's) holds back with
/// auto-heal disabled, asks the hold only when there is a victim, and drains every victim when not held.
class SurplusCoreRetirementHoldTest {
    private static final NodeId SURPLUS_A = new NodeId("surplus-core-a");
    private static final NodeId SURPLUS_B = new NodeId("surplus-core-b");

    private final List<NodeId> drained = new CopyOnWriteArrayList<>();
    private final List<String> heldSubjects = new CopyOnWriteArrayList<>();
    private final List<DrainReason> drainReasons = new CopyOnWriteArrayList<>();
    private final AtomicInteger asked = new AtomicInteger();

    @Test
    void drainSurplusCores_held_drainsNothing() {
        TopologyAssembly.drainSurplusCores(List.of(SURPLUS_A), () -> askHeld(true), this::recordDrain)
                        .await();

        assertThat(drained).as("auto-heal disabled: a surplus core is not drained on the cluster's own initiative").isEmpty();
        assertThat(asked).as("the hold was asked").hasValue(1);
    }

    @Test
    void drainSurplusCores_notHeld_drainsEveryVictim() {
        TopologyAssembly.drainSurplusCores(List.of(SURPLUS_A, SURPLUS_B), () -> askHeld(false), this::recordDrain)
                        .await();

        assertThat(drained).as("control: not held, every victim is drained").containsExactly(SURPLUS_A, SURPLUS_B);
    }

    @Test
    void drainSurplusCores_noVictim_neverAsksTheHold() {
        TopologyAssembly.drainSurplusCores(List.of(), () -> askHeld(true), this::recordDrain)
                        .await();

        assertThat(asked).as("no surplus: no hold is raised").hasValue(0);
        assertThat(drained).isEmpty();
    }

    /// The production wiring, not the stubbed gate: the hold asked is the topology's own, for the subject `core`, and each victim leaves as
    /// an OVERPROVISION_SCALE_DOWN drain. Held, nothing drains; not held, every victim drains with that reason.
    @Test
    void retireSurplusCores_autoHealDisabled_askedForCore_drainsNothing() {
        var topology = topology(true);

        TopologyAssembly.retireSurplusCores(List.of(SURPLUS_A), topology)
                        .await();

        assertThat(heldSubjects).as("the hold is asked for the subject core").containsExactly(ClusterTopologyManager.coreSubject());
        assertThat(drained).as("auto-heal disabled: the production wiring drains no surplus core").isEmpty();
    }

    @Test
    void retireSurplusCores_autoHealEnabled_drainsAsOverprovisionScaleDown() {
        var topology = topology(false);

        TopologyAssembly.retireSurplusCores(List.of(SURPLUS_A, SURPLUS_B), topology)
                        .await();

        assertThat(heldSubjects).as("the hold is asked for the subject core").containsExactly(ClusterTopologyManager.coreSubject());
        assertThat(drained).as("auto-heal enabled: every surplus core is drained").containsExactly(SURPLUS_A, SURPLUS_B);
        assertThat(drainReasons).as("with the reason that keeps the retirement refusal and the grace re-check")
                                .containsOnly(DrainReason.OVERPROVISION_SCALE_DOWN);
    }

    private ClusterTopologyManager topology(boolean held) {
        return (ClusterTopologyManager) Proxy.newProxyInstance(ClusterTopologyManager.class.getClassLoader(),
                                                               new Class<?>[]{ClusterTopologyManager.class},
                                                               (proxy, method, args) -> answer(method.getName(), args, held));
    }

    private Object answer(String method, Object[] args, boolean held) {
        return switch (method) {
            case "scalingHeld" -> {
                heldSubjects.add((String) args[0]);
                yield held;
            }
            case "drainNode" -> {
                drainReasons.add((DrainReason) args[1]);
                yield recordDrain((NodeId) args[0]);
            }
            default -> throw new UnsupportedOperationException(method);
        };
    }

    private boolean askHeld(boolean held) {
        asked.incrementAndGet();

        return held;
    }

    private Promise<Unit> recordDrain(NodeId node) {
        drained.add(node);

        return Promise.unitPromise();
    }
}
