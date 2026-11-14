// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.ArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.deployment.cluster.community.CommunityLivenessView;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.worker.health.CommunityHealthIndex;
import org.pragmatica.aether.worker.health.CommunityHealthRuntime;
import org.pragmatica.aether.worker.health.CommunityMemberDirectory;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3159: the liveness view [`WorkerHierarchyAssembly#communityReachability`] builds is what the management API's LEGACY count reads and what
/// the deployment manager is handed. Each branch of the view, and the hand-over, is driven through the real assembly function: a
/// community-assigned member is absent exactly when the community's health evidence does not reach it, an unassigned one follows the leader's
/// pong observation, and the very view the node serves counts from is the one installed in the manager.
class CommunityLivenessWiringTest {
    private static final NodeId ASSIGNED = new NodeId("assigned");
    private static final NodeId UNASSIGNED = new NodeId("unassigned");

    private final AetherNodeConfig config = Mockito.mock(AetherNodeConfig.class, Answers.RETURNS_DEEP_STUBS);
    private final ClusterDeploymentManager manager = Mockito.mock(ClusterDeploymentManager.class);
    private final ClusterSyncCollector metrics = Mockito.mock(ClusterSyncCollector.class);
    private final CommunityHealthIndex health = Mockito.mock(CommunityHealthIndex.class);
    private final CommunityMemberDirectory directory = CommunityMemberDirectory.communityMemberDirectory();

    @BeforeEach
    void wire() {
        Mockito.when(config.timeouts().cluster().communityAbsence()).thenReturn(timeSpan(30).seconds());
        directory.put(ASSIGNED, ActivationDirectiveValue.worker("east", ""));
    }

    @Test
    void assignedMember_isNotAbsent_whenTheCommunityHealthEvidenceReachesIt() {
        Mockito.when(health.isReachable(ASSIGNED)).thenReturn(true);

        assertThat(view().isAbsent(ASSIGNED)).isFalse();
    }

    @Test
    void assignedMember_isAbsent_whenTheCommunityHealthEvidenceDoesNotReachIt() {
        Mockito.when(health.isReachable(ASSIGNED)).thenReturn(false);

        assertThat(view().isAbsent(ASSIGNED)).isTrue();
    }

    @Test
    void unassignedMember_isNotAbsent_whileTheLeaderHasNoPongHistoryButJustTookLeadership() {
        Mockito.when(metrics.sinceLastPong(UNASSIGNED)).thenReturn(Option.none());

        assertThat(view().isAbsent(UNASSIGNED)).isFalse();
    }

    @Test
    void unassignedMember_isAbsent_whenTheLastPongIsOlderThanTheAbsenceWindow() {
        Mockito.when(metrics.sinceLastPong(UNASSIGNED)).thenReturn(Option.some(timeSpan(31).seconds()));

        assertThat(view().isAbsent(UNASSIGNED)).isTrue();
    }

    @Test
    void theViewHandedBack_isTheOneInstalledInTheDeploymentManager() {
        var view = view();

        Mockito.verify(manager).setCommunityLiveness(view);
    }

    private CommunityLivenessView view() {
        return WorkerHierarchyAssembly.communityReachability(new WorkerHierarchyAssembly.CommunityReachabilityInputs(config,
                                                                                                                     manager,
                                                                                                                     metrics,
                                                                                                                     directory,
                                                                                                                     new ArrayList<>(),
                                                                                                                     Mockito.mock(MembershipFsm.class),
                                                                                                                     health,
                                                                                                                     Mockito.mock(CommunityHealthRuntime.class)));
    }
}
