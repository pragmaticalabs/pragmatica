// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2201 I-20, the author pre-check of PR-2a: the limit of the purge, as its javadoc states it
/// ([StreamPartitionManager#purgePartitionState]). The purge drops what was recorded BEFORE it ran; an entry-less write (here
/// the owner's visible position, [StreamPartitionManager#commitAdvanced]) that lands for the name after the removal is
/// recorded under the name, and a stream created again under it sees that row. A CHARACTERISATION of today's behaviour, not a
/// guarantee: if this goes red because such a write is now refused, the javadoc's caveat can go.
class StreamPartitionStateLateWriteTest {
    private static final String STREAM = "orders";
    private static final Epoch EPOCH = Epoch.epoch(1L, 1L, 1L);

    private final AtomicReference<Role> role = new AtomicReference<>(Role.OWNER);
    private final StreamPartitionManager manager = streamPartitionManager(Long.MAX_VALUE);

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void commitAdvanced_afterTheStreamWasDestroyed_isSeenByTheStreamCreatedAgain() {
        manager.placementRoleSupplier((_, _) -> role.get());
        manager.createStream(config()).onFailure(cause -> fail(cause.message()));
        manager.destroyStream(STREAM).onFailure(cause -> fail(cause.message()));
        assertThat(manager.heldPartitionRows(STREAM, 0)).as("fixture: the destroy purged the name").isEmpty();

        role.set(Role.REPLICA);
        manager.commitAdvanced(STREAM, 0, 3L, EPOCH);
        role.set(Role.OWNER);
        manager.createStream(config()).onFailure(cause -> fail(cause.message()));

        assertThat(manager.heldPartitionRows(STREAM, 0)).as("the late, entry-less write is carried into the new life")
                                                        .contains("replicaCommitted");
    }

    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 3_600_000L), "earliest");
    }
}
