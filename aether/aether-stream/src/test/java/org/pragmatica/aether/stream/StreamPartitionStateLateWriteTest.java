// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2201 I-20, the incarnation stamp of know `f7bc44d170` (v2559), as [StreamPartitionManager#purgePartitionState] states it.
/// An entry-less write (here the owner's visible position, [StreamPartitionManager#commitAdvanced]) that lands for the name
/// after the removal finds no life on this node and is dropped, so a stream created again under the name does not see it. A
/// removal whose purge runs after the name was created again drops only the ended life's state, never the new life's.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StreamPartitionStateLateWriteTest {
    private static final String STREAM = "orders";
    private static final Epoch EPOCH = Epoch.epoch(1L, 1L, 1L);
    private static final long OLD_LIFE = 11L;
    private static final long NEW_LIFE = 22L;

    private final AtomicReference<Role> role = new AtomicReference<>(Role.OWNER);
    private final CountDownLatch deleting = new CountDownLatch(1);
    private final CountDownLatch recreated = new CountDownLatch(1);
    private final StreamPartitionManager manager = streamPartitionManager(Long.MAX_VALUE, new HeldDeletion());

    @AfterEach
    void tearDown() {
        recreated.countDown();
        manager.close();
    }

    @Test
    void commitAdvanced_afterTheStreamWasDestroyed_isDropped_notSeenByTheStreamCreatedAgain() {
        recreated.countDown();
        manager.placementRoleSupplier((_, _) -> role.get());
        manager.createStream(config(OLD_LIFE)).onFailure(cause -> fail(cause.message()));
        manager.destroyStream(STREAM).onFailure(cause -> fail(cause.message()));

        role.set(Role.REPLICA);
        manager.commitAdvanced(STREAM, 0, 3L, EPOCH);
        role.set(Role.OWNER);
        manager.createStream(config(OLD_LIFE)).onFailure(cause -> fail(cause.message()));

        assertThat(manager.heldPartitionRows(STREAM, 0)).as("the late, entry-less write found no life and was dropped")
                                                        .doesNotContain("replicaCommitted");
    }

    /// Know `f7bc44d170` item 4, the reverse race: the removal is held inside `closeAndRelease` (before its purge) while the
    /// name is created again and the new life records its state; the late purge must leave that state alone.
    @Test
    void purge_ofARemovalStillRunning_keepsTheStateOfTheStreamCreatedAgainMeanwhile() throws Exception {
        manager.placementRoleSupplier((_, _) -> role.get());
        manager.createStream(config(OLD_LIFE)).onFailure(cause -> fail(cause.message()));
        var removal = CompletableFuture.supplyAsync(() -> manager.destroyStream(STREAM));

        assertThat(deleting.await(30, TimeUnit.SECONDS)).as("fixture: the removal reached closeAndRelease").isTrue();
        manager.createStream(config(NEW_LIFE)).onFailure(cause -> fail(cause.message()));
        role.set(Role.REPLICA);
        manager.commitAdvanced(STREAM, 0, 3L, EPOCH);
        assertThat(manager.heldPartitionRows(STREAM, 0)).as("fixture: the new life recorded its state").contains("replicaCommitted");
        recreated.countDown();
        removal.get(30, TimeUnit.SECONDS).onFailure(cause -> fail(cause.message()));

        assertThat(manager.heldPartitionRows(STREAM, 0)).as("the ended life's purge kept the new life's state").contains("replicaCommitted");
    }

    private static StreamConfig config(long incarnation) {
        return StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 3_600_000L), "earliest")
                           .withIncarnation(incarnation);
    }

    /// Holds a removal at [EvictionListener#onStreamDeleted], which `closeAndRelease` calls before the purge, until the test has
    /// created the stream again.
    private final class HeldDeletion implements EvictionListener {
        @Override
        public Result<Unit> onEviction(String streamName, int partition, List<OffHeapRingBuffer.RawEvent> events) {
            return Result.unitResult();
        }

        @Override
        public Unit onStreamDeleted(String streamName) {
            deleting.countDown();
            awaitRecreated();

            return Unit.unit();
        }

        private void awaitRecreated() {
            try {
                recreated.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
