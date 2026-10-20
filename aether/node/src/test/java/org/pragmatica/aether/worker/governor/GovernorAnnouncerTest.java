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
