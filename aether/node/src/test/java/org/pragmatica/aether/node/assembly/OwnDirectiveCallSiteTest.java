// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.config.WorkerConfig;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.drain.InFlightRequestTracker;
import org.pragmatica.aether.deployment.membership.ntt.DrainProcedure;
import org.pragmatica.aether.metrics.NodeReportedStateHolder;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.utility.react.ReactFailureSink;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/// #3193 ruling item 2, the CALL SITE: `workerActivation` itself must wrap the router it builds with the own-directive observer, keyed to
/// `selfId`. `OwnDirectiveWiringTest` pins the two seams; this pins that the assembly calls them: a put then a remove of the own directive,
/// delivered through the router `workerActivation` returns, starts the local drain.
class OwnDirectiveCallSiteTest {
    private static final NodeId SELF = new NodeId("worker-self");

    @Test
    @SuppressWarnings("unchecked")
    void workerActivationRouter_ownDirectivePutThenRemove_startsTheLocalDrain() throws InterruptedException {
        var reasons = new CopyOnWriteArrayList<DrainReason>();
        var drained = new CountDownLatch(1);
        var procedure = DrainProcedure.drainProcedure(InFlightRequestTracker.inFlightRequestTracker(),
                                                      () -> {},
                                                      reason -> {
                                                          reasons.add(reason);
                                                          drained.countDown();
                                                      },
                                                      () -> {});
        var react = ReactExecutor.reactExecutor("test", () -> true, ReactFailureSink.loggingOnly());
        var config = Mockito.mock(AetherNodeConfig.class);

        when(config.self()).thenReturn(SELF);
        when(config.workerConfig()).thenReturn(Option.some(Mockito.mock(WorkerConfig.class)));

        var kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        var detector = new AtomicReference<org.pragmatica.aether.node.health.CoreSwimHealthDetector>(
            Mockito.mock(org.pragmatica.aether.node.health.CoreSwimHealthDetector.class));
        var inputs = new WorkerHierarchyAssembly.WorkerActivationInputs(config,
                                                                         MessageRouter.DelegateRouter.delegate(),
                                                                         kvStore,
                                                                         null,
                                                                         new AtomicReference<>(),
                                                                         null,
                                                                         null,
                                                                         null,
                                                                         null,
                                                                         null,
                                                                         null,
                                                                         _ -> {},
                                                                         new AtomicReference<>(() -> true),
                                                                         detector,
                                                                         null,
                                                                         new ArrayList<>(),
                                                                         org.slf4j.LoggerFactory.getLogger("test"),
                                                                         SELF,
                                                                         Mockito.mock(org.pragmatica.aether.node.GossipKeyRotationHandler.class),
                                                                         react,
                                                                         procedure,
                                                                         NodeReportedStateHolder.nodeReportedStateHolder(() -> true),
                                                                         OperatorWarningSink.handingOffTo(_ -> {}));
        var router = WorkerHierarchyAssembly.workerActivation(inputs).activationKvRouter();
        var routes = MessageRouter.mutable();

        router.asRouteEntries().forEach(entry -> entry.entries().forEach(route -> routes.addRoute(route.first(), route.last())));
        var store = new KVStore<AetherKey, AetherValue>(routes, stubSerializer(), stubDeserializer());
        var key = ActivationDirectiveKey.activationDirectiveKey(SELF);

        store.process(store.createBatch(List.of((KVCommand<AetherKey>) (KVCommand<?>) new KVCommand.Put<>(key, new ActivationDirectiveValue(ActivationDirectiveValue.WORKER, "", "")))));
        store.process(store.createBatch(List.of((KVCommand<AetherKey>) (KVCommand<?>) new KVCommand.Remove<>(key))));

        assertTrue(drained.await(5, TimeUnit.SECONDS), "workerActivation must register the own-directive observer on the router it returns");
        assertEquals(List.of(DrainReason.DIRECTIVE_RETIRED), reasons);
        react.close();
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
