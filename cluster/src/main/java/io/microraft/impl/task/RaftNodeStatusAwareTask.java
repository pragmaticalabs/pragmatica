/*
 * Original work Copyright (c) 2008-2020, Hazelcast, Inc.
 * Modified work Copyright (c) 2020, MicroRaft.
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

package io.microraft.impl.task;

import io.microraft.RaftEndpoint;
import io.microraft.RaftNode;
import io.microraft.impl.handler.PreVoteResponseHandler;
import io.microraft.impl.state.RaftState;
import io.microraft.model.RaftModelFactory;
import io.microraft.model.message.PreVoteRequest;
import io.microraft.model.message.RaftMessage;
import io.microraft.model.message.VoteRequest;
import org.pragmatica.lang.Tuple.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;

import static io.microraft.RaftRole.*;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.pragmatica.lang.Tuple.tuple;

/**
 * The base class for the tasks that should not run on some Raft node statuses.
 * <p>
 * Subclass tasks are executed only if the local Raft node is already started,
 * and not terminated or left the Raft group.
 */
public interface RaftNodeStatusAwareTask extends Runnable {
    RaftNode node();

    default RaftState state() {
        return node().state();
    }

    default RaftModelFactory modelFactory() {
        return node().modelFactory();
    }

    @Override
    default void run() {
        var status = node().status();
        if (status.isTerminal() || status.isInitial()) {
            getLogger().debug("{} Won't run, since status is {}", localEndpointStr(), status);
            return;
        }

        try {
            doRun();
        } catch (Throwable e) {
            getLogger().error("{} got a failure in {}", localEndpointStr(), getClass().getSimpleName(), e);
        }
    }

    default void doRun(){
    }

    private Logger getLogger() {
        return LoggerFactory.getLogger(getClass());
    }

    default RaftEndpoint localEndpoint() {
        return node().localEndpoint();
    }

    default String localEndpointStr() {
        return node().localEndpointName();
    }

    /**
     * Scheduled by {@link LeaderElectionTask} to trigger leader election again if a
     * leader is not elected yet.
     */
    interface LeaderElectionTimeoutTask extends RaftNodeStatusAwareTask {
        Logger LOGGER = LoggerFactory.getLogger(LeaderElectionTimeoutTask.class);

        @Override
        default void doRun() {
            if (state().role() != CANDIDATE) {
                return;
            }

            LOGGER.warn("{} Leader election for term: {} has timed out!", localEndpointStr(), node().state().term());
            LeaderElectionTask task = () -> tuple(node(), true);
            task.run();
        }
    }

    /**
     * If the append entries request backoff period is active for any follower, this
     * task will send a new append entries request on the backoff completion.
     */
    interface LeaderBackoffResetTask extends RaftNodeStatusAwareTask {
        @Override
        default void doRun() {
            var leaderState = state().leaderState();

            if (leaderState == null) {
                return;
            }

            leaderState.requestBackoffResetTaskScheduled(false);

            for (var e : leaderState.followerStates().entrySet()) {
                var followerState = e.getValue();
                if (followerState.isRequestBackoffSet()) {
                    if (followerState.completeBackoffRound()) {
                        node().sendAppendEntriesRequest(e.getKey());
                    } else {
                        node().scheduleLeaderRequestBackoffResetTask(leaderState);
                    }
                }
            }
        }
    }

    /**
     * Flushes the Raft node's local Raft log to the persistent storage and tries to
     * advance the commit index if the Raft node is the leader.
     */
    interface FlushTask extends RaftNodeStatusAwareTask {
        @Override
        default void doRun() {
            var log = state().log();
            log.flush();

            var leaderState = state().leaderState();
            if (leaderState == null) {
                return;
            }

            leaderState.flushTaskSubmitted(false);
            leaderState.flushedLogIndex(log.lastLogOrSnapshotIndex());

            node().tryAdvanceCommitIndex();
        }
    }

    /**
     * Checks whether currently there is a known leader endpoint and triggers the
     * pre-voting mechanism there is no known leader or the leader has timed out.
     */
    interface HeartbeatTask extends RaftNodeStatusAwareTask {
        Logger LOGGER = LoggerFactory.getLogger(HeartbeatTask.class);

        @Override
        default void doRun() {
            try {
                if (state().leaderState() != null) {
                    if (!node().demoteToFollowerIfQuorumHeartbeatTimeoutElapsed()) {
                        node().broadcastAppendEntriesRequest();
                        // TODO(basri) append no-op if snapshotIndex > 0 && snapshotIndex ==
                        // lastLogIndex
                    }

                    return;
                }

                var leader = state().leader();
                if (leader == null) {
                    if (state().role() == FOLLOWER && state().preCandidateState() == null) {
                        LOGGER.warn("{} We are FOLLOWER and there is no current leader. Will start new election round.",
                                localEndpointStr());
                        resetLeaderAndTryTriggerPreVote(false);
                    }
                } else if (node().isLeaderHeartbeatTimeoutElapsed() && state().preCandidateState() == null) {
                    LOGGER.warn("{} Current leader {}'s heartbeats are timed-out.", localEndpointStr(), leader.id());
                    resetLeaderAndTryTriggerPreVote(true);
                } else if (!state().committedGroupMembers().isKnownMember(leader) && state().preCandidateState() == null) {
                    LOGGER.warn("{} Current leader {} is not member anymore.", localEndpointStr(), leader.id());
                    resetLeaderAndTryTriggerPreVote(true);
                }
            } finally {
                node().executor().schedule(this, node().config().leaderHeartbeatPeriodSecs(), SECONDS);
            }
        }

        default void resetLeaderAndTryTriggerPreVote(boolean resetLeader) {
            if (resetLeader) {
                node().leader(null);
            }

            if (state().role() == LEARNER) {
                LOGGER.debug("{} is not starting pre-vote since it is {}", localEndpointStr(), LEARNER);
                return;
            }

            if (state().leaderElectionQuorumSize() > 1) {
                node().runPreVote();
            } else if (state().effectiveGroupMembers().getVotingMembers().contains(localEndpoint())) {
                // we can encounter this case if the leader crashes before it
                // commit the replicated membership change while it is leaving.
                LOGGER.info("{} is the single voting member left in the Raft group.", localEndpointStr());
                node().toSingletonLeader();
            }
        }
    }

    interface RaftNodeStatusAndContextAwareTask<T> extends RaftNodeStatusAwareTask {
        Tuple2<RaftNode, T> info();

        @Override
        default RaftNode node() {
            return info().first();
        }

        default T context() {
            return info().last();
        }
    }

    /**
     * Scheduled when the current leader is null, unreachable, or unknown by
     * {@link PreVoteResponseHandler} after a follower receives votes from the
     * majority. A Raft node becomes a candidate via {@link RaftState#toCandidate()}
     * and sends {@link VoteRequest}s to the other Raft group members.
     * <p>
     * Also a {@link LeaderElectionTimeoutTask} is scheduled with the
     * {@link RaftNode#leaderElectionTimeoutMs()} delay to trigger another
     * round of leader election if a leader is not elected yet.
     */
    interface LeaderElectionTask extends RaftNodeStatusAndContextAwareTask<Boolean> {
        Logger LOGGER = LoggerFactory.getLogger(LeaderElectionTask.class);

        default boolean sticky() {
            return context();
        }

        @Override
        default void doRun() {
            if (state().leader() != null) {
                LOGGER.warn("{} No new election round, we already have a LEADER: {}", localEndpointStr(),
                        state().leader().id());
                return;
            }

            node().toCandidate(sticky());
        }
    }

    /**
     * Scheduled when the current leader is null, unreachable, or unknown.
     * <p>
     * It sends {@link PreVoteRequest}s to other Raft group members to make sure the
     * current leader is considered to be unhealthy by the majority and a new leader
     * election round can be started.
     * <p>
     * Also a {@link PreVoteTimeoutTask} is scheduled with the
     * {@link RaftNode#leaderElectionTimeoutMs()} delay to trigger another
     * round of pre-voting if a leader is not available yet.
     */
    interface PreVoteTask extends RaftNodeStatusAndContextAwareTask<Integer> {
        Logger LOGGER = LoggerFactory.getLogger(PreVoteTask.class);

        default int term() {
            return context();
        }

        @Override
        default void doRun() {
            if (state().leader() != null) {
                LOGGER.debug("{} No new pre-vote phase, we already have a LEADER: {}", localEndpointStr(),
                        state().leader().id());
                return;
            } else if (state().term() != term()) {
                LOGGER.debug("{} No new pre-vote phase for term: {} because of new term: {}", localEndpointStr(), term(),
                        state().term());
                return;
            }

            if (state().remoteVotingMembers().isEmpty()) {
                // TODO(basri): why do we have this check?
                LOGGER.warn("{} Remote voting members is empty. No need for pre-voting.", localEndpointStr());
                return;
            }

            node().preCandidate();
        }
    }

    /**
     * Scheduled by {@link PreVoteTask} to trigger pre-voting again if the local
     * Raft node is still a follower and a leader is not yet available after the
     * leader election timeout.
     */
    interface PreVoteTimeoutTask extends RaftNodeStatusAndContextAwareTask<Integer> {
        Logger LOGGER = LoggerFactory.getLogger(PreVoteTimeoutTask.class);

        default int term() {
            return context();
        }

        @Override
        default void doRun() {
            if (state().role() != FOLLOWER) {
                return;
            }

            LOGGER.debug("{} Pre-vote for term: {} has timed out!", localEndpointStr(), node().state().term());
            PreVoteTask task = this::info;
            task.run();
        }
    }

    /**
     * Base class for {@link RaftMessage} handlers.
     */
    interface AbstractMessageHandler<T extends RaftMessage> extends RaftNodeStatusAndContextAwareTask<T> {
        default T message() {
            return context();
        }

        @Override
        default void doRun() {
            handle(message());
        }

        default void handle(@Nonnull T message) {
        }
    }

    /**
     * Base class for Raft RPC response handlers.
     * <p>
     * If {@link RaftMessage#getSender()} is not a known Raft group member, then the
     * response is ignored.
     */
    interface AbstractResponseHandler<T extends RaftMessage> extends AbstractMessageHandler<T> {
        @Override
        default void handle(@Nonnull T response) {
            requireNonNull(response);

            if (!state().isKnownMember(response.getSender())) {
                Logger logger = LoggerFactory.getLogger(getClass());
                logger.warn("{} Won't run, since {} is unknown to us.", localEndpointStr(), response.getSender().id());
                return;
            }

            handleResponse(response);
        }

        default void handleResponse(@Nonnull T response) {
        }
    }
}
