// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeResponse;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry.ScheduledTask;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskStateKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskStateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.FrameworkCodecs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// #1930: a SINGLE-mode task whose callee is REMOTE (the leader hosts nothing) must never overlap its own next fire. The
/// invoker below is the real one (invocation timeout 300 ms) over a network that records every request and answers only
/// when the test says so; the scheduler is the real one on a 1 s fixed rate. The claim must stay held while the callee runs,
/// whatever the invocation timeout, and be given up only when the callee completes, its node departs, or the scheduler's own
/// explicit completion bound fires (outcome UNKNOWN).
class ScheduledSingleModeNoOverlapTest {
    private static final NodeId SELF = new NodeId("leader");
    private static final NodeId HOST = new NodeId("host");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:scheduled-slice:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("cleanup").unwrap();
    private static final String SECTION = "scheduling.cleanup";

    private final CopyOnWriteArrayList<InvokeRequest> requests = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<ScheduledTaskStateKey, ScheduledTaskStateValue> state = new ConcurrentHashMap<>();
    private final ScheduledTaskManagerTest.TestLeaderManager leaderManager = new ScheduledTaskManagerTest.TestLeaderManager(SELF);
    private SliceInvoker invoker;
    private ScheduledTaskManager manager;
    private ScheduledTaskRegistry registry;

    @BeforeEach
    void setUp() {
        var network = new StubClusterNetwork() {
            @Override
            public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
                if (message instanceof InvokeRequest request) {
                    requests.add(request);
                }

                return unit();
            }
        };
        var endpoints = EndpointRegistry.endpointRegistry();
        var codec = FrameworkCodecs.frameworkCodecs();

        endpoints.registerEndpoint(new EndpointKey(ARTIFACT, METHOD, 0), EndpointValue.endpointValue(HOST));
        invoker = SliceInvoker.sliceInvoker(SELF, network, endpoints, InvocationHandler.invocationHandler(SELF, network), codec, codec, 300L, 60_000L, new StubDeploymentManager());
        registry = ScheduledTaskRegistry.scheduledTaskRegistry();
        manager = newManager();
    }

    /// The manager under test; overridden by tests that need another completion bound.
    ScheduledTaskManager newManager() {
        return ScheduledTaskManager.scheduledTaskManager(registry, invoker, SELF, this::write, key -> Option.option(state.get(key)), leaderManager);
    }

    @AfterEach
    void tearDown() {
        manager.stop();
        invoker.stop().await();
    }

    private void write(KVCommand<AetherKey> command) {
        if (command instanceof KVCommand.Put<AetherKey, ?> put && put.key() instanceof ScheduledTaskStateKey key && put.value() instanceof ScheduledTaskStateValue value) {
            state.put(key, value);
        }
    }

    private ScheduledTaskStateKey stateKey() {
        return ScheduledTaskStateKey.scheduledTaskStateKey(SECTION, ARTIFACT, METHOD);
    }

    private void startSingleModeTask() {
        var key = ScheduledTaskKey.scheduledTaskKey(SECTION, ARTIFACT, METHOD);

        registry.onScheduledTaskPut(new ValuePut<>(new KVCommand.Put<>(key, ScheduledTaskValue.intervalTask(SELF, "1s", ExecutionMode.SINGLE)), Option.none()));
        leaderManager.setLeader(true);
        manager.onLeaderChange(LeaderNotification.leaderChange(Option.some(SELF), true));
        manager.onQuorumStateChange(ClusterStateNotification.active());
    }

    @Test
    @Timeout(60)
    void remoteSingleModeFire_neverOverlapsItsNextTick_beyondTheInvocationTimeout_andCompletesWhenTheCalleeAnswersLate() {
        startSingleModeTask();
        await(() -> !requests.isEmpty(), 5_000);
        sleep(2_600);

        assertThat(requests).as("the callee is still running (no response): ticks at ~1 s and ~2 s, long after the 300 ms invocation timeout, must NOT dispatch a second run").hasSize(1);
        assertThat(state.get(stateKey())).as("the overlapping ticks are recorded as skipped").satisfies(value -> assertThat(value.skippedOverlaps()).isGreaterThanOrEqualTo(1));

        invoker.onInvokeResponse(InvokeResponse.invokeResponse(HOST, requests.getFirst().correlationId(), "r", true, new byte[0]));
        await(() -> state.get(stateKey()) != null && state.get(stateKey()).totalExecutions() == 1, 5_000);

        assertThat(state.get(stateKey()).lastOutcome()).as("the late completion is an execution").isEqualTo(ScheduledTaskStateValue.OUTCOME_SUCCESS);
        await(() -> requests.size() >= 2, 5_000);
    }

    private static void await(BooleanSupplier condition, long timeoutMs) {
        var deadline = System.currentTimeMillis() + timeoutMs;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Condition not satisfied within " + timeoutMs + "ms");
            }

            sleep(20);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
