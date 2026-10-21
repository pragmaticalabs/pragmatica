// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementServer;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardNotApplied;
import org.pragmatica.aether.http.forward.HttpForwardMessage.NotAppliedReason;
import org.pragmatica.aether.http.forward.HttpForwardMessage.Pipeline;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;

/// #2244 F1: a target's not-applied answer reaches the app server's forwarder through the node's router. Without the
/// route entry, or with a demux that drops the app pipeline, the answer is lost and the forward waits out its hop
/// timeout as "may have been applied": only slower, so no other test notices.
class AetherNodeHttpForwardRoutingTest {
    private static final NodeId TARGET = NodeId.nodeId("target-node").unwrap();

    private final List<String> appCalls = new CopyOnWriteArrayList<>();
    private final AppHttpServer appHttpServer = (AppHttpServer) Proxy.newProxyInstance(AppHttpServer.class.getClassLoader(),
                                                                                      new Class<?>[] {AppHttpServer.class},
                                                                                      this::record);
    private final List<MessageRouter.Entry<?>> entries = AetherNode.httpForwardRouteEntries(appHttpServer,
                                                                                            new AtomicReference<>(Option.<ManagementServer>none()));

    @Test
    void notAppliedForTheAppPipeline_reachesTheAppServer() {
        deliver(notApplied(Pipeline.APP));

        assertThat(appCalls).containsExactly("onHttpForwardNotApplied");
    }

    @Test
    void notAppliedForAnotherPipeline_neverReachesTheAppServer() {
        deliver(notApplied(Pipeline.MANAGEMENT));
        deliver(notApplied(Pipeline.UNKNOWN));

        assertThat(appCalls).isEmpty();
    }

    private void deliver(Message message) {
        entries.forEach(entry -> entry.asRouter().unwrap().route(message));
    }

    private static HttpForwardNotApplied notApplied(Pipeline pipeline) {
        return new HttpForwardNotApplied(TARGET, "corr-1", "req-1", NotAppliedReason.ROUTE_NOT_FOUND, pipeline);
    }

    private Object record(Object proxy, Method method, Object[] args) {
        appCalls.add(method.getName());

        return null;
    }
}
