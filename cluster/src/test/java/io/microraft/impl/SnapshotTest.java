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
import static io.microraft.RaftNodeStatus.ACTIVE;
import static io.microraft.impl.local.SimpleStateMachine.applyValue;
import static io.microraft.impl.log.RaftLog.FIRST_VALID_LOG_INDEX;
import static io.microraft.test.util.AssertionUtils.eventually;
import static io.microraft.test.util.RaftTestUtils.commitIndex;
import static io.microraft.test.util.RaftTestUtils.committedGroupMembers;
import static io.microraft.test.util.RaftTestUtils.lastLogOrSnapshotEntry;
import static io.microraft.test.util.RaftTestUtils.matchIndex;
import static io.microraft.test.util.RaftTestUtils.role;
import static io.microraft.test.util.RaftTestUtils.snapshotChunkCollector;
import static io.microraft.test.util.RaftTestUtils.snapshotEntry;
import static io.microraft.test.util.RaftTestUtils.status;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.junit.After;
import org.junit.Test;

import io.microraft.Ordered;
import io.microraft.RaftConfig;
import io.microraft.RaftNode;
import io.microraft.RaftRole;
import io.microraft.exception.IndeterminateStateException;
import io.microraft.impl.local.LocalRaftGroup;
import io.microraft.impl.local.SimpleStateMachine;
import io.microraft.impl.log.SnapshotChunkCollector;
import io.microraft.model.impl.groupop.DefaultUpdateRaftGroupMembersOpOrBuilder;
import io.microraft.model.impl.message.DefaultAppendEntriesRequestOrBuilder;
import io.microraft.model.message.AppendEntriesFailureResponse;
import io.microraft.model.message.AppendEntriesRequest;
import io.microraft.model.message.AppendEntriesSuccessResponse;
import io.microraft.model.message.InstallSnapshotRequest;
import io.microraft.model.message.InstallSnapshotResponse;
import io.microraft.model.message.RaftMessage;
import io.microraft.report.RaftGroupMembers;
import io.microraft.report.RaftNodeReport;
import io.microraft.test.util.BaseTest;

public class SnapshotTest extends BaseTest {

    private LocalRaftGroup group;

    @After
    public void destroy() {
        if (group != null) {
            group.destroy();
        }
    }

    @Test(timeout = 300_000)
    public void when_commitLogAdvances_then_snapshotIsTaken() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount).build();
        group = LocalRaftGroup.start(3, config);

        var leader = group.waitUntilLeaderElected();

        for (int i = 0; i < entryCount; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> {
            for (var node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(entryCount);
                assertThat(snapshotEntry(node).getIndex()).isEqualTo(entryCount);

                var stateMachine = group.stateMachine(node.localEndpoint());
                assertThat(stateMachine.size()).isEqualTo(entryCount);
                for (int i = 0; i < entryCount; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_snapshotIsTaken_then_nextEntryIsCommitted() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount).build();
        group = LocalRaftGroup.start(3, config);

        var leader = group.waitUntilLeaderElected();

        for (int i = 0; i < entryCount; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> {
            for (var node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(entryCount);
                assertThat(snapshotEntry(node).getIndex()).isEqualTo(entryCount);
            }
        });

        leader.replicate(applyValue("valFinal")).join();

        eventually(() -> {
            for (var node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(entryCount + 1);
                SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
                assertThat(stateMachine.size()).isEqualTo(entryCount + 1);
                for (int i = 0; i < entryCount; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
                assertThat(stateMachine.get(51)).isEqualTo("valFinal");
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_followersMatchIndexIsUnknown_then_itInstallsSnapshotFromLeaderOnly() {
        when_followersMatchIndexIsUnknown_then_itInstallsSnapshot(false);
    }

    @Test(timeout = 300_000)
    public void when_followersMatchIndexIsUnknown_then_itInstallsSnapshotFromLeaderAndFollowers() {
        when_followersMatchIndexIsUnknown_then_itInstallsSnapshot(true);
    }

    private void when_followersMatchIndexIsUnknown_then_itInstallsSnapshot(
            boolean transferSnapshotFromFollowersEnabled) {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount)
                .setTransferSnapshotsFromFollowersEnabled(transferSnapshotFromFollowersEnabled).build();
        group = LocalRaftGroup.start(3, config);

        RaftNode leader = group.waitUntilLeaderElected();

        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode slowFollower = followers.get(1);

        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);

        for (int i = 0; i < entryCount; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> assertThat(snapshotEntry(leader).getIndex()).isEqualTo(entryCount));

        leader.replicate(applyValue("valFinal")).join();

        group.allowMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);
        group.allowMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);

        eventually(() -> assertThat(commitIndex(slowFollower)).isEqualTo(commitIndex(leader)));

        group.resetAllRulesFrom(leader.localEndpoint());

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(entryCount + 1);
                SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
                assertThat(stateMachine.size()).isEqualTo(entryCount + 1);
                for (int i = 0; i < entryCount; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
                assertThat(stateMachine.get(51)).isEqualTo("valFinal");
            }
        });

        assertThat(snapshotChunkCollector(slowFollower)).isNull();
    }

    @Test(timeout = 300_000)
    public void when_leaderKnowsOthersSnapshot_then_slowFollowerInstallsSnapshotFromLeaderWithoutOptimization() {
        when_leaderKnowsOtherFollowerInstalledSnapshot_then_slowFollowerInstallsSnapshot(false);
    }

    @Test(timeout = 300_000)
    public void when_leaderKnowsOthersSnapshot_then_slowFollowerInstallsSnapshotFromAllWithOptimization() {
        when_leaderKnowsOtherFollowerInstalledSnapshot_then_slowFollowerInstallsSnapshot(true);
    }

    private void when_leaderKnowsOtherFollowerInstalledSnapshot_then_slowFollowerInstallsSnapshot(
            boolean transferSnapshotFromFollowersEnabled) {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount)
                .setTransferSnapshotsFromFollowersEnabled(transferSnapshotFromFollowersEnabled).build();
        group = LocalRaftGroup.start(3, config);

        RaftNode leader = group.waitUntilLeaderElected();

        leader.replicate(applyValue("val0")).join();

        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        eventually(() -> {
            for (RaftNode follower : followers) {
                assertThat(commitIndex(follower)).isEqualTo(commitIndex(leader));
            }
        });

        RaftNode slowFollower = followers.get(1);

        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);

        for (int i = 1; i < entryCount; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> assertThat(snapshotEntry(leader).getIndex()).isEqualTo(entryCount));

        leader.replicate(applyValue("valFinal")).join();

        group.allowMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);
        group.allowMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);

        eventually(() -> assertThat(commitIndex(slowFollower)).isEqualTo(commitIndex(leader)));

        group.resetAllRulesFrom(leader.localEndpoint());

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(entryCount + 1);
                SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
                assertThat(stateMachine.size()).isEqualTo(entryCount + 1);
                for (int i = 0; i < entryCount; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
                assertThat(stateMachine.get(51)).isEqualTo("valFinal");
            }
        });

        assertThat(snapshotChunkCollector(slowFollower)).isNull();
    }

    @Test(timeout = 300_000)
    public void when_leaderDoesNotKnowOthersSnapshot_then_slowFollowerInstallsSnapshotFromLeaderOnlyWithoutOptimization() {
        when_leaderDoesNotKnowOtherFollowerInstalledSnapshot_then_slowFollowerInstallsSnapshotFromLeaderOnly(false);
    }

    @Test(timeout = 300_000)
    public void when_leaderDoesNotKnowOthersSnapshot_then_slowFollowerInstallsSnapshotFromLeaderOnlyWithOptimization() {
        when_leaderDoesNotKnowOtherFollowerInstalledSnapshot_then_slowFollowerInstallsSnapshotFromLeaderOnly(true);
    }

    private void when_leaderDoesNotKnowOtherFollowerInstalledSnapshot_then_slowFollowerInstallsSnapshotFromLeaderOnly(
            boolean transferSnapshotFromFollowersEnabled) {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount)
                .setTransferSnapshotsFromFollowersEnabled(transferSnapshotFromFollowersEnabled).build();
        group = LocalRaftGroup.start(3, config);

        RaftNode leader = group.waitUntilLeaderElected();

        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode fastFollower = followers.get(0);
        RaftNode slowFollower = followers.get(1);

        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);

        while (snapshotEntry(leader).getIndex() == 0) {
            leader.replicate(applyValue("val")).join();
        }

        eventually(() -> assertThat(snapshotEntry(leader).getIndex()).isGreaterThan(0));
        eventually(() -> assertThat(snapshotEntry(fastFollower).getIndex()).isGreaterThan(0));

        group.allowMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);
        group.allowMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);

        eventually(() -> assertThat(commitIndex(slowFollower)).isEqualTo(entryCount));

        group.resetAllRulesFrom(leader.localEndpoint());

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(commitIndex(leader));
                SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
                assertThat(stateMachine.size()).isEqualTo(entryCount);
            }
        });

        assertThat(snapshotChunkCollector(slowFollower)).isNull();
    }

    @Test(timeout = 300_000)
    public void when_leaderMissesResponse_then_itAdvancesMatchIndexWithNextResponse_leaderOnly() {
        when_leaderMissesInstallSnapshotResponse_then_itAdvancesMatchIndexWithNextInstallSnapshotResponse(false);
    }

    @Test(timeout = 300_000)
    public void when_leaderMissesResponse_then_itAdvancesMatchIndexWithNextResponse_leaderAndFollowers() {
        when_leaderMissesInstallSnapshotResponse_then_itAdvancesMatchIndexWithNextInstallSnapshotResponse(true);
    }

    private void when_leaderMissesInstallSnapshotResponse_then_itAdvancesMatchIndexWithNextInstallSnapshotResponse(
            boolean transferSnapshotFromFollowersEnabled) {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount)
                .setTransferSnapshotsFromFollowersEnabled(transferSnapshotFromFollowersEnabled).build();
        group = LocalRaftGroup.start(3, config);

        RaftNode leader = group.waitUntilLeaderElected();

        RaftNode slowFollower = group.anyNodeExcept(leader.localEndpoint());

        // the leader cannot send AppendEntriesRPC to the follower
        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);

        // the follower cannot send append response to the leader after installing the
        // snapshot
        group.dropMessagesTo(slowFollower.localEndpoint(), leader.localEndpoint(),
                             AppendEntriesSuccessResponse.class);

        for (int i = 0; i < entryCount; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> assertThat(snapshotEntry(leader).getIndex()).isEqualTo(entryCount));

        leader.replicate(applyValue("valFinal")).join();

        group.resetAllRulesFrom(leader.localEndpoint());

        eventually(() -> {
            for (RaftNode node : group.<RaftNode>nodesExcept(slowFollower.localEndpoint())) {
                assertThat(commitIndex(node)).isEqualTo(entryCount + 1);
                SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
                assertThat(stateMachine.size()).isEqualTo(entryCount + 1);
                for (int i = 0; i < entryCount; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
                assertThat(stateMachine.get(51)).isEqualTo("valFinal");
            }

            assertThat(commitIndex(slowFollower)).isEqualTo(entryCount);
            SimpleStateMachine service = group.stateMachine(slowFollower.localEndpoint());
            assertThat(service.size()).isEqualTo(entryCount);
            for (int i = 0; i < entryCount; i++) {
                assertThat(service.get(i + 1)).isEqualTo("val" + i);
            }
        });

        group.resetAllRulesFrom(slowFollower.localEndpoint());

        long commitIndex = commitIndex(leader);

        eventually(() -> {
            for (RaftNode node : group.nodesExcept(leader.localEndpoint())) {
                assertThat(matchIndex(leader, node.localEndpoint())).isEqualTo(commitIndex);
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_leaderMissesInstallSnapshotResponses_then_followerInstallsSnapshotsViaOtherFollowers() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount).build();
        group = LocalRaftGroup.start(3, config);

        RaftNode leader = group.waitUntilLeaderElected();

        leader.replicate(applyValue("val0")).join();

        RaftNode slowFollower = group.anyNodeExcept(leader.localEndpoint());

        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);
        group.dropMessagesTo(slowFollower.localEndpoint(), leader.localEndpoint(), InstallSnapshotResponse.class);

        for (int i = 1; i < entryCount; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> assertThat(snapshotEntry(leader).getIndex()).isEqualTo(entryCount));

        leader.replicate(applyValue("valFinal")).join();

        group.allowMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);

        eventually(() -> assertThat(commitIndex(slowFollower)).isEqualTo(entryCount));

        group.resetAllRulesFrom(leader.localEndpoint());

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(entryCount + 1);
                SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
                assertThat(stateMachine.size()).isEqualTo(entryCount + 1);
                for (int i = 0; i < entryCount; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
                assertThat(stateMachine.get(51)).isEqualTo("valFinal");
            }
        });

        assertThat(snapshotChunkCollector(slowFollower)).isNull();
    }

    @Test(timeout = 300_000)
    public void when_leaderAndSomeFollowersMissInstallSnapshotResponses_then_followerInstallsSnapshotsViaOtherFollowers() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount).build();
        group = LocalRaftGroup.start(5, config);

        RaftNode leader = group.waitUntilLeaderElected();

        leader.replicate(applyValue("val0")).join();

        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode slowFollower = followers.get(0);

        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);
        group.dropMessagesTo(slowFollower.localEndpoint(), leader.localEndpoint(), InstallSnapshotResponse.class);
        group.dropMessagesTo(slowFollower.localEndpoint(), followers.get(2).localEndpoint(),
                             InstallSnapshotResponse.class);
        group.dropMessagesTo(slowFollower.localEndpoint(), followers.get(3).localEndpoint(),
                             InstallSnapshotResponse.class);

        for (int i = 1; i < entryCount; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> assertThat(snapshotEntry(leader).getIndex()).isEqualTo(entryCount));

        leader.replicate(applyValue("valFinal")).join();

        group.allowMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);

        eventually(() -> assertThat(commitIndex(slowFollower)).isEqualTo(entryCount));

        group.resetAllRulesFrom(leader.localEndpoint());

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(entryCount + 1);
                SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
                assertThat(stateMachine.size()).isEqualTo(entryCount + 1);
                for (int i = 0; i < entryCount; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
                assertThat(stateMachine.get(51)).isEqualTo("valFinal");
            }
        });

        assertThat(snapshotChunkCollector(slowFollower)).isNull();
    }

    @Test(timeout = 300_000)
    public void when_followerMissesTheLastEntryThatGoesIntoTheSnapshot_then_itCatchesUpWithoutInstallingSnapshot() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount).build();
        group = LocalRaftGroup.start(3, config);

        RaftNode leader = group.waitUntilLeaderElected();

        RaftNode slowFollower = group.anyNodeExcept(leader.localEndpoint());

        for (int i = 0; i < entryCount - 1; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> {
            for (RaftNode follower : group.<RaftNode>nodesExcept(leader.localEndpoint())) {
                assertThat(matchIndex(leader, follower.localEndpoint())).isEqualTo(entryCount - 1);
            }
        });

        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);

        leader.replicate(applyValue("val" + (entryCount - 1))).join();

        eventually(() -> assertThat(snapshotEntry(leader).getIndex()).isEqualTo(entryCount));

        leader.replicate(applyValue("valFinal")).join();

        group.allowMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(entryCount + 1);
                SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
                assertThat(stateMachine.size()).isEqualTo(entryCount + 1);
                for (int i = 0; i < entryCount; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
                assertThat(stateMachine.get(51)).isEqualTo("valFinal");
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_followerMissesAFewEntriesBeforeTheSnapshot_then_itCatchesUpWithoutInstallingSnapshot() {
        int entryCount = 50;
        // entryCount * 0.1 - 2
        int missingEntryCountOnSlowFollower = 4;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount).build();
        group = LocalRaftGroup.start(3, config);

        RaftNode leader = group.waitUntilLeaderElected();

        RaftNode slowFollower = group.anyNodeExcept(leader.localEndpoint());

        for (int i = 0; i < entryCount - missingEntryCountOnSlowFollower; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> {
            for (RaftNode follower : group.<RaftNode>nodesExcept(leader.localEndpoint())) {
                assertThat(matchIndex(leader, follower.localEndpoint()))
                        .isEqualTo(entryCount - missingEntryCountOnSlowFollower);
            }
        });

        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);

        for (int i = entryCount - missingEntryCountOnSlowFollower; i < entryCount; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> assertThat(snapshotEntry(leader).getIndex()).isEqualTo(entryCount));

        leader.replicate(applyValue("valFinal")).join();

        group.allowMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(entryCount + 1);
                SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
                assertThat(stateMachine.size()).isEqualTo(entryCount + 1);
                for (int i = 0; i < entryCount; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
                assertThat(stateMachine.get(51)).isEqualTo("valFinal");
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_isolatedLeaderAppendsEntries_then_itInvalidatesTheirFeaturesUponInstallSnapshot()
            throws Exception {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(5)
                .setCommitCountToTakeSnapshot(entryCount).build();
        group = LocalRaftGroup.start(3, config);

        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());

        for (int i = 0; i < 40; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(40);
            }
        });

        group.splitMembers(leader.localEndpoint());

        List<Future<Ordered<Object>>> futures = new ArrayList<>();
        for (int i = 40; i < 45; i++) {
            Future<Ordered<Object>> f = leader.replicate(applyValue("isolated" + i));
            futures.add(f);
        }

        eventually(() -> {
            for (RaftNode raftNode : followers) {
                assertThat(raftNode.leaderEndpoint()).isNotNull().isNotEqualTo(leader.localEndpoint());
            }
        });

        RaftNode newLeader = group.node(followers.get(0).leaderEndpoint());

        for (int i = 40; i < 51; i++) {
            newLeader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> {
            for (RaftNode node : followers) {
                assertThat(snapshotEntry(node).getIndex()).isGreaterThan(0);
            }
        });

        group.dropMessagesTo(leader.localEndpoint(), followers.get(0).localEndpoint(),
                             AppendEntriesRequest.class);
        group.dropMessagesTo(leader.localEndpoint(), followers.get(1).localEndpoint(),
                             AppendEntriesRequest.class);
        group.merge();

        for (Future<Ordered<Object>> f : futures) {
            try {
                f.get();
                fail();
            } catch (ExecutionException e) {
                assertThat(e).hasCauseInstanceOf(IndeterminateStateException.class);
            }
        }

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(commitIndex(node)).isEqualTo(51);
                SimpleStateMachine stateMachine = group.stateMachine(node.localEndpoint());
                assertThat(stateMachine.size()).isEqualTo(51);
                for (int i = 0; i < 51; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_followersLastAppendIsMembershipChange_then_itUpdatesRaftNodeStateWithInstalledSnapshot() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount).build();
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

        var newRaftNode1 = group.createNewNode();
        CompletableFuture<Ordered<RaftGroupMembers>> f1 = leader.changeMembership(newRaftNode1.localEndpoint(),
                ADD_LEARNER, 0);

        eventually(() -> {
            for (RaftNode follower : followers) {
                assertThat(lastLogOrSnapshotEntry(follower).getIndex()).isEqualTo(2);
            }
        });

        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);

        for (RaftNode follower : followers) {
            if (follower != slowFollower) {
                group.allowAllMessagesTo(follower.localEndpoint(), leader.leaderEndpoint());
            }
        }

        f1.join();

        for (int i = 0; i < entryCount; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> assertThat(snapshotEntry(leader).getIndex()).isGreaterThanOrEqualTo(entryCount));

        group.allowAllMessagesTo(leader.leaderEndpoint(), slowFollower.localEndpoint());

        eventually(() -> assertThat(snapshotEntry(slowFollower).getIndex()).isGreaterThanOrEqualTo(entryCount));

        eventually(() -> {
            assertThat(committedGroupMembers(slowFollower).getLogIndex())
                    .isEqualTo(committedGroupMembers(leader).getLogIndex());
            assertThat(status(slowFollower)).isEqualTo(ACTIVE);
        });
    }

    @Test(timeout = 300_000)
    public void testMembershipChangeBlocksSnapshotBug() {
        // The comments below show how the code behaves before the mentioned bug is
        // fixed.

        int commitCountToTakeSnapshot = 50;
        int pendingEntryCount = 10;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(commitCountToTakeSnapshot)
                .setMaxPendingLogEntryCount(pendingEntryCount).build();
        group = LocalRaftGroup.start(3, config);

        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());

        group.dropMessagesTo(leader.localEndpoint(), followers.get(0).localEndpoint(),
                             AppendEntriesRequest.class);

        while (snapshotEntry(leader).getIndex() == 0) {
            leader.replicate(applyValue("into_snapshot")).join();
        }

        // now, the leader has taken a snapshot.
        // It also keeps some already committed entries in the log because followers[0]
        // hasn't appended them.
        // LOG: [ <46 - 49>, <50>], SNAPSHOT INDEX: 50, COMMIT INDEX: 50

        long leaderCommitIndex = commitIndex(leader);
        do {
            leader.replicate(applyValue("committed_after_snapshot")).join();
        } while (commitIndex(leader) < leaderCommitIndex + commitCountToTakeSnapshot - 1);

        // committing new entries.
        // one more entry is needed to take the next snapshot.
        // LOG: [ <46 - 49>, <50>, <51 - 99 (committed)> ],
        // SNAPSHOT INDEX: 50, COMMIT INDEX: 99

        group.dropMessagesTo(leader.localEndpoint(), followers.get(1).localEndpoint(),
                             AppendEntriesRequest.class);

        for (int i = 0; i < pendingEntryCount - 1; i++) {
            leader.replicate(applyValue("uncommitted_after_snapshot"));
        }

        // appended some more entries which will not be committed because the leader has
        // no majority.
        // the last uncommitted index is reserved for membership change.
        // LOG: [ <46 - 49>, <50>, <51 - 99 (committed)>, <100 - 108 (uncommitted)> ],
        // SNAPSHOT INDEX: 50, COMMIT INDEX: 99
        // There are only 2 empty indices in the log.

        var newRaftNode = group.createNewNode();

        Function<RaftMessage, RaftMessage> alterFunc = message -> {
            if (message instanceof AppendEntriesRequest request) {
                var entries = request.getLogEntries();
                if (!entries.isEmpty()) {
                    if (entries.getLast()
                            .getOperation() instanceof DefaultUpdateRaftGroupMembersOpOrBuilder) {
                        entries = entries.subList(0, entries.size() - 1);
                        return new DefaultAppendEntriesRequestOrBuilder()
                                .setSender(request.getSender())
                                .setTerm(request.getTerm())
                                .setPreviousLogTerm(request.getPreviousLogTerm())
                                .setPreviousLogIndex(request.getPreviousLogIndex())
                                .setCommitIndex(request.getCommitIndex())
                                .setLogEntries(entries)
                                .setQuerySequenceNumber(request.getQuerySequenceNumber())
                                .setFlowControlSequenceNumber(request.getFlowControlSequenceNumber())
                                .build();
                    } else if (entries.getFirst().getOperation() instanceof DefaultUpdateRaftGroupMembersOpOrBuilder) {
                        entries = emptyList();
                        return new DefaultAppendEntriesRequestOrBuilder()
                                .setSender(request.getSender())
                                .setTerm(request.getTerm())
                                .setPreviousLogTerm(request.getPreviousLogTerm())
                                .setPreviousLogIndex(request.getPreviousLogIndex())
                                .setCommitIndex(request.getCommitIndex())
                                .setLogEntries(entries)
                                .setQuerySequenceNumber(request.getQuerySequenceNumber())
                                .setFlowControlSequenceNumber(request.getFlowControlSequenceNumber())
                                .build();
                    }
                }
            }

            return message;
        };

        group.alterMessagesTo(leader.localEndpoint(), followers.get(1).localEndpoint(), alterFunc);
        group.alterMessagesTo(leader.localEndpoint(), newRaftNode.localEndpoint(), alterFunc);

        long lastLogIndex1 = lastLogOrSnapshotEntry(leader).getIndex();

        leader.changeMembership(newRaftNode.localEndpoint(), ADD_LEARNER, 0);

        // When the membership change entry is appended, the leader's log will be as
        // following:
        // LOG: [ <46 - 49>, <50>, <51 - 99 (committed)>, <100 - 108 (uncommitted)>,
        // <109 (membership change)> ],
        // SNAPSHOT INDEX: 50, COMMIT INDEX: 99

        eventually(() -> assertThat(lastLogOrSnapshotEntry(leader).getIndex()).isGreaterThan(lastLogIndex1));

        group.allowMessagesTo(leader.localEndpoint(), followers.get(1).localEndpoint(),
                              AppendEntriesRequest.class);

        // Then, only the entries before the membership change will be committed because
        // we alter the append request.
        // The log will be:
        // LOG: [ <46 - 49>, <50>, <51 - 108 (committed)>, <109 (membership change)> ],
        // SNAPSHOT INDEX: 50, COMMIT INDEX: 108
        // There is only 1 empty index in the log.

        eventually(() -> {
            assertThat(commitIndex(leader)).isEqualTo(lastLogIndex1);
            assertThat(commitIndex(followers.get(1))).isEqualTo(lastLogIndex1);
        });

        // eventually(() -> {
        // assertThat(getCommitIndex(leader)).isEqualTo(lastLogIndex1 + 1);
        // assertThat(getCommitIndex(followers[1])).isEqualTo(lastLogIndex1 + 1);
        // });

        long lastLogIndex2 = lastLogOrSnapshotEntry(leader).getIndex();

        leader.replicate(applyValue("after_membership_change_append"));

        eventually(() -> assertThat(lastLogOrSnapshotEntry(leader).getIndex()).isGreaterThan(lastLogIndex2));

        // Now the log is full. There is no empty space left.
        // LOG: [ <46 - 49>, <50>, <51 - 108 (committed)>, <109 (membership change)>,
        // <110 (uncommitted)> ],
        // SNAPSHOT INDEX: 50, COMMIT INDEX: 108

        long lastLogIndex3 = lastLogOrSnapshotEntry(leader).getIndex();

        Future<Ordered<Object>> f = leader.replicate(applyValue("after_membership_change_append"));

        eventually(() -> assertThat(lastLogOrSnapshotEntry(leader).getIndex()).isGreaterThan(lastLogIndex3));

        assertThat(f).isNotDone();
    }

    @Test(timeout = 300_000)
    public void when_slowFollowerReceivesAppendRequestThatDoesNotFitIntoItsRaftLog_then_itTruncatesAppendRequestEntries() {
        int appendEntriesRequestBatchSize = 100;
        int commitCountToTakeSnapshot = 100;
        int pendingEntryCount = 10;

        RaftConfig config = RaftConfig.newBuilder().setAppendEntriesRequestBatchSize(appendEntriesRequestBatchSize)
                .setCommitCountToTakeSnapshot(commitCountToTakeSnapshot).setMaxPendingLogEntryCount(pendingEntryCount)
                .build();
        group = LocalRaftGroup.start(5, config);

        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode slowFollower1 = followers.get(0);
        RaftNode slowFollower2 = followers.get(1);

        int count = 1;
        for (int i = 0; i < commitCountToTakeSnapshot; i++) {
            leader.replicate(applyValue("val" + (count++))).join();
        }

        eventually(() -> {
            for (RaftNode node : group.nodes()) {
                assertThat(snapshotEntry(node).getIndex()).isGreaterThan(0);
            }
        });

        group.dropMessagesTo(leader.localEndpoint(), slowFollower1.localEndpoint(), AppendEntriesRequest.class);

        for (int i = 0; i < commitCountToTakeSnapshot - 1; i++) {
            leader.replicate(applyValue("val" + (count++))).join();
        }

        eventually(() -> assertThat(commitIndex(slowFollower2)).isEqualTo(commitIndex(leader)));

        // slowFollower2's log: [ <91 - 100 before snapshot>, <100 snapshot>, <101 - 199
        // committed> ]

        group.dropMessagesTo(leader.localEndpoint(), slowFollower2.localEndpoint(), AppendEntriesRequest.class);

        for (int i = 0; i < commitCountToTakeSnapshot / 2; i++) {
            leader.replicate(applyValue("val" + (count++))).join();
        }

        eventually(() -> assertThat(snapshotEntry(leader).getIndex()).isGreaterThan(commitCountToTakeSnapshot));

        // leader's log: [ <191 - 199 before snapshot>, <200 snapshot>, <201 - 249
        // committed> ]

        group.allowMessagesTo(leader.localEndpoint(), slowFollower2.localEndpoint(), AppendEntriesRequest.class);

        // leader replicates 50 entries to slowFollower2 but slowFollower2 has only
        // available capacity of 11 indices.
        // so, slowFollower2 appends 11 of these 50 entries in the first AppendRequest,
        // takes a snapshot,
        // and receives another AppendRequest for the remaining entries...

        eventually(() -> {
            assertThat(commitIndex(slowFollower2)).isEqualTo(commitIndex(leader));
            assertThat(snapshotEntry(slowFollower2).getIndex()).isGreaterThan(commitCountToTakeSnapshot);
        });
    }

    @Test(timeout = 300_000)
    public void when_leaderFailsDuringSnapshotTransfer_then_followerTransfersSnapshotFromNewLeader_leaderOnly() {
        when_leaderFailsDuringSnapshotTransfer_then_followerTransfersSnapshotFromNewLeader(false);
    }

    @Test(timeout = 300_000)
    public void when_leaderFailsDuringSnapshotTransfer_then_followerTransfersSnapshotFromNewLeader_leaderAndFollowers() {
        when_leaderFailsDuringSnapshotTransfer_then_followerTransfersSnapshotFromNewLeader(true);
    }

    private void when_leaderFailsDuringSnapshotTransfer_then_followerTransfersSnapshotFromNewLeader(
            boolean transferSnapshotFromFollowersEnabled) {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(5)
                .setCommitCountToTakeSnapshot(entryCount)
                .setTransferSnapshotsFromFollowersEnabled(transferSnapshotFromFollowersEnabled).build();
        group = LocalRaftGroup.start(3, config);

        RaftNode leader = group.waitUntilLeaderElected();
        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode slowFollower = followers.get(0);
        RaftNode newLeader = followers.get(1);

        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);

        while (snapshotEntry(leader).getIndex() == 0) {
            leader.replicate(applyValue("val")).join();
        }

        eventually(() -> assertThat(snapshotEntry(newLeader).getIndex()).isGreaterThan(0));

        group.dropMessagesTo(slowFollower.localEndpoint(), leader.localEndpoint(), InstallSnapshotResponse.class);
        group.dropMessagesTo(slowFollower.localEndpoint(), newLeader.localEndpoint(),
                             InstallSnapshotResponse.class);

        group.allowMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);

        eventually(() -> assertThat(snapshotChunkCollector(slowFollower)).isNotNull());

        group.terminateNode(leader.localEndpoint());

        eventually(() -> assertThat(newLeader.leaderEndpoint()).isEqualTo(newLeader.localEndpoint()));

        group.allowMessagesTo(slowFollower.localEndpoint(), newLeader.localEndpoint(),
                              InstallSnapshotResponse.class);

        eventually(() -> assertThat(snapshotEntry(slowFollower).getIndex()).isGreaterThan(0));
    }

    @Test(timeout = 300_000)
    public void when_thereAreCrashedFollowers_then_theyAreSkippedDuringSnapshotTransfer() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount).build();
        group = LocalRaftGroup.start(3, config);

        RaftNode leader = group.waitUntilLeaderElected();

        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode slowFollower = followers.get(0);
        RaftNode otherFollower = followers.get(1);

        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);
        group.dropMessagesTo(slowFollower.localEndpoint(), leader.localEndpoint(), InstallSnapshotResponse.class);

        for (int i = 0; i < entryCount; i++) {
            leader.replicate(applyValue("val" + i)).join();
        }

        eventually(() -> assertThat(snapshotEntry(leader).getIndex()).isEqualTo(entryCount));

        leader.replicate(applyValue("valFinal")).join();

        group.terminateNode(otherFollower.localEndpoint());

        group.allowMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);
        group.allowMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);

        eventually(() -> {
            SnapshotChunkCollector snapshotChunkCollector = snapshotChunkCollector(slowFollower);
            assertThat(snapshotChunkCollector).isNotNull();
            assertThat(snapshotChunkCollector.getSnapshottedMembers()).hasSize(1);
        });

        group.allowMessagesTo(slowFollower.localEndpoint(), leader.localEndpoint(),
                              InstallSnapshotResponse.class);

        eventually(() -> assertThat(commitIndex(slowFollower)).isEqualTo(commitIndex(leader)));
        assertThat(snapshotChunkCollector(slowFollower)).isNull();
    }

    @Test(timeout = 300_000)
    public void when_leaderCrashesDuringSnapshotTransfer_then_newLeaderSendsItsSnapshottedMembers() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount)
                .setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(5).build();
        group = LocalRaftGroup.start(5, config);

        RaftNode leader = group.waitUntilLeaderElected();

        List<RaftNode> followers = group.nodesExcept(leader.localEndpoint());
        RaftNode slowFollower = followers.get(0);

        for (RaftNode follower : followers) {
            if (follower != slowFollower) {
                group.dropMessagesTo(follower.localEndpoint(), slowFollower.localEndpoint(),
                                     InstallSnapshotRequest.class);
                group.dropMessagesTo(slowFollower.localEndpoint(), follower.localEndpoint(),
                                     InstallSnapshotResponse.class);
            }
        }

        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);
        group.dropMessagesTo(slowFollower.localEndpoint(), leader.localEndpoint(), InstallSnapshotResponse.class);

        while (snapshotEntry(leader).getIndex() == 0) {
            leader.replicate(applyValue("val")).join();
        }

        eventually(() -> assertThat(snapshotEntry(leader).getIndex()).isGreaterThan(0));

        group.allowMessagesTo(leader.localEndpoint(), slowFollower.localEndpoint(), InstallSnapshotRequest.class);

        eventually(() -> {
            SnapshotChunkCollector snapshotChunkCollector = snapshotChunkCollector(slowFollower);
            assertThat(snapshotChunkCollector).isNotNull();
            assertThat(snapshotChunkCollector.getSnapshottedMembers().size()).isEqualTo(1);
        });

        group.terminateNode(leader.localEndpoint());

        for (RaftNode follower : followers) {
            eventually(
                    () -> assertThat(follower.leaderEndpoint()).isNotNull().isNotEqualTo(leader.localEndpoint()));
        }

        RaftNode newLeader = group.leaderNode();
        assertThat(newLeader).isNotNull();
        newLeader.replicate(applyValue("newLeaderVal")).join();

        group.allowMessagesTo(newLeader.localEndpoint(), slowFollower.localEndpoint(),
                              InstallSnapshotRequest.class);

        eventually(() -> assertThat(snapshotChunkCollector(slowFollower).getSnapshottedMembers().size())
                .isGreaterThan(1));

        for (RaftNode follower : followers) {
            if (follower != slowFollower) {
                group.allowMessagesTo(follower.localEndpoint(), slowFollower.localEndpoint(),
                                      InstallSnapshotRequest.class);
                group.allowMessagesTo(slowFollower.localEndpoint(), follower.localEndpoint(),
                                      InstallSnapshotResponse.class);
            }
        }

        eventually(() -> assertThat(commitIndex(slowFollower)).isEqualTo(commitIndex(newLeader)));
        assertThat(snapshotChunkCollector(slowFollower)).isNull();
    }

    @Test(timeout = 300_000)
    public void when_promotionCommitFallsIntoSnapshot_then_promotedMemberTurnsIntoVotingMemberByInstallingSnapshot() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount)
                .setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(5).build();
        group = LocalRaftGroup.newBuilder(3).enableNewTermOperation().setConfig(config).build();
        group.start();

        var leader = group.waitUntilLeaderElected();

        var newNode = group.createNewNode();

        var membershipChangeResult = leader
                .changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join();

        eventually(() -> assertThat(commitIndex(newNode)).isEqualTo(commitIndex(leader)));

        group.dropMessagesTo(leader.localEndpoint(), newNode.localEndpoint(), AppendEntriesRequest.class);

        leader.changeMembership(newNode.localEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER,
                                membershipChangeResult.getCommitIndex()).join();

        while (snapshotEntry(leader).getIndex() == 0) {
            leader.replicate(applyValue("val")).join();
        }

        eventually(() -> {
            assertThat(commitIndex(newNode)).isEqualTo(commitIndex(leader));
            assertThat(role(newNode)).isEqualTo(RaftRole.FOLLOWER);
        });
    }

    @Test(timeout = 300_000)
    public void when_snapshotTriggeredViaNotStartedSingletonNode_then_snapshotCannotBeTaken() {
        group = LocalRaftGroup.newBuilder(1).build();
        RaftNode node = group.nodes().get(0);

        try {
            node.takeSnapshot().join();
            fail("Cannot take snapshot manually before started");
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_snapshotTriggeredViaSingletonNodeWhenFirstLogEntryNotCommitted_then_snapshotCannotBeTaken() {
        group = LocalRaftGroup.start(1);
        RaftNode node = group.nodes().get(0);

        try {
            node.takeSnapshot().join();
            fail("Cannot take snapshot manually before first entry committed");
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_snapshotTriggeredViaSingletonNodeWhenFirstLogEntryCommitted_then_snapshotCanBeTaken() {
        group = LocalRaftGroup.newBuilder(1).enableNewTermOperation().start();
        RaftNode node = group.nodes().get(0);

        Ordered<RaftNodeReport> result = node.takeSnapshot().join();
        assertThat(result.getCommitIndex()).isEqualTo(FIRST_VALID_LOG_INDEX);
    }

    @Test(timeout = 300_000)
    public void when_snapshotTriggeredViaNotStartedNode_then_snapshotCannotBeTaken() {
        group = LocalRaftGroup.newBuilder(2).build();
        RaftNode node = group.nodes().get(0);

        try {
            node.takeSnapshot().join();
            fail("Cannot take snapshot manually before started");
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_snapshotTriggeredViaLeaderWhenFirstEntryNotCommitted_then_snapshotCannotBeTaken() {
        group = LocalRaftGroup.start(3);
        RaftNode node = group.waitUntilLeaderElected();

        try {
            node.takeSnapshot().join();
            fail("Cannot take snapshot manually before first entry committed");
        } catch (CompletionException e) {
            assertThat(e).hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test(timeout = 300_000)
    public void when_snapshotTriggeredViaLeaderWhenFirstEntryCommitted_then_snapshotCanBeTaken() {
        group = LocalRaftGroup.newBuilder(3).enableNewTermOperation().start();
        RaftNode node = group.waitUntilLeaderElected();
        eventually(() -> {
            assertThat(commitIndex(node)).isGreaterThan(0);
        });

        Ordered<RaftNodeReport> result = node.takeSnapshot().join();
        assertThat(result.getCommitIndex()).isEqualTo(FIRST_VALID_LOG_INDEX);
    }

    @Test(timeout = 300_000)
    public void when_snapshotTriggeredViaLeaderMultipleTimesAtSameIndex_then_singleSnapshotIsTaken() {
        group = LocalRaftGroup.newBuilder(3).enableNewTermOperation().start();
        RaftNode node = group.waitUntilLeaderElected();
        eventually(() -> {
            assertThat(commitIndex(node)).isGreaterThan(0);
        });

        Ordered<RaftNodeReport> result1 = node.takeSnapshot().join();
        assertThat(result1.getCommitIndex()).isEqualTo(FIRST_VALID_LOG_INDEX);
        assertThat(result1.getResult()).isNotNull();
        Ordered<RaftNodeReport> result2 = node.takeSnapshot().join();
        assertThat(result2.getCommitIndex()).isEqualTo(result1.getCommitIndex());
        assertThat(result2.getResult()).isNull();
    }

    @Test(timeout = 300_000)
    public void when_snapshotTriggeredViaFollowerMultipleTimesAtSameIndex_then_singleSnapshotIsTaken() {
        group = LocalRaftGroup.newBuilder(2).enableNewTermOperation().start();
        RaftNode leader = group.waitUntilLeaderElected();
        RaftNode node = group.anyNodeExcept(leader.leaderEndpoint());
        eventually(() -> {
            assertThat(commitIndex(node)).isGreaterThan(0);
        });

        Ordered<RaftNodeReport> result1 = node.takeSnapshot().join();
        assertThat(result1.getCommitIndex()).isEqualTo(FIRST_VALID_LOG_INDEX);
        Ordered<RaftNodeReport> result2 = node.takeSnapshot().join();
        assertThat(result2.getCommitIndex()).isEqualTo(result1.getCommitIndex());
    }

    @Test(timeout = 300_000)
    public void when_snapshotTriggeredViaFollowerWhenFirstEntryCommitted_then_snapshotCanBeTaken() {
        group = LocalRaftGroup.newBuilder(3).enableNewTermOperation().start();
        group.waitUntilLeaderElected();
        RaftNode node = group.anyNodeExcept(group.leaderEndpoint());
        eventually(() -> {
            assertThat(commitIndex(node)).isGreaterThan(0);
        });

        Ordered<RaftNodeReport> result = node.takeSnapshot().join();
        assertThat(result.getCommitIndex()).isEqualTo(FIRST_VALID_LOG_INDEX);
    }

    @Test(timeout = 300_000)
    public void when_snapshotTriggeredViaLearnerWhenFirstEntryCommitted_then_snapshotCanBeTaken() {
        group = LocalRaftGroup.newBuilder(3).enableNewTermOperation().start();
        RaftNode leader = group.waitUntilLeaderElected();

        RaftNode newNode = group.createNewNode();

        long commitIndex = leader.changeMembership(newNode.localEndpoint(), ADD_LEARNER, 0).join().getCommitIndex();

        eventually(() -> {
            assertThat(commitIndex(newNode)).isEqualTo(commitIndex(leader));
        });

        Ordered<RaftNodeReport> result = newNode.takeSnapshot().join();
        assertThat(result.getCommitIndex()).isEqualTo(commitIndex);
    }

    @Test(timeout = 300_000)
    public void when_manualSnapshotIsTakenViaLeader_then_leaderCanTakeAutomaticSnapshot() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount)
                .setMaxPendingLogEntryCount(entryCount / 10).build();
        group = LocalRaftGroup.newBuilder(3).setConfig(config).enableNewTermOperation().start();
        RaftNode leader = group.waitUntilLeaderElected();
        eventually(() -> {
            assertThat(commitIndex(leader)).isGreaterThan(0);
        });

        long snapshotIndex1 = leader.takeSnapshot().join().getCommitIndex();

        for (int i = 0; i < entryCount * 5; i++) {
            leader.replicate(applyValue("val")).join();
        }

        long snapshotIndex2 = snapshotEntry(leader).getIndex();
        assertThat(snapshotIndex2).isGreaterThan(snapshotIndex1);

        for (int i = 0; i < entryCount * 10 + entryCount / 2; i++) {
            leader.replicate(applyValue("val")).join();
        }

        long snapshotIndex3 = leader.takeSnapshot().join().getCommitIndex();

        assertThat(snapshotIndex3).isGreaterThan(snapshotIndex2);
    }

    @Test(timeout = 300_000)
    public void when_manualSnapshotIsTakenViaFollower_then_followerCanTakeAutomaticSnapshot() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount)
                .setMaxPendingLogEntryCount(entryCount / 10).build();
        group = LocalRaftGroup.newBuilder(3).setConfig(config).enableNewTermOperation().start();
        RaftNode leader = group.waitUntilLeaderElected();
        RaftNode follower = group.anyNodeExcept(leader.localEndpoint());
        eventually(() -> {
            assertThat(commitIndex(follower)).isGreaterThan(0);
        });

        long snapshotIndex1 = follower.takeSnapshot().join().getCommitIndex();

        for (int i = 0; i < entryCount * 5; i++) {
            leader.replicate(applyValue("val")).join();
        }

        eventually(() -> {
            assertThat(commitIndex(follower)).isEqualTo(commitIndex(leader));
        });

        long snapshotIndex2 = snapshotEntry(follower).getIndex();

        assertThat(snapshotIndex2).isGreaterThan(snapshotIndex1);

        for (int i = 0; i < entryCount * 10 + entryCount / 2; i++) {
            leader.replicate(applyValue("val")).join();
        }

        eventually(() -> {
            assertThat(commitIndex(follower)).isEqualTo(commitIndex(leader));
        });

        long snapshotIndex3 = follower.takeSnapshot().join().getCommitIndex();
        assertThat(snapshotIndex3).isGreaterThan(snapshotIndex2);
    }
}
