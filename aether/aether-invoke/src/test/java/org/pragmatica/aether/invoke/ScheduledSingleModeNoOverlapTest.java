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

/// #1930: a SINGLE-mode task whose callee is REMOTE (the leader hosts nothing) is not started again while this leader still has the previous fire in flight, up to the completion bound. The
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
    /// The committed claim rows both nodes of a two-node test see; applies the cluster applier's successor fence.
    private final ScheduledClaimStore claims = ScheduledClaimStore.inMemory();
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

    private ScheduledTaskManager newManager() {
        return ScheduledTaskManager.scheduledTaskManager(registry, invoker, SELF, this::write, key -> Option.option(state.get(key)), leaderManager);
    }

    /// A manager with a short explicit completion bound, so the bound can be reached within a test.
    private void useCompletionBound(long millis) {
        manager.stop();
        manager = ScheduledTaskManager.scheduledTaskManager(registry,
                                                            invoker,
                                                            SELF,
                                                            this::write,
                                                            key -> Option.option(state.get(key)),
                                                            leaderManager,
                                                            org.pragmatica.lang.io.TimeSpan.timeSpan(millis).millis());
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

    /// The hold is bounded: a lost response must not keep the task from ever firing again. At the scheduler's explicit
    /// completion bound the outcome is recorded as UNKNOWN (not a failure, not an execution) and the claim is given up, so
    /// the next tick fires.
    @Test
    @Timeout(60)
    void remoteSingleModeFire_withNoResponseUntilTheBound_isRecordedUnknown_andThenTheTaskFiresAgain() {
        useCompletionBound(1_500);
        startSingleModeTask();
        await(() -> !requests.isEmpty(), 5_000);
        sleep(900);

        assertThat(requests).as("before the bound the claim is held").hasSize(1);

        await(() -> state.get(stateKey()) != null && ScheduledTaskStateValue.OUTCOME_UNKNOWN.equals(state.get(stateKey()).lastOutcome()), 8_000);
        var unknown = state.get(stateKey());

        assertThat(unknown.totalExecutions()).as("unknown is not an execution").isZero();
        assertThat(unknown.consecutiveFailures()).as("unknown is not a failure").isZero();
        await(() -> requests.size() >= 2, 8_000);
    }

    /// A departed callee node ends the wait at once as a FAILURE (the callee is gone, so it is not running), and the claim
    /// is released.
    @Test
    @Timeout(60)
    void remoteSingleModeFire_whoseCalleeNodeDeparts_isAFailure_andReleasesTheClaim() {
        startSingleModeTask();
        await(() -> !requests.isEmpty(), 5_000);

        invoker.onNodeDeparture(HOST);
        await(() -> state.get(stateKey()) != null && state.get(stateKey()).consecutiveFailures() >= 1, 5_000);

        assertThat(state.get(stateKey()).lastOutcome()).isEqualTo(ScheduledTaskStateValue.OUTCOME_FAILURE);
        assertThat(state.get(stateKey()).totalExecutions()).isZero();
        await(() -> requests.size() >= 2, 5_000);
    }

    /// A node of a two-node cluster: its own invoker over a network that records the requests it sends, its own registry, and
    /// a scheduler writing into the one shared state map (the committed task row both nodes read).
    private final class Node {
        final NodeId self;
        final CopyOnWriteArrayList<InvokeRequest> sent = new CopyOnWriteArrayList<>();
        final ScheduledTaskManagerTest.TestLeaderManager leaderManager;
        final SliceInvoker invoker;
        final ScheduledTaskRegistry registry = ScheduledTaskRegistry.scheduledTaskRegistry();
        final ScheduledTaskManager manager;

        Node(NodeId self, long completionBoundMillis) {
            this.self = self;
            this.leaderManager = new ScheduledTaskManagerTest.TestLeaderManager(self);
            var network = new StubClusterNetwork() {
                @Override
                public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
                    if (message instanceof InvokeRequest request) {
                        sent.add(request);
                    }

                    return unit();
                }
            };
            var endpoints = EndpointRegistry.endpointRegistry();
            var codec = FrameworkCodecs.frameworkCodecs();

            endpoints.registerEndpoint(new EndpointKey(ARTIFACT, METHOD, 0), EndpointValue.endpointValue(HOST));
            this.invoker = SliceInvoker.sliceInvoker(self, network, endpoints, InvocationHandler.invocationHandler(self, network), codec, codec, 300L, 60_000L, new StubDeploymentManager());
            this.manager = newClusterManager(this, completionBoundMillis);
        }

        void becomeLeader() {
            var key = ScheduledTaskKey.scheduledTaskKey(SECTION, ARTIFACT, METHOD);

            registry.onScheduledTaskPut(new ValuePut<>(new KVCommand.Put<>(key, ScheduledTaskValue.intervalTask(self, "1s", ExecutionMode.SINGLE)), Option.none()));
            leaderManager.setLeader(true);
            manager.onLeaderChange(LeaderNotification.leaderChange(Option.some(self), true));
            manager.onQuorumStateChange(ClusterStateNotification.active());
        }

        void stopLeading(NodeId newLeader) {
            leaderManager.setLeader(false);
            manager.onLeaderChange(LeaderNotification.leaderChange(Option.some(newLeader), false));
        }

        void answerFirst() {
            invoker.onInvokeResponse(InvokeResponse.invokeResponse(HOST, sent.getFirst().correlationId(), "r", true, new byte[0]));
        }

        void stop() {
            manager.stop();
            invoker.stop().await();
        }
    }

    private ScheduledTaskManager newClusterManager(Node node, long completionBoundMillis) {
        return ScheduledTaskManager.scheduledTaskManager(node.registry,
                                                         node.invoker,
                                                         node.self,
                                                         this::write,
                                                         key -> Option.option(state.get(key)),
                                                         node.leaderManager,
                                                         org.pragmatica.lang.io.TimeSpan.timeSpan(completionBoundMillis).millis(),
                                                         ScheduledFireObserver.NONE,
                                                         claims);
    }

    /// #1155: leadership moves while the old leader's SINGLE-mode fire is still running. The new leader's scheduler starts with
    /// an empty in-flight set, so before the fix it fired on its next tick and the task ran twice for one schedule. The old
    /// leader's run is the one in flight; the new leader must wait for it, and fire again only once it has completed.
    @Test
    @Timeout(60)
    void leaderChangeMidFireDoesNotStartSecondRun() {
        var oldLeader = new Node(SELF, 60_000);
        var newLeader = new Node(new NodeId("leader2"), 60_000);

        try {
            oldLeader.becomeLeader();
            await(() -> !oldLeader.sent.isEmpty(), 5_000);

            oldLeader.stopLeading(newLeader.self);
            newLeader.becomeLeader();
            sleep(2_600);

            assertThat(newLeader.sent).as("the old leader's run is still in flight: the new leader's ticks at ~1 s and ~2 s must not start a second run").isEmpty();

            oldLeader.answerFirst();
            await(() -> state.get(stateKey()) != null && state.get(stateKey()).totalExecutions() == 1, 5_000);
            await(() -> !newLeader.sent.isEmpty(), 5_000);

            assertThat(state.get(stateKey()).totalExecutions()).as("the old leader's run is recorded exactly once").isEqualTo(1);
        } finally {
            oldLeader.stop();
            newLeader.stop();
        }
    }

    /// The old leader is gone mid-fire (it never answers and never releases): its claim stands until it is stale, then the new
    /// leader overrides it and runs. When the old run finally ends, its release is refused by the fence and its outcome is not
    /// recorded, so exactly one run is recorded.
    @Test
    @Timeout(60)
    void staleClaimIsOverriddenByTheNewLeader_andTheOldRunsOutcomeIsFencedOut() {
        var oldLeader = new Node(SELF, 60_000);
        var newLeader = new Node(new NodeId("leader2"), 1_500);

        try {
            oldLeader.becomeLeader();
            await(() -> !oldLeader.sent.isEmpty(), 5_000);
            oldLeader.stopLeading(newLeader.self);
            newLeader.becomeLeader();

            assertThat(newLeader.sent).as("the claim is fresh: no second run").isEmpty();
            await(() -> !newLeader.sent.isEmpty(), 8_000);

            newLeader.answerFirst();
            await(() -> state.get(stateKey()) != null && state.get(stateKey()).totalExecutions() == 1, 5_000);
            oldLeader.answerFirst();
            sleep(500);

            assertThat(state.get(stateKey()).totalExecutions()).as("the old run was fenced out: only the new leader's run is recorded").isEqualTo(1);
        } finally {
            oldLeader.stop();
            newLeader.stop();
        }
    }

    /// The claim row is one per task and goes with the task: it must not outlive its task in cluster state.
    @Test
    @Timeout(60)
    void claimRow_isRemovedWithItsTask() {
        var leader = new Node(SELF, 60_000);
        var claimKey = AetherKey.ScheduledTaskClaimKey.scheduledTaskClaimKey(SECTION, ARTIFACT, METHOD);

        try {
            leader.becomeLeader();
            await(() -> claims.read(claimKey).isPresent(), 5_000);

            var taskKey = ScheduledTaskKey.scheduledTaskKey(SECTION, ARTIFACT, METHOD);

            leader.registry.onScheduledTaskRemove(new org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove<>(new KVCommand.Remove<ScheduledTaskKey>(taskKey), Option.none()));

            assertThat(claims.read(claimKey).isPresent()).as("the claim row is removed with its task").isFalse();
        } finally {
            leader.stop();
        }
    }

    /// #1155: a manual run (`/trigger`, `/inject`) takes the same cluster claim a scheduled fire does, so it cannot overlap a
    /// fire running on another node's leader, and a fire cannot start over a manual run.
    @Test
    @Timeout(60)
    void manualRun_takesTheClusterClaim_soItNeverOverlapsAFireOnAnotherNode() {
        var leader = new Node(SELF, 60_000);
        var other = new Node(new NodeId("leader2"), 60_000);
        var task = new ScheduledTask(SECTION, ARTIFACT, METHOD, SELF, "1s", "", ExecutionMode.SINGLE, false);

        try {
            leader.becomeLeader();
            await(() -> !leader.sent.isEmpty(), 5_000);

            assertThat(other.manager.claimForManualRun(task).await().unwrap().isPresent()).as("a fire on another node holds the claim").isFalse();

            leader.answerFirst();
            await(() -> state.get(stateKey()) != null && state.get(stateKey()).totalExecutions() == 1, 5_000);
            leader.stopLeading(other.self);
            var manual = other.manager.claimForManualRun(task).await().unwrap();

            assertThat(manual.isPresent()).as("the fire released: the manual run may take the claim").isTrue();
            assertThat(leader.manager.claimForManualRun(task).await().unwrap().isPresent()).as("a manual run holds the claim over a fire").isFalse();

            manual.unwrap().release().await();
            assertThat(leader.manager.claimForManualRun(task).await().unwrap().isPresent()).as("released").isTrue();
        } finally {
            leader.stop();
            other.stop();
        }
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
