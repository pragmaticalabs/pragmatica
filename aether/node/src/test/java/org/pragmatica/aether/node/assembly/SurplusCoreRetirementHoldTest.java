// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node.assembly;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #3324: the voter-excluded core retirement (the second core surplus drain, beside the membership reconciler's) holds back with
/// auto-heal disabled, asks the hold on every evaluated pass (pending = a victim exists), and drains every victim when not held.
class SurplusCoreRetirementHoldTest {
    private static final NodeId SURPLUS_A = new NodeId("surplus-core-a");
    private static final NodeId SURPLUS_B = new NodeId("surplus-core-b");

    private final List<NodeId> drained = new CopyOnWriteArrayList<>();
    private final List<String> heldSubjects = new CopyOnWriteArrayList<>();
    private final List<DrainReason> drainReasons = new CopyOnWriteArrayList<>();
    private final AtomicInteger asked = new AtomicInteger();
    private final List<Boolean> askedPending = new CopyOnWriteArrayList<>();
    private final List<ClusterTopologyManager.ScalingAsker> askers = new CopyOnWriteArrayList<>();
    private final List<String> warned = new CopyOnWriteArrayList<>();
    private final SurplusRefusals refusals = SurplusRefusals.surplusRefusals((node, cause) -> warned.add(node.id() + ": " + cause.message()));
    /// The cause `refuseA` refuses SURPLUS_A with on this pass; none lets it drain.
    private final AtomicReference<Option<String>> refusalOfA = new AtomicReference<>(Option.none());

    /// #3626: a refused victim skips only itself. The leader's own id is refused by the topology manager on every pass while it
    /// leads; the victims after it in the pass are still drained.
    @Test
    void drainSurplusCores_refusedVictim_skipsOnlyItselfAndTheNextIsDrained() {
        refusalOfA.set(Option.some("Node surplus-core-a is the leader"));

        var pass = TopologyAssembly.drainSurplusCores(List.of(SURPLUS_A, SURPLUS_B), _ -> false, this::refuseA, refusals)
                                   .await();

        assertThat(pass.isSuccess()).as("a refusal is the victim's own, not the pass's").isTrue();
        assertThat(drained).as("the victim after the refused one is still drained").containsExactly(SURPLUS_B);
        assertThat(warned).containsExactly("surplus-core-a: Node surplus-core-a is the leader");
    }

    /// #3626: a refusal is warned once per transition of (victim, cause), not on every 1 s pass. The same cause again is silent; a
    /// new cause is told; a victim that drains, or that a pass does not try, is forgotten, so its next refusal is told again.
    @Test
    void drainSurplusCores_repeatedRefusal_warnedOncePerTransition() {
        refusalOfA.set(Option.some("first cause"));
        passes(3);
        assertThat(warned).as("three passes, one cause: told once").containsExactly("surplus-core-a: first cause");

        refusalOfA.set(Option.some("second cause"));
        passes(2);
        assertThat(warned).as("the cause changed: told once more").hasSize(2).endsWith("surplus-core-a: second cause");

        refusalOfA.set(Option.none());
        passes(1);
        refusalOfA.set(Option.some("second cause"));
        passes(1);
        assertThat(warned).as("drained between: the same cause is a new transition").hasSize(3);

        TopologyAssembly.drainSurplusCores(List.of(), _ -> false, this::refuseA, refusals)
                        .await();
        passes(1);
        assertThat(warned).as("not tried by a pass: forgotten, told again").hasSize(4);
    }

    /// #3626: a failed voter reconcile skips the retirement for that pass, so it tries nothing and forgets every told refusal; the
    /// same refusal after it is a new transition. A successful reconcile runs the retirement, and the refusal stays told.
    @Test
    void retirementPass_failedReconcile_forgetsTheToldRefusal() {
        refusalOfA.set(Option.some("first cause"));
        retirementPass(Promise.unitPromise());
        retirementPass(Promise.unitPromise());
        assertThat(warned).as("two passes, one cause: told once").hasSize(1);

        var skipped = TopologyAssembly.retirementPass(Causes.cause("reconcile failed").<Unit>promise(),
                                                      refusals,
                                                      this::drainSurplusA)
                                      .await();

        assertThat(skipped.isFailure()).as("the failed reconcile is the pass's failure").isTrue();
        retirementPass(Promise.unitPromise());
        assertThat(warned).as("a pass that tried nothing forgot it: told again").hasSize(2);
    }

    private void retirementPass(Promise<Unit> reconciled) {
        TopologyAssembly.retirementPass(reconciled, refusals, this::drainSurplusA)
                        .await();
    }

    private Promise<Unit> drainSurplusA() {
        return TopologyAssembly.drainSurplusCores(List.of(SURPLUS_A), _ -> false, this::refuseA, refusals);
    }

    private void passes(int count) {
        for (var i = 0; i < count; i++) {
            TopologyAssembly.drainSurplusCores(List.of(SURPLUS_A), _ -> false, this::refuseA, refusals)
                            .await();
        }
    }

    private Promise<Unit> refuseA(NodeId node) {
        return refusalOfA.get()
                         .filter(_ -> node.equals(SURPLUS_A))
                         .map(cause -> Causes.cause(cause).<Unit>promise())
                         .or(() -> recordDrain(node));
    }

    @Test
    void drainSurplusCores_held_drainsNothing() {
        TopologyAssembly.drainSurplusCores(List.of(SURPLUS_A), pending -> askHeld(true, pending), this::recordDrain, refusals)
                        .await();

        assertThat(drained).as("auto-heal disabled: a surplus core is not drained on the cluster's own initiative").isEmpty();
        assertThat(asked).as("the hold was asked").hasValue(1);
    }

    @Test
    void drainSurplusCores_notHeld_drainsEveryVictim() {
        TopologyAssembly.drainSurplusCores(List.of(SURPLUS_A, SURPLUS_B), pending -> askHeld(false, pending), this::recordDrain, refusals)
                        .await();

        assertThat(drained).as("control: not held, every victim is drained").containsExactly(SURPLUS_A, SURPLUS_B);
    }

    /// #3358: asked on every evaluated pass, so a pass with no victim asks with nothing pending (this asker's answer goes false), and drains nothing.
    @Test
    void drainSurplusCores_noVictim_asksTheHoldWithNothingPending() {
        TopologyAssembly.drainSurplusCores(List.of(), pending -> askHeld(true, pending), this::recordDrain, refusals)
                        .await();

        assertThat(askedPending).as("no victim: the hold is still asked, with nothing pending").containsExactly(false);
        assertThat(drained).isEmpty();
    }

    /// #3358: a victim is a pending change.
    @Test
    void drainSurplusCores_victim_asksTheHoldWithAChangePending() {
        TopologyAssembly.drainSurplusCores(List.of(SURPLUS_A), pending -> askHeld(true, pending), this::recordDrain, refusals)
                        .await();

        assertThat(askedPending).containsExactly(true);
    }

    /// The production wiring, not the stubbed gate: the hold asked is the topology's own, for the subject `core`, and each victim leaves as
    /// an OVERPROVISION_SCALE_DOWN drain. Held, nothing drains; not held, every victim drains with that reason.
    @Test
    void retireSurplusCores_autoHealDisabled_askedForCore_drainsNothing() {
        var topology = topology(true);

        TopologyAssembly.retireSurplusCores(List.of(SURPLUS_A), topology, refusals)
                        .await();

        assertThat(heldSubjects).as("the hold is asked for the subject core").containsExactly(ClusterTopologyManager.coreSubject());
        assertThat(askers).as("as the retirement, not the reconciler").containsExactly(ClusterTopologyManager.ScalingAsker.RETIREMENT);
        assertThat(drained).as("auto-heal disabled: the production wiring drains no surplus core").isEmpty();
    }

    @Test
    void retireSurplusCores_autoHealEnabled_drainsAsOverprovisionScaleDown() {
        var topology = topology(false);

        TopologyAssembly.retireSurplusCores(List.of(SURPLUS_A, SURPLUS_B), topology, refusals)
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
                askers.add((ClusterTopologyManager.ScalingAsker) args[1]);
                askedPending.add((Boolean) args[2]);
                yield held;
            }
            case "drainNode" -> {
                drainReasons.add((DrainReason) args[1]);
                yield recordDrain((NodeId) args[0]);
            }
            default -> throw new UnsupportedOperationException(method);
        };
    }

    private boolean askHeld(boolean held, boolean pending) {
        asked.incrementAndGet();
        askedPending.add(pending);

        return held;
    }

    private Promise<Unit> recordDrain(NodeId node) {
        drained.add(node);

        return Promise.unitPromise();
    }
}
