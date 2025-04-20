/*
 * Copyright (c) 2020, MicroRaft.
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
import static io.microraft.QueryPolicy.EVENTUAL_CONSISTENCY;
import static io.microraft.impl.local.LocalRaftGroup.IN_MEMORY_RAFT_STATE_STORE_FACTORY;
import static io.microraft.impl.local.SimpleStateMachine.applyValue;
import static io.microraft.impl.local.SimpleStateMachine.queryLastValue;
import static io.microraft.test.util.AssertionUtils.allTheTime;
import static io.microraft.test.util.AssertionUtils.eventually;
import static io.microraft.test.util.RaftTestUtils.commitIndex;
import static io.microraft.test.util.RaftTestUtils.effectiveGroupMembers;
import static io.microraft.test.util.RaftTestUtils.lastLogOrSnapshotEntry;
import static io.microraft.test.util.RaftTestUtils.raftStore;
import static io.microraft.test.util.RaftTestUtils.restoredState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.After;
import org.junit.Test;

import io.microraft.Ordered;
import io.microraft.QueryPolicy;
import io.microraft.RaftConfig;
import io.microraft.RaftNode;
import io.microraft.RaftRole;
import io.microraft.impl.local.LocalRaftGroup;
import io.microraft.impl.local.SimpleStateMachine;
import io.microraft.model.log.BaseLogEntry;
import io.microraft.model.message.AppendEntriesRequest;
import io.microraft.model.message.AppendEntriesSuccessResponse;
import io.microraft.persistence.RaftStore;
import io.microraft.persistence.RestoredRaftState;
import io.microraft.report.RaftGroupMembers;
import io.microraft.report.RaftNodeReport;
import io.microraft.test.util.BaseTest;

public class SingletonRaftGroupTest extends BaseTest {

    private LocalRaftGroup group;

    @After
    public void destroy() {
        if (group != null) {
            group.destroy();
        }
    }

    @Test(timeout = 300_000)
    public void when_singletonRaftGroupIsStarted_then_leaderIsElected() {
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(3)
                .build();
        group = LocalRaftGroup.start(1, config);

        RaftNode leader = group.waitUntilLeaderElected();
        assertThat(leader).isNotNull();
        assertThat(leader.leaderEndpoint()).isEqualTo(leader.localEndpoint());
        assertThat(leader.termState().term()).isGreaterThan(0);
        List<RaftNodeReport> reports = group.reports(leader.localEndpoint());
        assertThat(reports).hasSize(3);
        assertThat(reports.get(0).role()).isEqualTo(RaftRole.FOLLOWER);
        assertThat(reports.get(0).leaderHeartbeatTimestamp()).isEmpty();
        assertThat(reports.get(0).quorumHeartbeatTimestamp()).isEmpty();
        assertThat(reports.get(0).heartbeatTimestamps()).isEmpty();
        assertThat(reports.get(1).role()).isEqualTo(RaftRole.CANDIDATE);
        assertThat(reports.get(1).leaderHeartbeatTimestamp()).isEmpty();
        assertThat(reports.get(1).quorumHeartbeatTimestamp()).isEmpty();
        assertThat(reports.get(1).heartbeatTimestamps()).isEmpty();
        assertThat(reports.get(2).role()).isEqualTo(RaftRole.LEADER);
        assertThat(reports.get(2).leaderHeartbeatTimestamp()).isEmpty();
        assertThat(reports.get(2).quorumHeartbeatTimestamp()).isPresent();
        assertThat(reports.get(2).heartbeatTimestamps()).isEmpty();

        Optional<Long> quorumTimestamp = reports.get(2).quorumHeartbeatTimestamp();
        assertThat(quorumTimestamp.get()).isGreaterThan(0).isLessThan(Long.MAX_VALUE);

        allTheTime(() -> assertThat(leader.leaderEndpoint()).isEqualTo(leader.localEndpoint()),
                2 * config.leaderHeartbeatTimeoutSecs());
    }

    @Test(timeout = 300_000)
    public void when_singletonRaftGroupIsStarted_then_logEntryIsCommitted() {
        group = LocalRaftGroup.start(1);

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal = "val";
        Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();

        Object val = group.stateMachine(leader.localEndpoint()).get(result.getCommitIndex());
        assertThat(val).isEqualTo(expectedVal);

        Optional<Long> quorumTimestamp = leader.report().join().getResult().quorumHeartbeatTimestamp();
        assertThat(quorumTimestamp.get()).isGreaterThan(0).isLessThan(Long.MAX_VALUE);
    }

    @Test(timeout = 300_000)
    public void when_singletonRaftGroupIsStarted_then_multipleLogEntriesAreCommitted() {
        group = LocalRaftGroup.start(1);

        RaftNode leader = group.waitUntilLeaderElected();
        List<Entry<CompletableFuture<Ordered<Object>>, String>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String expectedVal = "val" + i;
            CompletableFuture<Ordered<Object>> future = leader.replicate(applyValue(expectedVal));
            futures.add(new SimpleEntry<>(future, expectedVal));
        }

        SimpleStateMachine stateMachine = group.stateMachine(leader.localEndpoint());

        for (Entry<CompletableFuture<Ordered<Object>>, String> e : futures) {
            Ordered<Object> result = e.getKey().join();
            Object val = stateMachine.get(result.getCommitIndex());
            assertThat(val).isEqualTo(e.getValue());
        }
    }

    @Test(timeout = 300_000)
    public void when_singletonClusterIsStartedWithRaftStore_then_logEntryIsCommitted() {
        group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal = "val";
        Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();

        Object val = group.stateMachine(leader.localEndpoint()).get(result.getCommitIndex());
        assertThat(val).isEqualTo(expectedVal);
    }

    @Test(timeout = 300_000)
    public void when_singletonRaftGroupIsStartedWithRaftStore_then_multipleLogEntriesAreCommitted() {
        group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        List<Entry<CompletableFuture<Ordered<Object>>, String>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String expectedVal = "val" + i;
            CompletableFuture<Ordered<Object>> future = leader.replicate(applyValue(expectedVal));
            futures.add(new SimpleEntry<>(future, expectedVal));
        }

        SimpleStateMachine stateMachine = group.stateMachine(leader.localEndpoint());

        for (Entry<CompletableFuture<Ordered<Object>>, String> e : futures) {
            Ordered<Object> result = e.getKey().join();
            Object val = stateMachine.get(result.getCommitIndex());
            assertThat(val).isEqualTo(e.getValue());
        }
    }

    @Test(timeout = 300_000)
    public void when_learnerIsAddedToSingletonRaftGroup_then_quorumDoesNotChange() {
        group = LocalRaftGroup.start(1);

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal = "val";
        Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();

        assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult.getResult().getMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getVotingMembers()).doesNotContain(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(1);

        eventually(() -> {
            Object val = group.stateMachine(newNode.localEndpoint()).get(result.getCommitIndex());
            assertThat(val).isEqualTo(expectedVal);
        });
    }

    @Test(timeout = 300_000)
    public void when_followerIsAddedToSingletonRaftGroup_then_quorumIsUpdated() {
        group = LocalRaftGroup.start(1);

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal = "val";
        Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, 0).join();

        assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult.getResult().getMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getVotingMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(2);

        eventually(() -> {
            Object val = group.stateMachine(newNode.localEndpoint()).get(result.getCommitIndex());
            assertThat(val).isEqualTo(expectedVal);
        });
    }

    @Test(timeout = 300_000)
    public void when_learnerIsPromotedAfterAddedToSingletonRaftGroup_then_quorumIsUpdated() {
        group = LocalRaftGroup.start(1);

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal = "val";
        Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult1 = leader
                .changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();

        Ordered<RaftGroupMembers> membershipChangeResult2 = leader.changeMembership(newNode.localEndpoint(),
                ADD_OR_PROMOTE_TO_FOLLOWER, membershipChangeResult1.getCommitIndex()).join();

        assertThat(membershipChangeResult2.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult2.getResult().getMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult2.getResult().getVotingMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult2.getResult().getMajorityQuorumSize()).isEqualTo(2);

        eventually(() -> {
            Object val = group.stateMachine(newNode.localEndpoint()).get(result.getCommitIndex());
            assertThat(val).isEqualTo(expectedVal);
        });
    }

    @Test(timeout = 300_000)
    public void when_learnerIsAddedToSingletonRaftGroupWithRaftStore_then_quorumDoesNotChange() {
        group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal = "val";
        Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();

        assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult.getResult().getMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getVotingMembers()).doesNotContain(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(1);

        eventually(() -> {
            Object val = group.stateMachine(newNode.localEndpoint()).get(result.getCommitIndex());
            assertThat(val).isEqualTo(expectedVal);
        });
    }

    @Test(timeout = 300_000)
    public void when_followerIsAddedToSingletonRaftGroupWithRaftStore_then_quorumIsUpdated() {
        group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal = "val";
        Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, 0).join();

        assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult.getResult().getMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getVotingMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(2);

        eventually(() -> {
            Object val = group.stateMachine(newNode.localEndpoint()).get(result.getCommitIndex());
            assertThat(val).isEqualTo(expectedVal);
        });
    }

    @Test(timeout = 300_000)
    public void when_learnerIsPromotedAfterAddedToSingletonRaftGroupWithRaftStore_then_quorumIncreases() {
        group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal = "val";
        Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult1 = leader
                .changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();

        Ordered<RaftGroupMembers> membershipChangeResult2 = leader.changeMembership(newNode.localEndpoint(),
                ADD_OR_PROMOTE_TO_FOLLOWER, membershipChangeResult1.getCommitIndex()).join();

        assertThat(membershipChangeResult2.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult2.getResult().getMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult2.getResult().getVotingMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult2.getResult().getMajorityQuorumSize()).isEqualTo(2);

        eventually(() -> {
            Object val = group.stateMachine(newNode.localEndpoint()).get(result.getCommitIndex());
            assertThat(val).isEqualTo(expectedVal);
        });
    }

    @Test(timeout = 300_000)
    public void when_learnerIsAddedToSingletonRaftGroup_then_newLogEntryIsCommitted() {
        group = LocalRaftGroup.start(1);

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal1 = "val1";
        Ordered<Object> result1 = leader.replicate(applyValue(expectedVal1)).join();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();

        assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult.getResult().getMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getVotingMembers()).doesNotContain(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(1);

        String expectedVal2 = "val2";
        Ordered<Object> result2 = leader.replicate(applyValue(expectedVal2)).join();

        eventually(() -> {
            Object val1 = group.stateMachine(newNode.localEndpoint()).get(result1.getCommitIndex());
            assertThat(val1).isEqualTo(expectedVal1);
            Object val2 = group.stateMachine(newNode.localEndpoint()).get(result2.getCommitIndex());
            assertThat(val2).isEqualTo(expectedVal2);
        });
    }

    @Test(timeout = 300_000)
    public void when_followerIsAddedToSingletonRaftGroup_then_newLogEntryIsCommitted() {
        group = LocalRaftGroup.start(1);

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal1 = "val1";
        Ordered<Object> result1 = leader.replicate(applyValue(expectedVal1)).join();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, 0).join();

        assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult.getResult().getMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getVotingMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(2);

        String expectedVal2 = "val2";
        Ordered<Object> result2 = leader.replicate(applyValue(expectedVal2)).join();

        eventually(() -> {
            Object val1 = group.stateMachine(newNode.localEndpoint()).get(result1.getCommitIndex());
            assertThat(val1).isEqualTo(expectedVal1);
            Object val2 = group.stateMachine(newNode.localEndpoint()).get(result2.getCommitIndex());
            assertThat(val2).isEqualTo(expectedVal2);
        });
    }

    @Test(timeout = 300_000)
    public void when_learnerIsPromotedAfterAddedToSingletonRaftGroup_then_newLogEntryIsCommitted() {
        group = LocalRaftGroup.start(1);

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal1 = "val1";
        Ordered<Object> result1 = leader.replicate(applyValue(expectedVal1)).join();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult1 = leader
                .changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();
        Ordered<RaftGroupMembers> membershipChangeResult2 = leader.changeMembership(newNode.localEndpoint(),
                ADD_OR_PROMOTE_TO_FOLLOWER, membershipChangeResult1.getCommitIndex()).join();

        assertThat(membershipChangeResult2.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult2.getResult().getMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult2.getResult().getVotingMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult2.getResult().getMajorityQuorumSize()).isEqualTo(2);

        String expectedVal2 = "val2";
        Ordered<Object> result2 = leader.replicate(applyValue(expectedVal2)).join();

        eventually(() -> {
            Object val1 = group.stateMachine(newNode.localEndpoint()).get(result1.getCommitIndex());
            assertThat(val1).isEqualTo(expectedVal1);
            Object val2 = group.stateMachine(newNode.localEndpoint()).get(result2.getCommitIndex());
            assertThat(val2).isEqualTo(expectedVal2);
        });
    }

    @Test(timeout = 300_000)
    public void when_learnerIsAddedToSingletonRaftGroupWithRaftStore_then_newLogEntryIsCommitted() {
        group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal1 = "val1";
        Ordered<Object> result1 = leader.replicate(applyValue(expectedVal1)).join();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();

        assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult.getResult().getMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getVotingMembers()).doesNotContain(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(1);

        String expectedVal2 = "val2";
        Ordered<Object> result2 = leader.replicate(applyValue(expectedVal2)).join();

        eventually(() -> {
            Object val1 = group.stateMachine(newNode.localEndpoint()).get(result1.getCommitIndex());
            assertThat(val1).isEqualTo(expectedVal1);
            Object val2 = group.stateMachine(newNode.localEndpoint()).get(result2.getCommitIndex());
            assertThat(val2).isEqualTo(expectedVal2);
        });
    }

    @Test(timeout = 300_000)
    public void when_followerIsAddedToSingletonRaftGroupWithRaftStore_then_newLogEntryIsCommitted() {
        group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal1 = "val1";
        Ordered<Object> result1 = leader.replicate(applyValue(expectedVal1)).join();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, 0).join();

        assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult.getResult().getMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getVotingMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(2);

        String expectedVal2 = "val2";
        Ordered<Object> result2 = leader.replicate(applyValue(expectedVal2)).join();

        eventually(() -> {
            Object val1 = group.stateMachine(newNode.localEndpoint()).get(result1.getCommitIndex());
            assertThat(val1).isEqualTo(expectedVal1);
            Object val2 = group.stateMachine(newNode.localEndpoint()).get(result2.getCommitIndex());
            assertThat(val2).isEqualTo(expectedVal2);
        });
    }

    @Test(timeout = 300_000)
    public void when_learnerIsPromotedAfterAddedToSingletonRaftGroupWithRaftStore_then_newLogEntryIsCommitted() {
        group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal1 = "val1";
        Ordered<Object> result1 = leader.replicate(applyValue(expectedVal1)).join();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult1 = leader
                .changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();
        Ordered<RaftGroupMembers> membershipChangeResult2 = leader.changeMembership(newNode.localEndpoint(),
                ADD_OR_PROMOTE_TO_FOLLOWER, membershipChangeResult1.getCommitIndex()).join();

        assertThat(membershipChangeResult2.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult2.getResult().getMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult2.getResult().getVotingMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult2.getResult().getMajorityQuorumSize()).isEqualTo(2);

        String expectedVal2 = "val2";
        Ordered<Object> result2 = leader.replicate(applyValue(expectedVal2)).join();

        eventually(() -> {
            Object val1 = group.stateMachine(newNode.localEndpoint()).get(result1.getCommitIndex());
            assertThat(val1).isEqualTo(expectedVal1);
            Object val2 = group.stateMachine(newNode.localEndpoint()).get(result2.getCommitIndex());
            assertThat(val2).isEqualTo(expectedVal2);
        });
    }

    @Test(timeout = 300_000)
    public void when_learnerIsAddedToSingletonRaftGroup_then_newLogEntryCanBeCommittedOnlyWithLeader() {
        group = LocalRaftGroup.start(1);

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal1 = "val1";
        leader.replicate(applyValue(expectedVal1)).join();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();

        assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult.getResult().getMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getVotingMembers()).doesNotContain(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(1);

        group.dropMessagesTo(leader.localEndpoint(), newNode.localEndpoint(), AppendEntriesRequest.class);

        String expectedVal2 = "val2";
        Ordered<Object> result2 = leader.replicate(applyValue(expectedVal2)).join();

        group.allowMessagesTo(leader.localEndpoint(), newNode.localEndpoint(), AppendEntriesRequest.class);

        eventually(() -> {
            assertThat(commitIndex(newNode)).isEqualTo(result2.getCommitIndex());
            assertThat(group.stateMachine(newNode.localEndpoint()).get(result2.getCommitIndex()))
                    .isEqualTo(expectedVal2);
        });
    }

    @Test(timeout = 300_000)
    public void when_learnerIsPromotedAfterAddedToSingletonRaftGroup_then_newLogEntryCannotBeCommittedOnlyWithLeader() {
        group = LocalRaftGroup.start(1);

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal1 = "val1";
        leader.replicate(applyValue(expectedVal1)).join();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult1 = leader
                .changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();
        Ordered<RaftGroupMembers> membershipChangeResult2 = leader.changeMembership(newNode.localEndpoint(),
                ADD_OR_PROMOTE_TO_FOLLOWER, membershipChangeResult1.getCommitIndex()).join();

        assertThat(membershipChangeResult2.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult2.getResult().getMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult2.getResult().getVotingMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult2.getResult().getMajorityQuorumSize()).isEqualTo(2);

        group.dropMessagesTo(leader.localEndpoint(), newNode.localEndpoint(), AppendEntriesRequest.class);

        String expectedVal2 = "val2";
        CompletableFuture<Ordered<Object>> future = leader.replicate(applyValue(expectedVal2));

        allTheTime(() -> assertThat(commitIndex(leader)).isEqualTo(membershipChangeResult2.getCommitIndex()), 3);

        group.allowMessagesTo(leader.localEndpoint(), newNode.localEndpoint(), AppendEntriesRequest.class);

        Ordered<Object> result2 = future.join();

        eventually(() -> {
            assertThat(commitIndex(leader)).isEqualTo(result2.getCommitIndex());
            assertThat(commitIndex(newNode)).isEqualTo(result2.getCommitIndex());
            assertThat(group.stateMachine(leader.localEndpoint()).get(result2.getCommitIndex()))
                    .isEqualTo(expectedVal2);
            assertThat(group.stateMachine(newNode.localEndpoint()).get(result2.getCommitIndex()))
                    .isEqualTo(expectedVal2);
        });
    }

    @Test(timeout = 300_000)
    public void when_learnerIsAddedToSingletonRaftGroupWithRaftStore_then_newLogEntryCanBeCommittedOnlyWithLeader() {
        group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal1 = "val1";
        leader.replicate(applyValue(expectedVal1)).join();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();

        assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult.getResult().getMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getVotingMembers()).doesNotContain(newNode.localEndpoint());
        assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(1);

        group.dropMessagesTo(leader.localEndpoint(), newNode.localEndpoint(), AppendEntriesRequest.class);

        String expectedVal2 = "val2";
        Ordered<Object> result2 = leader.replicate(applyValue(expectedVal2)).join();

        group.allowMessagesTo(leader.localEndpoint(), newNode.localEndpoint(), AppendEntriesRequest.class);

        eventually(() -> {
            assertThat(commitIndex(newNode)).isEqualTo(result2.getCommitIndex());
            assertThat(group.stateMachine(newNode.localEndpoint()).get(result2.getCommitIndex()))
                    .isEqualTo(expectedVal2);
        });
    }

    @Test(timeout = 300_000)
    public void when_learnerIsPromotedAfterAddedToSingletonRaftGroupWithRaftStore_then_newLogEntryCannotBeCommittedOnlyWithLeader() {
        group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal1 = "val1";
        leader.replicate(applyValue(expectedVal1)).join();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult1 = leader
                .changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();
        Ordered<RaftGroupMembers> membershipChangeResult2 = leader.changeMembership(newNode.localEndpoint(),
                ADD_OR_PROMOTE_TO_FOLLOWER, membershipChangeResult1.getCommitIndex()).join();

        assertThat(membershipChangeResult2.getResult().getMembers().size()).isEqualTo(2);
        assertThat(membershipChangeResult2.getResult().getMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult2.getResult().getVotingMembers()).contains(newNode.localEndpoint());
        assertThat(membershipChangeResult2.getResult().getMajorityQuorumSize()).isEqualTo(2);

        group.dropMessagesTo(leader.localEndpoint(), newNode.localEndpoint(), AppendEntriesRequest.class);

        String expectedVal2 = "val2";
        CompletableFuture<Ordered<Object>> future = leader.replicate(applyValue(expectedVal2));

        allTheTime(() -> assertThat(commitIndex(leader)).isEqualTo(membershipChangeResult2.getCommitIndex()), 3);

        group.allowMessagesTo(leader.localEndpoint(), newNode.localEndpoint(), AppendEntriesRequest.class);

        Ordered<Object> result2 = future.join();

        eventually(() -> {
            assertThat(commitIndex(leader)).isEqualTo(result2.getCommitIndex());
            assertThat(commitIndex(newNode)).isEqualTo(result2.getCommitIndex());
            assertThat(group.stateMachine(leader.localEndpoint()).get(result2.getCommitIndex()))
                    .isEqualTo(expectedVal2);
            assertThat(group.stateMachine(newNode.localEndpoint()).get(result2.getCommitIndex()))
                    .isEqualTo(expectedVal2);
        });
    }

    @Test(timeout = 300_000)
    public void when_singletonRaftGroupIsStarted_then_linearizableQueryIsExecuted() {
        group = LocalRaftGroup.start(1);
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        String val = "val";
        Ordered<Object> result = leader.replicate(applyValue(val)).join();

        Ordered<Object> queryResult = leader
                .query(queryLastValue(), QueryPolicy.LINEARIZABLE, Optional.empty(), Optional.empty()).join();

        assertThat(queryResult.getCommitIndex()).isEqualTo(result.getCommitIndex());
        assertThat(queryResult.getResult()).isEqualTo(val);
    }

    @Test(timeout = 300_000)
    public void when_singletonRaftGroupIsStarted_then_leaderLeaseQueryIsExecuted() {
        group = LocalRaftGroup.start(1);
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        String val = "val";
        Ordered<Object> result = leader.replicate(applyValue(val)).join();

        Ordered<Object> queryResult = leader
                .query(queryLastValue(), QueryPolicy.LEADER_LEASE, Optional.empty(), Optional.empty()).join();

        assertThat(queryResult.getCommitIndex()).isEqualTo(result.getCommitIndex());
        assertThat(queryResult.getResult()).isEqualTo(val);
    }

    @Test(timeout = 300_000)
    public void when_singletonRaftGroupIsStarted_then_eventuallyConsistentQueryIsExecuted() {
        group = LocalRaftGroup.start(1);
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        String expectedVal = "val";
        Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();

        Ordered<Object> queryResult = leader
                .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join();

        assertThat(queryResult.getCommitIndex()).isEqualTo(result.getCommitIndex());
        assertThat(queryResult.getResult()).isEqualTo(expectedVal);
    }

    @Test(timeout = 300_000)
    public void when_singletonRaftGroupRestarted_then_leaderIsElected() {
        group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY)
                .enableNewTermOperation().build();

        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        String val = "val";
        Ordered<Object> result = leader.replicate(applyValue(val)).join();
        int term = leader.termState().term();

        RestoredRaftState restoredState = restoredState(leader);
        RaftStore raftStore = raftStore(leader);

        group.terminateNode(leader.localEndpoint());

        RaftNode restoredNode = group.restoreNode(restoredState, raftStore);

        eventually(() -> {
            assertThat(restoredNode.leaderEndpoint()).isEqualTo(restoredNode.localEndpoint());
            int newTerm = restoredNode.termState().term();
            assertThat(newTerm).isGreaterThan(term);

            BaseLogEntry entry = lastLogOrSnapshotEntry(restoredNode);
            assertThat(entry.getTerm()).isEqualTo(newTerm);

            long commitIndex = commitIndex(restoredNode);
            assertThat(entry.getIndex()).isEqualTo(commitIndex);
        });

        Object queryResult = restoredNode
                .query(queryLastValue(), QueryPolicy.LINEARIZABLE, Optional.empty(), Optional.empty()).join()
                .getResult();
        assertThat(queryResult).isEqualTo(val);

        assertThat(group.stateMachine(restoredNode.localEndpoint()).get(result.getCommitIndex())).isEqualTo(val);
    }

    @Test(timeout = 300_000)
    public void when_nodeRestartsAfterSingletonRaftGroupExpandedWithLearner_then_newLeaderIsElected() {
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(3)
                .build();
        group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY)
                .enableNewTermOperation().setConfig(config).build();
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        String val = "val";
        Ordered<Object> result = leader.replicate(applyValue(val)).join();
        int term = leader.termState().term();

        RaftNode newNode = group.createNewNode();

        leader.changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();

        RestoredRaftState restoredState = restoredState(leader);
        RaftStore raftStore = raftStore(leader);

        group.terminateNode(leader.localEndpoint());

        RaftNode restoredNode = group.restoreNode(restoredState, raftStore);

        group.waitUntilLeaderElected();

        eventually(() -> {
            for (RaftNode node : List.of(newNode, restoredNode)) {
                int newTerm = node.termState().term();
                assertThat(newTerm).isGreaterThan(term);

                BaseLogEntry entry = lastLogOrSnapshotEntry(node);
                assertThat(entry.getTerm()).isEqualTo(newTerm);

                long commitIndex = commitIndex(node);
                assertThat(entry.getIndex()).isEqualTo(commitIndex);
            }
        });

        Object queryResult = restoredNode
                .query(queryLastValue(), QueryPolicy.EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join()
                .getResult();
        assertThat(queryResult).isEqualTo(val);

        assertThat(group.stateMachine(restoredNode.localEndpoint()).get(result.getCommitIndex())).isEqualTo(val);
    }

    @Test(timeout = 300_000)
    public void when_nodeRestartsAfterSingletonRaftGroupExpandedWithFollower_then_newLeaderIsElected() {
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(3)
                .build();
        group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY)
                .enableNewTermOperation().setConfig(config).build();
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        String val = "val";
        Ordered<Object> result = leader.replicate(applyValue(val)).join();
        int term = leader.termState().term();

        RaftNode newNode = group.createNewNode();

        leader.changeMembership(newNode.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, 0).join();

        RestoredRaftState restoredState = restoredState(leader);
        RaftStore raftStore = raftStore(leader);

        group.terminateNode(leader.localEndpoint());

        RaftNode restoredNode = group.restoreNode(restoredState, raftStore);

        group.waitUntilLeaderElected();

        eventually(() -> {
            for (RaftNode node : List.of(newNode, restoredNode)) {
                int newTerm = node.termState().term();
                assertThat(newTerm).isGreaterThan(term);

                BaseLogEntry entry = lastLogOrSnapshotEntry(node);
                assertThat(entry.getTerm()).isEqualTo(newTerm);

                long commitIndex = commitIndex(node);
                assertThat(entry.getIndex()).isEqualTo(commitIndex);
            }
        });

        Object queryResult = restoredNode
                .query(queryLastValue(), QueryPolicy.EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join()
                .getResult();
        assertThat(queryResult).isEqualTo(val);

        assertThat(group.stateMachine(restoredNode.localEndpoint()).get(result.getCommitIndex())).isEqualTo(val);
    }

    @Test(timeout = 300_000)
    public void when_nodeRestartsAfterSingletonRaftGroupExpanded_then_newLeaderIsElected() {
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(3)
                .build();
        group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY)
                .enableNewTermOperation().setConfig(config).build();
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        String val = "val";
        Ordered<Object> result = leader.replicate(applyValue(val)).join();
        int term = leader.termState().term();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();
        leader.changeMembership(newNode.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER,
                                membershipChangeResult.getCommitIndex()).join();

        RestoredRaftState restoredState = restoredState(leader);
        RaftStore raftStore = raftStore(leader);

        group.terminateNode(leader.localEndpoint());

        RaftNode restoredNode = group.restoreNode(restoredState, raftStore);

        group.waitUntilLeaderElected();

        eventually(() -> {
            for (RaftNode node : List.of(newNode, restoredNode)) {
                int newTerm = node.termState().term();
                assertThat(newTerm).isGreaterThan(term);

                BaseLogEntry entry = lastLogOrSnapshotEntry(node);
                assertThat(entry.getTerm()).isEqualTo(newTerm);

                long commitIndex = commitIndex(node);
                assertThat(entry.getIndex()).isEqualTo(commitIndex);
            }
        });

        Object queryResult = restoredNode
                .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join().getResult();
        assertThat(queryResult).isEqualTo(val);

        assertThat(group.stateMachine(restoredNode.localEndpoint()).get(result.getCommitIndex())).isEqualTo(val);
    }

    @Test(timeout = 300_000)
    public void when_followerLeaves2NodeRaftGroup_then_singletonRaftGroupCommitsNewLogEntry() {
        LocalRaftGroup group = LocalRaftGroup.start(2);

        RaftNode leader = group.waitUntilLeaderElected();
        RaftNode follower = group.anyNodeExcept(leader.localEndpoint());

        String val1 = "val1";
        leader.replicate(applyValue(val1)).join();

        Ordered<RaftGroupMembers> mewGroupMembers = leader
                .changeMembership(follower.localEndpoint(), REMOVE_MEMBER, 0).join();

        follower.terminate();

        assertThat(mewGroupMembers.getResult().getMembers().size()).isEqualTo(1);
        assertThat(mewGroupMembers.getResult().getMembers()).contains(leader.localEndpoint());

        Ordered<Object> queryResult1 = leader
                .query(queryLastValue(), QueryPolicy.LINEARIZABLE, Optional.empty(), Optional.empty()).join();
        assertThat(queryResult1.getResult()).isEqualTo(val1);

        String val2 = "val2";
        Ordered<Object> result2 = leader.replicate(applyValue(val2)).join();

        assertThat(result2.getCommitIndex()).isGreaterThan(queryResult1.getCommitIndex());

        Ordered<Object> queryResult2 = leader
                .query(queryLastValue(), QueryPolicy.LINEARIZABLE, Optional.empty(), Optional.empty()).join();
        assertThat(queryResult2.getResult()).isEqualTo(val2);

        assertThat(queryResult2.getCommitIndex()).isEqualTo(result2.getCommitIndex());
    }

    @Test(timeout = 300_000)
    public void when_leaderLeaves2NodeRaftGroup_then_singletonRaftGroupCommitsNewLogEntry() {
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(3)
                .build();
        group = LocalRaftGroup.newBuilder(2).enableNewTermOperation().setConfig(config).build();
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        // this follower will be the new leader
        RaftNode follower = group.anyNodeExcept(leader.localEndpoint());

        String val1 = "val1";
        leader.replicate(applyValue(val1)).join();

        int term = leader.termState().term();

        Ordered<RaftGroupMembers> newGroupMembers = leader.changeMembership(leader.localEndpoint(), REMOVE_MEMBER, 0)
                .join();

        leader.terminate();

        assertThat(newGroupMembers.getResult().getMembers().size()).isEqualTo(1);
        assertThat(newGroupMembers.getResult().getMembers()).contains(follower.localEndpoint());

        eventually(() -> {
            assertThat(follower.leaderEndpoint()).isEqualTo(follower.localEndpoint());
            int newTerm = follower.termState().term();
            assertThat(newTerm).isGreaterThan(term);
            assertThat(commitIndex(follower)).isGreaterThan(newGroupMembers.getCommitIndex());
        });

        Ordered<Object> queryResult1 = follower
                .query(queryLastValue(), QueryPolicy.LINEARIZABLE, Optional.empty(), Optional.empty()).join();
        assertThat(queryResult1.getResult()).isEqualTo(val1);

        String val2 = "val2";
        Ordered<Object> result2 = follower.replicate(applyValue(val2)).join();

        assertThat(result2.getCommitIndex()).isGreaterThan(queryResult1.getCommitIndex());

        Ordered<Object> queryResult2 = follower
                .query(queryLastValue(), QueryPolicy.LINEARIZABLE, Optional.empty(), Optional.empty()).join();
        assertThat(queryResult2.getResult()).isEqualTo(val2);

        assertThat(queryResult2.getCommitIndex()).isEqualTo(result2.getCommitIndex());
    }

    @Test(timeout = 300_000)
    public void when_memberRemovalIsNotCommitted_then_singletonFollowerCompletesMembershipChange() {
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(3)
                .build();
        group = LocalRaftGroup.newBuilder(2).enableNewTermOperation().setConfig(config).build();
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        // this follower will be the new leader
        RaftNode follower = group.anyNodeExcept(leader.localEndpoint());

        String val1 = "val1";
        leader.replicate(applyValue(val1)).join();

        int term = leader.termState().term();

        group.dropMessagesTo(follower.localEndpoint(), leader.localEndpoint(),
                             AppendEntriesSuccessResponse.class);

        leader.changeMembership(leader.localEndpoint(), REMOVE_MEMBER, 0);

        eventually(() -> assertThat(effectiveGroupMembers(follower).memberCount()).isEqualTo(1));

        group.dropMessagesTo(leader.localEndpoint(), follower.localEndpoint(), AppendEntriesRequest.class);

        eventually(() -> {
            assertThat(follower.leaderEndpoint()).isEqualTo(follower.localEndpoint());
            int newTerm = follower.termState().term();
            assertThat(newTerm).isGreaterThan(term);
        });

        String val2 = "val2";
        Ordered<Object> result2 = follower.replicate(applyValue(val2)).join();

        Ordered<Object> queryResult = follower
                .query(queryLastValue(), QueryPolicy.LINEARIZABLE, Optional.empty(), Optional.empty()).join();
        assertThat(queryResult.getResult()).isEqualTo(val2);

        assertThat(queryResult.getCommitIndex()).isEqualTo(result2.getCommitIndex());
    }

    @Test(timeout = 300_000)
    public void when_singletonRaftGroupIsRunning_then_cannotRemoveEndpoint() {
        group = LocalRaftGroup.start(1);

        RaftNode leader = group.waitUntilLeaderElected();

        try {
            leader.changeMembership(leader.localEndpoint(), REMOVE_MEMBER, 0).join();
            fail("Cannot remove self from singleton Raft group");
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_singleVotingRaftNodeIsRunning_then_cannotRemoveEndpoint() {
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(3)
                .build();
        group = LocalRaftGroup.newBuilder(1).enableNewTermOperation().setConfig(config).build();
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();

        RaftNode newNode = group.createNewNode();
        Ordered<RaftGroupMembers> result = leader.changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();

        try {
            leader.changeMembership(leader.localEndpoint(), REMOVE_MEMBER, result.getCommitIndex()).join();
            fail("Cannot remove self from singleton Raft group");
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(IllegalStateException.class);
        }
    }

}
