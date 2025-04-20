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

package io.microraft.test.util;

import io.microraft.*;
import io.microraft.impl.local.InMemoryRaftStore;
import io.microraft.impl.log.SnapshotChunkCollector;
import io.microraft.impl.state.LeaderState;
import io.microraft.impl.state.RaftGroupMembersState;
import io.microraft.model.log.BaseLogEntry;
import io.microraft.model.log.SnapshotEntry;
import io.microraft.persistence.RaftStore;
import io.microraft.persistence.RestoredRaftState;
import io.microraft.report.RaftNodeReport;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.function.Function;

import static org.junit.Assert.assertNotNull;

public final class RaftTestUtils {

    public static final RaftConfig TEST_RAFT_CONFIG = RaftConfig.newBuilder().setLeaderElectionTimeoutMillis(2000)
            .setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(5).build();

    private RaftTestUtils() {
    }

    public static RaftRole role(RaftNode node) {
        return readRaftReport(node, RaftNodeReport::role);
    }

    private static <T> T readRaftReport(RaftNode node, Function<RaftNodeReport, T> query) {
        try {
            return query.apply(node.report().join().getResult());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static BaseLogEntry lastLogOrSnapshotEntry(RaftNode node) {
        return readRaftState(node, () -> node.state().log().lastLogOrSnapshotEntry());
    }

    //TODO: rework to use Promises instead of FutureTasks.
    public static <T> T readRaftState(RaftNode node, Callable<T> task) {
        try {

            var executor = node.executor();

            if (executor.isShutdown()) {
                return task.call();
            }

            var futureTask = new FutureTask<T>(task);
            executor.submit(futureTask);

            return futureTask.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static SnapshotEntry snapshotEntry(RaftNode node) {
        return readRaftState(node, () -> node.state().log().snapshotEntry());
    }

    public static SnapshotChunkCollector snapshotChunkCollector(RaftNode node) {
        return readRaftState(node, () -> {
            var snapshotChunkCollector = node.state().snapshotChunkCollector();
            if (snapshotChunkCollector == null) {
                return null;
            }

            return snapshotChunkCollector.copy();
        });
    }

    public static long commitIndex(RaftNode node) {
        return readRaftReport(node, report -> report.log().getCommitIndex());
    }

    public static long lastApplied(RaftNode node) {
        return readRaftState(node, () -> node.state().lastApplied());
    }

    public static int term(RaftNode node) {
        return readRaftReport(node, report -> report.term().term());
    }

    public static RaftEndpoint votedEndpoint(RaftNode node) {
        return readRaftReport(node, report -> report.term().votedEndpoint());
    }

    public static long matchIndex(RaftNode leader, RaftEndpoint follower) {
        Callable<Long> task = () -> {
            var leaderState = leader.state().leaderState();
            return leaderState.followerState(follower).matchIndex();
        };

        return readRaftState(leader, task);
    }

    public static long leaderQuerySequenceNumber(RaftNode leader) {
        Callable<Long> task = () -> {
            LeaderState leaderState = leader.state().leaderState();
            assertNotNull(leader.localEndpoint() + " has no leader state!", leaderState);
            return leaderState.querySequenceNumber(true);
        };

        return readRaftState(leader, task);
    }

    public static RaftNodeStatus status(RaftNode node) {
        return readRaftReport(node, RaftNodeReport::status);
    }

    public static RaftGroupMembersState effectiveGroupMembers(RaftNode node) {
        Callable<RaftGroupMembersState> task = () -> node.state().effectiveGroupMembers();
        return readRaftState(node, task);
    }

    public static RaftGroupMembersState committedGroupMembers(RaftNode node) {
        Callable<RaftGroupMembersState> task = () -> node.state().committedGroupMembers();
        return readRaftState(node, task);
    }

    public static RaftStore raftStore(RaftNode node) {
        return readRaftState(node, () -> node.state().store());
    }

    public static RestoredRaftState restoredState(RaftNode node) {
        Callable<RestoredRaftState> task = () -> {
            var store = (InMemoryRaftStore) node.state().store();
            return store.toRestoredRaftState();
        };

        return readRaftState(node, task);
    }

    public static int minority(int count) {
        return count - majority(count);
    }

    public static int majority(int count) {
        return count / 2 + 1;
    }

}
