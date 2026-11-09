// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/// C-04 with a WAL (#263, overhaul 7.2): a MEASUREMENT, not a gate. With a WAL each publish waits for the owner's group
/// commit, so 16 closed-loop callers put about `16 x 1 ms / F` events in a 1 ms window, `F` the fsync latency: about one
/// on a 15 ms disk (no coalescing), about 80 on a 0.2 ms one. The message ratio therefore measures the disk, and the
/// window is deliberately not lengthened to chase it (it would add its length to every publish). This test reports the
/// count and asserts only that every event arrived.
@PortBudget
class EmberReplicationBatchingWalMeasurementTest extends ReplicationBatchingFixture {
    private Path dataDir;

    /// The data directory goes only after the cluster has stopped.
    @AfterAll
    @Override
    void tearDown() {
        super.tearDown();
        StreamTripwireSupport.deleteTree(dataDir);
    }

    @Test
    @Timeout(600)
    void singlePublishesWithAWal_messageCountIsReported() {
        boot();

        var burst = publishSinglesConcurrently(SINGLE_PUBLISHES);

        System.out.printf("C-04 measured (WAL): %s ReplicateEvents per replica for %d concurrent single publishes%n",
                          burst.messagesPerReplica(),
                          SINGLE_PUBLISHES);
        assertThat(burst.eventsPerReplica()).as("every replica received all %d events", SINGLE_PUBLISHES)
                                            .allSatisfy(events -> assertThat(events).isEqualTo((long) SINGLE_PUBLISHES));
    }

    @Override
    EmberCluster threeNodes(int basePort) {
        dataDir = StreamTripwireSupport.newDataDir("c04-wal");

        return StreamTripwireSupport.threeNodes(basePort, "c04w", dataDir);
    }
}
