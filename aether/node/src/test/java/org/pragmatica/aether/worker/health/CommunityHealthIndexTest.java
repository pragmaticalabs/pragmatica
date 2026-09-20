// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.health;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.worker.health.CommunityHealthMessage.MemberHealth;
import org.pragmatica.aether.worker.health.CommunityHealthMessage.Report;
import org.pragmatica.aether.worker.health.CommunityHealthMessage.Request;
import static org.assertj.core.api.Assertions.assertThat;

class CommunityHealthIndexTest {
    private final NodeId core = new NodeId("core");
    private final NodeId governor = new NodeId("governor");
    private final NodeId worker = new NodeId("worker");
    private final AtomicLong clock = new AtomicLong(1_000);
    private final AtomicReference<GovernorAnnouncementValue> authority = new AtomicReference<>(
        announcement(governor, 3));
    private final Map<NodeId, String> assignments = new HashMap<>(Map.of(governor, "community", worker, "community"));
    private final CommunityHealthIndex index = index();

    private CommunityHealthIndex index() {
        return CommunityHealthIndex.communityHealthIndex(core, _ -> Option.some(authority.get()),
            node -> Option.option(assignments.get(node)), clock::get, org.pragmatica.lang.io.TimeSpan.timeSpan(100).nanos(), 200);
    }
    private Report report(Request request, long age) {
        return new Report(governor, "community", request.governorTerm(), request.incarnation(), request.sequence(),
            List.of(new MemberHealth(worker, 2, true, true, org.pragmatica.lang.io.TimeSpan.timeSpan(age).nanos())));
    }

    @Test void governorReadinessProjection_preservesDirectDrainAndExpiresAtOriginalObservationAge() {
        var request = index.request("community").unwrap();
        clock.addAndGet(10);
        assertThat(index.accept(governor, report(request, 20))).isTrue();
        assertThat(projected(Map.of()).get(worker)).isEqualTo(org.pragmatica.aether.metrics.NodeReportedState.READY);
        assertThat(projected(Map.of(worker, org.pragmatica.aether.metrics.NodeReportedState.DRAINING)).get(worker))
            .isEqualTo(org.pragmatica.aether.metrics.NodeReportedState.DRAINING);
        assertThat(projected(Map.of(worker, org.pragmatica.aether.metrics.NodeReportedState.SYNCING)).get(worker))
            .isEqualTo(org.pragmatica.aether.metrics.NodeReportedState.SYNCING);
        clock.addAndGet(70);
        assertThat(projected(Map.of())).doesNotContainKey(worker);
    }

    @Test void governorReadinessProjection_dropsRevokedTermWithoutWaitingForCacheExpiry() {
        var request = index.request("community").unwrap();
        assertThat(index.accept(governor, report(request, 0))).isTrue();
        assertThat(projected(Map.of())).containsKey(worker);
        authority.set(announcement(governor, 4));
        assertThat(projected(Map.of())).doesNotContainKey(worker);
    }

    private Map<NodeId, org.pragmatica.aether.metrics.NodeReportedState> projected(
        Map<NodeId, org.pragmatica.aether.metrics.NodeReportedState> direct) {
        var community = index.readyMembers().stream().collect(java.util.stream.Collectors.toMap(
            node -> node, _ -> org.pragmatica.aether.metrics.NodeReportedState.READY));
        return org.pragmatica.aether.metrics.ReadinessProjection.merge(community, direct);
    }

    @Test void matchingFreshChallenge_exposesReadyAndExpiresWithoutDeclaringDeath() {
        var request = index.request("community").unwrap();
        clock.addAndGet(10);
        assertThat(index.accept(governor, report(request, 20))).isTrue();
        assertThat(index.isReady(worker)).isTrue();
        clock.addAndGet(70);
        assertThat(index.isReachable(worker)).isFalse();
        assertThat(index.isReady(worker)).isFalse();
    }

    @Test void duplicateDelayedAndWrongSenderReports_doNotRenewObservation() {
        var request = index.request("community").unwrap();
        assertThat(index.accept(worker, report(request, 0))).isFalse();
        assertThat(index.accept(governor, report(request, 0))).isTrue();
        clock.addAndGet(50);
        assertThat(index.accept(governor, report(request, 0))).isFalse();
        clock.addAndGet(50);
        assertThat(index.isReady(worker)).isFalse();
        var next = index.request("community").unwrap();
        clock.addAndGet(100);
        assertThat(index.accept(governor, report(next, 0))).isFalse();
    }

    @Test void coreRestartAndAuthorityChange_rejectOldReportsImmediately() {
        var request = index.request("community").unwrap();
        assertThat(index().accept(governor, report(request, 0))).isFalse();
        assertThat(index.accept(governor, report(request, 0))).isTrue();
        authority.set(announcement(governor, 4));
        assertThat(index.isReady(worker)).isFalse();
    }

    @Test void wrongAssignmentAndDuplicateMember_rejectEntireReport() {
        var request = index.request("community").unwrap();
        assignments.put(worker, "different");
        assertThat(index.accept(governor, report(request, 0))).isFalse();
        assignments.put(worker, "community");
        var member = new MemberHealth(worker, 2, true, true, org.pragmatica.lang.io.TimeSpan.timeSpan(0).nanos());
        assertThat(index.accept(governor, new Report(governor, "community", request.governorTerm(),
            request.incarnation(), request.sequence(), List.of(member, member)))).isFalse();
        assertThat(index.isReady(worker)).isFalse();
    }

    @Test void staleWorkerObservation_cannotBecomeFreshThroughFreshGovernorResponse() {
        var request = index.request("community").unwrap();
        assertThat(index.accept(governor, report(request, 100))).isTrue();
        assertThat(index.isReachable(worker)).isFalse();
    }
    private static GovernorAnnouncementValue announcement(NodeId governor, long term) {
        return GovernorAnnouncementValue.governorAnnouncementValue(governor, List.of(governor), "", 0, term,
            org.pragmatica.aether.slice.generation.Epoch.ZERO, org.pragmatica.aether.slice.generation.Epoch.ZERO,
            org.pragmatica.hlc.HlcTimestamp.ZERO, false);
    }

}
