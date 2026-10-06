// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.forward.HttpForwarder;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.resource.entity.EntityCheckpointDriver;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/// #1921 structural guard, the class behind two live defects: a route whose [ManagementRoute] declares N path slots while its
/// handler registers M. A trailing literal the handler leaves unregistered is NOT counted (`STREAM_REPLICAS_LOCAL` omits its
/// `replicas-local` spacer and still reads the right two slots, forge-tested); a missing or extra PARAMETER, or a handler that
/// consumes more segments than the template has, is. The matcher accepts the path the enum describes, the handler then reads a slot that is not there (or
/// reads the wrong one), and the answer is a bare 404 or a lookup of the wrong value. `STREAM_CONSUMERS` (one slot registered,
/// three declared) looked a namespace up as a stream name; `STREAM_NAMESPACES_GET` (one declared, three registered) was
/// unreachable over HTTP, and the test that "pinned" its 404 fed the handler a three-value path the router can never deliver.
///
/// The probe goes through the real parts: the path is rendered by `ManagementRoute.assemble`, MATCHED by the real
/// `ManagementRoute.match`, and the handler is the one the `ManagementServerImpl`'s own router resolves for that route name,
/// asked how many parameters and segments it consumes. Nothing feeds a value to a handler directly.
class ManagementRouteArityGuardTest {
    private static final String CLAIMING_PREFIX = "/repository";

    @Test
    void everyServedRoute_registersTheParametersItsTemplateDeclares() {
        var router = server().router();
        var mismatches = new ArrayList<String>();
        var unregistered = new ArrayList<ManagementRoute>();

        for (var route : ManagementRoute.values()) {
            var handler = router.registered(route.name());

            if (handler.isEmpty()) {
                unregistered.add(route);

                continue;
            }

            var path = route.assemble(placeholders(route.paramCount())).unwrap();
            var matched = ManagementRoute.match(route.method(), path);

            if (matched.isFailure() || matched.unwrap().route() != route) {
                mismatches.add(route.name() + ": its own assembled path " + path + " does not match back to it");

                continue;
            }

            var declared = segments(path) - segments(route.prefix());
            var registeredArity = handler.unwrap().pathParamCount();
            var registeredParams = registeredArity - handler.unwrap().spacerSlots().size();

            if (registeredParams != route.paramCount() || registeredArity > declared) {
                mismatches.add(route.name() + ": template " + route.prefix() + " declares " + route.paramCount() + " parameter(s) in "
                               + declared + " trailing segment(s), handler registers " + registeredParams + " parameter(s) in "
                               + registeredArity + " segment(s)");
            }
        }

        assertThat(mismatches).as("routes whose handler registration disagrees with the path template").isEmpty();
        assertThat(unregistered).as("only routes a prefix handler claims may have no registered handler")
                                .allSatisfy(route -> assertThat(route.prefix()).startsWith(CLAIMING_PREFIX));
        assertThat(ManagementRoute.values().length - unregistered.size()).as("the probe examined a real table, not an empty one")
                                                                         .isGreaterThan(150);
    }

    private static List<String> placeholders(int count) {
        return IntStream.range(0, count).mapToObj(i -> "v" + i).toList();
    }

    private static int segments(String path) {
        return (int) Arrays.stream(path.split("/")).filter(segment -> !segment.isEmpty()).count();
    }

    /// The node fixture of [ManagementServerForwardDispatchTest], reduced to what constructing the server reads; duplicated for the
    /// reason that test gives (each probe must be able to fail independently).
    private static ManagementServerImpl server() {
        var node = mock(ManageableNode.class);
        var appHttpServer = mock(AppHttpServer.class);
        var self = NodeId.nodeId("node-self").unwrap();

        when(appHttpServer.httpRoutePublisher()).thenReturn(Option.none());
        when(node.self()).thenReturn(self);
        when(node.leader()).thenReturn(Option.none());
        when(node.appHttpServer()).thenReturn(appHttpServer);
        when(node.httpRouteRegistry()).thenReturn(HttpRouteRegistry.httpRouteRegistry());
        when(node.taskGroupOwnerResolver()).thenReturn(HttpForwarder.UNASSIGNED_RESOLVER);
        when(node.clusterTopologyManager()).thenReturn(Option.none());
        when(node.consumerGroupCoordinator()).thenReturn(ConsumerGroupCoordinator.noOp());
        when(node.consumerGroupRegistry()).thenReturn(ConsumerGroupRegistry.consumerGroupRegistry());
        when(node.streamNamespacesService()).thenReturn(StreamNamespacesService.inMemory());

        return new ManagementServerImpl(0,
                                        () -> node,
                                        mock(EntityCheckpointDriver.class),
                                        mock(AlertManager.class),
                                        mock(ObservabilityConfigRegistry.class),
                                        mock(InvocationTraceStore.class),
                                        mock(LogLevelRegistry.class),
                                        Option.some(mock(DynamicConfigManager.class)),
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
                                        Option.<Serializer> none(),
                                        Option.<Deserializer> none(),
                                        _ -> {},
                                        Set::of);
    }
}
