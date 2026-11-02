// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.provenance.PartitionFlags;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1596 / #2201 A-05, the author pre-check of PR-2a: `raisedLocally` (now keyed `partition#kind#evidence` inside the stream's
/// [PartitionState]) raises a condition once, and a raise that FAILED is forgotten under the same key, so the next occurrence
/// raises again. The re-keying could make the forget miss its entry (a different key than the raise added) and silently turn
/// "retried" into "never again"; no other test fails a raise.
class StreamPartitionRaiseRetryTest {
    private static final String STREAM = "orders";
    private static final Epoch EPOCH = Epoch.epoch(1L, 1L, 1L);

    @TempDir
    Path walDir;

    private final AtomicReference<Role> role = new AtomicReference<>(Role.OWNER);
    private final AtomicInteger attempts = new AtomicInteger();
    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        manager.placementRoleSupplier((_, _) -> role.get());
        manager.ownerEpochSource((_, _) -> EPOCH);
        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 3_600_000L), "earliest"))
               .onFailure(cause -> fail(cause.message()));
        role.set(Role.REPLICA);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void aFailedRaise_isForgotten_andTheNextOccurrenceRaisesAgain() {
        manager.partitionFlags(flags(true));
        unattributed(0);

        live(1);
        awaitAttempts(1);
        live(2);
        awaitAttempts(2);

        assertThat(attempts.get()).as("the failed raise was retried at the next occurrence").isEqualTo(2);
    }

    /// Control: a raise that succeeded is not repeated (the once-per-process half), so the retry above is the forget's doing.
    @Test
    void aSucceededRaise_isNotRepeated() {
        manager.partitionFlags(flags(false));
        unattributed(0);

        live(1);
        awaitAttempts(1);
        live(2);
        awaitAttempts(2);

        assertThat(attempts.get()).as("one raise for the condition").isEqualTo(1);
    }

    private PartitionFlags flags(boolean failFirst) {
        return new PartitionFlags() {
            @Override
            public Promise<PartitionFlag> raise(String stream, int partition, AetherValue.PartitionRecoveryReason reason) {
                var attempt = attempts.incrementAndGet();

                return failFirst && attempt == 1
                       ? Causes.cause("no leader").<PartitionFlag> promise()
                       : Promise.success(new PartitionFlag(AetherValue.StreamPartitionRecoveryValue.raised(Option.none(), reason), "digest"));
            }

            @Override
            public Option<PartitionFlag> status(String stream, int partition) {
                return Option.none();
            }

            @Override
            public AetherValue.PartitionRecoveryReason local(AetherValue.PartitionRecoveryReasonKind kind, String evidence) {
                return AetherValue.PartitionRecoveryReason.partitionRecoveryReason(kind, Option.some("self"), evidence);
            }
        };
    }

    /// A record with no owner-epoch provenance: the log then holds records and no history.
    private void unattributed(long offset) {
        manager.appendRecovered(STREAM, 0, offset, "e".getBytes(UTF_8), 1L).onFailure(cause -> fail(cause.message()));
    }

    /// An attributed append the log cannot attribute: the `HISTORY_MISSING` condition.
    private void live(long offset) {
        manager.appendRecovered(STREAM, 0, offset, "e".getBytes(UTF_8), 1L, EPOCH).onFailure(cause -> fail(cause.message()));
    }

    private void awaitAttempts(int expected) {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);

        while (attempts.get() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }
}
