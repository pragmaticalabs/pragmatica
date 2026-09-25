// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityPlacementOperationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PlacementOperationPhase;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityRetirementIndexTest {
    @Test
    void snapshotReplacementNeverPublishesPartiallyRestoredProtection() throws InterruptedException {
        var index = CommunityRetirementIndex.communityRetirementIndex();
        var previous = new NodeId("previous");
        var next = new NodeId("next");
        index.put(operation("previous-community", previous));
        var restoring = new CountDownLatch(1);
        var resume = new Semaphore(0);
        var snapshot = new ArrayList<CommunityPlacementOperationValue>(List.of(operation("next-community", next))) {
            @Override
            public void forEach(Consumer<? super CommunityPlacementOperationValue> action) {
                super.forEach(action);
                restoring.countDown();
                resume.acquireUninterruptibly();
            }
        };
        var writer = Thread.ofPlatform().start(() -> index.restore(snapshot));
        try {
            assertThat(restoring.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(index.including(Set.of())).containsExactly(previous);
        } finally {
            resume.release();
            writer.join(5000);
        }
        assertThat(writer.isAlive()).isFalse();
        assertThat(index.including(Set.of())).containsExactly(next);
        index.remove("next-community");
        assertThat(index.including(Set.of(previous))).containsExactly(previous);
    }

    private static CommunityPlacementOperationValue operation(String community, NodeId previous) {
        return new CommunityPlacementOperationValue("operation", community, new NodeId("replacement"), "east",
            Option.none(), "binding", Option.some(previous), "west", PlacementOperationPhase.DRAIN_REQUESTED,
            new LeaderValue(new NodeId("core"), 1), 0, 0, "");
    }
}
