// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.worker.metadata;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class WorkerMetadataFailureReporterTest {
    @Test
    void failureStormHasOneNodeWideBudgetAndRetainsSuppressionCount() {
        var clock = new AtomicLong();
        var events = new ArrayList<WorkerMetadataFailureReporter.Rejection>();
        var interval = TimeSpan.timeSpan(30).seconds();
        var reporter = WorkerMetadataFailureReporter.workerMetadataFailureReporter(clock::get, interval, events::add);

        for (int index = 0; index < 1000; index++) {
            reporter.report(new NodeId("worker-" + index), "projection-unavailable-or-oversize");
        }

        assertThat(events).hasSize(1);
        clock.set(interval.nanos());
        reporter.report(new NodeId("worker-later"), "manifest-capacity");
        assertThat(events).hasSize(2);
        assertThat(events.getLast().suppressed()).isEqualTo(999);
        assertThat(events.getLast().reason()).isEqualTo("manifest-capacity");
    }
}
