// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.routes.NodeLifecycleRoutes;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.forward.HttpForwarder;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.resource.entity.EntityCheckpointDriver;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// C6a-0 wiring pin (#3467): the [NodeLifecycleRoutes.DrainPort] the `ManagementServer.managementServer` factory is given is the one its
/// drain admission reads. `replacementDrainUnderWay` reaches `drainPort.pending()` through the server, so a factory
/// that hands the routes a null port, or a constructor that substitutes `DrainPort.none()`, reads an empty pending set
/// and the commanded drain is not seen as under way. The port's own tests cannot see this: they never go through the
/// server.
class ManagementServerDrainPortWiringTest {
    private static final NodeId SELF = NodeId.nodeId("node-self").unwrap();
    private static final NodeId COMMANDED = NodeId.nodeId("node-commanded").unwrap();

    private final StreamPartitionManager partitionManager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

    @AfterEach
    void closePartitionManager() {
        partitionManager.close();
    }

    @Test
    void replacementDrainUnderWay_readsThePendingSetOfThePortTheServerWasBuiltWith() {
        var port = NodeLifecycleRoutes.DrainPort.registry(_ -> {}, () -> Set.of(COMMANDED));

        assertThat(server(port).replacementDrainUnderWay(COMMANDED)).as("a drain the port reports pending is under way")
                                                                    .isTrue();
    }

    private ManagementServer server(NodeLifecycleRoutes.DrainPort port) {
        var node = mock(ManageableNode.class);
        var appHttpServer = mock(AppHttpServer.class);

        when(appHttpServer.httpRoutePublisher()).thenReturn(Option.none());
        when(node.self()).thenReturn(SELF);
        when(node.isLeader()).thenReturn(true);
        when(node.leader()).thenReturn(Option.some(SELF));
        when(node.appHttpServer()).thenReturn(appHttpServer);
        when(node.httpRouteRegistry()).thenReturn(HttpRouteRegistry.httpRouteRegistry());
        when(node.taskGroupOwnerResolver()).thenReturn(HttpForwarder.UNASSIGNED_RESOLVER);
        when(node.clusterTopologyManager()).thenReturn(Option.none());
        when(node.consumerGroupCoordinator()).thenReturn(ConsumerGroupCoordinator.noOp());
        when(node.consumerGroupRegistry()).thenReturn(ConsumerGroupRegistry.consumerGroupRegistry());
        when(node.streamNamespacesService()).thenReturn(mock(StreamNamespacesService.class));
        when(node.streamReadRouter()).thenReturn(StreamReadRouter.streamReadRouter(partitionManager,
                                                                                   Option.none(),
                                                                                   Option.none(),
                                                                                   SELF,
                                                                                   (_, _) -> Option.none(),
                                                                                   StreamReadForwardMetrics.NOOP));

        return ManagementServer.managementServer(0,
                                             () -> node,
                                             mock(EntityCheckpointDriver.class),
                                             mock(AlertManager.class),
                                             mock(ObservabilityConfigRegistry.class),
                                             mock(InvocationTraceStore.class),
                                             mock(LogLevelRegistry.class),
                                             Option.none(),
                                             mock(ScheduledTaskRegistry.class),
                                             mock(ScheduledTaskManager.class),
                                             mock(SliceInvoker.class),
                                             mock(ScheduledTaskStateRegistry.class),
                                             Option.none(),
                                             mock(SecurityValidator.class),
                                             false,
                                             Map::of,
                                             Option.none(),
                                             Option.none(),
                                             HttpProtocol.H1,
                                             ForwardingTimeouts.forwardingTimeouts(),
                                             Option.none(),
                                             Option.none(),
                                             Option.none(),
                                             port,
                                             NodeLifecycleRoutes.SliceFloor.sliceFloor((_, _) -> java.util.List.of(),
                                                                                       OperatorWarningSink.logOnly()));
    }
}
