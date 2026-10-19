// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskStateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// The state row of a SINGLE-mode task is written on behalf of the fire that took the task's claim (#1155), and the cluster's
/// applier refuses the write once a newer acquisition is committed: a superseded fire's late write must not overwrite the
/// row of the fire that replaced it. Every test runs over the cluster's own applier (`KVStore`), claims and state rows in one
/// store, as in a node.
class ScheduledTaskStateFenceTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final NodeId HOST = new NodeId("host");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:scheduled-slice:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("cleanup").unwrap();
    private static final String SECTION = "scheduling.cleanup";
    private static final ScheduledTask SINGLE = new ScheduledTask(SECTION, ARTIFACT, METHOD, A, "1s", "", ExecutionMode.SINGLE, false);
    private static final ScheduledTaskStateKey STATE_KEY = ScheduledTaskStateKey.scheduledTaskStateKey(SECTION, ARTIFACT, METHOD);
    /// Stale after max(1 s, 2 s + 1 s) = 3 s.
    private static final TimeSpan BOUND = TimeSpan.timeSpan(2_000).millis();

    private final KVStore<AetherKey, AetherValue> applier = newApplier();
    private final ScheduledClaimStore store = ScheduledClaimStore.over(applier);
    private final AtomicLong now = new AtomicLong(10_000);

    private static KVStore<AetherKey, AetherValue> newApplier() {
        var codec = FrameworkCodecs.frameworkCodecs();

        return new KVStore<>(MessageRouter.mutable(), codec, codec);
    }

    private Option<ScheduledTaskStateValue> stateRow() {
        return applier.getTyped(STATE_KEY, ScheduledTaskStateValue.class);
    }

    private ScheduledTaskClaims claimsOf(NodeId node) {
        return new ScheduledTaskClaims(store, node, new ScheduledTaskManagerTest.TestLeaderManager(node), BOUND, now::get);
    }

    private void writeState(ScheduledTaskStateKey key, ScheduledTaskStateValue value) {
        store.write(new KVCommand.Put<>(key, value)).await();
    }

    private static ScheduledTaskStateValue success(int executions, Option<String> holder) {
        return new ScheduledTaskStateValue(1, 2, 0, executions, "", 3, 0).boundTo(holder);
    }

    /// A takes the claim and runs; the claim goes stale and B overrides it; A's fire ends and A writes its outcome bound to
    /// its own claim. The applier refuses it, B's own write lands.
    @Test
    void supersededFiresStateWrite_isRefused_andTheReplacementsWriteLands() {
        var old = claimsOf(A).acquire(SINGLE).await().unwrap().unwrap();

        now.addAndGet(3_000);
        var fresh = claimsOf(B).acquire(SINGLE).await().unwrap().unwrap();

        writeState(STATE_KEY, success(1, old.holder()));
        assertThat(stateRow().isPresent()).as("the superseded fire's late write is refused").isFalse();

        writeState(STATE_KEY, success(2, fresh.holder()));
        assertThat(stateRow().unwrap().totalExecutions()).as("the replacement's write lands").isEqualTo(2);

        writeState(STATE_KEY, success(1, old.holder()));
        assertThat(stateRow().unwrap().totalExecutions()).as("and the superseded one still cannot overwrite it").isEqualTo(2);
    }

    /// The release of a fire lands before it records its outcome, and the outcome is the fire's own: it is admitted after the
    /// release, for as long as no newer claim is committed.
    @Test
    void firesStateWrite_afterItsOwnRelease_isAccepted_untilANewerClaimIsCommitted() {
        var ticket = claimsOf(A).acquire(SINGLE).await().unwrap().unwrap();

        assertThat(ticket.release().await().unwrap()).isTrue();
        writeState(STATE_KEY, success(1, ticket.holder()));
        assertThat(stateRow().unwrap().totalExecutions()).isEqualTo(1);

        claimsOf(B).acquire(SINGLE).await().unwrap().unwrap();
        writeState(STATE_KEY, success(5, ticket.holder()));
        assertThat(stateRow().unwrap().totalExecutions()).as("a newer claim is committed: A's late write is refused").isEqualTo(1);
    }

    @Test
    void stateWrite_boundToAClaimThatWasRemovedWithItsTask_isRefused() {
        var ticket = claimsOf(A).acquire(SINGLE).await().unwrap().unwrap();

        claimsOf(A).forget(ScheduledTaskKey.scheduledTaskKey(SECTION, ARTIFACT, METHOD)).await();
        writeState(STATE_KEY, success(1, ticket.holder()));

        assertThat(stateRow().isPresent()).as("the task is gone: its fire's outcome does not bring its state row back").isFalse();
    }

    /// A row written under no claim (a skipped tick, a manual inject, the clean-up of a removed task) and a per-node row (ALL-mode
    /// fires hold no claim) are not gated.
    @Test
    void stateWrite_boundToNoClaim_orToAPerNodeRow_isNeverGated() {
        var perNode = ScheduledTaskStateKey.scheduledTaskStateKey(SECTION, ARTIFACT, METHOD, A);

        claimsOf(B).acquire(SINGLE).await().unwrap().unwrap();
        writeState(STATE_KEY, success(1, Option.none()));
        writeState(perNode, success(1, Option.some("a-token-nobody-holds")));

        assertThat(stateRow().unwrap().totalExecutions()).isEqualTo(1);
        assertThat(applier.getTyped(perNode, ScheduledTaskStateValue.class).isPresent()).isTrue();
    }

    /// The same, through the scheduler itself: the old leader's release lands, its outcome write is still on its way when the
    /// new leader claims the task; the late write is refused, so the row holds the new leader's fire and only that.
    @Test
    @Timeout(60)
    void scheduler_supersededFiresOutcomeWrite_isRefusedByTheApplier() {
        var oldLeader = new Node(A, true);
        var newLeader = new Node(B, false);

        try {
            oldLeader.becomeLeader();
            await(() -> !oldLeader.sent.isEmpty(), 5_000);
            oldLeader.answerFirst();
            await(() -> !oldLeader.deferred.isEmpty(), 5_000);
            oldLeader.stopLeading(newLeader.self);

            newLeader.becomeLeader();
            await(() -> !newLeader.sent.isEmpty(), 8_000);

            oldLeader.flushDeferred();
            assertThat(stateRow().isPresent()).as("the old fire's outcome write was made under a claim that is no longer the committed one").isFalse();

            newLeader.answerFirst();
            await(() -> stateRow().isPresent() && stateRow().unwrap().totalExecutions() == 1, 5_000);

            assertThat(stateRow().unwrap().fireSeq()).as("the row is the new leader's fire, the first and only one recorded").isEqualTo(1);
        } finally {
            oldLeader.stop();
            newLeader.stop();
        }
    }

    /// A node of the cluster: its own invoker over a network that records the requests it sends, and a scheduler over the one
    /// shared applier. A `deferring` node keeps its state writes back until the test releases them.
    private final class Node {
        final NodeId self;
        final CopyOnWriteArrayList<InvokeRequest> sent = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<KVCommand<AetherKey>> deferred = new CopyOnWriteArrayList<>();
        final AtomicBoolean deferring;
        final ScheduledTaskManagerTest.TestLeaderManager leaderManager;
        final SliceInvoker invoker;
        final ScheduledTaskRegistry registry = ScheduledTaskRegistry.scheduledTaskRegistry();
        final ScheduledTaskManager manager;

        Node(NodeId self, boolean deferring) {
            this.self = self;
            this.deferring = new AtomicBoolean(deferring);
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
            this.manager = ScheduledTaskManager.scheduledTaskManager(registry,
                                                                     invoker,
                                                                     self,
                                                                     this::write,
                                                                     key -> applier.getTyped(key, ScheduledTaskStateValue.class),
                                                                     leaderManager,
                                                                     TimeSpan.timeSpan(1_500).millis(),
                                                                     ScheduledFireObserver.NONE,
                                                                     store);
        }

        private void write(KVCommand<AetherKey> command) {
            if (deferring.get()) {
                deferred.add(command);
            } else {
                store.write(command);
            }
        }

        void flushDeferred() {
            deferring.set(false);
            List.copyOf(deferred).forEach(store::write);
            deferred.clear();
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

    private static void await(BooleanSupplier condition, long timeoutMs) {
        var deadline = System.currentTimeMillis() + timeoutMs;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Condition not satisfied within " + timeoutMs + "ms");
            }

            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
