/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderDoubtConfirmed;
import org.pragmatica.consensus.net.NetworkMessage.LeaderPreVoteRequest;
import org.pragmatica.consensus.net.NetworkMessage.LeaderPreVoteResponse;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Leader pre-vote (#1748): a follower that lost ITS OWN view of the committed leader does not elect on that
/// alone. It first asks the electorate whether they still see the leader; it proceeds to election only when a
/// majority of the electorate (itself included) affirmatively doubts the leader.
///
/// **Why.** A follower that loses only its own link to a healthy leader (SWIM or QUIC) used to go
/// `Led -> NodeGone(leader) -> ReElecting` and propose itself. Any majority that could still reach it, and
/// the incumbent, committed the challenger, so one broken link deposed a healthy leader (rc4 cloud run,
/// 12-network S05: a minority node won 3 of 5 through links the firewall had not cut yet; earlier, ~420
/// leader flaps in 35 minutes). The commit is a consensus decision, so the guard sits where the information
/// is: before the proposal, in the one place that knows the follower is about to act on a private view.
///
/// **The tally.** The electorate is the installed voter set. The asker counts itself as doubting. A
/// responder doubts when it follows another leader, has none, or follows this one but has lost it (leader
/// absent from its transport view, or sustained leader-ping silence — the same lease the follower's own
/// `LeaderSilent` uses, so a freshly elected leader still warming up is not doubted). A responder with no
/// view of the leadership (booting, quorum lost, passive) does not answer. SILENCE IS NOT DOUBT: only an
/// affirmative doubt counts towards proceeding, so a follower cut off from most of the cluster can never
/// assemble the majority it needs from nodes it cannot hear.
///
/// A round decides as soon as the outcome is fixed — `doubting >= majority` proceeds, `vouching >
/// electorate - majority` cannot — and otherwise at the round timeout (refrain). A refrain is followed by a
/// retry after the proposal retry delay for as long as the follower is still in `Led(leader)` and still
/// suspects it. The follower KEEPS the leader meanwhile: the leader is the committed one, and dropping it on
/// a private view is exactly the churn being removed.
///
/// **Liveness.** A genuinely dead or majority-partitioned leader is doubted by every survivor of that
/// majority, each through its own failure detector, so the added latency over today's immediate
/// `ReElecting` is the detection skew between the last survivor needed for the majority and this follower,
/// plus at most one retry delay (`proposalRetryDelay` x [1, 1.5), 500-750 ms) and, when responses are lost,
/// one round timeout ([#DEFAULT_TIMEOUT]). It is bounded by the lease the follower already runs (4 silent
/// checks), not additive to it. A leader that a majority still sees is not replaced, by design.
///
/// **Not a lease on the voters.** Voters do not refuse to commit a challenger: Rabia decides on proposal
/// vectors and a per-node veto there would make the decision depend on local state. This guards the
/// well-behaved proposer, which is the observed failure; it does not defend against a proposer that skips it.
///
/// Thread safety: all episode state is guarded by `this`; effects (sends, FSM dispatch) run after the lock is
/// released, so FSM re-entry cannot deadlock against it.
public final class LeaderPreVote {
    private static final Logger log = LoggerFactory.getLogger(LeaderPreVote.class);
    /// How long a round waits for answers before it refrains. Sized above a LAN/WAN round trip plus a loaded
    /// handler, below the proposal retry horizon, so a lost answer costs one retry rather than a stall.
    public static final TimeSpan DEFAULT_TIMEOUT = TimeSpan.timeSpan(1).seconds();

    private static final Runnable NO_EFFECT = () -> {};

    private final LeaderElectionContext ctx;
    private final TimeSpan timeout;
    private final AtomicLong rounds = new AtomicLong(0);
    private Option<Episode> episode = none();

    private LeaderPreVote(LeaderElectionContext ctx, TimeSpan timeout) {
        this.ctx = ctx;
        this.timeout = timeout;
    }

    public static LeaderPreVote leaderPreVote(LeaderElectionContext ctx, TimeSpan timeout) {
        return new LeaderPreVote(ctx, timeout);
    }

    /// One suspicion of one leader: either a round is open (`asking`) or the follower is backing off before
    /// the next round. Guarded by the enclosing [`LeaderPreVote`].
    private static final class Episode {
        final NodeId leader;
        long round;
        boolean asking;
        Set<NodeId> vouching = new HashSet<>();
        Set<NodeId> doubting = new HashSet<>();
        Option<ScheduledFuture<?>> timer = none();

        Episode(NodeId leader) {
            this.leader = leader;
        }
    }

    /// The follower suspects `leader`. Idempotent per leader: a second suspicion while an episode for the
    /// same leader is live (a repeated `LeaderSilent`, a `NodeGone` after one) changes nothing.
    @Contract
    public void suspect(NodeId leader) {
        beginEpisode(leader).run();
    }

    /// Ends the live episode about `leader` (the follower left that `Led` tenure). Scoped to the leader: a
    /// tenure's late `onExit` must not end an episode the next tenure has already started about another one.
    @Contract
    public void cancel(NodeId leader) {
        cancelEpisode(leader);
    }

    /// Answers a peer's question from this node's own view. No view of the leadership, no answer.
    @Contract
    public void onRequest(LeaderPreVoteRequest request) {
        if (!ctx.electorate().contains(request.sender()) || request.sender().equals(ctx.self())) {
            return;
        }

        stanceOn(request.leader()).onPresent(healthy -> reply(request, healthy));
    }

    @Contract
    public void onResponse(LeaderPreVoteResponse response) {
        recordResponse(response).run();
    }

    private Option<Boolean> stanceOn(NodeId leader) {
        return switch (ctx.fsm()
                          .current()) {
            case LeaderElectionState.Led led -> some(sees(led, leader));
            case LeaderElectionState.Electing _, LeaderElectionState.ReElecting _ -> some(false);
            default -> none();
        };
    }

    private boolean sees(LeaderElectionState.Led led, NodeId leader) {
        return led.leader()
                  .equals(leader) && (leader.equals(ctx.self()) || !led.isLeaderSuspected());
    }

    private void reply(LeaderPreVoteRequest request, boolean healthy) {
        ctx.router()
           .route(new Send(request.sender(),
                           new LeaderPreVoteResponse(ctx.self(),
                                                     request.leader(),
                                                     request.round(),
                                                     healthy)));
    }

    private synchronized Runnable beginEpisode(NodeId leader) {
        if (episode.filter(e -> e.leader.equals(leader)).isPresent()) {
            return NO_EFFECT;
        }

        cancelLocked();
        var started = new Episode(leader);

        episode = some(started);

        return openRound(started);
    }

    private synchronized void cancelEpisode(NodeId leader) {
        if (episode.filter(e -> e.leader.equals(leader)).isPresent()) {
            cancelLocked();
        }
    }

    private void cancelLocked() {
        episode.onPresent(this::stopTimer);
        episode = none();
    }

    private void stopTimer(Episode current) {
        current.timer.onPresent(future -> future.cancel(false));
        current.timer = none();
    }

    /// Opens a round: the asker counts itself as doubting, asks every other voter, and arms the round
    /// timeout. Returns the effect to run outside the lock (the sends, or the verdict if the electorate is
    /// the asker alone).
    private Runnable openRound(Episode current) {
        var voters = ctx.electorate();
        var round = rounds.incrementAndGet();

        current.round = round;
        current.asking = true;
        current.vouching = new HashSet<>();
        current.doubting = new HashSet<>(Set.of(ctx.self()));
        stopTimer(current);
        current.timer = some(SharedScheduler.schedule(() -> onRoundTimeout(current, round), timeout));
        log.info("Leader pre-vote round {}: {} lost its view of leader {}, asking {} voter(s) whether they still see it",
                 round,
                 ctx.self(),
                 current.leader,
                 voters.size() - 1);
        var ask = askAll(voters, current.leader, round);
        var verdict = evaluate(current, voters);

        return () -> Stream.of(ask, verdict).forEach(Runnable::run);
    }

    private Runnable askAll(List<NodeId> voters, NodeId leader, long round) {
        return () -> voters.stream()
                           .filter(voter -> !voter.equals(ctx.self()))
                           .forEach(voter -> ask(voter, leader, round));
    }

    private void ask(NodeId voter, NodeId leader, long round) {
        ctx.router().route(new Send(voter, new LeaderPreVoteRequest(ctx.self(), leader, round)));
    }

    private synchronized Runnable recordResponse(LeaderPreVoteResponse response) {
        var voters = ctx.electorate();

        return episode.filter(e -> accepts(e, response, voters))
                      .map(e -> tally(e, response, voters))
                      .or(NO_EFFECT);
    }

    private boolean accepts(Episode current, LeaderPreVoteResponse response, List<NodeId> voters) {
        return current.asking
               && current.round == response.round()
               && current.leader.equals(response.leader())
               && voters.contains(response.sender())
               && !response.sender()
                           .equals(ctx.self());
    }

    private Runnable tally(Episode current, LeaderPreVoteResponse response, List<NodeId> voters) {
        if (response.leaderHealthy()) {
            current.vouching.add(response.sender());
        } else {
            current.doubting.add(response.sender());
        }

        return evaluate(current, voters);
    }

    /// Decides the round if its outcome is fixed: proceed once a majority doubts; refrain once so many
    /// voters vouch that a majority can no longer be reached; otherwise keep waiting for the timeout.
    private Runnable evaluate(Episode current, List<NodeId> voters) {
        var majority = voters.size() / 2 + 1;

        if (current.doubting.size() >= majority) {
            return proceed(current);
        }

        return voters.size() - current.vouching.size() < majority
               ? refrain(current)
               : NO_EFFECT;
    }

    private Runnable proceed(Episode current) {
        var leader = current.leader;
        var doubters = current.doubting.size();
        var vouchers = current.vouching.size();

        stopTimer(current);
        episode = none();
        log.info("Leader pre-vote: {} of {} voter(s) doubt leader {} ({} vouch) — proceeding to election",
                 doubters,
                 ctx.electorate().size(),
                 leader,
                 vouchers);

        return () -> ctx.fsm()
                        .dispatch(new LeaderDoubtConfirmed(leader));
    }

    private Runnable refrain(Episode current) {
        var round = current.round;

        stopTimer(current);
        current.asking = false;
        log.info("Leader pre-vote round {}: leader {} is still seen by the electorate ({} doubt, {} vouch of {}) — "
                + "keeping it, retrying while the suspicion lasts",
                 round,
                 current.leader,
                 current.doubting.size(),
                 current.vouching.size(),
                 ctx.electorate().size());
        current.timer = some(SharedScheduler.schedule(() -> onRetry(current), retryDelay()));

        return NO_EFFECT;
    }

    private TimeSpan retryDelay() {
        return TimeSpan.timeSpan((long)(ctx.proposalRetryDelay().millis() * (1.0 + ctx.jitterSource().getAsDouble()))).millis();
    }

    private void onRoundTimeout(Episode current, long round) {
        timeoutEffect(current, round).run();
    }

    private synchronized Runnable timeoutEffect(Episode current, long round) {
        return isLive(current) && current.asking && current.round == round
               ? refrain(current)
               : NO_EFFECT;
    }

    private void onRetry(Episode current) {
        retryEffect(current).run();
    }

    private synchronized Runnable retryEffect(Episode current) {
        if (!isLive(current) || current.asking) {
            return NO_EFFECT;
        }

        if (stillSuspected(current.leader)) {
            return openRound(current);
        }

        cancelLocked();

        return NO_EFFECT;
    }

    private boolean isLive(Episode current) {
        return episode.filter(live -> live == current)
                      .isPresent();
    }

    private boolean stillSuspected(NodeId leader) {
        return ctx.fsm()
                  .current() instanceof LeaderElectionState.Led led
               && led.leader()
                     .equals(leader)
               && led.isLeaderSuspected();
    }
}
