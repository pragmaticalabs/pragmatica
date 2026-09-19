// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.segment.SegmentError;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.lang.Option;

import java.nio.file.Path;
import java.util.stream.LongStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.WalRangeReader.walRangeReader;

/// #1234: a heap-spilled pending seal is rebuilt from EXACTLY its WAL range. A range the WAL cannot supply in
/// full is a loud [SegmentError.WalRangeMissing] — never a shorter or gapped list that would seal a hole as if
/// it were whole.
class WalRangeReaderTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;

    @TempDir
    Path walDir;

    private PartitionWal wal;
    private WalRangeReader reader;

    @BeforeEach
    void setUp() {
        wal = PartitionWal.open(walDir.resolve("0.wal")).onFailure(cause -> fail(cause.message())).unwrap();
        reader = walRangeReader((_, _) -> Option.some(wal));
        // Offsets 0-4 and 6-9: offset 5 is missing from the WAL.
        LongStream.rangeClosed(0, 9).filter(offset -> offset != 5).forEach(this::append);
    }

    @AfterEach
    void tearDown() {
        wal.close();
    }

    @Test
    void read_exactRange_returnsEveryRecordInOrder_byteIdentical() {
        var events = reader.read(STREAM, PARTITION, 1, 4).onFailure(cause -> fail(cause.message())).unwrap();

        assertThat(events).extracting(RawEvent::offset).containsExactly(1L, 2L, 3L, 4L);
        assertThat(events).allSatisfy(event -> assertThat(new String(event.data(), UTF_8)).isEqualTo("evt-" + event.offset()));
        assertThat(events).allSatisfy(event -> assertThat(event.timestamp()).isEqualTo(1000L + event.offset()));
    }

    @Test
    void read_rangeWithAGap_failsLoudly_insteadOfReturningAShortRange() {
        reader.read(STREAM, PARTITION, 3, 7)
              .onSuccess(events -> fail("expected WalRangeMissing, got " + events.size() + " events"))
              .onFailure(cause -> assertThat(cause).isEqualTo(new SegmentError.WalRangeMissing(STREAM, PARTITION, 3, 7, 4)))
              .onFailure(cause -> assertThat(cause.isTerminal()).isTrue());
    }

    @Test
    void read_rangePastTheWalEnd_failsLoudly() {
        reader.read(STREAM, PARTITION, 8, 12)
              .onSuccess(events -> fail("expected WalRangeMissing, got " + events.size() + " events"))
              .onFailure(cause -> assertThat(cause).isEqualTo(new SegmentError.WalRangeMissing(STREAM, PARTITION, 8, 12, 2)));
    }

    @Test
    void read_partitionWithoutWal_failsLoudly_andIsNotDurable() {
        var noWal = walRangeReader((_, _) -> Option.none());

        assertThat(noWal.durable(STREAM, PARTITION)).isFalse();
        assertThat(reader.durable(STREAM, PARTITION)).isTrue();
        noWal.read(STREAM, PARTITION, 0, 1)
             .onSuccess(_ -> fail("expected WalRangeMissing"))
             .onFailure(cause -> assertThat(cause).isInstanceOf(SegmentError.WalRangeMissing.class));
    }

    private void append(long offset) {
        wal.append(offset, ("evt-" + offset).getBytes(UTF_8), 1000L + offset).await().onFailure(cause -> fail(cause.message()));
    }
}
