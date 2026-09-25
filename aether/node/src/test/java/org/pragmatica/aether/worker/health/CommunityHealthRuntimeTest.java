// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.health;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.cluster.metrics.MetricObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;
import static org.assertj.core.api.Assertions.assertThat;

class CommunityHealthRuntimeTest {
    @Test void periodicExchange_drivesFreshReadinessAndPreservesCoreReadinessOnWorkerExpiry() {
        var core = new NodeId("core");
        var governor = new NodeId("governor");
        var worker = new NodeId("worker");
        var directory = CommunityMemberDirectory.communityMemberDirectory();
        directory.put(governor, new ActivationDirectiveValue(ActivationDirectiveValue.WORKER, "c", ""));
        directory.put(worker, new ActivationDirectiveValue(ActivationDirectiveValue.WORKER, "c", ""));
        var authority = GovernorAnnouncementValue.governorAnnouncementValue(governor, 2);
        java.util.function.Function<String, Option<GovernorAnnouncementValue>> lookup = _ -> Option.some(authority);
        var clock = new AtomicLong(1);
        long ttl = 1_000_000_000;
        var index = CommunityHealthIndex.communityHealthIndex(core, lookup, directory::assignment, clock::get, org.pragmatica.lang.io.TimeSpan.timeSpan(ttl).nanos(), 100);
        var coreReporter = CommunityHealthReporter.communityHealthReporter(core, lookup, directory::assignment, core::equals, clock::get, org.pragmatica.lang.io.TimeSpan.timeSpan(ttl).nanos());
        var governorReporter = CommunityHealthReporter.communityHealthReporter(governor, lookup, directory::assignment, core::equals, clock::get, org.pragmatica.lang.io.TimeSpan.timeSpan(ttl).nanos());
        var sent = new ArrayList<ProtocolMessage>();
        var evidence = new ArrayList<CommunityHealthIndex.GovernorEvidence>();
        var runtime = new CommunityHealthRuntime(core, directory, index, coreReporter, lookup, () -> true,
            () -> "READY", () -> 1, (_, message) -> sent.add(message), evidence::add);
        assertThat(runtime.readyNodes(Set.of(core, worker))).containsExactly(core);
        governorReporter.recordPong(worker, "READY", 1L, new MetricObservation(1, 1, System.currentTimeMillis(), Map.of()));
        governorReporter.recordSelf("READY", 1);
        runtime.poll();
        assertThat(sent).hasSize(1);
        var request = (CommunityHealthMessage.Request) sent.getFirst();
        runtime.onReport(governorReporter.respond(core, request).unwrap());
        assertThat(runtime.readyNodes(Set.of(core))).containsExactlyInAnyOrder(core, worker, governor);
        assertThat(evidence).hasSize(2);
        clock.addAndGet(ttl);
        assertThat(runtime.readyNodes(Set.of(core, worker))).containsExactly(core);
        assertThat(runtime.aliveNodes(Set.of(core, worker))).containsExactly(core);
        assertThat(evidence).hasSize(2);
    }
}
