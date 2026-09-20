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
import org.pragmatica.cluster.metrics.MetricObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import static org.assertj.core.api.Assertions.assertThat;

class WorkerAdmissionTest {
    @Test void preAssignmentSyncingProof_requiresIndependentIntentAndBoundedRequestedObservation() {
        var worker = new NodeId("worker");
        var unknown = new NodeId("unknown");
        var clock = new AtomicLong(1);
        var probed = new ArrayList<NodeId>();
        var admitted = new ArrayList<NodeId>();
        var admission = WorkerAdmission.workerAdmission(Set.of(worker)::contains, probed::add, (node, _) -> admitted.add(node),
            clock::get, TimeSpan.timeSpan(1).seconds(), 2);
        admission.request(unknown);
        admission.request(worker);
        admission.request(worker);
        assertThat(probed).containsExactly(worker);
        var observation = new MetricObservation(1, 1, System.currentTimeMillis(), Map.of());
        assertThat(admission.recordPong(unknown, "SYNCING", observation)).isFalse();
        assertThat(admission.recordPong(worker, "DRAINING", observation)).isFalse();
        assertThat(admission.recordPong(worker, "SYNCING", observation)).isTrue();
        assertThat(admitted).containsExactly(worker);
        admission.request(worker);
        assertThat(admission.recordPong(worker, "SYNCING", observation)).isFalse();
        assertThat(admission.recordPong(worker, "SYNCING", new MetricObservation(1, 2, System.currentTimeMillis(), Map.of()))).isTrue();
    }
    @Test void silentFirstBatch_doesNotStarveLaterAdmittedWorkers() {
        var nodes = java.util.stream.IntStream.range(0, 129).mapToObj(value -> new NodeId("worker-" + value)).toList();
        var clock = new AtomicLong(1);
        var probes = new ArrayList<NodeId>();
        var admission = WorkerAdmission.workerAdmission(_ -> true, probes::add, (_, _) -> {}, clock::get, TimeSpan.timeSpan(1).seconds(), 128);
        admission.poll(nodes);
        assertThat(probes).hasSize(128).doesNotContain(nodes.getLast());
        clock.addAndGet(TimeSpan.timeSpan(1).seconds().nanos());
        admission.poll(nodes);
        assertThat(probes.get(128)).isEqualTo(nodes.getLast());
    }

    @Test void expiredAcceptedVersion_releasesBudgetWithoutMakingReplayValid() {
        var first = new NodeId("first");
        var next = new NodeId("next");
        var probes = new ArrayList<NodeId>();
        var admission = WorkerAdmission.workerAdmission(_ -> true, probes::add, (_, _) -> {}, System::nanoTime, TimeSpan.timeSpan(1).seconds(), 1);
        var old = new MetricObservation(1, 1, System.currentTimeMillis() - MetricObservation.MAX_AGE_MS + 200, Map.of());
        admission.request(first);
        assertThat(admission.recordPong(first, "SYNCING", old)).isTrue();
        admission.request(next);
        assertThat(probes).containsExactly(first);
        org.awaitility.Awaitility.await().atMost(2, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> !MetricObservation.isTimestampFresh(old.observedAtMs(), System.currentTimeMillis()));
        admission.request(next);
        assertThat(probes).containsExactly(first, next);
        assertThat(admission.recordPong(next, "SYNCING", old)).isFalse();
    }

}
