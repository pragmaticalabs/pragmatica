// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.config.WorkerConfig;
import org.pragmatica.aether.node.assembly.WorkerHierarchyAssembly;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.worker.governor.GovernorAnnouncer;
import org.pragmatica.aether.worker.governor.GovernorAuthorityClient;
import org.pragmatica.aether.worker.health.CommunityMemberDirectory;
import org.pragmatica.cluster.node.ForwardingClusterNode;
import org.pragmatica.cluster.node.SwitchableClusterNode;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.slf4j.LoggerFactory;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1840: a worker's seed ANNOUNCE is held until its activation directive is committed; the directive for
/// this node, in the node's own role, is what releases it. A directive for another node, or one that
/// `handleActivationDirective` rejects, does not.
class AetherNodeWorkerDirectiveAssignmentTest {
    private static final NodeId SELF = new NodeId("worker-1");
    private static final NodeId OTHER = new NodeId("worker-2");

    private final CoreSwimHealthDetector detector = Mockito.mock(CoreSwimHealthDetector.class);
    private final AtomicReference<GovernorAnnouncer> announcer = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        Option.option(announcer.get()).onPresent(GovernorAnnouncer::stop);
    }

    @Test
    void handleActivationDirective_workerDirectiveForThisWorker_releasesTheHeldAnnounce() {
        handle(SELF, ActivationDirectiveValue.worker("c-0", ""));

        verify(detector).markAssigned();
    }

    @Test
    void handleActivationDirective_directiveForAnotherNode_doesNotRelease() {
        handle(OTHER, ActivationDirectiveValue.worker("c-0", ""));

        verify(detector, never()).markAssigned();
    }

    @Test
    void handleActivationDirective_coreRoleDirectiveForAWorker_isRejectedAndDoesNotRelease() {
        handle(SELF, ActivationDirectiveValue.core());

        verify(detector, never()).markAssigned();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void handle(NodeId target, ActivationDirectiveValue value) {
        var config = Mockito.mock(AetherNodeConfig.class);

        when(config.self()).thenReturn(SELF);
        when(config.workerConfig()).thenReturn(Option.some(Mockito.mock(WorkerConfig.class)));
        when(config.topology()).thenReturn(new TopologyConfig(SELF, 5, timeSpan(1).seconds(), timeSpan(10).seconds(), List.of()));
        var put = new ValuePut<ActivationDirectiveKey, ActivationDirectiveValue>(new KVCommand.Put<>(new ActivationDirectiveKey(target), value),
                                                                                  Option.none());

        WorkerHierarchyAssembly.handleActivationDirective(put,
                                             SELF,
                                             Mockito.mock(RabiaNode.class),
                                             Mockito.mock(SwitchableClusterNode.class),
                                             Mockito.mock(ForwardingClusterNode.class),
                                             config,
                                             MessageRouter.DelegateRouter.delegate(),
                                             Mockito.mock(KVStore.class),
                                             null,
                                             null,
                                             () -> detector,
                                             CommunityMemberDirectory.communityMemberDirectory(),
                                             announcer,
                                             Mockito.mock(GovernorAuthorityClient.class),
                                             () -> false,
                                             LoggerFactory.getLogger(AetherNodeWorkerDirectiveAssignmentTest.class),
                                             () -> {});
    }
}
