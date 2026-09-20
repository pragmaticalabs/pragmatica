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
import org.pragmatica.aether.worker.governor.GovernorAuthority;
import org.pragmatica.aether.worker.governor.GovernorAuthorityMessage;
import org.pragmatica.cluster.metrics.MetricObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import static org.assertj.core.api.Assertions.assertThat;

class GovernorRecoveryTest {
    @Test void asymmetricCoreUplinkLoss_replacesLiveIncumbentOnlyAfterExpiryAndDirectProof() {
        var core = new NodeId("core");
        var old = new NodeId("old");
        var next = new NodeId("next");
        var directory = CommunityMemberDirectory.communityMemberDirectory();
        directory.put(old, ActivationDirectiveValue.worker("c", ""));
        directory.put(next, ActivationDirectiveValue.worker("c", ""));
        var authority = new AtomicReference<>(GovernorAnnouncementValue.governorAnnouncementValue(old, List.of(old, next), "old:9"));
        var clock = new AtomicLong(1);
        var grace = TimeSpan.timeSpan(1).seconds();
        var reports = CommunityHealthIndex.communityHealthIndex(core, _ -> Option.some(authority.get()), directory::assignment, clock::get, grace, 100);
        var candidates = GovernorCandidateHealth.governorCandidateHealth(directory::assignment, clock::get, grace, 2);
        var probes = new ArrayList<NodeId>();
        var granted = new ArrayList<NodeId>();
        var grants = new GovernorAuthority() {
            public Promise<GovernorAuthorityMessage.Response> handle(GovernorAuthorityMessage.Request request) { return Promise.success(new GovernorAuthorityMessage.Response(core, "c", 1, Option.some(authority.get()))); }
            public Promise<Option<GovernorAnnouncementValue>> reconcile(String community, NodeId candidate, long expectedTerm, String address) {
                assertThat(expectedTerm).isEqualTo(authority.get().communityTerm());
                granted.add(candidate);
                return Promise.success(Option.some(authority.get()));
            }
        };
        var leader = new java.util.concurrent.atomic.AtomicBoolean(false);
        var recovery = GovernorRecovery.governorRecovery(directory, reports, candidates, _ -> Option.some(authority.get()), grants,
            leader::get, _ -> Option.some("host:9"), probes::add, clock::get, grace, TimeSpan.timeSpan(100).millis());
        recovery.poll();
        clock.addAndGet(grace.nanos() * 10);
        recovery.poll();
        assertThat(probes).isEmpty();
        assertThat(granted).isEmpty();
        // Becoming leader after a long follower tenure starts a fresh bounded no-history grace.
        leader.set(true);
        assertThat(candidates.request(old, "c")).isTrue();
        assertThat(candidates.recordPong(old, "READY", new MetricObservation(1, 1, System.currentTimeMillis(), Map.of()))).isTrue();
        recovery.poll();
        assertThat(probes).isEmpty();
        clock.addAndGet(grace.nanos() - 1);
        recovery.poll();
        assertThat(probes).isEmpty();
        clock.incrementAndGet();
        assertThat(candidates.request(old, "c")).isTrue();
        assertThat(candidates.recordPong(old, "READY", new MetricObservation(1, 2, System.currentTimeMillis(), Map.of()))).isTrue();
        assertThat(candidates.isEligible(old)).isTrue();
        recovery.poll();
        assertThat(probes).containsExactly(next);
        assertThat(granted).isEmpty();
        assertThat(candidates.recordPong(next, "READY", new MetricObservation(1, 1, System.currentTimeMillis(), Map.of()))).isTrue();
        recovery.poll();
        assertThat(granted).containsExactly(next);
        assertThat(directory.members("c")).containsExactlyInAnyOrder(old, next);
    }
}
