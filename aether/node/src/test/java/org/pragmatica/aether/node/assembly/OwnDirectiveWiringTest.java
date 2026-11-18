// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.utility.react.ReactFailureSink;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3193 ruling item 2, the wiring: the observer `OwnDirectiveRetirementTest` pins in isolation, assembled by
/// `WorkerHierarchyAssembly` and fed by a REAL [KVStore] through the registered router, so a KV put/remove of the
/// worker's own `ActivationDirectiveKey` reaches the halt and the event only if the assembly registered both edges and
/// supplied the right drain probe and worker flag; the halt reads no store (#3330). The halt is the real local drain, submitted as an undo.
class OwnDirectiveWiringTest {
    private static final NodeId SELF = new NodeId("worker-self");
    private static final NodeId OTHER = new NodeId("worker-other");
    private static final ActivationDirectiveValue WORKER_DIRECTIVE = new ActivationDirectiveValue(ActivationDirectiveValue.WORKER, "", "");
    private static final ActivationDirectiveValue OTHER_COMMUNITY_DIRECTIVE = new ActivationDirectiveValue(ActivationDirectiveValue.WORKER,
                                                                                                          "community-2",
                                                                                                          "");

    private final List<DrainReason> reasons = new CopyOnWriteArrayList<>();
    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(warnings::add);
    private final CountDownLatch drained = new CountDownLatch(1);
    private ReactExecutor react;
    private DrainProcedure procedure;
    private KVStore<AetherKey, AetherValue> kvStore;
    private MessageRouter.MutableRouter router;

    @BeforeEach
    void setUp() {
        react = ReactExecutor.reactExecutor("test", () -> true, ReactFailureSink.loggingOnly());
        procedure = DrainProcedure.drainProcedure(InFlightRequestTracker.inFlightRequestTracker(),
                                                  () -> {},
                                                  reason -> {
                                                      reasons.add(reason);
                                                      drained.countDown();
                                                  },
                                                  () -> {});

        router = MessageRouter.mutable();
        kvStore = new KVStore<>(router, stubSerializer(), stubDeserializer());
    }

    @AfterEach
    void tearDown() {
        react.close();
    }

    @Test
    void ownDirectivePutThenRemove_throughTheAssembledRouter_startsTheLocalDrain_andRaisesTheEvent() throws InterruptedException {
        arm(workerConfig());

        apply(new KVCommand.Put<>(ownKey(), WORKER_DIRECTIVE));
        apply(new KVCommand.Remove<>(ownKey()));

        assertTrue(drained.await(5, TimeUnit.SECONDS), "the removal must reach the halt through the registered remove edge");
        assertEquals(List.of(DrainReason.DIRECTIVE_RETIRED), reasons);
        assertEquals(List.of(OperatorWarningCode.OWN_DIRECTIVE_RETIRED), ownWarningCodes());
    }

    @Test
    void ownDirectiveRemove_withoutAPriorPut_doesNotHalt_soTheRemovalAloneIsNotTheTrigger() {
        var retirement = arm(workerConfig());

        apply(new KVCommand.Remove<>(ownKey()));
        flushHalts();

        assertFalse(retirement.halted());
        assertEquals(List.of(), reasons);
    }

    @Test
    void ownDirectiveRemove_storeAlreadyHoldsALaterDirective_stillHalts() throws InterruptedException {
        var retirement = arm(workerConfig());

        apply(new KVCommand.Put<>(ownKey(), WORKER_DIRECTIVE));
        applyAll(new KVCommand.Remove<>(ownKey()), new KVCommand.Put<>(ownKey(), OTHER_COMMUNITY_DIRECTIVE));

        assertTrue(drained.await(5, TimeUnit.SECONDS),
                   "#3330: the removal alone decides the halt; a later directive already in the store must not cancel it");
        assertTrue(retirement.halted());
        assertEquals(List.of(DrainReason.DIRECTIVE_RETIRED), reasons);
        assertEquals(List.of(OperatorWarningCode.OWN_DIRECTIVE_RETIRED), ownWarningCodes());
    }

    /// #3330 put edge: a remove + re-create inside one install gap reaches the worker as ONE put naming another community
    /// (KVStore install diff). Through the registered put edge that put halts the node like a removal.
    @Test
    void ownDirectivePutForAnotherCommunity_throughTheAssembledRouter_startsTheLocalDrain_andRaisesTheEvent() throws InterruptedException {
        var retirement = arm(workerConfig());

        apply(new KVCommand.Put<>(ownKey(), WORKER_DIRECTIVE));
        apply(new KVCommand.Put<>(ownKey(), OTHER_COMMUNITY_DIRECTIVE));

        assertTrue(drained.await(5, TimeUnit.SECONDS), "a put naming another community must reach the halt through the registered put edge");
        assertTrue(retirement.halted());
        assertEquals(List.of(DrainReason.DIRECTIVE_RETIRED), reasons);
        assertEquals(List.of(OperatorWarningCode.OWN_DIRECTIVE_RETIRED), ownWarningCodes());
    }

    @Test
    void ownDirectivePutForTheSameCommunity_isARepeat_andDoesNotHalt() {
        var retirement = arm(workerConfig());

        apply(new KVCommand.Put<>(ownKey(), WORKER_DIRECTIVE));
        apply(new KVCommand.Put<>(ownKey(), new ActivationDirectiveValue(ActivationDirectiveValue.WORKER, "", "governor-hint")));
        flushHalts();

        assertFalse(retirement.halted());
        assertEquals(List.of(), reasons);
        assertEquals(List.of(), ownWarningCodes());
    }

    /// Mutant D of v3350 (own-key check removed on the remove edge) was noticed only by the unit test; this pins it here.
    @Test
    void anotherNodesDirectiveRemove_afterOwnPut_doesNotHalt() {
        var retirement = arm(workerConfig());

        apply(new KVCommand.Put<>(ownKey(), WORKER_DIRECTIVE));
        apply(new KVCommand.Put<>(ActivationDirectiveKey.activationDirectiveKey(OTHER), WORKER_DIRECTIVE));
        apply(new KVCommand.Remove<>(ActivationDirectiveKey.activationDirectiveKey(OTHER)));
        flushHalts();

        assertFalse(retirement.halted());
        assertEquals(List.of(), reasons);
    }

    @Test
    void ownDirectiveRemove_whileAlreadyDraining_doesNotHaltAgain_andRaisesNothing() {
        var retirement = arm(workerConfig());

        apply(new KVCommand.Put<>(ownKey(), WORKER_DIRECTIVE));
        procedure.initiate(DrainReason.COMMANDED);
        apply(new KVCommand.Remove<>(ownKey()));
        flushHalts();

        assertFalse(retirement.halted(), "a drain already running ends the process; the removal must not start a second one");
        assertEquals(List.of(DrainReason.COMMANDED), reasons);
        assertEquals(List.of(), ownWarningCodes());
    }

    @Test
    void ownDirectiveRemove_onANodeNotConfiguredAsWorker_neverHalts() {
        var retirement = arm(coreConfig());

        apply(new KVCommand.Put<>(ownKey(), WORKER_DIRECTIVE));
        apply(new KVCommand.Remove<>(ownKey()));
        flushHalts();

        assertFalse(retirement.halted());
        assertEquals(List.of(), reasons);
        assertEquals(List.of(), ownWarningCodes());
    }

    private org.pragmatica.aether.worker.isolation.OwnDirectiveRetirement arm(AetherNodeConfig config) {
        var retirement = WorkerHierarchyAssembly.ownDirectiveRetirementFor(config,
                                                                            SELF,
                                                                            procedure,
                                                                            react,
                                                                            NodeReportedStateHolder.nodeReportedStateHolder(() -> true),
                                                                            sink);
        var built = WorkerHierarchyAssembly.withOwnDirectiveRetirement(KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class),
                                                                       retirement)
                                           .build();

        built.asRouteEntries()
             .forEach(entry -> entry.entries()
                                    .forEach(route -> router.addRoute(route.first(), route.last())));

        return retirement;
    }

    private static ActivationDirectiveKey ownKey() {
        return ActivationDirectiveKey.activationDirectiveKey(SELF);
    }

    @SuppressWarnings("unchecked")
    private void apply(KVCommand<?> command) {
        applyAll(command);
    }

    @SuppressWarnings("unchecked")
    private void applyAll(KVCommand<?>... commands) {
        kvStore.process(kvStore.createBatch(java.util.Arrays.stream(commands)
                                                 .map(c -> (KVCommand<AetherKey>) c)
                                                 .toList()));
    }

    /// The halt is an undo on a single ordered thread: an undo submitted after it runs after it, so a "no halt" arm reads a settled state.
    private void flushHalts() {
        var done = new CountDownLatch(1);

        react.undo("flush", done::countDown);
        try {
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<OperatorWarningCode> ownWarningCodes() {
        sink.accept(OperatorWarning.operatorWarning(OperatorWarningCode.CORE_ABSENCE_FENCE, "sentinel", "sentinel"));

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (warnings.stream().noneMatch(w -> "sentinel".equals(w.subject())) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        return warnings.stream()
                       .map(OperatorWarning::code)
                       .filter(c -> c == OperatorWarningCode.OWN_DIRECTIVE_RETIRED)
                       .toList();
    }

    private static AetherNodeConfig workerConfig() {
        var config = Mockito.mock(AetherNodeConfig.class);

        when(config.self()).thenReturn(SELF);
        when(config.workerConfig()).thenReturn(Option.some(Mockito.mock(WorkerConfig.class)));
        return config;
    }

    private static AetherNodeConfig coreConfig() {
        var self = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 9001).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "core"));
        var config = Mockito.mock(AetherNodeConfig.class);

        when(config.self()).thenReturn(SELF);
        when(config.workerConfig()).thenReturn(Option.none());
        when(config.topology()).thenReturn(new TopologyConfig(SELF, 5, timeSpan(1).seconds(), timeSpan(10).seconds(), List.of(self)));
        return config;
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
