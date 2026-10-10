package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 N3: a replacement holds the cluster's one slot while it runs and while it waits, kept-both, to be settled, so
/// `begin` can neither overwrite a kept-both record nor start another replacement beside it.
class NodeReplacementWiringHoldsCapacityTest {
    @Test
    void runningPhasesAndKeptBoth_holdTheSlot_finishedOnesDoNot() {
        for (var phase : NodeReplacementPhase.values()) {
            var holds = switch (phase) {
                case PROVISIONING, JOINING, SWAPPING, CANARY, DRAINING_OLD, RETIRING_OLD, REVERTING, FAILED_KEPT_BOTH -> true;
                case DONE, ROLLED_BACK, UNKNOWN -> false;
            };

            assertThat(NodeReplacementWiring.holdsCapacity(phase)).as(phase.name()).isEqualTo(holds);
        }
    }
}
