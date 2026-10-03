// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.fsm.MembershipTransitionRecord;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.PartitionKey;
import org.pragmatica.aether.stream.replication.ReplicaDescriptor;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.replication.StreamCatalog;
import org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmObserver;

import static org.assertj.core.api.Assertions.assertThat;

/// #1339 / #1732 — the stream replica-set reconcile is a pure function of CURRENT state, so it is correct exactly
/// when something runs it after every change of every input. It ran after membership decisions, quorum edges and
/// stream-config puts, and after nothing else:
///
///  - #1732: a replacement core is counted by the FSM at once but becomes a placement member only when the voter
///    installation adds it, after the join decision's pass had already run — it never entered a replica set, and RF
///    stayed degraded for good.
///  - #1339: the ownership writer is leader-only, so a removal pass that ran while no live leader existed wrote
///    nothing, and nothing ran the pass again when a leader appeared — the partition stayed write-refused.
///
/// Every test below isolates ONE input change, asserts first that the registry/commit has NOT moved without the
/// trigger, and goes through the production wiring (`wireReplicaSetInputTriggers`, `onFsmTransition` hook,
/// `livePlacementMembers`) with the real FSM, the real controller and the real ownership writer.
class AetherNodeReplicaSetTriggersTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId C = new NodeId("node-c");
    private static final NodeId D = new NodeId("node-d");

    private final AtomicReference<Set<NodeId>> voters = new AtomicReference<>(Set.of(A, B, C));
    private final List<Consumer<VoterConfiguration>> voterListeners = new ArrayList<>();
    private final AtomicReference<Option<NodeId>> committedOwner = new AtomicReference<>(Option.none());
    private final AtomicBoolean leader = new AtomicBoolean(false);
    private final List<KVCommand<AetherKey>> commits = new ArrayList<>();
    private final List<MessageRouter.Entry<?>> entries = new ArrayList<>();
    private final AtomicReference<ReplicaSetController> controllerRef = new AtomicReference<>();
    private final ReplicaRegistry registry = ReplicaRegistry.replicaRegistry();
    private final MembershipFsm fsm = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                                                   System::currentTimeMillis,
                                                                   Long.MAX_VALUE,
                                                                   TimeSpan.timeSpan(40).millis());
    private ReplicaSetController controller;

    /// Wires what `AetherNode` wires: the FSM, the single placement-member source, the leader-gated ownership
    /// writer driven from the controller's pass seam, and the placement-input triggers.
    private void assemble(int replicationFactor) {
        fsm.seed(Set.of(A, B, C));
        var writer = StreamPartitionOwnershipWriter.streamPartitionOwnershipWriter(leader::get,
                                                                                    () -> Epoch.epoch(0L, 1L, 0L),
                                                                                    HlcClock.hlcClock(A),
                                                                                    (_, _) -> committedOwner.get()
                                                                                                          .map(AetherNodeReplicaSetTriggersTest::ownership),
                                                                                    (stream, partition) -> controllerRef.get()
                                                                                                                       .desiredOwner(stream, partition));

        controller = ReplicaSetController.replicaSetController(registry,
                                                              A,
                                                              AetherNode.livePlacementMembers(voters::get, fsm),
                                                              () -> voters.get().size(),
                                                              () -> List.of(new StreamCatalog.StreamSpec(STREAM,
                                                                                                       1,
                                                                                                       replicationFactor,
                                                                                                       0)),
                                                              (_, _) -> {},
                                                              (List<PartitionKey> reconciled) -> applyCommits(writer.writeOwnershipChanges(reconciled)),
                                                              Runnable::run);
        controllerRef.set(controller);
        controller.committedOwnerSource((_, _) -> committedOwner.get());
        AetherNode.wireReplicaSetInputTriggers(entries, controller::reconcile, this::voterSource);
        fsm.onTransition(record -> AetherNode.reconcileReplicaSetOnCountedBoundary(controllerRef, record));
        controller.reconcile();
    }

    private static StreamPartitionOwnershipValue ownership(NodeId owner) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, Epoch.epoch(0L, 1L, 0L), 1L, HlcTimestamp.ZERO);
    }

    /// The leader's consensus apply, reduced to its observable effect: the committed record changes, and the KV
    /// notification for it reaches every node's routes.
    @SuppressWarnings("unchecked")
    private void applyCommits(List<KVCommand<AetherKey>> commands) {
        commands.forEach(command -> {
            commits.add(command);
            var put = (KVCommand.Put<AetherKey, AetherValue>) command;
            var value = (StreamPartitionOwnershipValue) put.value();

            committedOwner.set(Option.some(value.owner()));
            fire(new ValuePut<AetherKey, AetherValue>(put, Option.none()));
        });
    }

    /// `RabiaEngine.onVoterConfiguration` semantics: register, and replay an already-installed configuration.
    private void voterSource(Consumer<VoterConfiguration> listener) {
        voterListeners.add(listener);
    }

    /// genesis / §4 command / sync adoption: the engine stores the configuration, THEN calls its listeners.
    private void installVoters(NodeId... members) {
        voters.set(Set.of(members));
        var configuration = VoterConfiguration.voterConfiguration(1, List.of(members)).unwrap();

        voterListeners.forEach(listener -> listener.accept(configuration));
    }

    /// The message router's dispatch: every route entry registered for exactly this message class.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void fire(Message message) {
        entries.stream()
               .flatMap(MessageRouter.Entry::entries)
               .filter(tuple -> tuple.first().isInstance(message))
               .forEach(tuple -> ((Consumer) tuple.last()).accept(message));
    }

    private Set<NodeId> replicaSet() {
        var nodes = new HashSet<NodeId>();

        registry.replicasFor(STREAM, PARTITION).forEach((ReplicaDescriptor descriptor) -> nodes.add(descriptor.nodeId()));

        return nodes;
    }

    // ---- #1732 -------------------------------------------------------------------------------------------

    /// A replacement core joins: the FSM counts it and the join decision runs a pass, but it is not a voter yet,
    /// so placement cannot include it. Only the voter install can — and nothing but the trigger runs the pass.
    @Test
    void replacementJoinedBeforeItsVoterInstall_entersTheReplicaSetOnTheInstall() {
        assemble(4);
        fsm.seed(Set.of(D));
        controller.onMembershipDecision(MembershipDecision.nodeJoined(D, List.of(A, B, C, D)));

        assertThat(replicaSet()).as("arming: counted, decided, but not yet a voter").containsExactlyInAnyOrder(A, B, C);

        installVoters(A, B, C, D);

        assertThat(replicaSet()).as("#1732: the voter install places the replacement; RF is restored").containsExactlyInAnyOrder(A, B, C, D);
    }

    // ---- #1339 -------------------------------------------------------------------------------------------

    /// The owner leaves placement while no live leader exists: the removal pass writes nothing (a follower's writer
    /// short-circuits). When a leader appears, only the leadership edge can say the writer is now able to commit.
    @Test
    void ownerLeavesWhileNoLeader_theNewLeadersFirstPassCommitsANewOwner() {
        assemble(2);
        committedOwner.set(Option.some(B));
        fsm.onDrainRequested(B);
        controller.onMembershipDecision(MembershipDecision.nodeRemoved(B, List.of(A, C)));

        assertThat(commits).as("arming: no leader, so the removal pass wrote nothing").isEmpty();
        assertThat(committedOwner.get()).as("the dead owner is still the committed owner").isEqualTo(Option.some(B));

        leader.set(true);
        fire(LeaderNotification.leaderChange(Option.some(A), true));

        assertThat(commits).as("#1339: leadership gained re-runs the pass").hasSize(1);
        assertThat(committedOwner.get()).as("the committed owner moved off the departed node")
                                        .isNotEqualTo(Option.some(B))
                                        .isNotEqualTo(Option.none());
    }

    // ---- committed ownership record ---------------------------------------------------------------------

    /// Placement follows the committed owner, so a record landing changes the replica set and re-derives it on every
    /// node — no membership event accompanies it.
    @Test
    void committedOwnershipRecordLands_registryFollowsIt() {
        assemble(2);
        var outsider = List.of(A, B, C).stream()
                           .filter(node -> !replicaSet().contains(node))
                           .findFirst()
                           .orElseThrow();

        assertThat(replicaSet()).as("arming: two of three members hold the partition").hasSize(2);

        committedOwner.set(Option.some(outsider));
        var put = new KVCommand.Put<AetherKey, AetherValue>(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(STREAM, PARTITION),
                                                            ownership(outsider));

        assertThat(replicaSet()).as("the record alone does not move the registry").doesNotContain(outsider);

        fire(new ValuePut<AetherKey, AetherValue>(put, Option.none()));

        assertThat(replicaSet()).as("the put re-derives the replica set: the committed owner leads it").contains(outsider);
    }

    // ---- FSM counted boundary -----------------------------------------------------------------------------

    /// A graceful drain drops the member from placement and a withdrawn drain returns it — neither emits a
    /// membership decision, so the FSM transition is the only announcement of either.
    @Test
    void drainAndItsWithdrawal_moveTheMemberOutOfAndBackIntoTheReplicaSet() {
        assemble(3);

        assertThat(replicaSet()).as("arming").containsExactlyInAnyOrder(A, B, C);

        fsm.onDrainRequested(B);

        assertThat(replicaSet()).as("DEPARTING is not counted: placement drops it").containsExactlyInAnyOrder(A, C);

        fsm.onSwimHealthy(B, 5L);

        assertThat(replicaSet()).as("a refuted drain recovers to MEMBER with no decision: placement takes it back")
                                .containsExactlyInAnyOrder(A, B, C);
    }

    @Test
    void crossesCountedBoundary_isTrueOnlyWhenCountedFlips() {
        assertThat(AetherNode.crossesCountedBoundary(transition("Observed", "Member"))).isTrue();
        assertThat(AetherNode.crossesCountedBoundary(transition("Member", "Departing"))).isTrue();
        assertThat(AetherNode.crossesCountedBoundary(transition("Departing", "Member"))).isTrue();
        assertThat(AetherNode.crossesCountedBoundary(transition("Suspect", "Dead"))).isTrue();
        assertThat(AetherNode.crossesCountedBoundary(transition("Member", "Suspect"))).as("SUSPECT still counts").isFalse();
        assertThat(AetherNode.crossesCountedBoundary(transition("Suspect", "Member"))).isFalse();
        assertThat(AetherNode.crossesCountedBoundary(transition("Dead", "Observed"))).isFalse();
    }

    private static MembershipTransitionRecord transition(String from, String to) {
        return new MembershipTransitionRecord(B, from, to, "test", 0L, "core", 0L);
    }
}
