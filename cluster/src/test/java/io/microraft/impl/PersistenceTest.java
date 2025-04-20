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

import static io.microraft.MembershipChangeMode.ADD_OR_PROMOTE_TO_FOLLOWER;
import static io.microraft.MembershipChangeMode.REMOVE_MEMBER;
import static io.microraft.RaftRole.FOLLOWER;
import static io.microraft.RaftRole.LEARNER;
import static io.microraft.impl.local.LocalRaftGroup.IN_MEMORY_RAFT_STATE_STORE_FACTORY;
import static io.microraft.impl.local.SimpleStateMachine.applyValue;
import static io.microraft.test.util.AssertionUtils.eventually;
import static io.microraft.test.util.RaftTestUtils.TEST_RAFT_CONFIG;
import static io.microraft.test.util.RaftTestUtils.commitIndex;
import static io.microraft.test.util.RaftTestUtils.committedGroupMembers;
import static io.microraft.test.util.RaftTestUtils.effectiveGroupMembers;
import static io.microraft.test.util.RaftTestUtils.lastApplied;
import static io.microraft.test.util.RaftTestUtils.raftStore;
import static io.microraft.test.util.RaftTestUtils.restoredState;
import static io.microraft.test.util.RaftTestUtils.role;
import static io.microraft.test.util.RaftTestUtils.snapshotEntry;
import static io.microraft.test.util.RaftTestUtils.term;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.HashSet;
import java.util.List;

import io.microraft.*;
import org.junit.After;
import org.junit.Test;

import io.microraft.impl.local.InMemoryRaftStore;
import io.microraft.impl.local.LocalRaftGroup;
import io.microraft.impl.local.SimpleStateMachine;
import io.microraft.model.log.LogEntry;
import io.microraft.model.log.SnapshotEntry;
import io.microraft.model.message.AppendEntriesRequest;
import io.microraft.model.message.PreVoteRequest;
import io.microraft.persistence.RestoredRaftState;
import io.microraft.report.RaftGroupMembers;
import io.microraft.test.util.BaseTest;

public class PersistenceTest extends BaseTest {

    private LocalRaftGroup group;

    @After
    public void destroy() {
        if (group != null) {
            group.destroy();
        }
    }

    @Test(timeout = 300_000)
    public void testTermAndVoteArePersisted() {
        group = LocalRaftGroup.newBuilder(3).setConfig(TEST_RAFT_CONFIG)
                              .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        var leader = group.waitUntilLeaderElected();
        var followers = group.nodesExcept(leader.localEndpoint());
        var endpoints = new HashSet<RaftEndpoint>();

        for (var node : group.nodes()) {
            endpoints.add(node.localEndpoint());
        }

        int term1 = term(leader);
        eventually(() -> {
            for (var node : group.nodes()) {
                RestoredRaftState restoredState = restoredState(node);
                assertThat(restoredState.localEndpointPersistentState().getLocalEndpoint())
                        .isEqualTo(node.localEndpoint());
                assertThat(restoredState.termPersistentState().getTerm()).isEqualTo(term1);
                assertThat(restoredState.initialGroupMembers().getMembers()).isEqualTo(endpoints);
            }
        });

        group.terminateNode(leader.localEndpoint());

        eventually(() -> {
            for (RaftNode node : followers) {
                RaftEndpoint l = node.leaderEndpoint();
                assertNotNull(l);
                assertThat(l).isNotEqualTo(leader.leaderEndpoint());
            }
        });

        RaftNode newLeader = group.waitUntilLeaderElected();
        int term2 = term(newLeader);

        eventually(() -> {
            for (RaftNode node : followers) {
                RestoredRaftState restoredState = restoredState(node);
                assertThat(restoredState.termPersistentState().getTerm()).isEqualTo(term2);
                assertThat(restoredState.termPersistentState().getVotedFor())
                        .isEqualTo(newLeader.leaderEndpoint());
            }
        });
    }

    @Test(timeout = 300_000)
    public void testCommittedEntriesArePersisted() {
        group = LocalRaftGroup.newBuilder(3).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        RaftNode leader = group.waitUntilLeaderElected();
        int count = 10;
        for (int i = 0; i < count; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                RestoredRaftState restoredState = restoredState(node);
                List<LogEntry> entries = restoredState.getLogEntries();
                assertThat(entries).hasSize(count);
                for (int i = 0; i < count; i++) {
                    assertThat(entries.get(i).getIndex()).isEqualTo(i + 1);
                }
            }
        });
    }

    @Test(timeout = 300_000)
    public void testUncommittedEntriesArePersisted() {
        group = LocalRaftGroup.newBuilder(3).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode responsiveFollower = followers.get(0);

        for (int i = 1; i < followers.size(); i++) {
            group.dropMessagesTo(leader.localEndpoint(), followers.get(i).localEndpoint(),
                                 AppendEntriesRequest.class);
        }

        int count = 10;
        for (int i = 0; i < count; i++) {
            leader.replicate(applyValue("val" + i));
        }

        eventually(() -> {
            for (RaftNode node : List.of(leader, responsiveFollower)) {
                RestoredRaftState restoredState = restoredState(node);
                List<LogEntry> entries = restoredState.getLogEntries();
                assertThat(entries).hasSize(count);
                for (int i = 0; i < count; i++) {
                    assertThat(entries.get(i).getIndex()).isEqualTo(i + 1);
                }
            }
        });
    }

    @Test(timeout = 300_000)
    public void testSnapshotIsPersisted() {
        int commitCountToTakeSnapshot = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(commitCountToTakeSnapshot).build();
        group = LocalRaftGroup.newBuilder(3).setConfig(config).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY)
                              .start();

        RaftNode leader = group.waitUntilLeaderElected();
        for (int i = 0; i < commitCountToTakeSnapshot; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> {
            assertThat(snapshotEntry(leader).getIndex()).isEqualTo(commitCountToTakeSnapshot);

            for (RaftNode node : group.nodes()) {
                RestoredRaftState restoredState = restoredState(node);
                SnapshotEntry snapshot = restoredState.getSnapshotEntry();
                assertNotNull(snapshot);
                assertThat(snapshot.getIndex()).isEqualTo(commitCountToTakeSnapshot);
                assertNotNull(snapshot.getOperation());
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_leaderAppendEntriesInMinoritySplit_then_itTruncatesEntriesOnStore() {
        group = LocalRaftGroup.newBuilder(3).setConfig(TEST_RAFT_CONFIG)
                              .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        RaftNode leader = group.waitUntilLeaderElected();

        leader.replicate(applyValue("val1")).join();

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(1);
            }
        });

        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        group.splitMembers(leader.localEndpoint());

        for (int i = 0; i < 10; i++) {
            leader.replicate(applyValue("isolated" + i));
        }

        eventually(() -> {
            for (RaftNode node : followers) {
                RaftEndpoint leaderEndpoint = node.leaderEndpoint();
                assertNotNull(leaderEndpoint);
                assertThat(leaderEndpoint).isNotEqualTo(leader.localEndpoint());
            }
        });

        eventually(() -> {
            RestoredRaftState restoredState = restoredState(leader);
            assertThat(restoredState.getLogEntries()).hasSize(11);
        });

        RaftNode newLeader = group.node(followers.get(0).leaderEndpoint());
        for (int i = 0; i < 10; i++) {
            newLeader.replicate(applyValue("valNew" + i)).join();
        }

        eventually(() -> {
            for (RaftNode node : followers) {
                assertThat(commitIndex(node)).isEqualTo(11);
            }
        });

        group.merge();

        RaftNode finalLeader = group.waitUntilLeaderElected();

        assertThat(finalLeader.localEndpoint()).isNotEqualTo(leader.localEndpoint());

        eventually(() -> {
            RestoredRaftState state = restoredState(leader);
            assertThat(state.getLogEntries()).hasSize(11);
        });
    }

    @Test(timeout = 300_000)
    public void when_leaderIsRestarted_then_itBecomesFollowerAndRestoresItsRaftState() {
        group = LocalRaftGroup.newBuilder(3).setConfig(TEST_RAFT_CONFIG).enableNewTermOperation()
                              .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        RaftNode leader = group.waitUntilLeaderElected();
        int count = 10;
        for (int i = 0; i < count; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        RaftEndpoint terminatedEndpoint = leader.localEndpoint();
        group.terminateNode(terminatedEndpoint);

        var stateStore = raftStore(leader);
        var terminatedState = stateStore.toRestoredRaftState();

        var newLeader = group.waitUntilLeaderElected();
        var restartedNode = group.restoreNode(terminatedState, stateStore);

        assertThat(committedGroupMembers(restartedNode).getMembersList())
                .isEqualTo(committedGroupMembers(newLeader).getMembersList());
        assertThat(effectiveGroupMembers(restartedNode).getMembersList())
                .isEqualTo(effectiveGroupMembers(newLeader).getMembersList());

        eventually(() -> {
            assertThat(restartedNode.leaderEndpoint()).isEqualTo(newLeader.localEndpoint());
            assertThat(term(restartedNode)).isEqualTo(term(newLeader));
            assertThat(commitIndex(restartedNode)).isEqualTo(commitIndex(newLeader));
            assertThat(lastApplied(restartedNode)).isEqualTo(lastApplied(newLeader));
            SimpleStateMachine stateMachine = group.stateMachine(restartedNode.localEndpoint());
            List<Object> values = stateMachine.valueList();
            assertNotNull(values);
            assertThat(values).hasSize(count);
            for (int i = 0; i < count; i++) {
                assertThat(values.get(i)).isEqualTo("val" + i);
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_leaderIsRestarted_then_itRestoresItsRaftStateAndBecomesLeader() {
        group = LocalRaftGroup.newBuilder(3).enableNewTermOperation()
                              .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        RaftNode leader = group.waitUntilLeaderElected();
        int count = 10;
        for (int i = 0; i < count; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        int term = term(leader);
        long commitIndex = commitIndex(leader);

        var terminatedEndpoint = leader.localEndpoint();
        var stateStore = raftStore(leader);
        var terminatedState = stateStore.toRestoredRaftState();

        // Block voting between followers
        // to avoid a leader election before leader restarts.
        blockVotingBetweenFollowers();

        group.terminateNode(terminatedEndpoint);
        RaftNode restartedNode = group.restoreNode(terminatedState, stateStore);

        RaftNode newLeader = group.waitUntilLeaderElected();
        assertThat(restartedNode).isSameAs(newLeader);

        eventually(() -> {
            assertThat(term(restartedNode)).isGreaterThan(term);
            assertThat(commitIndex(restartedNode)).isEqualTo(commitIndex + 1);
            SimpleStateMachine stateMachine = group.stateMachine(restartedNode.localEndpoint());
            List<Object> values = stateMachine.valueList();
            assertNotNull(values);
            assertThat(values).hasSize(count);
            for (int i = 0; i < count; i++) {
                assertThat(values.get(i)).isEqualTo("val" + i);
            }
        });
    }

    private void blockVotingBetweenFollowers() {
        for (RaftNode follower : group.<RaftNode>nodesExcept(group.leaderEndpoint())) {
            group.dropMessagesToAll(follower.localEndpoint(), PreVoteRequest.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_followerIsRestarted_then_itRestoresItsRaftState() {
        group = LocalRaftGroup.newBuilder(3).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        RaftNode leader = group.waitUntilLeaderElected();
        RaftNode terminatedFollower = group.anyNodeExcept(leader.localEndpoint());
        int count = 10;
        for (int i = 0; i < count; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> assertEquals(commitIndex(leader), commitIndex(terminatedFollower)));

        var terminatedEndpoint = terminatedFollower.localEndpoint();
        group.terminateNode(terminatedEndpoint);

        var stateStore = raftStore(terminatedFollower);
        var terminatedState = stateStore.toRestoredRaftState();

        leader.replicate(applyValue("val" + count)).join();

        var restartedNode = group.restoreNode(terminatedState, stateStore);

        assertThat(committedGroupMembers(restartedNode).getMembersList())
                .isEqualTo(committedGroupMembers(leader).getMembersList());
        assertThat(effectiveGroupMembers(restartedNode).getMembersList())
                .isEqualTo(effectiveGroupMembers(leader).getMembersList());

        eventually(() -> {
            assertThat(restartedNode.leaderEndpoint()).isEqualTo(leader.localEndpoint());
            assertThat(term(restartedNode)).isEqualTo(term(leader));
            assertThat(commitIndex(restartedNode)).isEqualTo(commitIndex(leader));
            assertThat(lastApplied(restartedNode)).isEqualTo(lastApplied(leader));
            SimpleStateMachine stateMachine = group.stateMachine(restartedNode.localEndpoint());
            List<Object> values = stateMachine.valueList();
            assertNotNull(values);
            assertThat(values).hasSize(count + 1);
            for (int i = 0; i <= count; i++) {
                assertThat(values.get(i)).isEqualTo("val" + i);
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_learnerIsRestarted_then_itRestoresItsRaftState() {
        group = LocalRaftGroup.newBuilder(3, 2).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        RaftNode leader = group.waitUntilLeaderElected();
        RaftNode terminatedLearner = null;
        for (RaftNode node : group.<RaftNode>nodesExcept(leader.localEndpoint())) {
            if (role(node) == LEARNER) {
                terminatedLearner = node;
                break;
            }
        }
        assertNotNull(terminatedLearner);

        int count = 10;
        for (int i = 0; i < count; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        RaftNode terminated = terminatedLearner;
        eventually(() -> assertEquals(commitIndex(leader), commitIndex(terminated)));

        RaftEndpoint terminatedEndpoint = terminatedLearner.localEndpoint();
        group.terminateNode(terminatedEndpoint);

        var stateStore = raftStore(terminatedLearner);
        var terminatedState = stateStore.toRestoredRaftState();

        leader.replicate(applyValue("val" + count)).join();

        var restartedNode = group.restoreNode(terminatedState, stateStore);

        assertThat(role(restartedNode)).isEqualTo(LEARNER);
        assertThat(committedGroupMembers(restartedNode).getMembersList())
                .isEqualTo(committedGroupMembers(leader).getMembersList());
        assertThat(effectiveGroupMembers(restartedNode).getMembersList())
                .isEqualTo(effectiveGroupMembers(leader).getMembersList());

        eventually(() -> {
            assertThat(restartedNode.leaderEndpoint()).isEqualTo(leader.localEndpoint());
            assertThat(term(restartedNode)).isEqualTo(term(leader));
            assertThat(commitIndex(restartedNode)).isEqualTo(commitIndex(leader));
            assertThat(lastApplied(restartedNode)).isEqualTo(lastApplied(leader));
            SimpleStateMachine stateMachine = group.stateMachine(restartedNode.localEndpoint());
            List<Object> values = stateMachine.valueList();
            assertNotNull(values);
            assertThat(values).hasSize(count + 1);
            for (int i = 0; i <= count; i++) {
                assertThat(values.get(i)).isEqualTo("val" + i);
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_leaderIsRestarted_then_itBecomesFollowerAndRestoresItsRaftStateWithSnapshot() {
        int commitCountToTakeSnapshot = 50;
        var config = RaftConfig.newBuilder()
                               .setLeaderElectionTimeoutMillis(2000)
                               .setLeaderHeartbeatPeriodSecs(1)
                               .setLeaderHeartbeatTimeoutSecs(5)
                               .setCommitCountToTakeSnapshot(commitCountToTakeSnapshot)
                               .build();
        group = LocalRaftGroup.newBuilder(3).setConfig(config).enableNewTermOperation()
                              .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        var leader = group.waitUntilLeaderElected();

        for (int i = 0; i <= commitCountToTakeSnapshot; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        assertThat(snapshotEntry(leader).getIndex()).isGreaterThan(0);

        var terminatedEndpoint = leader.localEndpoint();
        group.terminateNode(terminatedEndpoint);

        var stateStore = raftStore(leader);
        var terminatedState = stateStore.toRestoredRaftState();

        var newLeader = group.waitUntilLeaderElected();
        var restartedNode = group.restoreNode(terminatedState, stateStore);

        assertThat(committedGroupMembers(restartedNode).getMembersList())
                .isEqualTo(committedGroupMembers(newLeader).getMembersList());
        assertThat(effectiveGroupMembers(restartedNode).getMembersList())
                .isEqualTo(effectiveGroupMembers(newLeader).getMembersList());

        eventually(() -> {
            assertThat(restartedNode.leaderEndpoint()).isEqualTo(newLeader.localEndpoint());
            assertThat(term(restartedNode)).isEqualTo(term(newLeader));
            assertThat(commitIndex(restartedNode)).isEqualTo(commitIndex(newLeader));
            assertThat(lastApplied(restartedNode)).isEqualTo(lastApplied(newLeader));
            SimpleStateMachine stateMachine = group.stateMachine(restartedNode.localEndpoint());
            List<Object> values = stateMachine.valueList();
            assertNotNull(values);
            assertThat(values).hasSize(commitCountToTakeSnapshot + 1);
            for (int i = 0; i <= commitCountToTakeSnapshot; i++) {
                assertThat(values.get(i)).isEqualTo("val" + i);
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_followerIsRestarted_then_itRestoresItsRaftStateWithSnapshot() {
        int commitCountToTakeSnapshot = 50;
        var config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(commitCountToTakeSnapshot).build();
        group = LocalRaftGroup.newBuilder(3).setConfig(config).enableNewTermOperation()
                              .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        var leader = group.waitUntilLeaderElected();

        for (int i = 0; i <= commitCountToTakeSnapshot; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> {
            for (var node : group.nodes()) {
                assertThat(snapshotEntry(node).getIndex()).isGreaterThan(0);
            }
        });

        var terminatedFollower = group.anyNodeExcept(leader.localEndpoint());
        var terminatedEndpoint = terminatedFollower.localEndpoint();
        group.terminateNode(terminatedEndpoint);

        var stateStore = raftStore(terminatedFollower);
        var terminatedState = stateStore.toRestoredRaftState();

        leader.replicate(applyValue("val" + (commitCountToTakeSnapshot + 1))).join();

        var restartedNode = group.restoreNode(terminatedState, stateStore);

        assertThat(committedGroupMembers(restartedNode).getMembersList())
                .isEqualTo(committedGroupMembers(leader).getMembersList());
        assertThat(effectiveGroupMembers(restartedNode).getMembersList())
                .isEqualTo(effectiveGroupMembers(leader).getMembersList());

        eventually(() -> {
            assertThat(restartedNode.leaderEndpoint()).isEqualTo(leader.localEndpoint());
            assertThat(term(restartedNode)).isEqualTo(term(leader));
            assertThat(commitIndex(restartedNode)).isEqualTo(commitIndex(leader));
            assertThat(lastApplied(restartedNode)).isEqualTo(lastApplied(leader));
            SimpleStateMachine stateMachine = group.stateMachine(restartedNode.localEndpoint());
            List<Object> values = stateMachine.valueList();
            assertNotNull(values);
            assertThat(values).hasSize(commitCountToTakeSnapshot + 2);
            for (int i = 0; i <= commitCountToTakeSnapshot + 1; i++) {
                assertThat(values.get(i)).isEqualTo("val" + i);
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_learnerIsRestarted_then_itRestoresItsRaftStateWithSnapshot() {
        int commitCountToTakeSnapshot = 50;
        var config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(commitCountToTakeSnapshot).build();
        group = LocalRaftGroup.newBuilder(3, 2).setConfig(config).enableNewTermOperation()
                              .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        var leader = group.waitUntilLeaderElected();

        for (int i = 0; i <= commitCountToTakeSnapshot; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> {
            for (var node : group.nodes()) {
                assertThat(snapshotEntry(node).getIndex()).isGreaterThan(0);
            }
        });

        RaftNode terminatedLearner = null;
        for (var node : group.nodesExcept(leader.localEndpoint())) {
            if (role(node) == LEARNER) {
                terminatedLearner = node;
                break;
            }
        }
        assertNotNull(terminatedLearner);

        var terminatedEndpoint = terminatedLearner.localEndpoint();
        group.terminateNode(terminatedEndpoint);

        var stateStore = raftStore(terminatedLearner);
        var terminatedState = stateStore.toRestoredRaftState();

        leader.replicate(applyValue("val" + (commitCountToTakeSnapshot + 1))).join();

        RaftNode restartedNode = group.restoreNode(terminatedState, stateStore);

        assertThat(committedGroupMembers(restartedNode).getMembersList())
                .isEqualTo(committedGroupMembers(leader).getMembersList());
        assertThat(effectiveGroupMembers(restartedNode).getMembersList())
                .isEqualTo(effectiveGroupMembers(leader).getMembersList());
        assertThat(role(restartedNode)).isEqualTo(LEARNER);

        eventually(() -> {
            assertThat(restartedNode.leaderEndpoint()).isEqualTo(leader.localEndpoint());
            assertThat(term(restartedNode)).isEqualTo(term(leader));
            assertThat(commitIndex(restartedNode)).isEqualTo(commitIndex(leader));
            assertThat(lastApplied(restartedNode)).isEqualTo(lastApplied(leader));
            SimpleStateMachine stateMachine = group.stateMachine(restartedNode.localEndpoint());
            List<Object> values = stateMachine.valueList();
            assertNotNull(values);
            assertThat(values).hasSize(commitCountToTakeSnapshot + 2);
            for (int i = 0; i <= commitCountToTakeSnapshot + 1; i++) {
                assertThat(values.get(i)).isEqualTo("val" + i);
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_leaderIsRestarted_then_itRestoresItsRaftStateWithSnapshotAndBecomesLeader() {
        int commitCountToTakeSnapshot = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(commitCountToTakeSnapshot).build();
        group = LocalRaftGroup.newBuilder(3).setConfig(config).enableNewTermOperation()
                              .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        RaftNode leader = group.waitUntilLeaderElected();

        for (int i = 0; i <= commitCountToTakeSnapshot; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        assertThat(snapshotEntry(leader).getIndex()).isGreaterThan(0);
        int term = term(leader);
        long commitIndex = commitIndex(leader);

        var terminatedEndpoint = leader.localEndpoint();
        var stateStore = raftStore(leader);
        var terminatedState = stateStore.toRestoredRaftState();

        // Block voting between followers
        // to avoid a leader election before leader restarts.
        blockVotingBetweenFollowers();

        group.terminateNode(terminatedEndpoint);
        var restartedNode = group.restoreNode(terminatedState, stateStore);

        var newLeader = group.waitUntilLeaderElected();
        assertThat(newLeader).isSameAs(restartedNode);

        eventually(() -> {
            assertThat(term(restartedNode)).isGreaterThan(term);
            assertThat(commitIndex(restartedNode)).isEqualTo(commitIndex + 1);
            SimpleStateMachine stateMachine = group.stateMachine(restartedNode.localEndpoint());
            List<Object> values = stateMachine.valueList();
            assertNotNull(values);
            assertThat(values).hasSize(commitCountToTakeSnapshot + 1);
            for (int i = 0; i <= commitCountToTakeSnapshot; i++) {
                assertThat(values.get(i)).isEqualTo("val" + i);
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_leaderIsRestarted_then_itBecomesLeaderAndAppliesPreviouslyCommittedMemberList() {
        group = LocalRaftGroup.newBuilder(3).enableNewTermOperation()
                              .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode removedFollower = followers.get(0);
        RaftNode runningFollower = followers.get(1);

        group.terminateNode(removedFollower.localEndpoint());
        leader.replicate(applyValue("val")).join();
        leader.changeMembership(removedFollower.localEndpoint(), REMOVE_MEMBER, 0).join();

        var terminatedEndpoint = leader.localEndpoint();
        var stateStore = raftStore(leader);
        var terminatedState = stateStore.toRestoredRaftState();

        // Block voting between followers
        // to avoid a leader election before leader restarts.
        blockVotingBetweenFollowers();

        group.terminateNode(terminatedEndpoint);
        var restartedNode = group.restoreNode(terminatedState, stateStore);
        var newLeader = group.waitUntilLeaderElected();
        
        assertThat(newLeader).isSameAs(restartedNode);

        eventually(() -> {
            assertThat(commitIndex(restartedNode)).isEqualTo(commitIndex(runningFollower));
            assertThat(committedGroupMembers(restartedNode).getMembersList())
                    .isEqualTo(committedGroupMembers(runningFollower).getMembersList());
            assertThat(effectiveGroupMembers(restartedNode).getMembersList())
                    .isEqualTo(effectiveGroupMembers(runningFollower).getMembersList());
        });
    }

    @Test(timeout = 300_000)
    public void when_followerIsRestarted_then_itAppliesPreviouslyCommittedMemberList() {
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(30)
                                      .build();
        group = LocalRaftGroup.newBuilder(3).setConfig(config).enableNewTermOperation()
                              .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode removedFollower = followers.get(0);
        RaftNode terminatedFollower = followers.get(1);

        group.terminateNode(removedFollower.localEndpoint());
        leader.replicate(applyValue("val")).join();
        leader.changeMembership(removedFollower.localEndpoint(), REMOVE_MEMBER, 0).join();

        var terminatedEndpoint = terminatedFollower.localEndpoint();
        group.terminateNode(terminatedEndpoint);

        var stateStore = raftStore(terminatedFollower);
        var terminatedState = stateStore.toRestoredRaftState();

        var restartedNode = group.restoreNode(terminatedState, stateStore);

        eventually(() -> {
            assertThat(commitIndex(restartedNode)).isEqualTo(commitIndex(leader));
            assertThat(lastApplied(restartedNode)).isEqualTo(lastApplied(leader));
            assertThat(committedGroupMembers(restartedNode).getMembersList())
                    .isEqualTo(committedGroupMembers(leader).getMembersList());
            assertThat(effectiveGroupMembers(restartedNode).getMembersList())
                    .isEqualTo(effectiveGroupMembers(leader).getMembersList());
        });
    }

    @Test(timeout = 300_000)
    public void when_learnerIsRestarted_then_itAppliesItsPromotion() {
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(30)
                                      .build();
        group = LocalRaftGroup.newBuilder(3, 2).setConfig(config).enableNewTermOperation()
                              .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        RaftNode leader = group.waitUntilLeaderElected();

        RaftNode terminatedLearner = null;
        for (RaftNode node : group.<RaftNode>nodesExcept(leader.localEndpoint())) {
            if (role(node) == LEARNER) {
                terminatedLearner = node;
                break;
            }
        }
        assertNotNull(terminatedLearner);

        leader.replicate(applyValue("val")).join();
        leader.changeMembership(terminatedLearner.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, 0).join();

        group.terminateNode(terminatedLearner.localEndpoint());

        var stateStore = raftStore(terminatedLearner);
        RestoredRaftState terminatedState = stateStore.toRestoredRaftState();

        RaftNode restartedNode = group.restoreNode(terminatedState, stateStore);

        eventually(() -> {
            assertThat(role(restartedNode)).isEqualTo(FOLLOWER);
            assertThat(commitIndex(restartedNode)).isEqualTo(commitIndex(leader));
            assertThat(lastApplied(restartedNode)).isEqualTo(lastApplied(leader));
            assertThat(committedGroupMembers(restartedNode).getMembersList())
                    .isEqualTo(committedGroupMembers(leader).getMembersList());
            assertThat(effectiveGroupMembers(restartedNode).getMembersList())
                    .isEqualTo(effectiveGroupMembers(leader).getMembersList());
        });
    }

    @Test(timeout = 300_000)
    public void when_leaderIsRestarted_then_itBecomesLeaderAndAppliesPreviouslyCommittedMemberListViaSnapshot() {
        int commitCountToTakeSnapshot = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(commitCountToTakeSnapshot).build();
        group = LocalRaftGroup.newBuilder(3).setConfig(config).enableNewTermOperation()
                              .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode removedFollower = followers.get(0);
        RaftNode runningFollower = followers.get(1);

        group.terminateNode(removedFollower.localEndpoint());
        leader.replicate(applyValue("val")).join();
        leader.changeMembership(removedFollower.localEndpoint(), REMOVE_MEMBER, 0).join();

        while (snapshotEntry(leader).getIndex() == 0) {
            leader.replicate(applyValue("val")).join();
        }

        RaftEndpoint terminatedEndpoint = leader.localEndpoint();
        var stateStore = raftStore(leader);
        RestoredRaftState terminatedState = stateStore.toRestoredRaftState();

        // Block voting between followers
        // to avoid a leader election before leader restarts.
        blockVotingBetweenFollowers();

        group.terminateNode(terminatedEndpoint);
        RaftNode restartedNode = group.restoreNode(terminatedState, stateStore);

        RaftNode newLeader = group.waitUntilLeaderElected();
        assertThat(newLeader).isSameAs(restartedNode);

        eventually(() -> {
            assertThat(commitIndex(restartedNode)).isEqualTo(commitIndex(runningFollower));
            assertThat(committedGroupMembers(restartedNode).getMembersList())
                    .isEqualTo(committedGroupMembers(runningFollower).getMembersList());
            assertThat(effectiveGroupMembers(restartedNode).getMembersList())
                    .isEqualTo(effectiveGroupMembers(runningFollower).getMembersList());
        });
    }

    @Test(timeout = 300_000)
    public void when_followerIsRestarted_then_itAppliesPreviouslyCommittedMemberListViaSnapshot() {
        int commitCountToTakeSnapshot = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(commitCountToTakeSnapshot)
                                      .setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(30).build();
        group = LocalRaftGroup.newBuilder(3).setConfig(config).enableNewTermOperation()
                              .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode removedFollower = followers.get(0);
        RaftNode terminatedFollower = followers.get(1);

        group.terminateNode(removedFollower.localEndpoint());
        leader.replicate(applyValue("val")).join();
        leader.changeMembership(removedFollower.localEndpoint(), REMOVE_MEMBER, 0).join();

        while (snapshotEntry(terminatedFollower).getIndex() == 0) {
            leader.replicate(applyValue("val")).join();
        }

        RaftEndpoint terminatedEndpoint = terminatedFollower.localEndpoint();
        group.terminateNode(terminatedEndpoint);

        var stateStore = raftStore(terminatedFollower);
        RestoredRaftState terminatedState = stateStore.toRestoredRaftState();

        RaftNode restartedNode = group.restoreNode(terminatedState, stateStore);

        eventually(() -> {
            assertThat(commitIndex(restartedNode)).isEqualTo(commitIndex(leader));
            assertThat(lastApplied(restartedNode)).isEqualTo(lastApplied(leader));
            assertThat(committedGroupMembers(restartedNode).getMembersList())
                    .isEqualTo(committedGroupMembers(leader).getMembersList());
            assertThat(effectiveGroupMembers(restartedNode).getMembersList())
                    .isEqualTo(effectiveGroupMembers(leader).getMembersList());
        });
    }

    @Test(timeout = 300_000)
    public void when_learnerIsRestarted_then_itAppliesItsPromotionViaSnapshot() {
        int commitCountToTakeSnapshot = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(commitCountToTakeSnapshot)
                                      .setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(30).build();
        group = LocalRaftGroup.newBuilder(3, 2).setConfig(config).enableNewTermOperation()
                              .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        RaftNode leader = group.waitUntilLeaderElected();

        RaftNode terminatedLearner = null;
        for (RaftNode node : group.<RaftNode>nodesExcept(leader.localEndpoint())) {
            if (role(node) == LEARNER) {
                terminatedLearner = node;
                break;
            }
        }
        assertNotNull(terminatedLearner);

        leader.replicate(applyValue("val")).join();
        leader.changeMembership(terminatedLearner.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, 0).join();

        while (snapshotEntry(terminatedLearner).getIndex() == 0) {
            leader.replicate(applyValue("val")).join();
        }

        group.terminateNode(terminatedLearner.localEndpoint());

        var stateStore = raftStore(terminatedLearner);
        RestoredRaftState terminatedState = stateStore.toRestoredRaftState();

        RaftNode restartedNode = group.restoreNode(terminatedState, stateStore);

        eventually(() -> {
            assertThat(role(restartedNode)).isEqualTo(FOLLOWER);
            assertThat(commitIndex(restartedNode)).isEqualTo(commitIndex(leader));
            assertThat(lastApplied(restartedNode)).isEqualTo(lastApplied(leader));
            assertThat(committedGroupMembers(restartedNode).getMembersList())
                    .isEqualTo(committedGroupMembers(leader).getMembersList());
            assertThat(effectiveGroupMembers(restartedNode).getMembersList())
                    .isEqualTo(effectiveGroupMembers(leader).getMembersList());
        });
    }

    @Test(timeout = 300_000)
    public void when_learnerIsRestarted_then_itRestartsAsNonVotingMember() {
        group = LocalRaftGroup.newBuilder(3).enableNewTermOperation()
                              .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        RaftNode leader = group.waitUntilLeaderElected();

        RaftNode newNode = group.createNewNode();

        leader.changeMembership(newNode.localEndpoint(), MembershipChangeMode.ADD_LEARNER, 0).join();

        eventually(() -> assertThat(commitIndex(newNode)).isEqualTo(commitIndex(leader)));

        group.terminateNode(newNode.localEndpoint());

        var stateStore = raftStore(newNode);
        RestoredRaftState terminatedState = stateStore.toRestoredRaftState();

        RaftNode restartedNode = group.restoreNode(terminatedState, stateStore);

        eventually(() -> assertThat(commitIndex(restartedNode)).isEqualTo(commitIndex(leader)));
        assertThat(role(restartedNode)).isEqualTo(LEARNER);
    }

    @Test(timeout = 300_000)
    public void when_promotedMemberIsRestarted_then_itRestartsAsVotingMember() {
        group = LocalRaftGroup.newBuilder(3).enableNewTermOperation()
                              .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        RaftNode leader = group.waitUntilLeaderElected();

        RaftNode newNode = group.createNewNode();

        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.localEndpoint(), MembershipChangeMode.ADD_LEARNER, 0).join();
        leader.changeMembership(newNode.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER,
                                membershipChangeResult.getCommitIndex()).join();

        eventually(() -> assertThat(commitIndex(newNode)).isEqualTo(commitIndex(leader)));

        group.terminateNode(newNode.localEndpoint());

        var stateStore = raftStore(newNode);
        RestoredRaftState terminatedState = stateStore.toRestoredRaftState();

        RaftNode restartedNode = group.restoreNode(terminatedState, stateStore);

        eventually(() -> assertThat(commitIndex(restartedNode)).isEqualTo(commitIndex(leader)));
        assertThat(role(restartedNode)).isEqualTo(RaftRole.FOLLOWER);
    }

    @Test(timeout = 300_000)
    public void when_promotingMemberIsRestarted_then_itRestartsAsVotingMember() {
        group = LocalRaftGroup.newBuilder(3).enableNewTermOperation()
                              .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();

        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());

        RaftNode newNode = group.createNewNode();
        Ordered<RaftGroupMembers> membershipChangeResult = leader
                .changeMembership(newNode.localEndpoint(), MembershipChangeMode.ADD_LEARNER, 0).join();

        for (RaftNode follower : followers) {
            group.dropMessagesTo(leader.localEndpoint(), follower.localEndpoint(), AppendEntriesRequest.class);
        }

        leader.changeMembership(newNode.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER,
                                membershipChangeResult.getCommitIndex());

        eventually(() -> assertThat(role(newNode)).isEqualTo(RaftRole.FOLLOWER));

        group.terminateNode(newNode.localEndpoint());

        var stateStore = raftStore(newNode);
        RestoredRaftState terminatedState = stateStore.toRestoredRaftState();

        RaftNode restartedNode = group.restoreNode(terminatedState, stateStore);

        eventually(() -> assertThat(commitIndex(restartedNode)).isEqualTo(commitIndex(leader)));
        assertThat(role(restartedNode)).isEqualTo(RaftRole.FOLLOWER);
    }

    // TODO [basri] add snapshot chunk truncation tests

}
