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
import static io.microraft.QueryPolicy.EVENTUAL_CONSISTENCY;
import static io.microraft.QueryPolicy.LEADER_LEASE;
import static io.microraft.impl.local.SimpleStateMachine.applyValue;
import static io.microraft.impl.local.SimpleStateMachine.queryLastValue;
import static io.microraft.test.util.AssertionUtils.eventually;
import static io.microraft.test.util.RaftTestUtils.TEST_RAFT_CONFIG;
import static io.microraft.test.util.RaftTestUtils.commitIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

import java.util.Optional;
import java.util.concurrent.CompletionException;

import org.junit.After;
import org.junit.Test;

import io.microraft.Ordered;
import io.microraft.RaftEndpoint;
import io.microraft.RaftNode;
import io.microraft.exception.LaggingCommitIndexException;
import io.microraft.exception.NotLeaderException;
import io.microraft.impl.local.LocalRaftGroup;
import io.microraft.model.message.AppendEntriesRequest;
import io.microraft.test.util.BaseTest;

public class LocalQueryTest extends BaseTest {

    private LocalRaftGroup group;

    @After
    public void destroy() {
        if (group != null) {
            group.destroy();
        }
    }

    @Test(timeout = 300_000)
    public void when_queryFromLeader_withoutAnyCommit_then_returnDefaultValue() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();

        Ordered<Object> o = leader.query(queryLastValue(), LEADER_LEASE, Optional.empty(), Optional.empty()).join();
        assertThat(o.getResult()).isNull();
        assertThat(o.getCommitIndex()).isEqualTo(0);
    }

    @Test(timeout = 300_000)
    public void when_queryFromLeaderWithCommitIndex_withoutAnyCommit_then_fail() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();

        try {
            leader.query(queryLastValue(), LEADER_LEASE, Optional.of(commitIndex(leader) + 1), Optional.empty())
                    .join();
            fail();
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(LaggingCommitIndexException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_queryFromFollower_withoutAnyCommit_then_returnDefaultValue() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();
        RaftNode follower = group.anyNodeExcept(leader.localEndpoint());

        Ordered<Object> o = follower.query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty())
                .join();
        assertThat(o.getResult()).isNull();
        assertThat(o.getCommitIndex()).isEqualTo(0);
    }

    @Test(timeout = 300_000)
    public void when_queryFromFollowerWithCommitIndex_withoutAnyCommit_then_returnDefaultValue() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();
        RaftNode follower = group.anyNodeExcept(leader.localEndpoint());

        try {
            follower.query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.of(commitIndex(follower) + 1),
                    Optional.empty()).join();
            fail();
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(LaggingCommitIndexException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_queryFromLeaderWithoutCommitIndex_onStableRaftGroup_then_readLatestValue() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();

        int count = 3;
        for (int i = 1; i <= count; i++) {
            leader.replicate(applyValue("value" + i)).join();
        }

        long commitIndex = commitIndex(leader);
        Ordered<Object> result = leader.query(queryLastValue(), LEADER_LEASE, Optional.empty(), Optional.empty())
                .join();
        assertThat(result.getResult()).isEqualTo("value" + count);
        assertThat(result.getCommitIndex()).isEqualTo(commitIndex);
    }

    @Test(timeout = 300_000)
    public void when_queryFromLeaderWithCommitIndex_onStableRaftGroup_then_readLatestValue() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();

        int count = 3;
        for (int i = 1; i <= count; i++) {
            leader.replicate(applyValue("value" + i)).join();
        }

        long commitIndex = commitIndex(leader);
        Ordered<Object> result = leader
                .query(queryLastValue(), LEADER_LEASE, Optional.of(commitIndex), Optional.empty()).join();
        assertThat(result.getResult()).isEqualTo("value" + count);
        assertThat(result.getCommitIndex()).isEqualTo(commitIndex);
    }

    @Test(timeout = 300_000)
    public void when_queryFromLeaderWithFurtherCommitIndex_onStableRaftGroup_then_fail() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();

        int count = 3;
        for (int i = 1; i <= count; i++) {
            leader.replicate(applyValue("value" + i)).join();
        }

        long commitIndex = commitIndex(leader);
        try {
            leader.query(queryLastValue(), LEADER_LEASE, Optional.of(commitIndex + 1), Optional.empty()).join();
            fail();
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(LaggingCommitIndexException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_queryFromFollower_withLeaderLeasePolicy_then_fail() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();
        leader.replicate(applyValue("value")).join();

        try {
            group.anyNodeExcept(leader.localEndpoint())
                    .query(queryLastValue(), LEADER_LEASE, Optional.empty(), Optional.empty()).join();
            fail();
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(NotLeaderException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_queryFromFollowerWithoutCommitIndex_onStableRaftGroup_then_readLatestValue() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();

        int count = 3;
        for (int i = 1; i <= count; i++) {
            leader.replicate(applyValue("value" + i)).join();
        }

        String latestValue = "value" + count;

        eventually(() -> {
            long commitIndex = commitIndex(leader);
            for (RaftNode follower : group.<RaftNode>nodesExcept(leader.localEndpoint())) {
                assertThat(commitIndex(follower)).isEqualTo(commitIndex);
                Ordered<Object> result = follower
                        .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join();
                assertThat(result.getResult()).isEqualTo(latestValue);
                assertThat(result.getCommitIndex()).isEqualTo(commitIndex);
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_queryFromFollowerWithCommitIndex_onStableRaftGroup_then_readLatestValue() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();

        int count = 3;
        for (int i = 1; i <= count; i++) {
            leader.replicate(applyValue("value" + i)).join();
        }

        String latestValue = "value" + count;

        eventually(() -> {
            long commitIndex = commitIndex(leader);
            for (RaftNode follower : group.<RaftNode>nodesExcept(leader.localEndpoint())) {
                assertThat(commitIndex(follower)).isEqualTo(commitIndex);
                Ordered<Object> result = follower
                        .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.of(commitIndex), Optional.empty())
                        .join();
                assertThat(result.getResult()).isEqualTo(latestValue);
                assertThat(result.getCommitIndex()).isEqualTo(commitIndex);
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_queryFromSlowFollower_then_readStaleValue() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();
        RaftNode slowFollower = group.anyNodeExcept(leader.localEndpoint());

        Object firstValue = "value1";
        leader.replicate(applyValue(firstValue)).join();
        long leaderCommitIndex = commitIndex(leader);

        eventually(() -> assertThat(commitIndex(slowFollower)).isEqualTo(leaderCommitIndex));

        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);

        leader.replicate(applyValue("value2")).join();

        Ordered<Object> result = slowFollower
                .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join();
        assertThat(result.getResult()).isEqualTo(firstValue);
        assertThat(result.getCommitIndex()).isEqualTo(leaderCommitIndex);
    }

    @Test(timeout = 300_000)
    public void when_queryFromSlowFollower_then_eventuallyReadLatestValue() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();
        leader.replicate(applyValue("value1")).join();

        RaftNode slowFollower = group.anyNodeExcept(leader.localEndpoint());
        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);

        Object lastValue = "value2";
        leader.replicate(applyValue(lastValue)).join();

        group.allowAllMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint());

        eventually(() -> {
            long commitIndex = commitIndex(leader);
            Ordered<Object> result = slowFollower
                    .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join();
            assertThat(result.getResult()).isEqualTo(lastValue);
            assertThat(result.getCommitIndex()).isEqualTo(commitIndex);
        });
    }

    @Test(timeout = 300_000)
    public void when_queryFromSplitLeaderWithAnyLocal_then_readStaleValue() {
        group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);

        RaftNode leader = group.waitUntilLeaderElected();

        Object firstValue = "value1";
        leader.replicate(applyValue(firstValue)).join();
        long firstCommitIndex = commitIndex(leader);

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(firstCommitIndex);
            }
        });

        RaftNode followerNode = group.anyNodeExcept(leader.localEndpoint());
        group.splitMembers(leader.localEndpoint());

        eventually(() -> {
            RaftEndpoint leaderEndpoint = followerNode.leaderEndpoint();
            assertThat(leaderEndpoint).isNotNull().isNotEqualTo(leader.localEndpoint());
        });

        RaftNode newLeader = group.node(followerNode.leaderEndpoint());
        Object lastValue = "value2";
        newLeader.replicate(applyValue(lastValue)).join();
        long lastCommitIndex = commitIndex(newLeader);

        Ordered<Object> result1 = newLeader
                .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join();
        assertThat(result1.getResult()).isEqualTo(lastValue);
        assertThat(result1.getCommitIndex()).isEqualTo(lastCommitIndex);

        Ordered<Object> result2 = leader
                .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join();
        assertThat(result2.getResult()).isEqualTo(firstValue);
        assertThat(result2.getCommitIndex()).isEqualTo(firstCommitIndex);
    }

    @Test(timeout = 300_000)
    public void when_queryFromSplitLeaderWithLeaderLocal_then__readFailsAfterLeaderDemotesToFollower() {
        group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);

        RaftNode leader = group.waitUntilLeaderElected();

        Object firstValue = "value1";
        leader.replicate(applyValue(firstValue)).join();
        long firstCommitIndex = commitIndex(leader);

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(firstCommitIndex);
            }
        });

        group.splitMembers(leader.localEndpoint());

        eventually(() -> {
            try {
                leader.query(queryLastValue(), LEADER_LEASE, Optional.empty(), Optional.empty()).join();
                fail();
            } catch (CompletionException e) {
                assertThat(e).hasCauseInstanceOf(NotLeaderException.class);
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_queryFromSplitLeader_then_eventuallyReadLatestValue() {
        group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);

        RaftNode leader = group.waitUntilLeaderElected();

        Object firstValue = "value1";
        leader.replicate(applyValue(firstValue)).join();
        long leaderCommitIndex = commitIndex(leader);

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(leaderCommitIndex);
            }
        });

        RaftNode followerNode = group.anyNodeExcept(leader.localEndpoint());
        group.splitMembers(leader.localEndpoint());

        eventually(() -> {
            RaftEndpoint leaderEndpoint = followerNode.leaderEndpoint();
            assertThat(leaderEndpoint).isNotNull().isNotEqualTo(leader.localEndpoint());
        });

        RaftNode newLeader = group.node(followerNode.leaderEndpoint());
        Object lastValue = "value2";
        newLeader.replicate(applyValue(lastValue)).join();
        long lastCommitIndex = commitIndex(newLeader);

        group.merge();

        eventually(() -> {
            Ordered<Object> result = leader
                    .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join();
            assertThat(result.getResult()).isEqualTo(lastValue);
            assertThat(result.getCommitIndex()).isEqualTo(lastCommitIndex);
        });
    }

    @Test(timeout = 300_000)
    public void when_queryFromLearner_then_readLatestValue() {
        group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);

        RaftNode leader = group.waitUntilLeaderElected();

        Object value = "value";
        leader.replicate(applyValue(value)).join();

        RaftNode newFollower = group.createNewNode();

        leader.changeMembership(newFollower.localEndpoint(), ADD_LEARNER, 0).join();

        eventually(() -> assertThat(commitIndex(newFollower)).isEqualTo(commitIndex(leader)));

        Ordered<Object> result = newFollower
                .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join();

        assertThat(result.getCommitIndex()).isEqualTo(commitIndex(leader));
        assertThat(result.getResult()).isEqualTo(value);
    }

    @Test(timeout = 300_000)
    public void when_queryFromLearnerWithStaleCommitIndex_then_readStaleValue() {
        group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);

        RaftNode leader = group.waitUntilLeaderElected();

        Object value1 = "value1";
        leader.replicate(applyValue(value1)).join();

        RaftNode newFollower = group.createNewNode();

        long commitIndex1 = leader.changeMembership(newFollower.localEndpoint(), ADD_LEARNER, 0).join()
                                  .getCommitIndex();

        eventually(() -> assertThat(commitIndex(newFollower)).isEqualTo(commitIndex1));

        group.dropMessagesTo(leader.localEndpoint(), newFollower.localEndpoint(), AppendEntriesRequest.class);

        Object value2 = "value2";
        leader.replicate(applyValue(value2)).join();

        Ordered<Object> result = newFollower
                .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.of(commitIndex1), Optional.empty()).join();
        assertThat(result.getResult()).isEqualTo(value1);
        assertThat(result.getCommitIndex()).isEqualTo(commitIndex1);
    }

    @Test(timeout = 300_000)
    public void when_queryFromLearnerWithInvalidCommitIndex_then_queryFails() {
        group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);

        RaftNode leader = group.waitUntilLeaderElected();

        Object value = "value";
        leader.replicate(applyValue(value)).join();

        RaftNode newFollower = group.createNewNode();

        leader.changeMembership(newFollower.localEndpoint(), ADD_LEARNER, 0).join();

        eventually(() -> assertThat(commitIndex(newFollower)).isEqualTo(commitIndex(leader)));

        try {
            newFollower.query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.of(commitIndex(leader) + 1),
                    Optional.empty()).join();
            fail();
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(LaggingCommitIndexException.class);
        }
    }

}
