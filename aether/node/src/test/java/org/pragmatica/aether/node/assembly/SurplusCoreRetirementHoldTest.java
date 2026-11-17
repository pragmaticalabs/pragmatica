package org.pragmatica.aether.node.assembly;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
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

    private boolean askHeld(boolean held) {
        asked.incrementAndGet();

        return held;
    }

    private Promise<Unit> recordDrain(NodeId node) {
        drained.add(node);

        return Promise.unitPromise();
    }
}
