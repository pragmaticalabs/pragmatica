// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityPlacementOperationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PlacementOperationPhase;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityDrainCoordinatorTest {
    private static final NodeId CORE = new NodeId("core");
    private static final NodeId OTHER = new NodeId("other");
    private static final NodeId SELF = new NodeId("worker");

    @Test
    void rejectsStaleLeaderAndWrongOperationWithoutClosingAdmission() {
        var initiated = new AtomicInteger();
        var coordinator = CommunityDrainCoordinator.communityDrainCoordinator(SELF, () -> Option.some(CORE),
            () -> Option.some(operation()), (_, _) -> {}, initiated::incrementAndGet);
        coordinator.onRequest(new CommunityPlacementMessage.DrainRequested(OTHER, "move"));
        coordinator.onRequest(new CommunityPlacementMessage.DrainRequested(CORE, "wrong"));
        assertThat(initiated.get()).isZero();
    }

    @Test
    void reportsCompletionOnlyAfterQuiescenceAndWaitsForCommittedAcknowledgement() {
        var sent = new ArrayList<CommunityPlacementMessage>();
        var initiated = new AtomicInteger();
        var coordinator = CommunityDrainCoordinator.communityDrainCoordinator(SELF, () -> Option.some(CORE),
            () -> Option.some(operation()), (_, message) -> sent.add(message), initiated::incrementAndGet);
        coordinator.onRequest(new CommunityPlacementMessage.DrainRequested(CORE, "move"));
        assertThat(initiated.get()).isEqualTo(1);
        assertThat(sent).isEmpty();
        var completion = coordinator.onQuiesced();
        assertThat(sent).containsExactly(new CommunityPlacementMessage.DrainCompleted(SELF, "move"));
        coordinator.onAccepted(new CommunityPlacementMessage.DrainAccepted(OTHER, "move", true));
        coordinator.onAccepted(new CommunityPlacementMessage.DrainAccepted(CORE, "wrong", true));
        coordinator.onAccepted(new CommunityPlacementMessage.DrainAccepted(CORE, "move", false));
        assertThat(completion.isResolved()).isFalse();
        coordinator.onRequest(new CommunityPlacementMessage.DrainRequested(CORE, "move"));
        assertThat(sent).hasSize(2);
        assertThat(initiated.get()).isEqualTo(1);
        coordinator.onAccepted(new CommunityPlacementMessage.DrainAccepted(CORE, "move", true));
        assertThat(completion.await(TimeSpan.timeSpan(1).seconds()).isSuccess()).isTrue();
    }

    private static CommunityPlacementOperationValue operation() {
        return new CommunityPlacementOperationValue("move", "community", new NodeId("replacement"),
            "west", Option.none(), "binding", Option.some(SELF), "east", PlacementOperationPhase.DRAIN_REQUESTED,
            new LeaderValue(CORE, 7), 1, 1, "");
    }
}
