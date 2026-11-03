// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.AppendLog;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;

/// NT-26 (#2201 A-01): the in-section guard refuses before an offset exists, and `PartitionSection.admit` checks the fence, the
/// provenance and the log's health in that order. The end-to-end race (a stale-epoch publish waiting on the section while a newer epoch
/// writes) is `OrderedSectionAbortTripwireTest.EpochRace`.
@Timeout(30)
class PartitionSectionTest {
    @TempDir
    Path dir;

    @Test
    void appendOrdered_refusingGuard_assignsNoOffset_andRunsNoContinuation() {
        try (var ring = offHeapRingBuffer(8, 4096)) {
            ring.append(bytes("a"), 1L).onFailure(cause -> fail(cause.message()));
            var before = ring.headOffset();
            var continuations = new AtomicInteger();
            var refusal = Causes.cause("stale epoch");

            var refused = ring.<Long> appendOrdered(bytes("stale"), 2L, refusal::result, offset -> countAndAdmit(continuations, offset));

            refused.onSuccess(offset -> fail("a refused append took offset " + offset))
                   .onFailure(cause -> assertThat(cause).isSameAs(refusal));
            assertThat(continuations.get()).as("no inOrder for a refused append").isZero();
            assertThat(ring.headOffset()).isEqualTo(before);
            assertThat(ring.<Long> appendOrdered(bytes("next"), 3L, Result::unitResult, Result::success).unwrap()).isEqualTo(before + 1);
        }
    }

    @Test
    void appendBatchOrdered_refusingGuard_assignsNoOffset_andRunsNoContinuation() {
        try (var ring = offHeapRingBuffer(8, 4096)) {
            var continuations = new AtomicInteger();
            var refusal = Causes.cause("provenance regression");

            var refused = ring.<Long> appendBatchOrdered(List.of(bytes("x"), bytes("y")),
                                                         new long[]{1L, 2L},
                                                         refusal::result,
                                                         offset -> countAndAdmit(continuations, offset));

            refused.onSuccess(offset -> fail("a refused run took offsets through " + offset))
                   .onFailure(cause -> assertThat(cause).isSameAs(refusal));
            assertThat(continuations.get()).isZero();
            assertThat(ring.headOffset()).isEqualTo(-1L);
        }
    }

    @Test
    void admit_checksTheFenceFirst_thenTheProvenance_thenTheLog() {
        var fence = Causes.cause("fence");
        var provenance = Causes.cause("provenance");
        var calls = new AtomicInteger();

        assertThat(PartitionSection.partitionSection("orders", 0, fence::result, () -> counted(calls, provenance), Option::none).admit().isFailure()).isTrue();
        assertThat(calls.get()).as("a refused fence stops the guard").isZero();
        PartitionSection.partitionSection("orders", 0, Result::unitResult, provenance::result, Option::none)
                        .admit()
                        .onSuccess(_ -> fail("a provenance refusal was admitted"))
                        .onFailure(cause -> assertThat(cause).isSameAs(provenance));
        assertThat(PartitionSection.partitionSection("orders", 0, Result::unitResult, Result::unitResult, Option::none).admit().isSuccess())
            .as("a partition without a log has no health to check").isTrue();
    }

    @Test
    void admit_refusesAClosedLog_asPartitionFailStopped_andAdmitsAnOpenOne() {
        var wal = AppendLog.open(dir.resolve("p.wal")).unwrap();
        var section = PartitionSection.partitionSection("orders", 0, Result::unitResult, Result::unitResult, () -> Option.some(wal));

        assertThat(section.admit().isSuccess()).as("an open, healthy log is admitted").isTrue();
        wal.close();
        section.admit()
               .onSuccess(_ -> fail("a closed log was admitted"))
               .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.PartitionFailStopped("orders",
                                                                                                    0,
                                                                                                    AppendLog.WalError.General.WAL_CLOSED)));
    }

    private static Result<Long> countAndAdmit(AtomicInteger continuations, long offset) {
        continuations.incrementAndGet();
        return Result.success(offset);
    }

    private static Result<Unit> counted(AtomicInteger calls, Cause cause) {
        calls.incrementAndGet();
        return cause.result();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(UTF_8);
    }
}
