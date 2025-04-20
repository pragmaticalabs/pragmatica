/*
 * Original work Copyright (c) 2008-2020, Hazelcast, Inc.
 * Modified work Copyright 2020, MicroRaft.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.microraft.impl;

import static io.microraft.MembershipChangeMode.ADD_LEARNER;
import static io.microraft.MembershipChangeMode.ADD_OR_PROMOTE_TO_FOLLOWER;
import static io.microraft.MembershipChangeMode.REMOVE_MEMBER;
import static io.microraft.RaftNodeStatus.ACTIVE;
import static io.microraft.RaftNodeStatus.TERMINATED;
import static io.microraft.RaftRole.FOLLOWER;
import static io.microraft.RaftRole.LEARNER;
import static io.microraft.impl.local.SimpleStateMachine.applyValue;
import static io.microraft.test.util.AssertionUtils.allTheTime;
import static io.microraft.test.util.AssertionUtils.eventually;
import static io.microraft.test.util.RaftTestUtils.TEST_RAFT_CONFIG;
import static io.microraft.test.util.RaftTestUtils.commitIndex;
import static io.microraft.test.util.RaftTestUtils.committedGroupMembers;
import static io.microraft.test.util.RaftTestUtils.effectiveGroupMembers;
import static io.microraft.test.util.RaftTestUtils.lastLogOrSnapshotEntry;
import static io.microraft.test.util.RaftTestUtils.role;
import static io.microraft.test.util.RaftTestUtils.snapshotEntry;
import static io.microraft.test.util.RaftTestUtils.status;
import static io.microraft.test.util.RaftTestUtils.majority;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.After;
import org.junit.Test;

import io.microraft.Ordered;
import io.microraft.RaftConfig;
import io.microraft.RaftEndpoint;
import io.microraft.RaftNode;
import io.microraft.exception.CannotReplicateException;
import io.microraft.exception.IndeterminateStateException;
import io.microraft.exception.NotLeaderException;
import io.microraft.impl.local.LocalRaftGroup;
import io.microraft.impl.local.SimpleStateMachine;
import io.microraft.impl.state.RaftGroupMembersState;
import io.microraft.model.message.AppendEntriesFailureResponse;
import io.microraft.model.message.AppendEntriesRequest;
import io.microraft.model.message.AppendEntriesSuccessResponse;
import io.microraft.model.message.PreVoteRequest;
import io.microraft.model.message.VoteRequest;
import io.microraft.report.RaftGroupMembers;
import io.microraft.test.util.BaseTest;

public class MembershipChangeTest extends BaseTest {

    private LocalRaftGroup group;

    @After
    public void destroy() {
        if (group != null) {
            group.destroy();
        }
    }

    @Test(timeout = 300_000)
    public void when_newNodeJoinsAsFollower_then_itAppendsMissingEntries() {
        int initialMemberCount = 3;
        group = LocalRaftGroup.start(initialMemberCount);

        RaftNode leader = group.waitUntilLeaderElected();
        leader.replicate(applyValue("val")).join();

        var initialMembers = leader.initialMembers();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> result = leader
                .changeMembership(newNode.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, 0).join();

        assertThat(result.getCommitIndex()).isEqualTo(commitIndex(leader));

        for (var groupMembers : List.of(result.getResult(), leader.committedMembers(),
                                        leader.effectiveMembers())) {
            assertThat(groupMembers.getMembers()).contains(newNode.localEndpoint());
            assertThat(groupMembers.getVotingMembers()).contains(newNode.localEndpoint());
            assertThat(groupMembers.getMajorityQuorumSize()).isEqualTo(1 + majority(initialMemberCount));
        }

        assertThat(leader.initialMembers().getMembers()).isEqualTo(initialMembers.getMembers());

        long commitIndex = commitIndex(leader);
        eventually(() -> assertThat(commitIndex(newNode)).isEqualTo(commitIndex));

        assertThat(role(newNode)).isEqualTo(FOLLOWER);

        RaftGroupMembersState effectiveGroupMembers = effectiveGroupMembers(leader);
        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(status(node)).isEqualTo(ACTIVE);
                assertThat(effectiveGroupMembers(node).getMembers()).isEqualTo(effectiveGroupMembers.getMembers());
                assertThat(effectiveGroupMembers(node).getLogIndex()).isEqualTo(effectiveGroupMembers.getLogIndex());
                assertThat(committedGroupMembers(node).getMembers()).isEqualTo(effectiveGroupMembers.getMembers());
                assertThat(committedGroupMembers(node).getLogIndex()).isEqualTo(effectiveGroupMembers.getLogIndex());
            }
        });

        SimpleStateMachine stateMachine = group.stateMachine(newNode.localEndpoint());
        assertThat(stateMachine.size()).isEqualTo(1);
        assertThat(stateMachine.valueSet()).contains("val");

        assertThat(newNode.initialMembers().getMembers()).isEqualTo(initialMembers.getMembers());
        assertThat(newNode.committedMembers().getMembers()).isEqualTo(leader.committedMembers().getMembers());
        assertThat(newNode.committedMembers().getLogIndex()).isEqualTo(leader.committedMembers().getLogIndex());
        assertThat(newNode.committedMembers().getMajorityQuorumSize()).isEqualTo(1 + majority(initialMemberCount));
        assertThat(newNode.effectiveMembers().getMembers()).isEqualTo(leader.effectiveMembers().getMembers());
        assertThat(newNode.effectiveMembers().getLogIndex()).isEqualTo(leader.effectiveMembers().getLogIndex());
        assertThat(newNode.effectiveMembers().getMajorityQuorumSize()).isEqualTo(1 + majority(initialMemberCount));

        Map<RaftEndpoint, Long> heartbeatTimestamps = leader.report().join().getResult().heartbeatTimestamps();
        assertThat(heartbeatTimestamps).containsKey(newNode.localEndpoint());
    }

    @Test(timeout = 300_000)
    public void when_newNodeJoinsAsLearner_then_itAppendsMissingEntries() {
        int initialMemberCount = 3;
        group = LocalRaftGroup.start(initialMemberCount);

        var leader = group.waitUntilLeaderElected();
        leader.replicate(applyValue("val")).join();

        var initialMembers = leader.initialMembers();
        var newNode = group.createNewNode();
        var result = leader.changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();

        assertThat(result.getCommitIndex()).isEqualTo(commitIndex(leader));

        for (RaftGroupMembers groupMembers : List.of(result.getResult(), leader.committedMembers(),
                leader.effectiveMembers())) {
            assertThat(groupMembers.getMembers()).contains(newNode.localEndpoint());
            assertThat(groupMembers.getVotingMembers()).doesNotContain(newNode.localEndpoint());
            assertThat(groupMembers.getMajorityQuorumSize()).isEqualTo(majority(initialMemberCount));
        }

        assertThat(leader.initialMembers().getMembers()).isEqualTo(initialMembers.getMembers());

        long commitIndex = commitIndex(leader);
        eventually(() -> assertThat(commitIndex(newNode)).isEqualTo(commitIndex));

        assertThat(role(newNode)).isEqualTo(LEARNER);

        RaftGroupMembersState effectiveGroupMembers = effectiveGroupMembers(leader);
        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(status(node)).isEqualTo(ACTIVE);
                assertThat(effectiveGroupMembers(node).getMembers()).isEqualTo(effectiveGroupMembers.getMembers());
                assertThat(effectiveGroupMembers(node).getLogIndex()).isEqualTo(effectiveGroupMembers.getLogIndex());
                assertThat(committedGroupMembers(node).getMembers()).isEqualTo(effectiveGroupMembers.getMembers());
                assertThat(committedGroupMembers(node).getLogIndex()).isEqualTo(effectiveGroupMembers.getLogIndex());
            }
        });

        SimpleStateMachine stateMachine = group.stateMachine(newNode.localEndpoint());
        assertThat(stateMachine.size()).isEqualTo(1);
        assertThat(stateMachine.valueSet()).contains("val");

        assertThat(newNode.initialMembers().getMembers()).isEqualTo(initialMembers.getMembers());
        assertThat(newNode.committedMembers().getMembers()).isEqualTo(leader.committedMembers().getMembers());
        assertThat(newNode.committedMembers().getLogIndex()).isEqualTo(leader.committedMembers().getLogIndex());
        assertThat(newNode.committedMembers().getMajorityQuorumSize()).isEqualTo(majority(initialMemberCount));
        assertThat(newNode.effectiveMembers().getMembers()).isEqualTo(leader.effectiveMembers().getMembers());
        assertThat(newNode.effectiveMembers().getLogIndex()).isEqualTo(leader.effectiveMembers().getLogIndex());
        assertThat(newNode.effectiveMembers().getMajorityQuorumSize()).isEqualTo(majority(initialMemberCount));

        Map<RaftEndpoint, Long> heartbeatTimestamps = leader.report().join().getResult().heartbeatTimestamps();
        assertThat(heartbeatTimestamps).containsKey(newNode.localEndpoint());
    }

    @Test(timeout = 300_000)
    public void when_thereIsSingleLearner_then_followerIsAdded() {
        int initialMemberCount = 3;
        group = LocalRaftGroup.start(initialMemberCount);

        RaftNode leader = group.waitUntilLeaderElected();
        leader.replicate(applyValue("val")).join();

        var initialMembers = leader.initialMembers();

        RaftNode newNode1 = group.createNewNode();

        Ordered<RaftGroupMembers> result1 = leader.changeMembership(newNode1.localEndpoint(), ADD_LEARNER, 0).join();

        assertThat(result1.getCommitIndex()).isEqualTo(commitIndex(leader));

        for (RaftGroupMembers groupMembers : List.of(result1.getResult(), leader.committedMembers(),
                leader.effectiveMembers())) {
            assertThat(groupMembers.getMembers()).contains(newNode1.localEndpoint());
            assertThat(groupMembers.getVotingMembers()).doesNotContain(newNode1.localEndpoint());
            assertThat(groupMembers.getMajorityQuorumSize()).isEqualTo(majority(initialMemberCount));
        }

        assertThat(result1.getResult().getMembers()).contains(newNode1.localEndpoint());

        RaftNode newNode2 = group.createNewNode();

        Ordered<RaftGroupMembers> result2 = leader
                .changeMembership(newNode2.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, result1.getCommitIndex())
                .join();

        assertThat(result2.getCommitIndex()).isEqualTo(commitIndex(leader));

        for (RaftGroupMembers groupMembers : List.of(result2.getResult(), leader.committedMembers(),
                leader.effectiveMembers())) {
            assertThat(groupMembers.getMembers()).contains(newNode1.localEndpoint());
            assertThat(groupMembers.getMembers()).contains(newNode2.localEndpoint());
            assertThat(groupMembers.getVotingMembers()).doesNotContain(newNode1.localEndpoint());
            assertThat(groupMembers.getVotingMembers()).contains(newNode2.localEndpoint());
            assertThat(groupMembers.getMajorityQuorumSize()).isEqualTo(1 + majority(initialMemberCount));
        }

        long commitIndex = commitIndex(leader);
        eventually(() -> assertThat(commitIndex(newNode1)).isEqualTo(commitIndex));
        eventually(() -> assertThat(commitIndex(newNode2)).isEqualTo(commitIndex));

        Map<RaftEndpoint, Long> heartbeatTimestamps = leader.report().join().getResult().heartbeatTimestamps();
        assertThat(heartbeatTimestamps).containsKey(newNode1.localEndpoint());
        assertThat(heartbeatTimestamps).containsKey(newNode2.localEndpoint());

        assertThat(newNode1.report().join().getResult().role()).isEqualTo(LEARNER);
        assertThat(newNode2.report().join().getResult().role()).isEqualTo(FOLLOWER);

        RaftGroupMembersState effectiveGroupMembers = effectiveGroupMembers(leader);
        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(status(node)).isEqualTo(ACTIVE);
                assertThat(effectiveGroupMembers(node).getMembers()).isEqualTo(effectiveGroupMembers.getMembers());
                assertThat(effectiveGroupMembers(node).getLogIndex()).isEqualTo(effectiveGroupMembers.getLogIndex());
                assertThat(committedGroupMembers(node).getMembers()).isEqualTo(effectiveGroupMembers.getMembers());
                assertThat(committedGroupMembers(node).getLogIndex()).isEqualTo(effectiveGroupMembers.getLogIndex());
            }
        });

        for (RaftNode newNode : List.of(newNode1, newNode2)) {
            SimpleStateMachine stateMachine = group.stateMachine(newNode.localEndpoint());
            assertThat(stateMachine.size()).isEqualTo(1);
            assertThat(stateMachine.valueSet()).contains("val");

            assertThat(newNode.initialMembers().getMembers()).isEqualTo(initialMembers.getMembers());
            assertThat(newNode.committedMembers().getMembers()).isEqualTo(leader.committedMembers().getMembers());
            assertThat(newNode.committedMembers().getLogIndex())
                    .isEqualTo(leader.committedMembers().getLogIndex());
            assertThat(newNode.effectiveMembers().getMembers()).isEqualTo(leader.effectiveMembers().getMembers());
            assertThat(newNode.effectiveMembers().getLogIndex())
                    .isEqualTo(leader.effectiveMembers().getLogIndex());
        }
    }

    @Test(timeout = 300_000)
    public void when_thereIsSingleLearner_then_secondLearnerIsAdded() {
        int initialMemberCount = 3;
        group = LocalRaftGroup.start(initialMemberCount);

        RaftNode leader = group.waitUntilLeaderElected();
        leader.replicate(applyValue("val")).join();

        var initialMembers = leader.initialMembers();

        RaftNode newNode1 = group.createNewNode();

        Ordered<RaftGroupMembers> result1 = leader.changeMembership(newNode1.localEndpoint(), ADD_LEARNER, 0).join();

        assertThat(result1.getCommitIndex()).isEqualTo(commitIndex(leader));

        for (RaftGroupMembers groupMembers : List.of(result1.getResult(), leader.committedMembers(),
                leader.effectiveMembers())) {
            assertThat(groupMembers.getMembers()).contains(newNode1.localEndpoint());
            assertThat(groupMembers.getVotingMembers()).doesNotContain(newNode1.localEndpoint());
            assertThat(groupMembers.getMajorityQuorumSize()).isEqualTo(majority(initialMemberCount));
        }

        assertThat(result1.getResult().getMembers()).contains(newNode1.localEndpoint());

        RaftNode newNode2 = group.createNewNode();

        Ordered<RaftGroupMembers> result2 = leader
                .changeMembership(newNode2.localEndpoint(), ADD_LEARNER, result1.getCommitIndex()).join();

        assertThat(result2.getCommitIndex()).isEqualTo(commitIndex(leader));

        for (RaftGroupMembers groupMembers : List.of(result2.getResult(), leader.committedMembers(),
                leader.effectiveMembers())) {
            assertThat(groupMembers.getMembers()).contains(newNode1.localEndpoint());
            assertThat(groupMembers.getMembers()).contains(newNode2.localEndpoint());
            assertThat(groupMembers.getVotingMembers()).doesNotContain(newNode1.localEndpoint());
            assertThat(groupMembers.getVotingMembers()).doesNotContain(newNode2.localEndpoint());
            assertThat(groupMembers.getMajorityQuorumSize()).isEqualTo(majority(initialMemberCount));
        }

        long commitIndex = commitIndex(leader);
        eventually(() -> assertThat(commitIndex(newNode1)).isEqualTo(commitIndex));
        eventually(() -> assertThat(commitIndex(newNode2)).isEqualTo(commitIndex));

        Map<RaftEndpoint, Long> heartbeatTimestamps = leader.report().join().getResult().heartbeatTimestamps();
        assertThat(heartbeatTimestamps).containsKey(newNode1.localEndpoint());
        assertThat(heartbeatTimestamps).containsKey(newNode2.localEndpoint());

        assertThat(newNode1.report().join().getResult().role()).isEqualTo(LEARNER);
        assertThat(newNode2.report().join().getResult().role()).isEqualTo(LEARNER);

        RaftGroupMembersState effectiveGroupMembers = effectiveGroupMembers(leader);
        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(status(node)).isEqualTo(ACTIVE);
                assertThat(effectiveGroupMembers(node).getMembers()).isEqualTo(effectiveGroupMembers.getMembers());
                assertThat(effectiveGroupMembers(node).getLogIndex()).isEqualTo(effectiveGroupMembers.getLogIndex());
                assertThat(committedGroupMembers(node).getMembers()).isEqualTo(effectiveGroupMembers.getMembers());
                assertThat(committedGroupMembers(node).getLogIndex()).isEqualTo(effectiveGroupMembers.getLogIndex());
            }
        });

        for (RaftNode newNode : List.of(newNode1, newNode2)) {
            SimpleStateMachine stateMachine = group.stateMachine(newNode.localEndpoint());
            assertThat(stateMachine.size()).isEqualTo(1);
            assertThat(stateMachine.valueSet()).contains("val");

            assertThat(newNode.initialMembers().getMembers()).isEqualTo(initialMembers.getMembers());
            assertThat(newNode.committedMembers().getMembers()).isEqualTo(leader.committedMembers().getMembers());
            assertThat(newNode.committedMembers().getLogIndex())
                    .isEqualTo(leader.committedMembers().getLogIndex());
            assertThat(newNode.effectiveMembers().getMembers()).isEqualTo(leader.effectiveMembers().getMembers());
            assertThat(newNode.effectiveMembers().getLogIndex())
                    .isEqualTo(leader.effectiveMembers().getLogIndex());
        }
    }

    @Test(timeout = 300_000)
    public void when_thereAreTwoLearners_then_followerIsAdded() {
        int initialMemberCount = 3;
        group = LocalRaftGroup.start(initialMemberCount);

        RaftNode leader = group.waitUntilLeaderElected();
        leader.replicate(applyValue("val")).join();

        RaftNode newNode1 = group.createNewNode();

        Ordered<RaftGroupMembers> result1 = leader.changeMembership(newNode1.localEndpoint(), ADD_LEARNER, 0).join();

        RaftNode newNode2 = group.createNewNode();

        Ordered<RaftGroupMembers> result2 = leader
                .changeMembership(newNode2.localEndpoint(), ADD_LEARNER, result1.getCommitIndex()).join();

        RaftNode newNode3 = group.createNewNode();

        Ordered<RaftGroupMembers> result3 = leader
                .changeMembership(newNode3.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, result2.getCommitIndex())
                .join();

        assertThat(result3.getCommitIndex()).isEqualTo(commitIndex(leader));

        for (RaftGroupMembers groupMembers : List.of(result3.getResult(), leader.committedMembers(),
                leader.effectiveMembers())) {
            assertThat(groupMembers.getMembers()).contains(newNode1.localEndpoint());
            assertThat(groupMembers.getMembers()).contains(newNode2.localEndpoint());
            assertThat(groupMembers.getMembers()).contains(newNode3.localEndpoint());
            assertThat(groupMembers.getVotingMembers()).doesNotContain(newNode1.localEndpoint());
            assertThat(groupMembers.getVotingMembers()).doesNotContain(newNode2.localEndpoint());
            assertThat(groupMembers.getVotingMembers()).contains(newNode3.localEndpoint());
            assertThat(groupMembers.getMajorityQuorumSize()).isEqualTo(1 + majority(initialMemberCount));
        }

        long commitIndex = commitIndex(leader);
        eventually(() -> assertThat(commitIndex(newNode1)).isEqualTo(commitIndex));
        eventually(() -> assertThat(commitIndex(newNode2)).isEqualTo(commitIndex));
        eventually(() -> assertThat(commitIndex(newNode3)).isEqualTo(commitIndex));

        Map<RaftEndpoint, Long> heartbeatTimestamps = leader.report().join().getResult().heartbeatTimestamps();
        assertThat(heartbeatTimestamps).containsKey(newNode1.localEndpoint());
        assertThat(heartbeatTimestamps).containsKey(newNode2.localEndpoint());
        assertThat(heartbeatTimestamps).containsKey(newNode3.localEndpoint());

        assertThat(newNode1.report().join().getResult().role()).isEqualTo(LEARNER);
        assertThat(newNode2.report().join().getResult().role()).isEqualTo(LEARNER);
        assertThat(newNode3.report().join().getResult().role()).isEqualTo(FOLLOWER);

        RaftGroupMembersState effectiveGroupMembers = effectiveGroupMembers(leader);
        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(status(node)).isEqualTo(ACTIVE);
                assertThat(effectiveGroupMembers(node).getMembers()).isEqualTo(effectiveGroupMembers.getMembers());
                assertThat(effectiveGroupMembers(node).getLogIndex()).isEqualTo(effectiveGroupMembers.getLogIndex());
                assertThat(committedGroupMembers(node).getMembers()).isEqualTo(effectiveGroupMembers.getMembers());
                assertThat(committedGroupMembers(node).getLogIndex()).isEqualTo(effectiveGroupMembers.getLogIndex());
            }
        });

        for (RaftNode newNode : List.of(newNode1, newNode2, newNode3)) {
            SimpleStateMachine stateMachine = group.stateMachine(newNode.localEndpoint());
            assertThat(stateMachine.size()).isEqualTo(1);
            assertThat(stateMachine.valueSet()).contains("val");

            assertThat(newNode.committedMembers().getMembers()).isEqualTo(leader.committedMembers().getMembers());
            assertThat(newNode.committedMembers().getLogIndex())
                    .isEqualTo(leader.committedMembers().getLogIndex());
            assertThat(newNode.effectiveMembers().getMembers()).isEqualTo(leader.effectiveMembers().getMembers());
            assertThat(newNode.effectiveMembers().getLogIndex())
                    .isEqualTo(leader.effectiveMembers().getLogIndex());
        }
    }

    @Test(timeout = 300_000)
    public void when_thereAreTwoLearners_then_thirdLearnerCannotBeAdded() {
        int initialMemberCount = 3;
        group = LocalRaftGroup.start(initialMemberCount);

        RaftNode leader = group.waitUntilLeaderElected();
        leader.replicate(applyValue("val")).join();

        RaftNode newNode1 = group.createNewNode();

        Ordered<RaftGroupMembers> result1 = leader.changeMembership(newNode1.localEndpoint(), ADD_LEARNER, 0).join();

        RaftNode newNode2 = group.createNewNode();

        Ordered<RaftGroupMembers> result2 = leader
                .changeMembership(newNode2.localEndpoint(), ADD_LEARNER, result1.getCommitIndex()).join();

        RaftNode newNode3 = group.createNewNode();

        try {
            leader.changeMembership(newNode3.localEndpoint(), ADD_LEARNER, result2.getCommitIndex()).join();
            fail();
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_learnerIsPromoted_then_majorityIsUpdated() {
        int initialMemberCount = 3;
        group = LocalRaftGroup.start(initialMemberCount);

        RaftNode leader = group.waitUntilLeaderElected();
        leader.replicate(applyValue("val")).join();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> result1 = leader.changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();

        Ordered<RaftGroupMembers> result2 = leader
                .changeMembership(newNode.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, result1.getCommitIndex())
                .join();

        int newMajority = 1 + majority(initialMemberCount);

        for (RaftGroupMembers groupMembers : List.of(result2.getResult(), leader.committedMembers(),
                leader.effectiveMembers())) {
            assertThat(groupMembers.getMembers()).contains(newNode.localEndpoint());
            assertThat(groupMembers.getVotingMembers()).contains(newNode.localEndpoint());
            assertThat(groupMembers.getMajorityQuorumSize()).isEqualTo(newMajority);
        }

        eventually(() -> assertThat(commitIndex(newNode)).isEqualTo(commitIndex(leader)));
        assertThat(role(newNode)).isEqualTo(FOLLOWER);
    }

    @Test(timeout = 300_000)
    public void when_learnerIsPromoted_then_newLearnerCanBeAdded() {
        int initialMemberCount = 3;
        group = LocalRaftGroup.start(initialMemberCount);

        RaftNode leader = group.waitUntilLeaderElected();
        leader.replicate(applyValue("val")).join();

        RaftNode newNode1 = group.createNewNode();

        Ordered<RaftGroupMembers> result1 = leader.changeMembership(newNode1.localEndpoint(), ADD_LEARNER, 0).join();

        Ordered<RaftGroupMembers> result2 = leader
                .changeMembership(newNode1.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, result1.getCommitIndex())
                .join();

        int newMajority = 1 + majority(initialMemberCount);

        RaftNode newNode2 = group.createNewNode();

        Ordered<RaftGroupMembers> result3 = leader
                .changeMembership(newNode2.localEndpoint(), ADD_LEARNER, result2.getCommitIndex()).join();

        for (RaftGroupMembers groupMembers : List.of(result3.getResult(), leader.committedMembers(),
                leader.effectiveMembers())) {
            assertThat(groupMembers.getMembers()).contains(newNode1.localEndpoint());
            assertThat(groupMembers.getMembers()).contains(newNode2.localEndpoint());
            assertThat(groupMembers.getVotingMembers()).contains(newNode1.localEndpoint());
            assertThat(groupMembers.getVotingMembers()).doesNotContain(newNode2.localEndpoint());
            assertThat(groupMembers.getMajorityQuorumSize()).isEqualTo(newMajority);
        }

        eventually(() -> assertThat(commitIndex(newNode2)).isEqualTo(commitIndex(leader)));
        assertThat(role(newNode2)).isEqualTo(LEARNER);
    }

    @Test(timeout = 300_000)
    public void when_secondLearnerIsPromoted_then_majorityDoesNotChange() {
        int initialMemberCount = 3;
        group = LocalRaftGroup.start(initialMemberCount);

        RaftNode leader = group.waitUntilLeaderElected();
        leader.replicate(applyValue("val")).join();

        RaftNode newNode1 = group.createNewNode();

        Ordered<RaftGroupMembers> result1 = leader.changeMembership(newNode1.localEndpoint(), ADD_LEARNER, 0).join();

        RaftNode newNode2 = group.createNewNode();

        Ordered<RaftGroupMembers> result2 = leader
                .changeMembership(newNode2.localEndpoint(), ADD_LEARNER, result1.getCommitIndex()).join();

        Ordered<RaftGroupMembers> result3 = leader
                .changeMembership(newNode1.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, result2.getCommitIndex())
                .join();

        Ordered<RaftGroupMembers> result4 = leader
                .changeMembership(newNode2.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, result3.getCommitIndex())
                .join();

        int newMajority = majority(initialMemberCount + 2);

        for (RaftGroupMembers groupMembers : List.of(result4.getResult(), leader.committedMembers(),
                leader.effectiveMembers())) {
            assertThat(groupMembers.getMembers()).contains(newNode1.localEndpoint());
            assertThat(groupMembers.getVotingMembers()).contains(newNode1.localEndpoint());
            assertThat(groupMembers.getMembers()).contains(newNode2.localEndpoint());
            assertThat(groupMembers.getVotingMembers()).contains(newNode2.localEndpoint());
            assertThat(groupMembers.getMajorityQuorumSize()).isEqualTo(newMajority);
        }

        eventually(() -> assertThat(commitIndex(newNode1)).isEqualTo(commitIndex(leader)));
        eventually(() -> assertThat(commitIndex(newNode2)).isEqualTo(commitIndex(leader)));
        assertThat(role(newNode1)).isEqualTo(FOLLOWER);
        assertThat(role(newNode2)).isEqualTo(FOLLOWER);
    }

    @Test(timeout = 300_000)
    public void when_followerIsPromoted_then_promotionFails() {
        int initialMemberCount = 3;
        group = LocalRaftGroup.start(initialMemberCount);

        RaftNode leader = group.waitUntilLeaderElected();

        try {
            leader.changeMembership(leader.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, 0).join();
            fail();
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_leaderFailsBeforeCommittingPromotion_then_followersElectNewLeaderViaGettingVoteFromLearner() {
        int initialMemberCount = 3;
        group = LocalRaftGroup.newBuilder(initialMemberCount).enableNewTermOperation().setConfig(
                RaftConfig.newBuilder().setLeaderHeartbeatTimeoutSecs(3).setLeaderHeartbeatPeriodSecs(1).build())
                .start();

        RaftNode leader = group.waitUntilLeaderElected();

        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> result = leader.changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(commitIndex(leader));
            }
        });

        for (RaftNode follower : followers) {
            group.dropMessagesTo(follower.localEndpoint(), leader.localEndpoint(),
                                 AppendEntriesSuccessResponse.class);
            group.dropMessagesTo(follower.localEndpoint(), leader.localEndpoint(),
                                 AppendEntriesFailureResponse.class);
        }

        group.dropMessagesTo(leader.localEndpoint(), newNode.localEndpoint(), AppendEntriesRequest.class);

        leader.changeMembership(newNode.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, result.getCommitIndex());

        eventually(() -> {
            assertThat(leader.effectiveMembers().getVotingMembers()).contains(newNode.localEndpoint());
            for (RaftNode follower : followers) {
                assertThat(follower.effectiveMembers().getVotingMembers()).contains(newNode.localEndpoint());
            }
        });

        group.terminateNode(leader.localEndpoint());

        group.waitUntilLeaderElected();

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(node.effectiveMembers().getLogIndex())
                        .isEqualTo(node.committedMembers().getLogIndex());
                assertThat(node.effectiveMembers().getVotingMembers()).contains(newNode.localEndpoint());
            }
        });

        assertThat(role(newNode)).isEqualTo(FOLLOWER);
    }

    @Test(timeout = 300_000)
    public void when_initialMemberIsRemoved_then_itCannotBeAddedAgain() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();
        RaftNode leavingFollower = group.anyNodeExcept(leader.localEndpoint());

        leader.replicate(applyValue("val")).join();

        Ordered<RaftGroupMembers> result = leader.changeMembership(leavingFollower.localEndpoint(), REMOVE_MEMBER, 0)
                .join();

        group.terminateNode(leavingFollower.localEndpoint());

        try {
            leader.changeMembership(leavingFollower.localEndpoint(), ADD_LEARNER, result.getCommitIndex()).join();
            fail();
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_followerLeaves_then_itIsRemovedFromTheGroupMembers() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode leavingFollower = followers.get(0);
        RaftNode stayingFollower = followers.get(1);

        leader.replicate(applyValue("val")).join();

        Ordered<RaftGroupMembers> result = leader.changeMembership(leavingFollower.localEndpoint(), REMOVE_MEMBER, 0)
                .join();

        assertThat(result.getResult().getMembers()).doesNotContain(leavingFollower.localEndpoint());

        eventually(() -> {
            for (RaftNode node : List.of(leader, stayingFollower)) {
                assertThat(effectiveGroupMembers(node).isKnownMember(leavingFollower.localEndpoint())).isFalse();
                assertThat(committedGroupMembers(node).isKnownMember(leavingFollower.localEndpoint())).isFalse();
            }
        });

        group.terminateNode(leavingFollower.localEndpoint());
    }

    @Test(timeout = 300_000)
    public void when_newNodeJoinsAfterAnotherNodeLeaves_then_itAppendsMissingEntries() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();
        leader.replicate(applyValue("val")).join();

        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode leavingFollower = followers.get(0);
        RaftNode stayingFollower = followers.get(1);

        long newMembersCommitIndex = leader.changeMembership(leavingFollower.localEndpoint(), REMOVE_MEMBER, 0)
                .join().getCommitIndex();

        RaftNode newNode = group.createNewNode();

        leader.changeMembership(newNode.localEndpoint(), ADD_LEARNER, newMembersCommitIndex).join();

        long commitIndex = commitIndex(leader);
        eventually(() -> assertThat(commitIndex(newNode)).isEqualTo(commitIndex));

        RaftGroupMembersState effectiveGroupMembers = effectiveGroupMembers(leader);
        eventually(() -> {
            for (RaftNode node : List.of(leader, stayingFollower, newNode)) {
                assertThat(status(node)).isEqualTo(ACTIVE);
                assertThat(effectiveGroupMembers(node).getMembers()).isEqualTo(effectiveGroupMembers.getMembers());
                assertThat(effectiveGroupMembers(node).getLogIndex()).isEqualTo(effectiveGroupMembers.getLogIndex());
                assertThat(committedGroupMembers(node).getMembers()).isEqualTo(effectiveGroupMembers.getMembers());
                assertThat(committedGroupMembers(node).getLogIndex()).isEqualTo(effectiveGroupMembers.getLogIndex());
                assertThat(effectiveGroupMembers(node).isKnownMember(leavingFollower.localEndpoint())).isFalse();
                assertThat(committedGroupMembers(node).isKnownMember(leavingFollower.localEndpoint())).isFalse();
            }
        });

        SimpleStateMachine stateMachine = group.stateMachine(newNode.localEndpoint());
        assertThat(stateMachine.size()).isEqualTo(1);
        assertThat(stateMachine.valueSet()).contains("val");
    }

    @Test(timeout = 300_000)
    public void when_newNodeJoinsAfterAnotherNodeLeavesAndSnapshotIsTaken_then_itAppendsMissingEntries() {
        int commitCountToTakeSnapshot = 10;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(commitCountToTakeSnapshot).build();
        group = LocalRaftGroup.start(3, config);

        RaftNode leader = group.waitUntilLeaderElected();

        leader.replicate(applyValue("val")).join();

        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode leavingFollower = followers.get(0);
        RaftNode stayingFollower = followers.get(1);

        long newMembersIndex = leader.changeMembership(leavingFollower.localEndpoint(), REMOVE_MEMBER, 0).join()
                                     .getCommitIndex();

        for (int i = 0; i < commitCountToTakeSnapshot; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> assertThat(snapshotEntry(leader).getIndex()).isGreaterThan(0));

        RaftNode newNode = group.createNewNode();

        leader.changeMembership(newNode.localEndpoint(), ADD_LEARNER, newMembersIndex).join();

        long commitIndex = commitIndex(leader);
        eventually(() -> assertThat(commitIndex(newNode)).isEqualTo(commitIndex));

        RaftGroupMembersState effectiveGroupMembers = effectiveGroupMembers(leader);
        eventually(() -> {
            for (RaftNode node : List.of(leader, stayingFollower, newNode)) {
                assertThat(status(node)).isEqualTo(ACTIVE);
                assertThat(effectiveGroupMembers(node).getMembers()).isEqualTo(effectiveGroupMembers.getMembers());
                assertThat(effectiveGroupMembers(node).getLogIndex()).isEqualTo(effectiveGroupMembers.getLogIndex());
                assertThat(committedGroupMembers(node).getMembers()).isEqualTo(effectiveGroupMembers.getMembers());
                assertThat(committedGroupMembers(node).getLogIndex()).isEqualTo(effectiveGroupMembers.getLogIndex());
                assertThat(effectiveGroupMembers(node).isKnownMember(leavingFollower.localEndpoint())).isFalse();
                assertThat(committedGroupMembers(node).isKnownMember(leavingFollower.localEndpoint())).isFalse();
            }
        });

        SimpleStateMachine stateMachine = group.stateMachine(newNode.localEndpoint());
        assertThat(stateMachine.size()).isEqualTo(commitCountToTakeSnapshot + 1);
        assertThat(stateMachine.valueSet()).contains("val");
        for (int i = 0; i < commitCountToTakeSnapshot; i++) {
            assertThat(stateMachine.valueSet()).contains("val" + i);
        }
    }

    @Test(timeout = 300_000)
    public void when_leaderLeaves_then_itIsRemovedFromTheGroupMembers() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();

        leader.replicate(applyValue("val")).join();
        leader.changeMembership(leader.localEndpoint(), REMOVE_MEMBER, 0).join();

        assertThat(leader.status()).isEqualTo(TERMINATED);

        eventually(() -> {
            for (RaftNode node : group.<RaftNode>nodesExcept(leader.localEndpoint())) {
                assertThat(effectiveGroupMembers(node).isKnownMember(leader.localEndpoint())).isFalse();
                assertThat(committedGroupMembers(node).isKnownMember(leader.localEndpoint())).isFalse();
            }
        });

        SimpleStateMachine stateMachine = group.stateMachine(leader.localEndpoint());
        assertThat(stateMachine.isTerminated()).isTrue();
    }

    @Test(timeout = 300_000)
    public void when_leaderLeaves_then_itCannotVoteForCommitOfMemberChange() {
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).build();
        group = LocalRaftGroup.start(3, config);

        RaftNode leader = group.waitUntilLeaderElected();
        RaftNode follower = group.anyNodeExcept(leader.localEndpoint());

        group.dropMessagesTo(follower.localEndpoint(), leader.localEndpoint(),
                             AppendEntriesSuccessResponse.class);
        leader.replicate(applyValue("val")).join();

        leader.changeMembership(leader.localEndpoint(), REMOVE_MEMBER, 0);

        allTheTime(() -> assertThat(commitIndex(leader)).isEqualTo(1), 5);
    }

    @Test(timeout = 300_000)
    public void when_leaderLeaves_then_followersElectNewLeader() {
        group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);

        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());

        leader.replicate(applyValue("val")).join();
        leader.changeMembership(leader.localEndpoint(), REMOVE_MEMBER, 0).join();

        eventually(() -> {
            for (RaftNode node : followers) {
                assertThat(effectiveGroupMembers(node).isKnownMember(leader.localEndpoint())).isFalse();
                assertThat(committedGroupMembers(node).isKnownMember(leader.localEndpoint())).isFalse();
            }
        });

        group.terminateNode(leader.localEndpoint());

        eventually(() -> {
            for (RaftNode node : followers) {
                RaftEndpoint newLeader = node.leaderEndpoint();
                assertThat(newLeader).isNotNull().isNotEqualTo(leader.localEndpoint());
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_membershipChangeRequestIsMadeWithInvalidType_then_membershipChangeFails() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();
        leader.replicate(applyValue("val")).join();

        try {
            leader.changeMembership(leader.localEndpoint(), null, 0);
            fail();
        } catch (NullPointerException ignored) {
        }
    }

    @Test(timeout = 300_000)
    public void when_nonExistingEndpointIsRemoved_then_membershipChangeFails() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();
        RaftNode leavingFollower = group.anyNodeExcept(leader.localEndpoint());

        leader.replicate(applyValue("val")).join();
        long newMembersIndex = leader.changeMembership(leavingFollower.localEndpoint(), REMOVE_MEMBER, 0).join()
                                     .getCommitIndex();

        try {
            leader.changeMembership(leavingFollower.localEndpoint(), REMOVE_MEMBER, newMembersIndex).join();
            fail();
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_existingEndpointIsAdded_then_membershipChangeFails() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();

        leader.replicate(applyValue("val")).join();

        try {
            leader.changeMembership(leader.localEndpoint(), ADD_LEARNER, 0).join();
            fail();
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_thereIsNoCommitInTheCurrentTerm_then_cannotMakeMemberChange() {
        // https://groups.google.com/forum/#!msg/raft-dev/t4xj6dJTP6E/d2D9LrWRza8J

        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();

        try {
            leader.changeMembership(leader.localEndpoint(), REMOVE_MEMBER, 0).join();
            fail();
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(CannotReplicateException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_appendNopEntryOnLeaderElection_then_canMakeMemberChangeAfterNopEntryCommitted() {
        // https://groups.google.com/forum/#!msg/raft-dev/t4xj6dJTP6E/d2D9LrWRza8J

        group = LocalRaftGroup.newBuilder(3).enableNewTermOperation().start();

        RaftNode leader = group.waitUntilLeaderElected();

        eventually(() -> {
            // may fail until nop-entry is committed
            try {
                leader.changeMembership(leader.localEndpoint(), REMOVE_MEMBER, 0).join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof CannotReplicateException) {
                    fail();
                }

                throw e;
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_newJoiningNodeFirstReceivesSnapshot_then_itInstallsSnapshot() {
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(5).build();
        group = LocalRaftGroup.start(3, config);

        RaftNode leader = group.waitUntilLeaderElected();
        for (int i = 0; i < 4; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        RaftNode newNode = group.createNewNode();

        group.dropMessagesTo(leader.localEndpoint(), newNode.localEndpoint(), AppendEntriesRequest.class);

        leader.changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();

        eventually(() -> assertThat(snapshotEntry(leader).getIndex()).isGreaterThan(0));

        group.resetAllRulesFrom(leader.localEndpoint());

        eventually(() -> {
            assertThat(commitIndex(newNode)).isEqualTo(commitIndex(leader));
            assertThat(effectiveGroupMembers(newNode).getMembers())
                    .isEqualTo(effectiveGroupMembers(leader).getMembers());
            assertThat(committedGroupMembers(newNode).getMembers())
                    .isEqualTo(effectiveGroupMembers(leader).getMembers());
            SimpleStateMachine stateMachine = group.stateMachine(newNode.localEndpoint());
            assertThat(stateMachine.size()).isEqualTo(4);
        });
    }

    @Test(timeout = 300_000)
    public void when_leaderFailsWhileLeavingRaftGroup_othersCommitTheMembershipChange() {
        group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);

        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());

        leader.replicate(applyValue("val")).join();

        for (RaftNode follower : followers) {
            group.dropMessagesTo(follower.localEndpoint(), leader.localEndpoint(),
                                 AppendEntriesSuccessResponse.class);
            group.dropMessagesTo(follower.localEndpoint(), leader.localEndpoint(),
                                 AppendEntriesFailureResponse.class);
        }

        leader.changeMembership(leader.localEndpoint(), REMOVE_MEMBER, 0);

        eventually(() -> {
            for (RaftNode follower : followers) {
                assertThat(lastLogOrSnapshotEntry(follower).getIndex()).isEqualTo(2);
            }
        });

        group.terminateNode(leader.localEndpoint());

        eventually(() -> {
            for (RaftNode follower : followers) {
                RaftEndpoint newLeaderEndpoint = follower.leaderEndpoint();
                assertThat(newLeaderEndpoint).isNotNull().isNotEqualTo(leader.localEndpoint());
            }
        });

        RaftNode newLeader = group.node(followers.get(0).leaderEndpoint());
        newLeader.replicate(applyValue("val2"));

        eventually(() -> {
            for (RaftNode follower : followers) {
                assertThat(committedGroupMembers(follower).isKnownMember(leader.localEndpoint())).isFalse();
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_followerAppendsMultipleMembershipChangesAtOnce_then_itCommitsThemCorrectly() {
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).build();
        group = LocalRaftGroup.start(5, config);

        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());

        leader.replicate(applyValue("val")).join();

        eventually(() -> {
            for (RaftNode follower : followers) {
                assertThat(commitIndex(follower)).isEqualTo(1);
            }
        });

        RaftNode slowFollower = followers.get(0);

        for (RaftNode follower : followers) {
            if (follower != slowFollower) {
                group.dropMessagesTo(follower.localEndpoint(), follower.leaderEndpoint(),
                                     AppendEntriesSuccessResponse.class);
                group.dropMessagesTo(follower.localEndpoint(), follower.leaderEndpoint(),
                                     AppendEntriesFailureResponse.class);
            }
        }

        RaftNode newNode1 = group.createNewNode();
        group.dropMessagesTo(leader.localEndpoint(), newNode1.localEndpoint(), AppendEntriesRequest.class);
        CompletableFuture<Ordered<RaftGroupMembers>> f1 = leader.changeMembership(newNode1.localEndpoint(),
                ADD_LEARNER, 0);

        eventually(() -> {
            for (RaftNode follower : followers) {
                assertThat(lastLogOrSnapshotEntry(follower).getIndex()).isEqualTo(2);
            }
        });

        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);

        for (RaftNode follower : followers) {
            if (follower != slowFollower) {
                group.allowAllMessagesTo(follower.localEndpoint(), leader.leaderEndpoint());
            }
        }

        long newMembersIndex = f1.join().getCommitIndex();
        eventually(() -> {
            for (RaftNode follower : followers) {
                if (follower != slowFollower) {
                    assertThat(committedGroupMembers(follower).getMembers()).hasSize(6);
                } else {
                    assertThat(committedGroupMembers(follower).getMembers()).hasSize(5);
                    assertThat(effectiveGroupMembers(follower).getMembers()).hasSize(6);
                }
            }
        });

        RaftNode newNode2 = group.createNewNode();
        leader.changeMembership(newNode2.localEndpoint(), ADD_LEARNER, newMembersIndex).join();

        group.allowAllMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint());
        group.allowAllMessagesTo(slowFollower.localEndpoint(), leader.localEndpoint());
        group.allowAllMessagesTo(leader.localEndpoint(), newNode1.localEndpoint());

        RaftGroupMembersState leaderCommittedGroupMembers = committedGroupMembers(leader);
        eventually(() -> {
            assertThat(committedGroupMembers(slowFollower).getLogIndex())
                    .isEqualTo(leaderCommittedGroupMembers.getLogIndex());
            assertThat(committedGroupMembers(newNode1).getLogIndex())
                    .isEqualTo(leaderCommittedGroupMembers.getLogIndex());
            assertThat(committedGroupMembers(newNode2).getLogIndex())
                    .isEqualTo(leaderCommittedGroupMembers.getLogIndex());
        });
    }

    @Test(timeout = 300_000)
    public void when_leaderIsSteppingDown_then_itDoesNotAcceptNewAppends() {
        group = LocalRaftGroup.newBuilder(3).enableNewTermOperation().start();

        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        group.dropMessagesTo(leader.localEndpoint(), followers.get(0).localEndpoint(),
                             AppendEntriesRequest.class);
        group.dropMessagesTo(leader.localEndpoint(), followers.get(1).localEndpoint(),
                             AppendEntriesRequest.class);

        CompletableFuture<Ordered<RaftGroupMembers>> f1 = leader.changeMembership(leader.localEndpoint(),
                REMOVE_MEMBER, 0);
        CompletableFuture<Ordered<Object>> f2 = leader.replicate(applyValue("1"));

        assertThat(f1).isNotDone();
        eventually(() -> assertThat(f2).isDone());

        try {
            f2.join();
            fail();
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(CannotReplicateException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_replicatedMembershipChangeIsReverted_then_itCanBeCommittedOnSecondReplicate() {
        group = LocalRaftGroup.newBuilder(3).setConfig(TEST_RAFT_CONFIG).enableNewTermOperation().start();

        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());

        leader.replicate(applyValue("val1")).join();
        long oldLeaderCommitIndexBeforeMembershipChange = commitIndex(leader);

        eventually(() -> {
            for (RaftNode follower : followers) {
                assertThat(commitIndex(follower)).isEqualTo(oldLeaderCommitIndexBeforeMembershipChange);
            }
        });

        group.dropMessagesTo(leader.localEndpoint(), followers.get(0).localEndpoint(),
                             AppendEntriesRequest.class);
        group.dropMessagesTo(leader.localEndpoint(), followers.get(1).localEndpoint(),
                             AppendEntriesRequest.class);

        RaftNode newNode = group.createNewNode();

        leader.changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0);

        eventually(() -> {
            long leaderLastLogIndex = lastLogOrSnapshotEntry(leader).getIndex();
            assertThat(leaderLastLogIndex).isGreaterThan(oldLeaderCommitIndexBeforeMembershipChange)
                    .isEqualTo(lastLogOrSnapshotEntry(newNode).getIndex());
        });

        group.dropMessagesToAll(newNode.localEndpoint(), PreVoteRequest.class);
        group.dropMessagesToAll(newNode.localEndpoint(), VoteRequest.class);

        group.terminateNode(leader.localEndpoint());

        eventually(() -> {
            RaftEndpoint l0 = followers.get(0).leaderEndpoint();
            RaftEndpoint l1 = followers.get(1).leaderEndpoint();
            assertThat(l0).isNotNull().isNotEqualTo(leader.localEndpoint());
            assertThat(l1).isNotNull().isNotEqualTo(leader.localEndpoint()).isEqualTo(l0);
        });

        RaftNode newLeader = group.node(followers.get(0).leaderEndpoint());
        newLeader.replicate(applyValue("val1")).join();
        newLeader.changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();

        eventually(() -> {
            assertThat(commitIndex(newNode)).isEqualTo(commitIndex(newLeader));
            assertThat(committedGroupMembers(newNode).getLogIndex())
                    .isEqualTo(committedGroupMembers(newLeader).getLogIndex());
        });
    }

    @Test(timeout = 300_000)
    public void when_raftGroupIsExtendedToEvenNumberOfServers_then_logReplicationQuorumSizeCanBeDecreased() {
        group = LocalRaftGroup.newBuilder(3).setConfig(TEST_RAFT_CONFIG).enableNewTermOperation().start();

        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode newNode = group.createNewNode();
        Ordered<RaftGroupMembers> result = leader.changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();
        leader.changeMembership(newNode.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, result.getCommitIndex()).join();

        eventually(() -> assertThat(newNode.leaderEndpoint()).isEqualTo(leader.localEndpoint()));

        for (RaftNode follower : followers) {
            group.dropMessagesTo(leader.localEndpoint(), follower.localEndpoint(), AppendEntriesRequest.class);
        }

        leader.replicate(applyValue("val")).join();
    }

    @Test(timeout = 300_000)
    public void when_raftGroupIsExtendingToEvenNumberOfServers_then_membershipChangeCannotBeCommittedWithoutMajority() {
        group = LocalRaftGroup.newBuilder(3).setConfig(TEST_RAFT_CONFIG).enableNewTermOperation().start();

        RaftNode leader = group.waitUntilLeaderElected();
        for (RaftNode follower : group.nodesExcept(leader.localEndpoint())) {
            group.dropMessagesTo(leader.localEndpoint(), follower.localEndpoint(), AppendEntriesRequest.class);
        }

        RaftNode newNode = group.createNewNode();
        try {
            leader.changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();
            fail();
        } catch (CompletionException e) {
            assertThat(e).satisfiesAnyOf(e2 -> assertThat(e2).hasCauseInstanceOf(IndeterminateStateException.class),
                    e2 -> assertThat(e2).hasCauseInstanceOf(NotLeaderException.class));

        }
    }

    @Test(timeout = 300_000)
    public void when_thereIsSingleVotingMember_then_learnerCanBeRemoved() {
        group = LocalRaftGroup.newBuilder(2, 1).setConfig(TEST_RAFT_CONFIG).enableNewTermOperation().start();
        RaftNode leader = group.waitUntilLeaderElected();
        RaftNode learner = group.anyNodeExcept(leader.localEndpoint());

        Ordered<RaftGroupMembers> result = leader.changeMembership(learner.localEndpoint(), REMOVE_MEMBER, 0).join();

        assertThat(result.getResult().getMembers()).contains(leader.localEndpoint());
        assertThat(result.getResult().getMembers()).doesNotContain(learner.localEndpoint());
        assertThat(result.getResult().getVotingMembers()).contains(leader.localEndpoint());
        assertThat(result.getResult().getVotingMembers()).doesNotContain(learner.localEndpoint());
    }

    @Test(timeout = 300_000)
    public void when_thereIsSingleVotingMember_then_votingMemberCannotBeRemoved() {
        group = LocalRaftGroup.newBuilder(2, 1).setConfig(TEST_RAFT_CONFIG).enableNewTermOperation().start();
        RaftNode leader = group.waitUntilLeaderElected();

        try {
            leader.changeMembership(leader.localEndpoint(), REMOVE_MEMBER, 0).join();
            fail();
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_leaderCrashesWhileLeavingRaftGroup_then_remainingVotingMemberCommitsMembershipChange() {
        group = LocalRaftGroup.newBuilder(3, 2).setConfig(TEST_RAFT_CONFIG).enableNewTermOperation().start();
        RaftNode leader = group.waitUntilLeaderElected();
        RaftNode follower = null;
        for (RaftNode node : group.<RaftNode>nodesExcept(leader.localEndpoint())) {
            if (role(node) == FOLLOWER) {
                follower = node;
                break;
            }
        }
        assertNotNull(follower);

        group.dropMessagesTo(follower.localEndpoint(), leader.localEndpoint(),
                             AppendEntriesSuccessResponse.class);

        leader.changeMembership(leader.localEndpoint(), REMOVE_MEMBER, 0);

        RaftNode node = follower;
        eventually(() -> assertThat(effectiveGroupMembers(node).getLogIndex()).isGreaterThan(0));

        leader.terminate();

        eventually(() -> assertThat(node.leaderEndpoint()).isEqualTo(node.localEndpoint()));

        assertThat(committedGroupMembers(follower).getMembers()).doesNotContain(leader.localEndpoint());
    }

}
