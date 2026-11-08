// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.replication.ReplicationTransport;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #263 condition 1 on the owner's publish path: the production replication manager's send budget is reserved by every
/// owner append before it takes an offset. With the replica path stuck (the transport blocks the flusher), publishes
/// are accepted until the held bytes reach the budget, then refused with `REPLICATION_BEHIND`, typed and transient,
/// taking no offset, for single and batch publishes alike. Once the sends return, publishing resumes at the next offset.
class ReplicationSendBudgetPublishTest {
    private static final String STREAM = "budgeted";
    private static final int P = 0;
    private static final int EVENT = 64;
    private static final long BUDGET = 3L * EVENT;
    private static final NodeId SELF = new NodeId("owner");
    private static final NodeId PEER = new NodeId("peer");

    private final CountDownLatch replicaPath = new CountDownLatch(1);
    private StreamPartitionManager manager;
    private ReplicationManager replication;

    @AfterEach
    void tearDown() {
        replicaPath.countDown();
        if (manager != null) {
            manager.close();
        }
        if (replication != null) {
            replication.close();
        }
    }

    @Test
    void publish_isRefusedBeforeItTakesAnOffset_whileTheSendBudgetIsSpent_andResumesOnceSent() {
        start();

        for (var offset = 0L; offset < 3; offset++) {
            assertThat(accepted(publish())).isEqualTo(offset);
        }

        assertRefusedBehind(publish());
        assertRefusedBehind(manager.publishLocalBatchAtFloor(STREAM, P, List.of(new byte[EVENT], new byte[EVENT]), 1L, 0));
        assertThat(manager.nextExpectedOffset(STREAM, P)).as("the refused publishes took no offset").isEqualTo(3L);

        replicaPath.countDown();

        assertThat(publishOnceSent()).as("the next offset, once the replica path returned").isEqualTo(3L);
        assertThat(accepted(manager.publishLocalBatchAtFloor(STREAM, P, List.of(new byte[EVENT]), 1L, 0))).isEqualTo(4L);
    }

    private void start() {
        var registry = ReplicaRegistry.replicaRegistry();

        registry.registerReplica(STREAM, P, SELF);
        registry.registerReplica(STREAM, P, PEER);
        replication = ReplicationManager.batchingReplicationManager(SELF,
                                                                    registry,
                                                                    this::stuckUntilOpened,
                                                                    (_, _) -> -1L,
                                                                    100,
                                                                    TimeSpan.timeSpan(1).millis(),
                                                                    BUDGET);
        manager = streamPartitionManager(Long.MAX_VALUE, (_, _, _) -> Result.unitResult(), replication);
        manager.createStream(StreamConfig.streamConfig(STREAM,
                                                       1,
                                                       RetentionPolicy.retentionPolicy(1_000, 1024 * 1024, 60_000),
                                                       "earliest",
                                                       1_048_576L,
                                                       ConsistencyMode.EVENTUAL,
                                                       2,
                                                       1,
                                                       StreamCompression.NONE,
                                                       Option.none()))
               .onFailure(cause -> fail("createStream: " + cause.message()));
    }

    private Promise<WriteOutcome> stuckUntilOpened(NodeId target, ReplicationMessage message) {
        try {
            replicaPath.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        return Promise.success(new WriteOutcome.Sent(target));
    }

    private static long accepted(Result<Long> outcome) {
        outcome.onFailure(cause -> fail(cause.message()));

        return outcome.unwrap();
    }

    private static void assertRefusedBehind(Result<?> outcome) {
        assertThat(outcome.isFailure()).as("the send budget is spent: %s", outcome).isTrue();
        outcome.onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.REPLICATION_BEHIND));
    }

    private Result<Long> publish() {
        return manager.publishLocal(STREAM, P, new byte[EVENT], 1L);
    }

    /// Polls the publish until the released sends free the budget (bounded); a refusal other than the budget's fails.
    private long publishOnceSent() {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline) {
            var outcome = publish();

            if (outcome.isSuccess()) {
                return accepted(outcome);
            }

            outcome.onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.REPLICATION_BEHIND));
            Thread.onSpinWait();
        }

        return fail("the budget was not freed after the replica path returned");
    }
}
