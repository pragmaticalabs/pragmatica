// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/// Streams overhaul C-04 (#263, overhaul 7.2): an owner batches the `ReplicateEvents` it sends for single publishes.
///
/// Each node counts, through its inbound fault filter (a pure observer: it always lets the message through), the
/// `ReplicateEvents` messages it receives. 192 single publishes are made through the owner's real `StreamWriteRouter`
/// from 16 concurrent callers, so the batcher has something to coalesce. A positive control makes one owner-local
/// `publishBatch` of 100 events, sent as one run: its replicas receive a handful of messages, which shows the counter
/// sees batching.
///
/// **No data directory, on purpose** (the WAL variant is measured, not gated, in [EmberReplicationBatchingWalMeasurementTest]).
/// In a closed loop with `n` callers a partition sees about `n x window / L` events per 1 ms window, `L` the publish
/// latency; with a WAL `L` is about the owner fsync, so the ratio depends on the disk (overhaul 7.2, L-04). Without one,
/// appends are microseconds apart and the wiring is what is measured.
/// - Acceptance: at most 0.5 messages per event per replica (96 for 192). Before #263 the production manager sent one
///   message per event per replica (the deleted tripwire asserted at least 0.9).
@PortBudget
class EmberReplicationBatchingTest extends ReplicationBatchingFixture {
    @Test
    @Timeout(600)
    void singlePublishes_areBatchedIntoFewerMessages() {
        boot();

        var burst = publishSinglesConcurrently(SINGLE_PUBLISHES);
        var control = publishOneBatch(BATCH);

        System.out.printf("C-04 measured (no WAL): %s ReplicateEvents per replica for %d concurrent single publishes%n",
                          burst.messagesPerReplica(),
                          SINGLE_PUBLISHES);
        assertThat(burst.eventsPerReplica()).as("control: every replica received all %d events", SINGLE_PUBLISHES).allSatisfy(events -> assertThat(events).isEqualTo((long) SINGLE_PUBLISHES));
        assertThat(control.eventsPerReplica()).as("control: every replica received the %d batched events", BATCH).allSatisfy(events -> assertThat(events).isEqualTo((long) BATCH));
        assertThat(control.messagesPerReplica()).as("control: an owner-local publishBatch is one run, so the counter can see batching")
                                                .allSatisfy(messages -> assertThat(messages).isLessThanOrEqualTo(3L));
        assertThat(burst.messagesPerReplica()).as("ReplicateEvents messages per replica for %d concurrent single publishes", SINGLE_PUBLISHES)
                                              .allSatisfy(messages -> assertThat(messages).isLessThanOrEqualTo((long) (0.5 * SINGLE_PUBLISHES)));
    }

    @Override
    EmberCluster threeNodes(int basePort) {
        return EmberCluster.emberCluster(StreamTripwireSupport.CLUSTER_SIZE,
                                         basePort,
                                         basePort + StreamTripwireSupport.MGMT_OFFSET,
                                         basePort + StreamTripwireSupport.APP_HTTP_OFFSET,
                                         "c04");
    }
}
