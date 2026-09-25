// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.health;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.worker.health.CommunityHealthMessage.Request;
import static org.assertj.core.api.Assertions.assertThat;

class CommunityHealthReporterTest {
    @Test void onlyDirectAssignedEvidence_canProduceFreshReadiness() {
        var core = new NodeId("core");
        var governor = new NodeId("governor");
        var worker = new NodeId("worker");
        var clock = new AtomicLong(100);
        var assignments = Map.of(governor, "c", worker, "c");
        var authority = GovernorAnnouncementValue.governorAnnouncementValue(governor, 2);
        var reporter = CommunityHealthReporter.communityHealthReporter(governor, _ -> Option.some(authority),
            node -> Option.option(assignments.get(node)), core::equals, clock::get, org.pragmatica.lang.io.TimeSpan.timeSpan(100).millis());
        var request = new Request(core, "c", authority.communityTerm(), "challenge", 1);
        reporter.recordPong(new NodeId("other"), "READY", 42L, observation(1));
        assertThat(reporter.respond(core, request).unwrap().members()).isEmpty();
        reporter.recordPong(worker, "READY", 42L, observation(2));
        reporter.recordPong(worker, "DRAINING", 42L, observation(1));
        assertThat(reporter.respond(core, request).unwrap().members().getFirst().ready()).isTrue();
        assertThat(reporter.respond(core, request).unwrap().members().getFirst().incarnation()).isEqualTo(42L);
        clock.addAndGet(100_000_000);
        assertThat(reporter.respond(core, request).unwrap().members().getFirst().alive()).isFalse();
        assertThat(reporter.respond(worker, request).isEmpty()).isTrue();
        assertThat(reporter.respond(core, new Request(core, "c", authority.communityTerm() + 1, "challenge", 2)).isEmpty()).isTrue();
    }
    private static org.pragmatica.cluster.metrics.MetricObservation observation(long incarnation) {
        return new org.pragmatica.cluster.metrics.MetricObservation(incarnation, 1, System.currentTimeMillis(), Map.of());
    }

}
