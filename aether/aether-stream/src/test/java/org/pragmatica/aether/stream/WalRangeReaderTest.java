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

import java.nio.file.Path;
import java.util.stream.LongStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.WalRangeReader.readExactRange;

/// #1234: a heap-spilled pending seal is rebuilt from EXACTLY its WAL range. A range the WAL cannot supply in
/// full is a loud [SegmentError.WalRangeMissing] — never a shorter or gapped list that would seal a hole as if
/// it were whole.
class WalRangeReaderTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;

    @TempDir
    Path walDir;

    private PartitionWal wal;

    @BeforeEach
    void setUp() {
        wal = PartitionWal.open(walDir.resolve("0.wal")).onFailure(cause -> fail(cause.message())).unwrap();
        // Offsets 0-4 and 6-9: offset 5 is missing from the WAL.
        LongStream.rangeClosed(0, 9).filter(offset -> offset != 5).forEach(this::append);
    }

    @AfterEach
    void tearDown() {
        wal.close();
    }

    @Test
    void read_exactRange_returnsEveryRecordInOrder_byteIdentical() {
        var events = readExactRange(wal, STREAM, PARTITION, 1, 4).onFailure(cause -> fail(cause.message())).unwrap();

        assertThat(events).extracting(RawEvent::offset).containsExactly(1L, 2L, 3L, 4L);
        assertThat(events).allSatisfy(event -> assertThat(new String(event.data(), UTF_8)).isEqualTo("evt-" + event.offset()));
        assertThat(events).allSatisfy(event -> assertThat(event.timestamp()).isEqualTo(1000L + event.offset()));
    }

    @Test
    void read_rangeWithAGap_failsLoudly_insteadOfReturningAShortRange() {
        readExactRange(wal, STREAM, PARTITION, 3, 7)
              .onSuccess(events -> fail("expected WalRangeMissing, got " + events.size() + " events"))
              .onFailure(cause -> assertThat(cause).isEqualTo(new SegmentError.WalRangeMissing(STREAM, PARTITION, 3, 7, 4)))
              .onFailure(cause -> assertThat(cause.isTerminal()).isTrue());
    }

    @Test
    void read_rangePastTheWalEnd_failsLoudly() {
        readExactRange(wal, STREAM, PARTITION, 8, 12)
              .onSuccess(events -> fail("expected WalRangeMissing, got " + events.size() + " events"))
              .onFailure(cause -> assertThat(cause).isEqualTo(new SegmentError.WalRangeMissing(STREAM, PARTITION, 8, 12, 2)));
    }

    @Test
    void durableOffset_coversEveryAwaitedAppend() {
        assertThat(wal.durableOffset()).isEqualTo(9L);
    }

    private void append(long offset) {
        wal.append(offset, ("evt-" + offset).getBytes(UTF_8), 1000L + offset).await().onFailure(cause -> fail(cause.message()));
    }
}
