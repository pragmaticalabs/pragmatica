// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.swim.SwimMember;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class GovernorAnnouncerTest {
    private static final NodeId SELF = new NodeId("worker-5");
    private static final NodeId PEER = new NodeId("worker-8");
    private static final NodeId CORE = new NodeId("core");
    private final AtomicBoolean eligible = new AtomicBoolean(true);
    private final AtomicReference<Option<GovernorAnnouncementValue>> committed = new AtomicReference<>(Option.none());
    private final List<GovernorAuthorityMessage.Request> requests = new ArrayList<>();
    private final Promise<GovernorAuthorityMessage.Response> reply = Promise.promise();
    private final GovernorAnnouncer announcer = GovernorAnnouncer.governorAnnouncer(SELF, () -> "pool-a", () -> "host:9000",
        committed::get, eligible::get, request -> { requests.add(request); return reply; });

    @AfterEach
    void stop() { announcer.stop(); }

    @Test
    void nomination_doesNotGrantAuthorityBeforeCommit() {
        announcer.start();
        announcer.onMembershipChange(List.of(alive(SELF), alive(PEER)));
        assertThat(requests).hasSize(1);
        assertThat(announcer.isGovernor()).isFalse();
        var value = authority(SELF, 1);
        committed.set(Option.some(value));
        assertThat(announcer.isGovernor()).isTrue();
    }

    @Test
    void laterCommittedOwner_revokesPriorGovernor() {
        committed.set(Option.some(authority(SELF, 1)));
        announcer.start();
        assertThat(announcer.isGovernor()).isTrue();
        committed.set(Option.some(authority(PEER, 2)));
        assertThat(announcer.isGovernor()).isFalse();
        assertThat(announcer.currentGovernor().unwrap()).isEqualTo(PEER);
    }

    @Test
    void stop_pendingResponseCannotReactivateGovernor() {
        announcer.start();
        announcer.onMembershipChange(List.of(alive(SELF)));
        announcer.stop();
        reply.succeed(new GovernorAuthorityMessage.Response(CORE, "pool-a", requests.getFirst().requestId(),
                                                           Option.some(authority(SELF, 1))));
        assertThat(announcer.isGovernor()).isFalse();
    }

    @Test
    void retirement_revokesAuthorityEvenWhileAnnouncementStillExists() {
        committed.set(Option.some(authority(SELF, 1)));
        announcer.start();
        assertThat(announcer.isGovernor()).isTrue();
        eligible.set(false);
        assertThat(announcer.isGovernor()).isFalse();
    }

    @Test
    void repeatedMembershipWhileRequestPending_sendsOneRequest() {
        announcer.start();
        announcer.onMembershipChange(List.of(alive(SELF)));
        announcer.onMembershipChange(List.of(alive(SELF), alive(PEER)));
        assertThat(requests).hasSize(1);
    }

    /// #2542: only the timer's tick asks the core to re-commit an unchanged announcement; a membership edge never does.
    @Test
    void membershipEdge_doesNotAskForARefresh_theTimerTickDoes() {
        announcer.start();
        announcer.onMembershipChange(List.of(alive(SELF), alive(PEER)));
        reply.succeed(new GovernorAuthorityMessage.Response(CORE, "pool-a", requests.getFirst().requestId(), Option.some(authority(SELF, 1))));
        announcer.onMembershipChange(List.of(alive(SELF), alive(PEER)));
        announcer.refresh();
        assertThat(requests).hasSize(3);
        assertThat(requests.get(0).refresh()).isFalse();
        assertThat(requests.get(1).refresh()).as("a second edge").isFalse();
        assertThat(requests.get(2).refresh()).as("the tick").isTrue();
    }

    /// A tick that finds a request in flight is not lost: the next request carries the refresh until a grant answers it.
    @Test
    void timerTickDuringAPendingRequest_isCarriedByTheNextRequest() {
        announcer.start();
        announcer.onMembershipChange(List.of(alive(SELF), alive(PEER)));
        announcer.refresh();
        assertThat(requests).as("the tick found a request in flight").hasSize(1);
        reply.succeed(new GovernorAuthorityMessage.Response(CORE, "pool-a", requests.getFirst().requestId(), Option.some(authority(SELF, 1))));
        announcer.onMembershipChange(List.of(alive(SELF), alive(PEER)));
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).refresh()).isTrue();
    }

    /// The refresh is owed until a grant answers the request that carried it; then it is paid, and a stop forgets it.
    @Test
    void answeredRefresh_isNotCarriedByLaterEdges_andStopForgetsAPendingOne() {
        var sent = new ArrayList<GovernorAuthorityMessage.Request>();
        var replies = new ArrayList<Promise<GovernorAuthorityMessage.Response>>();
        var tracked = GovernorAnnouncer.governorAnnouncer(SELF, () -> "pool-a", () -> "host:9000", () -> Option.none(), () -> true, request -> {
            sent.add(request);
            replies.add(Promise.promise());

            return replies.getLast();
        });

        tracked.start();
        tracked.refresh();
        replies.getFirst().succeed(new GovernorAuthorityMessage.Response(CORE, "pool-a", sent.getFirst().requestId(), Option.some(authority(SELF, 1))));
        tracked.onMembershipChange(List.of(alive(SELF), alive(PEER)));
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).refresh()).as("the tick").isTrue();
        assertThat(sent.get(1).refresh()).as("the answered refresh is paid").isFalse();
        replies.getLast().succeed(new GovernorAuthorityMessage.Response(CORE, "pool-a", sent.getLast().requestId(), Option.some(authority(SELF, 1))));

        tracked.refresh();
        assertThat(sent).hasSize(3);
        tracked.stop();
        replies.getLast().succeed(new GovernorAuthorityMessage.Response(CORE, "pool-a", sent.getLast().requestId(), Option.none()));
        tracked.start();
        tracked.onMembershipChange(List.of(alive(SELF), alive(PEER)));
        assertThat(sent).hasSize(4);
        assertThat(sent.getLast().refresh()).as("a refresh owed before a stop is forgotten").isFalse();
        tracked.stop();
    }

    /// The re-announce timer is wired to the refresh: with no membership edge at all, its ticks ask for it.
    @Test
    void timer_tickAsksForARefresh() throws InterruptedException {
        var sent = new java.util.concurrent.CopyOnWriteArrayList<GovernorAuthorityMessage.Request>();
        var ticking = GovernorAnnouncer.governorAnnouncer(SELF, () -> "pool-a", () -> "host:9000", () -> Option.none(), () -> true,
            request -> { sent.add(request); return Promise.success(new GovernorAuthorityMessage.Response(CORE, "pool-a", request.requestId(), Option.some(authority(SELF, 1)))); },
            org.pragmatica.lang.io.TimeSpan.timeSpan(40).millis());

        try {
            ticking.start();
            for (var waited = 0; sent.size() < 2 && waited < 100; waited++) {
                Thread.sleep(50);
            }
            assertThat(sent).as("the timer ticked at least twice").hasSizeGreaterThanOrEqualTo(2);
            assertThat(sent).allMatch(GovernorAuthorityMessage.Request::refresh);
        } finally {
            ticking.stop();
        }
    }

    @Test
    void singletonReadyWorkerNominatesWithoutSwimSelfEntry() {
        announcer.start();
        announcer.onMembershipChange(List.of());
        assertThat(requests).hasSize(1);
    }

    @Test
    void peerOnlySwimViewIncludesReadySelfInElection() {
        announcer.start();
        announcer.onMembershipChange(List.of(alive(PEER)));
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().sender()).isEqualTo(SELF);
    }

    @Test
    void unreadySingletonDoesNotNominate() {
        eligible.set(false);
        announcer.start();
        announcer.onMembershipChange(List.of());
        assertThat(requests).isEmpty();
    }

    private static final int TRIALS = 40;

    /// One nomination whose reply resolves LATER, on another thread (a reply that resolves inline hides every ordering defect
    /// between the promise and its handlers). A probe registers its observer on the reply AFTER the announcer's own steps, so it
    /// runs when the announcer's dependent steps have; a handler form of those steps ran concurrently with it instead.
    private record Nomination(GovernorAnnouncer announcer, List<GovernorAuthorityMessage.Request> sent, Promise<GovernorAuthorityMessage.Response> reply) {
        static Nomination nominate() {
            var sent = new ArrayList<GovernorAuthorityMessage.Request>();
            var reply = new AtomicReference<Promise<GovernorAuthorityMessage.Response>>();
            var announcer = GovernorAnnouncer.governorAnnouncer(SELF, () -> "pool-a", () -> "host:9000", () -> Option.none(), () -> true, request -> {
                sent.add(request);
                reply.set(Promise.promise());

                return reply.get();
            });

            announcer.start();
            announcer.onMembershipChange(List.of(alive(SELF), alive(PEER)));

            return new Nomination(announcer, sent, reply.get());
        }

        void resolveLater(Option<GovernorAnnouncementValue> granted) {
            Thread.ofVirtual().start(() -> resolveAfterDelay(granted));
        }

        private void resolveAfterDelay(Option<GovernorAnnouncementValue> granted) {
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000L);
            reply.succeed(new GovernorAuthorityMessage.Response(CORE, "pool-a", sent.getFirst().requestId(), granted));
        }

        int renominateAndCount() {
            announcer.onMembershipChange(List.of(alive(SELF), alive(PEER)));

            return sent.size();
        }
    }

    @Test
    void grant_isRecordedBeforeTheReplyResolvesForItsDependents() {
        var missing = 0;

        for (var trial = 0; trial < TRIALS; trial++) {
            var nomination = Nomination.nominate();
            var governor = nomination.reply().map(_ -> nomination.announcer().isGovernor());

            nomination.resolveLater(Option.some(authority(SELF, 1)));
            if (!governor.await().unwrap()) {
                missing++;
            }
            nomination.announcer().stop();
        }

        assertThat(missing).as("nominations (of %d) whose grant was not on record when the reply's dependents ran", TRIALS).isZero();
    }

    @Test
    void pendingFlag_isReleasedBeforeTheReplyResolvesForItsDependents() {
        var stillPending = 0;

        for (var trial = 0; trial < TRIALS; trial++) {
            var nomination = Nomination.nominate();
            var requestsSent = nomination.reply().map(_ -> nomination.renominateAndCount());

            nomination.resolveLater(Option.none());
            if (requestsSent.await().unwrap() < 2) {
                stillPending++;
            }
            nomination.announcer().stop();
        }

        assertThat(stillPending).as("nominations (of %d) still marked pending when the reply's dependents ran", TRIALS).isZero();
    }

    private static GovernorAnnouncementValue authority(NodeId owner, long term) {
        return GovernorAnnouncementValue.governorAnnouncementValue(owner, List.of(SELF, PEER), "host:9000", 1,
                                                                    term, Epoch.epoch(0L, term, 0), Epoch.ZERO,
                                                                    HlcTimestamp.ZERO, false);
    }

    private static SwimMember alive(NodeId id) {
        return SwimMember.swimMember(id, SwimMember.MemberState.ALIVE, 0, new InetSocketAddress("127.0.0.1", 0));
    }
}
