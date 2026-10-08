// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.health;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.worker.health.CommunityHealthMessage.MemberDeparture;
import org.pragmatica.aether.worker.health.CommunityHealthMessage.Report;
import org.pragmatica.aether.worker.health.CommunityHealthMessage.Request;
import org.pragmatica.cluster.metrics.MetricObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #1717 — the governor relays a member's terminal death (its own SWIM reached DEAD) to the core. The core
/// applies it only from the committed governor of the exact challenged term, once per member, for a member of
/// that community carrying the pinned boot token. Silence, staleness and absence are never a death.
class CommunityDepartureRelayTest {
    private static final long TTL = 1_000_000_000L;
    private final NodeId core = new NodeId("core");
    private final NodeId governor = new NodeId("governor");
    private final NodeId worker = new NodeId("worker");
    private final NodeId stranger = new NodeId("stranger");
    private final AtomicLong clock = new AtomicLong(1);
    private final AtomicReference<GovernorAnnouncementValue> authority = new AtomicReference<>(GovernorAnnouncementValue.governorAnnouncementValue(governor, 2));
    private final CommunityMemberDirectory directory = CommunityMemberDirectory.communityMemberDirectory();
    private final CommunityHealthIndex index = CommunityHealthIndex.communityHealthIndex(core,
                                                                                       _ -> Option.some(authority.get()),
                                                                                       directory::assignment,
                                                                                       clock::get,
                                                                                       org.pragmatica.lang.io.TimeSpan.timeSpan(TTL).nanos(),
                                                                                       100);
    private final CommunityHealthReporter coreReporter = reporter(core);
    private final CommunityHealthReporter governorReporter = reporter(governor);
    private final List<ProtocolMessage> sent = new ArrayList<>();
    private final List<CommunityHealthIndex.GovernorDeparture> departures = new ArrayList<>();
    private final CommunityHealthRuntime runtime;

    CommunityDepartureRelayTest() {
        directory.put(governor, new ActivationDirectiveValue(ActivationDirectiveValue.WORKER, "c", ""));
        directory.put(worker, new ActivationDirectiveValue(ActivationDirectiveValue.WORKER, "c", ""));
        directory.put(stranger, new ActivationDirectiveValue(ActivationDirectiveValue.WORKER, "other", ""));
        runtime = new CommunityHealthRuntime(core,
                                             directory,
                                             index,
                                             coreReporter,
                                             _ -> Option.some(authority.get()),
                                             () -> true,
                                             () -> "READY",
                                             () -> 1,
                                             (_, message) -> sent.add(message),
                                             _ -> {},
                                             departures::add);
    }

    private CommunityHealthReporter reporter(NodeId self) {
        return CommunityHealthReporter.communityHealthReporter(self,
                                                               _ -> Option.some(authority.get()),
                                                               directory::assignment,
                                                               core::equals,
                                                               clock::get,
                                                               org.pragmatica.lang.io.TimeSpan.timeSpan(TTL).nanos());
    }

    private void observeWorker(long bootToken) {
        governorReporter.recordPong(worker, "READY", bootToken, new MetricObservation(1, 1, System.currentTimeMillis(), Map.of()));
        governorReporter.recordSelf("READY", 1);
    }

    private Request challenge() {
        runtime.poll();

        return sent.stream()
                   .filter(Request.class::isInstance)
                   .map(Request.class::cast)
                   .filter(request -> "c".equals(request.communityId()))
                   .reduce((first, second) -> second)
                   .orElseThrow();
    }

    @Test
    void governorRelaysItsSwimDeath_theCoreAppliesItOnce() {
        observeWorker(4);
        governorReporter.recordTerminalDeath(worker);

        var first = challenge();
        runtime.onReport(governorReporter.respond(core, first).unwrap());

        assertThat(departures).as("the committed governor's death report reaches the consumer").hasSize(1);
        assertThat(departures.getFirst().node()).isEqualTo(worker);
        assertThat(departures.getFirst().bootToken()).isEqualTo(4);
        assertThat(departures.getFirst().governorTerm()).isEqualTo(authority.get().communityTerm());
        clock.addAndGet(TTL + 1);
        var second = challenge();
        runtime.onReport(governorReporter.respond(core, second).unwrap());
        assertThat(departures).as("one report per member: the repeat is not delivered again").hasSize(1);
    }

    /// (b) A report from a superseded governor term is rejected whole, departures included.
    @Test
    void staleTermReport_isRejected_andDeliversNoDeparture() {
        observeWorker(4);
        governorReporter.recordTerminalDeath(worker);
        var request = challenge();
        var genuine = governorReporter.respond(core, request).unwrap();
        var stale = new Report(genuine.sender(), genuine.communityId(), genuine.governorTerm() - 1, genuine.incarnation(), genuine.sequence(), genuine.members(), genuine.departures());

        runtime.onReport(stale);

        assertThat(departures).as("a superseded term's departures are rejected with its report").isEmpty();
        runtime.onReport(genuine);
        assertThat(departures).as("control: the same departure under the current term IS delivered").hasSize(1);
    }

    @Test
    void departureForAMemberOfAnotherCommunity_isDropped() {
        observeWorker(4);
        var request = challenge();
        var genuine = governorReporter.respond(core, request).unwrap();
        var forged = new Report(genuine.sender(), genuine.communityId(), genuine.governorTerm(), genuine.incarnation(), genuine.sequence(), genuine.members(),
                                List.of(new MemberDeparture(stranger, 9)));

        runtime.onReport(forged);

        assertThat(departures).isEmpty();
    }

    @Test
    void departureCarryingAnotherBootToken_isDropped() {
        observeWorker(4);
        var request = challenge();
        runtime.onReport(governorReporter.respond(core, request).unwrap());
        clock.addAndGet(TTL + 1);
        var next = challenge();
        var genuine = governorReporter.respond(core, next).unwrap();
        var otherProcess = new Report(genuine.sender(), genuine.communityId(), genuine.governorTerm(), genuine.incarnation(), genuine.sequence(), genuine.members(),
                                      List.of(new MemberDeparture(worker, 99)));

        runtime.onReport(otherProcess);

        assertThat(departures).as("token 4 was pinned by the first report; 99 is another process").isEmpty();
    }

    /// (d) at the producer and the consumer: silence, expiry and a missing governor are not a death.
    @Test
    void silenceAndStaleness_relayNoDeparture() {
        observeWorker(4);
        var request = challenge();
        runtime.onReport(governorReporter.respond(core, request).unwrap());
        clock.addAndGet(10 * TTL);
        var later = challenge();
        var report = governorReporter.respond(core, later).unwrap();

        assertThat(report.members()).as("the member is long stale and reported not alive").allMatch(member -> !member.alive());
        assertThat(report.departures()).as("staleness is not a death report").isEmpty();
        runtime.onReport(report);
        assertThat(departures).isEmpty();
        // a governor that never answers again produces nothing at all
        clock.addAndGet(10 * TTL);
        challenge();
        assertThat(departures).isEmpty();
    }

    @Test
    void producerRelaysOnlyANonCoreMemberOfItsOwnCommunity_withAnObservedToken() {
        observeWorker(4);
        governorReporter.recordTerminalDeath(core);
        governorReporter.recordTerminalDeath(stranger);
        governorReporter.recordTerminalDeath(governor);
        var neverObserved = new NodeId("never-observed");
        directory.put(neverObserved, new ActivationDirectiveValue(ActivationDirectiveValue.WORKER, "c", ""));
        governorReporter.recordTerminalDeath(neverObserved);

        var report = governorReporter.respond(core, challenge()).unwrap();

        assertThat(report.departures()).as("core, other-community, self and never-observed members are not relayed").isEmpty();
        governorReporter.recordTerminalDeath(worker);
        assertThat(governorReporter.respond(core, challenge()).unwrap().departures()).extracting(MemberDeparture::node).containsExactly(worker);
        assertThat(governorReporter.respond(core, challenge()).unwrap().members()).as("a dead member is never reported alive again")
                                                                                .extracting(member -> member.node())
                                                                                .doesNotContain(worker);
    }
}
