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

import static io.microraft.impl.local.SimpleStateMachine.applyValue;
import static io.microraft.test.util.AssertionUtils.allTheTime;
import static io.microraft.test.util.AssertionUtils.eventually;
import static io.microraft.test.util.RaftTestUtils.TEST_RAFT_CONFIG;
import static io.microraft.test.util.RaftTestUtils.commitIndex;
import static io.microraft.test.util.RaftTestUtils.lastLogOrSnapshotEntry;
import static io.microraft.test.util.RaftTestUtils.role;
import static io.microraft.test.util.RaftTestUtils.term;
import static io.microraft.test.util.RaftTestUtils.votedEndpoint;
import static io.microraft.test.util.RaftTestUtils.majority;
import static io.microraft.test.util.RaftTestUtils.minority;
import static io.microraft.test.util.AssertionUtils.sleepMillis;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import org.junit.After;
import org.junit.Test;

import io.microraft.Ordered;
import io.microraft.RaftConfig;
import io.microraft.RaftEndpoint;
import io.microraft.RaftNode;
import io.microraft.RaftRole;
import io.microraft.exception.CannotReplicateException;
import io.microraft.exception.IndeterminateStateException;
import io.microraft.exception.NotLeaderException;
import io.microraft.impl.local.LocalRaftGroup;
import io.microraft.impl.local.SimpleStateMachine;
import io.microraft.model.message.AppendEntriesRequest;
import io.microraft.model.message.AppendEntriesSuccessResponse;
import io.microraft.model.message.VoteRequest;
import io.microraft.test.util.BaseTest;

public class RaftTest extends BaseTest {

    private LocalRaftGroup group;

    @After
    public void destroy() {
        if (group != null) {
            group.destroy();
        }
    }

    @Test(timeout = 300_000)
    public void when_2NodeRaftGroupIsStarted_then_leaderIsElected() {
        testLeaderElection(2);
    }

    private void testLeaderElection(int nodeCount) {
        group = LocalRaftGroup.start(nodeCount);
        group.waitUntilLeaderElected();

        RaftEndpoint leaderEndpoint = group.leaderEndpoint();
        assertThat(leaderEndpoint).isNotNull();

        RaftNode leaderNode = group.leaderNode();
        assertThat(leaderNode).isNotNull();

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(node.leaderEndpoint()).isEqualTo(leaderEndpoint);
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_3NodeRaftGroupIsStarted_then_leaderIsElected() {
        testLeaderElection(3);
    }

    @Test(timeout = 300_000)
    public void when_2NodeRaftGroupIsStarted_then_singleEntryCommitted() {
        testSingleCommitEntry(2);
    }

    private void testSingleCommitEntry(int nodeCount) {
        group = LocalRaftGroup.start(nodeCount);
        RaftNode leader = group.waitUntilLeaderElected();

        Object val = "val";
        Ordered<Object> result = leader.replicate(applyValue(val)).join();
        assertThat(result.getResult()).isEqualTo(val);
        assertThat(result.getCommitIndex()).isEqualTo(1);

        int expectedCommitIndex = 1;
        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(expectedCommitIndex);
                SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
                Object actual = stateMachine.get(expectedCommitIndex);
                assertThat(actual).isEqualTo(val);
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_3NodeRaftGroupIsStarted_then_singleEntryCommitted() {
        testSingleCommitEntry(3);
    }

    @Test(timeout = 300_000)
    public void when_followerAttemptsToReplicate_then_itFails() {
        group = LocalRaftGroup.start(3);
        RaftNode leader = group.waitUntilLeaderElected();
        RaftNode follower = group.anyNodeExcept(leader.localEndpoint());

        try {
            follower.replicate(applyValue("val")).join();
            fail();
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(NotLeaderException.class);
        }

        for (RaftNode node : group.nodes()) {
            SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
            assertThat(stateMachine.size()).isEqualTo(0);
        }
    }

    @Test(timeout = 300_000)
    public void when_4NodeRaftGroupIsStarted_then_leaderCannotCommitWithOnlyLocalAppend() throws Exception {
        testNoCommitWhenOnlyLeaderAppends(4);
    }

    private void testNoCommitWhenOnlyLeaderAppends(int nodeCount) throws Exception {
        group = LocalRaftGroup.start(nodeCount);
        RaftNode leader = group.waitUntilLeaderElected();

        group.dropMessagesToAll(leader.localEndpoint(), AppendEntriesRequest.class);

        try {
            leader.replicate(applyValue("val")).get(5, SECONDS);
            fail();
        } catch (TimeoutException ignored) {
        }

        for (RaftNode node : group.nodes()) {
            assertThat(commitIndex(node)).isEqualTo(0);
            SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
            assertThat(stateMachine.size()).isEqualTo(0);
        }
    }

    @Test(timeout = 300_000)
    public void when_3NodeRaftGroupIsStarted_then_leaderCannotCommitWithOnlyLocalAppend() throws Exception {
        testNoCommitWhenOnlyLeaderAppends(3);
    }

    @Test(timeout = 300_000)
    public void when_leaderAppendsToMinority_then_itCannotCommit() throws Exception {
        group = LocalRaftGroup.start(5);
        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());

        for (int i = 1; i < followers.size(); i++) {
            group.dropMessagesTo(leader.localEndpoint(), followers.get(i).localEndpoint(),
                                 AppendEntriesRequest.class);
        }

        Future<Ordered<Object>> f = leader.replicate(applyValue("val"));

        eventually(() -> {
            assertThat(lastLogOrSnapshotEntry(leader).getIndex()).isEqualTo(1);
            assertThat(lastLogOrSnapshotEntry(followers.get(0)).getIndex()).isEqualTo(1);
        });

        try {
            f.get(5, SECONDS);
            fail();
        } catch (TimeoutException ignored) {
        }

        for (RaftNode node : group.nodes()) {
            assertThat(commitIndex(node)).isEqualTo(0);
            SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
            assertThat(stateMachine.size()).isEqualTo(0);
        }
    }

    @Test(timeout = 300_000)
    public void when_4NodeRaftGroupIsStarted_then_leaderReplicateEntriesSequentially() {
        testReplicateEntriesSequentially(4);
    }

    private void testReplicateEntriesSequentially(int nodeCount) {
        int entryCount = 100;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount + 2).build();
        group = LocalRaftGroup.start(nodeCount, config);
        RaftNode leader = group.waitUntilLeaderElected();

        Optional<Long> quorumTimestamp = leader.report().join().getResult().quorumHeartbeatTimestamp();
        assertThat(quorumTimestamp).isPresent();

        Map<RaftEndpoint, Long> leaderHeartbeatTimestamps = new HashMap<>();

        for (int i = 0; i < entryCount; i++) {
            sleepMillis(1);
            Object val = "val" + i;
            Ordered<Object> result = leader.replicate(applyValue(val)).join();
            assertThat(result.getCommitIndex()).isEqualTo(i + 1);
            Optional<Long> quorumTimestamp2 = leader.report().join().getResult().quorumHeartbeatTimestamp();
            assertTrue(quorumTimestamp2.isPresent());
            assertThat(quorumTimestamp2.get()).isGreaterThan(quorumTimestamp.get());
            quorumTimestamp = quorumTimestamp2;

            for (RaftNode node : group.nodesExcept(leader.localEndpoint())) {
                Optional<Long> leaderHearthbeatTimestamp = node.report().join().getResult()
                                                               .leaderHeartbeatTimestamp();
                assertThat(leaderHearthbeatTimestamp).isPresent();
                long previousTimestamp = leaderHeartbeatTimestamps.getOrDefault(node.localEndpoint(), 0L);
                assertThat(leaderHearthbeatTimestamp.get()).isGreaterThan(previousTimestamp);
                leaderHeartbeatTimestamps.put(node.localEndpoint(), previousTimestamp);
            }
        }

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(entryCount);
                SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
                assertThat(stateMachine.size()).isEqualTo(100);
                for (int i = 0; i < entryCount; i++) {
                    int commitIndex = i + 1;
                    Object val = "val" + i;
                    assertThat(stateMachine.get(commitIndex)).isEqualTo(val);
                }
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_5NodeRaftGroupIsStarted_then_leaderReplicateEntriesSequentially() {
        testReplicateEntriesSequentially(5);
    }

    @Test(timeout = 300_000)
    public void when_4NodeRaftGroupIsStarted_then_leaderReplicatesEntriesConcurrently() {
        testReplicateEntriesConcurrently(4);
    }

    private void testReplicateEntriesConcurrently(int nodeCount) {
        int entryCount = 100;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount + 2).build();
        group = LocalRaftGroup.start(nodeCount, config);
        RaftNode leader = group.waitUntilLeaderElected();

        List<CompletableFuture<Ordered<Object>>> futures = new ArrayList<>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            Object val = "val" + i;
            futures.add(leader.replicate(applyValue(val)));
        }

        Set<Long> commitIndices = new HashSet<>();
        for (CompletableFuture<Ordered<Object>> f : futures) {
            long commitIndex = f.join().getCommitIndex();
            assertTrue(commitIndices.add(commitIndex));
        }

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(entryCount);
                SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
                assertThat(stateMachine.size()).isEqualTo(100);
                Set<Object> values = stateMachine.valueSet();
                for (int i = 0; i < entryCount; i++) {
                    Object val = "val" + i;
                    assertThat(values.contains(val)).isTrue();
                }
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_5NodeRaftGroupIsStarted_then_leaderReplicatesEntriesConcurrently() {
        testReplicateEntriesConcurrently(5);
    }

    @Test(timeout = 300_000)
    public void when_4NodeRaftGroupIsStarted_then_entriesAreSubmittedInParallel() throws Exception {
        testReplicateEntriesInParallel(4);
    }

    private void testReplicateEntriesInParallel(int nodeCount) throws Exception {
        int threadCount = 10;
        int opsPerThread = 10;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(threadCount * opsPerThread + 2)
                .build();
        group = LocalRaftGroup.start(nodeCount, config);
        RaftNode leader = group.waitUntilLeaderElected();

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            int start = i * opsPerThread;
            threads[i] = new Thread(() -> {
                List<CompletableFuture<Ordered<Object>>> futures = new ArrayList<>();
                for (int j = start; j < start + opsPerThread; j++) {
                    futures.add(leader.replicate(applyValue(j)));
                }

                for (CompletableFuture<Ordered<Object>> f : futures) {
                    f.join();
                }
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        int entryCount = threadCount * opsPerThread;

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(entryCount);
                SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
                assertThat(stateMachine.size()).isEqualTo(entryCount);
                Set<Object> values = stateMachine.valueSet();
                for (int i = 0; i < entryCount; i++) {
                    assertThat(values.contains(i)).isTrue();
                }
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_5NodeRaftGroupIsStarted_then_entriesAreSubmittedInParallel() throws Exception {
        testReplicateEntriesInParallel(5);
    }

    @Test(timeout = 300_000)
    public void when_followerSlowsDown_then_itCatchesLeaderEventually() {
        int entryCount = 100;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount + 2).build();
        group = LocalRaftGroup.start(3, config);
        RaftNode leader = group.waitUntilLeaderElected();
        RaftNode slowFollower = group.anyNodeExcept(leader.localEndpoint());

        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);

        for (int i = 0; i < entryCount; i++) {
            Object val = "val" + i;
            leader.replicate(applyValue(val)).join();
        }

        assertThat(commitIndex(slowFollower)).isEqualTo(0);

        group.resetAllRulesFrom(leader.localEndpoint());

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(entryCount);
                SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
                assertThat(stateMachine.size()).isEqualTo(entryCount);
                Set<Object> values = stateMachine.valueSet();
                for (int i = 0; i < entryCount; i++) {
                    Object val = "val" + i;
                    assertThat(values.contains(val)).isTrue();
                }
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_disruptiveFollowerStartsElection_then_itCannotTakeOverLeadershipFromLegitimateLeader() {
        group = LocalRaftGroup.start(3);

        RaftNode leader = group.waitUntilLeaderElected();
        int leaderTerm = term(leader);
        RaftNode disruptiveFollower = group.anyNodeExcept(leader.localEndpoint());

        RaftEndpoint disruptiveEndpoint = disruptiveFollower.localEndpoint();
        group.dropMessagesTo(leader.localEndpoint(), disruptiveEndpoint, AppendEntriesRequest.class);

        leader.replicate(applyValue("val")).join();

        group.splitMembers(disruptiveEndpoint);

        int[] disruptiveFollowerTermRef = new int[1];
        allTheTime(() -> {
            int followerTerm = term(disruptiveFollower);
            assertThat(followerTerm).isEqualTo(leaderTerm);
            disruptiveFollowerTermRef[0] = followerTerm;
        }, 3);

        group.resetAllRulesFrom(leader.localEndpoint());
        group.merge();

        RaftNode newLeader = group.waitUntilLeaderElected();
        RaftEndpoint newLeaderEndpoint = newLeader.localEndpoint();
        assertThat(newLeaderEndpoint).isNotEqualTo(disruptiveEndpoint);
        assertThat(disruptiveFollowerTermRef[0]).isEqualTo(term(newLeader));
    }

    @Test(timeout = 300_000)
    public void when_followerTerminatesInMinority_then_clusterRemainsAvailable() {
        group = LocalRaftGroup.start(3);
        RaftNode leader = group.waitUntilLeaderElected();

        group.terminateNode(group.anyNodeExcept(leader.localEndpoint()).localEndpoint());

        String value = "value";
        Ordered<Object> result = leader.replicate(applyValue(value)).join();
        assertThat(result.getResult()).isEqualTo(value);
    }

    @Test(timeout = 300_000)
    public void when_leaderTerminatesInMinority_then_clusterRemainsAvailable() {
        group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);

        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        int leaderTerm = term(leader);

        group.terminateNode(leader.localEndpoint());

        eventually(() -> {
            for (RaftNode node : followers) {
                assertThat(node.leaderEndpoint()).isNotNull().isNotEqualTo(leader.localEndpoint());
            }
        });

        RaftNode newLeader = group.waitUntilLeaderElected();
        assertThat(term(newLeader)).isGreaterThan(leaderTerm);

        String value = "value";
        Ordered<Object> result = newLeader.replicate(applyValue(value)).join();
        assertThat(result.getResult()).isEqualTo(value);
    }

    @Test(timeout = 300_000)
    public void when_leaderStaysInMajorityDuringSplit_thenItMergesBackSuccessfully() {
        group = LocalRaftGroup.start(5, TEST_RAFT_CONFIG);
        group.waitUntilLeaderElected();

        List<RaftEndpoint> minoritySplitMembers = group.randomNodes(minority(5), false);
        group.splitMembers(minoritySplitMembers);

        eventually(() -> {
            for (RaftEndpoint endpoint : minoritySplitMembers) {
                RaftNode node = group.node(endpoint);
                assertThat(node.leaderEndpoint()).isNull();
            }
        });

        group.merge();
        group.waitUntilLeaderElected();
    }

    @Test(timeout = 300_000)
    public void when_leaderStaysInMinorityDuringSplit_thenItMergesBackSuccessfully() {
        int nodeCount = 5;
        group = LocalRaftGroup.start(nodeCount, TEST_RAFT_CONFIG);
        RaftEndpoint leaderEndpoint = group.waitUntilLeaderElected().localEndpoint();

        List<RaftEndpoint> majoritySplitMembers = group.randomNodes(majority(nodeCount), false);
        group.splitMembers(majoritySplitMembers);

        eventually(() -> {
            for (RaftEndpoint endpoint : majoritySplitMembers) {
                RaftNode node = group.node(endpoint);
                assertThat(node.leaderEndpoint()).isNotNull().isNotEqualTo(leaderEndpoint);
            }
        });

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                if (!majoritySplitMembers.contains(node.localEndpoint())) {
                    assertThat(node.leaderEndpoint()).isNull();
                }
            }
        });

        group.merge();
        group.waitUntilLeaderElected();
    }

    @Test(timeout = 300_000)
    public void when_leaderCrashes_then_theFollowerWithLongestLogBecomesLeader() {
        group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);

        RaftNode leader = group.waitUntilLeaderElected();

        leader.replicate(applyValue("val1")).join();

        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode nextLeader = followers.get(0);
        long commitIndex = commitIndex(leader);

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(commitIndex);
            }
        });

        group.dropMessagesTo(leader.localEndpoint(), followers.get(1).localEndpoint(),
                             AppendEntriesRequest.class);
        group.dropMessagesTo(nextLeader.localEndpoint(), leader.localEndpoint(),
                             AppendEntriesSuccessResponse.class);

        leader.replicate(applyValue("val2"));

        eventually(() -> assertThat(lastLogOrSnapshotEntry(nextLeader).getIndex()).isGreaterThan(commitIndex));

        allTheTime(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(commitIndex);
            }
        }, 3);

        group.terminateNode(leader.localEndpoint());

        eventually(() -> {
            for (RaftNode node : followers) {
                assertThat(node.leaderEndpoint()).isEqualTo(nextLeader.localEndpoint());
            }
        });

        // new leader cannot commit "val2" before replicating a new entry in its term

        allTheTime(() -> {
            for (RaftNode node : followers) {
                assertThat(commitIndex(node)).isEqualTo(commitIndex);
                assertThat(lastLogOrSnapshotEntry(node).getIndex()).isGreaterThan(commitIndex);
            }
        }, 3);
    }

    @Test(timeout = 300_000)
    public void when_followerBecomesLeaderWithUncommittedEntries_then_thoseEntriesAreCommittedWithANewEntryOfNewTerm() {
        group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);

        RaftNode leader = group.waitUntilLeaderElected();

        leader.replicate(applyValue("val1")).join();

        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode nextLeader = followers.get(0);
        long commitIndex = commitIndex(leader);

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(commitIndex);
            }
        });

        group.dropMessagesTo(leader.localEndpoint(), followers.get(1).localEndpoint(),
                             AppendEntriesRequest.class);
        group.dropMessagesTo(nextLeader.localEndpoint(), leader.localEndpoint(),
                             AppendEntriesSuccessResponse.class);

        leader.replicate(applyValue("val2"));

        eventually(() -> assertThat(lastLogOrSnapshotEntry(nextLeader).getIndex()).isGreaterThan(commitIndex));

        allTheTime(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(commitIndex);
            }
        }, 3);

        group.terminateNode(leader.localEndpoint());

        eventually(() -> {
            for (RaftNode node : followers) {
                assertThat(node.leaderEndpoint()).isEqualTo(nextLeader.localEndpoint());
            }
        });

        nextLeader.replicate(applyValue("val3"));

        eventually(() -> {
            for (RaftNode node : followers) {
                assertThat(commitIndex(node)).isEqualTo(3);
                assertThat(lastLogOrSnapshotEntry(node).getIndex()).isEqualTo(3);
                SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
                assertThat(stateMachine.size()).isEqualTo(3);
                assertThat(stateMachine.get(1)).isEqualTo("val1");
                assertThat(stateMachine.get(2)).isEqualTo("val2");
                assertThat(stateMachine.get(3)).isEqualTo("val3");
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_leaderCrashes_then_theFollowerWithLongestLogMayNotBecomeLeaderIfItsLogIsNotMajority() {
        group = LocalRaftGroup.start(5, TEST_RAFT_CONFIG);

        RaftNode leader = group.waitUntilLeaderElected();

        leader.replicate(applyValue("val1")).join();

        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode followerWithLongestLog = followers.get(0);
        long commitIndex = commitIndex(leader);

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(commitIndex);
            }
        });

        for (int i = 1; i < followers.size(); i++) {
            group.dropMessagesTo(leader.localEndpoint(), followers.get(i).localEndpoint(),
                                 AppendEntriesRequest.class);
        }

        leader.replicate(applyValue("val2"));

        eventually(() -> assertThat(lastLogOrSnapshotEntry(followerWithLongestLog).getIndex())
                .isGreaterThan(commitIndex));

        allTheTime(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(commitIndex);
            }
        }, 3);

        group.dropMessagesTo(followerWithLongestLog.localEndpoint(), followers.get(1).localEndpoint(),
                             VoteRequest.class);
        group.dropMessagesTo(followerWithLongestLog.localEndpoint(), followers.get(2).localEndpoint(),
                             VoteRequest.class);
        group.dropMessagesTo(followerWithLongestLog.localEndpoint(), followers.get(3).localEndpoint(),
                             VoteRequest.class);

        group.terminateNode(leader.localEndpoint());

        RaftNode newLeader = group.waitUntilLeaderElected();

        // followerWithLongestLog has 2 entries, other 3 followers have 1 entry
        // and those 3 followers will elect a leader among themselves

        eventually(() -> {
            for (RaftNode node : followers) {
                RaftEndpoint l = node.leaderEndpoint();
                assertThat(l).isNotEqualTo(leader.localEndpoint());
                assertThat(l).isNotEqualTo(followerWithLongestLog.localEndpoint());
            }
        });

        for (int i = 1; i < followers.size(); i++) {
            assertThat(commitIndex(followers.get(i))).isEqualTo(commitIndex);
            assertThat(lastLogOrSnapshotEntry(followers.get(i)).getIndex()).isEqualTo(commitIndex);
        }

        // followerWithLongestLog does not truncate its extra log entry until the new
        // leader appends a new entry
        assertThat(lastLogOrSnapshotEntry(followerWithLongestLog).getIndex()).isGreaterThan(commitIndex);

        newLeader.replicate(applyValue("val3")).join();

        eventually(() -> {
            for (RaftNode follower : followers) {
                assertThat(commitIndex(follower)).isEqualTo(2);
                SimpleStateMachine stateMachine = group.stateMachine(follower.localEndpoint());
                assertThat(stateMachine.size()).isEqualTo(2);
                assertThat(stateMachine.get(1)).isEqualTo("val1");
                assertThat(stateMachine.get(2)).isEqualTo("val3");
            }
        });

        assertThat(lastLogOrSnapshotEntry(followerWithLongestLog).getIndex()).isEqualTo(2);
    }

    @Test(timeout = 300_000)
    public void when_leaderStaysInMinorityDuringSplit_then_itCannotCommitNewEntries() {
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(100).setLeaderHeartbeatPeriodSecs(1)
                .setLeaderHeartbeatTimeoutSecs(5).build();
        group = LocalRaftGroup.start(3, config);

        RaftNode leader = group.waitUntilLeaderElected();

        leader.replicate(applyValue("val1")).join();

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(1);
            }
        });

        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        group.splitMembers(leader.localEndpoint());

        eventually(() -> {
            for (RaftNode node : followers) {
                assertThat(node.leaderEndpoint()).isNotNull().isNotEqualTo(leader.localEndpoint());
            }
            assertThat(leader.leaderEndpoint()).isNull();
        });

        List<CompletableFuture<Ordered<Object>>> isolatedFutures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            isolatedFutures.add(leader.replicate(applyValue("isolated" + i)));
        }

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
        RaftEndpoint finalLeaderEndpoint = finalLeader.localEndpoint();
        assertThat(finalLeaderEndpoint).isNotEqualTo(leader.localEndpoint());
        for (CompletableFuture<Ordered<Object>> f : isolatedFutures) {
            try {
                f.join();
                fail();
            } catch (CompletionException e) {
                assertThat(e).hasCauseInstanceOf(NotLeaderException.class);
            }
        }

        eventually(() -> {
            for (RaftNode node : followers) {
                SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
                assertThat(stateMachine.size()).isEqualTo(11);
                assertThat(stateMachine.get(1)).isEqualTo("val1");
                for (int i = 0; i < 10; i++) {
                    assertThat(stateMachine.get(i + 2)).isEqualTo("valNew" + i);
                }
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_thereAreTooManyInflightAppendedEntries_then_newAppendsAreRejected() {
        int pendingEntryCount = 10;
        RaftConfig config = RaftConfig.newBuilder().setMaxPendingLogEntryCount(pendingEntryCount).build();
        group = LocalRaftGroup.start(3, config);

        RaftNode leader = group.waitUntilLeaderElected();

        for (RaftNode follower : group.nodesExcept(leader.localEndpoint())) {
            group.terminateNode(follower.localEndpoint());
        }

        for (int i = 0; i < pendingEntryCount; i++) {
            leader.replicate(applyValue("val" + i));
        }

        try {
            leader.replicate(applyValue("valFinal")).join();
            fail();
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(CannotReplicateException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_leaderStaysInMinority_then_itDemotesItselfToFollower() {
        group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);

        RaftNode leader = group.waitUntilLeaderElected();

        group.splitMembers(leader.localEndpoint());
        CompletableFuture<Ordered<Object>> f = leader.replicate(applyValue("val"));

        eventually(() -> assertThat(role(leader)).isEqualTo(RaftRole.FOLLOWER));

        try {
            f.join();
            fail();
        } catch (CompletionException e) {
            assertThat(e).satisfiesAnyOf(e2 -> assertThat(e2).hasCauseInstanceOf(IndeterminateStateException.class),
                    e2 -> assertThat(e2).hasCauseInstanceOf(NotLeaderException.class));
        }

        assertThat(leader.report().join().getResult().quorumHeartbeatTimestamp()).isEmpty();
        assertThat(leader.report().join().getResult().leaderHeartbeatTimestamp()).isEmpty();
    }

    @Test(timeout = 300_000)
    public void when_leaderDemotesToFollower_then_itShouldNotDeleteItsVote() {
        group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);

        RaftNode leader = group.waitUntilLeaderElected();

        assertThat(votedEndpoint(leader)).isEqualTo(leader.localEndpoint());

        group.splitMembers(leader.localEndpoint());

        eventually(() -> assertThat(role(leader)).isEqualTo(RaftRole.FOLLOWER));

        assertThat(votedEndpoint(leader)).isEqualTo(leader.localEndpoint());
    }

    @Test(timeout = 300_000)
    public void when_leaderTerminates_then_itFailsPendingFuturesWithIndeterminateStateException() {
        group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);

        RaftNode leader = group.waitUntilLeaderElected();
        for (RaftNode follower : group.nodesExcept(leader.leaderEndpoint())) {
            group.dropMessagesTo(leader.localEndpoint(), follower.localEndpoint(), AppendEntriesRequest.class);
        }

        CompletableFuture<Ordered<Object>> f = leader.replicate(applyValue("val"));

        eventually(() -> assertThat(lastLogOrSnapshotEntry(leader).getIndex()).isGreaterThan(0));

        leader.terminate().join();

        assertThat(leader.leaderEndpoint()).isNull();
        assertThat(f.isCompletedExceptionally());
        try {
            f.join();
            fail();
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(IndeterminateStateException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_evenSizedRaftGroupRunning_then_logReplicationQuorumCanBeDecreased() {
        group = LocalRaftGroup.start(4, TEST_RAFT_CONFIG);

        RaftNode leader = group.waitUntilLeaderElected();

        group.anyNodeExcept(leader.localEndpoint()).terminate().join();
        group.anyNodeExcept(leader.localEndpoint()).terminate().join();

        leader.replicate(applyValue("val")).join();
    }

    @Test(timeout = 300_000)
    public void when_initialGroupMembersIncludeLearners_then_leaderIsElected() {
        group = LocalRaftGroup.start(4, 2, TEST_RAFT_CONFIG);
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        int followerCount = 0;
        int learnerCount = 0;
        for (RaftNode node : group.<RaftNode>nodesExcept(leader.localEndpoint())) {
            RaftRole role = role(node);
            if (role == RaftRole.FOLLOWER) {
                followerCount++;
            } else if (role == RaftRole.LEARNER) {
                learnerCount++;
            } else {
                fail();
            }
        }

        assertThat(followerCount).isEqualTo(1);
        assertThat(learnerCount).isEqualTo(2);
    }

    @Test(timeout = 300_000)
    public void when_initialGroupMembersContainSingleVotingMemberAndMultipleLearners_then_leaderIsElected() {
        group = LocalRaftGroup.start(3, 1, TEST_RAFT_CONFIG);
        group.start();

        RaftNode leader = group.waitUntilLeaderElected();
        int learnerCount = 0;
        for (RaftNode node : group.<RaftNode>nodesExcept(leader.localEndpoint())) {
            if (role(node) == RaftRole.LEARNER) {
                learnerCount++;
            } else {
                fail();
            }
        }

        assertThat(learnerCount).isEqualTo(2);
    }

}
