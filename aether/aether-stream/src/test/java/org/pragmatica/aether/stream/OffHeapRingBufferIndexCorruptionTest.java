// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;


import org.junit.jupiter.api.Test;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;

/// #1247 — the native-access boundaries caught `IndexOutOfBoundsException` together with the closed-arena
/// `IllegalStateException` and reported both as `BUFFER_CLOSED` (Result paths) or as a closed-under-reader
/// race (the `long`/sweep paths). An out-of-bounds access is a ring index or offset-arithmetic defect, so it
/// must surface as its own logged cause and never be mistaken for a release.
///
/// The index is corrupted for real: slot 0's data position is overwritten with a negative value, so the
/// read path's segment copy goes out of bounds exactly as an arithmetic bug would.
class OffHeapRingBufferIndexCorruptionTest {
    private static final long CAPACITY = 64;
    private static final long DATA_REGION = 4096;
    private static final long HEADER_SIZE = 64;
    private static final long CORRUPT_DATA_POS = -1_000L;

    @Test
    void read_corruptedIndexEntry_failsWithRingIndexCorrupted_notBufferClosed() {
        var buffer = corruptedRing(EvictionListener.NOOP);

        buffer.read(0, 1)
              .onSuccess(value -> fail("expected RingIndexCorrupted, got success: " + value))
              .onFailure(OffHeapRingBufferIndexCorruptionTest::assertRingIndexCorrupted);

        assertThat(buffer.indexCorruptionCount()).isEqualTo(1);
        assertThat(buffer.closedUnderReaderCount()).isZero();
    }

    @Test
    void readSlice_corruptedIndexEntry_failsWithRingIndexCorrupted_notBufferClosed() {
        var buffer = corruptedRing(EvictionListener.NOOP);

        buffer.readSlice(0)
              .onSuccess(value -> fail("expected RingIndexCorrupted, got success: " + value))
              .onFailure(OffHeapRingBufferIndexCorruptionTest::assertRingIndexCorrupted);

        assertThat(buffer.indexCorruptionCount()).isEqualTo(1);
    }

    @Test
    void evictByAge_corruptedIndexEntry_countsCorruption_notClosedUnderReaderRace() {
        var evicted = new CopyOnWriteArrayList<List<OffHeapRingBuffer.RawEvent>>();
        var buffer = corruptedRing((_, _, events) -> accepted(evicted.add(events)));

        buffer.evictByAge(0);

        assertThat(evicted).isEmpty();
        assertThat(buffer.indexCorruptionCount()).isEqualTo(1);
        assertThat(buffer.closedUnderReaderCount()).isZero();
    }

    /// #1247 review N1: the ERROR line is part of the contract ("logged, not silent"). It names the stream and
    /// partition and carries the out-of-bounds exception, at ERROR — never WARN, never absent.
    @Test
    void read_corruptedIndexEntry_logsOneErrorNamingStreamAndPartition() {
        var appender = new CapturingAppender();
        var context = (LoggerContext) LogManager.getContext(false);
        var loggerConfig = context.getConfiguration().getLoggerConfig(OffHeapRingBuffer.class.getName());

        appender.start();
        loggerConfig.addAppender(appender, Level.TRACE, null);
        context.updateLoggers();

        try {
            corruptedRing(EvictionListener.NOOP).read(0, 1);
        } finally {
            loggerConfig.removeAppender(appender.getName());
            appender.stop();
            context.updateLoggers();
        }

        assertThat(appender.events()).hasSize(1);

        var event = appender.events().getFirst();

        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getMessage().getFormattedMessage()).contains("corrupt[3]")
                                                          .contains("NOT a concurrent close");
        assertThat(event.getThrown()).isInstanceOf(IndexOutOfBoundsException.class);
    }

    private static void assertRingIndexCorrupted(Cause cause) {
        assertThat(cause).isNotEqualTo(StreamError.General.BUFFER_CLOSED);
        assertThat(cause).isInstanceOf(StreamError.RingIndexCorrupted.class);
        assertThat(cause.message()).startsWith("Ring index corrupted at corrupt[3]: ");
    }

    /// A listener that records synchronously and takes the events (#1234): the ring reclaims them in the same pass.
    private static Result<Unit> accepted(boolean recorded) {
        return Result.unitResult();
    }

    private static OffHeapRingBuffer corruptedRing(EvictionListener listener) {
        var buffer = offHeapRingBuffer("corrupt", 3, CAPACITY, DATA_REGION, listener);

        buffer.append("payload".getBytes(), 1L);
        controlSegment(buffer).set(ValueLayout.JAVA_LONG, HEADER_SIZE, CORRUPT_DATA_POS);

        return buffer;
    }

    /// Captures every event of the ring's logger; immutable snapshots, so no event object is reused.
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        private CapturingAppender() {
            super("ring-index-corruption-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<LogEvent> events() {
            return List.copyOf(events);
        }
    }

    private static MemorySegment controlSegment(OffHeapRingBuffer buffer) {
        try {
            var field = OffHeapRingBuffer.class.getDeclaredField("controlSegment");

            field.setAccessible(true);

            return (MemorySegment) field.get(buffer);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("controlSegment field not reachable", e);
        }
    }
}
