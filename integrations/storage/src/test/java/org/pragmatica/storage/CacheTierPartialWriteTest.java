// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Unit.unit;


/// #910 (review of #1095): a cache tier that fails MID-WRITE keeps a truncated copy, and the read
/// waterfall stops at the first tier that returns bytes — so once the put reported success, every
/// read failed its integrity check while the durable copy sat unreachable behind the partial one
/// (reproduced by the reviewer on a real `LocalDiskTier` under ENOSPC). The failed promotion must
/// discard the partial copy (B-1), a failed disk write must release its reservation (SF-2), and a
/// dead cache tier must be visible at WARN without flooding (SF-1).
class CacheTierPartialWriteTest {
    private static final String STORAGE_LOGGER = DefaultStorageInstance.class.getName();

    @TempDir
    Path tempDir;

    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("PartialWriteCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig = getOrCreateLoggerConfig(ctx.getConfiguration());
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.ALL, null);
        loggerConfig.setLevel(Level.ALL);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        var ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
    }

    /// B-1 with a tier double that stores half the block and then fails — the ENOSPC shape.
    @Test
    void cacheTierFailsMidWrite_putSucceeds_partialCopyIsDiscarded_readServesDurableBytes() {
        var content = block(4096);
        var partial = new PartialWriteTier();
        var durable = MemoryTier.memoryTier(1024 * 1024, TierLevel.REMOTE);
        var instance = StorageInstance.storageInstance("partial", List.of(partial, durable));

        var id = instance.put(content).await().fold(cause -> fail("the durable write succeeded; the put must succeed: " + cause.message()), v -> v);

        assertThat(partial.entries).as("the truncated copy must not survive the failed promotion").doesNotContainKey(id);
        assertThat(partial.deletes.get()).isEqualTo(1);

        var read = instance.get(id).await().fold(cause -> fail("the read must reach the durable copy: " + cause.message()), v -> v);

        assertThat(read.isPresent()).isTrue();
        assertThat(read.unwrap()).isEqualTo(content);
    }

    /// SF-2 on the real `LocalDiskTier`: a directory squatting on the block's path makes the write
    /// fail after the reservation; the reservation must be released and nothing left behind.
    @Test
    @SuppressWarnings("JBCT-EX-01")
    void localDiskTier_failedWrite_releasesTheReservation() throws Exception {
        var tier = LocalDiskTier.localDiskTier(tempDir.resolve("blocks"), 1024 * 1024).unwrap();
        var content = block(2048);
        var id = BlockId.blockId(content).unwrap();
        var hex = id.hexString();

        Files.createDirectories(tempDir.resolve("blocks").resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4)).resolve(hex));

        tier.put(id, content).await().onSuccess(_ -> fail("writing over a directory must fail"));

        assertThat(tier.usedBytes()).as("a failed write keeps no reservation").isZero();
    }

    /// SF-1: a tier that is not merely full but broken logs the first failure at WARN and the rest
    /// at DEBUG; `TierFull` never reaches WARN.
    @Test
    void brokenCacheTier_warnsOnce_thenDebug_andTierFullNeverWarns() {
        var broken = new BrokenTier();
        var durable = MemoryTier.memoryTier(1024 * 1024, TierLevel.REMOTE);
        var instance = StorageInstance.storageInstance("broken", List.of(broken, durable));

        for (int i = 0; i < 5; i++) {
            instance.put(block(64 + i)).await().onFailure(cause -> fail("puts must succeed: " + cause.message()));
        }

        assertThat(appender.warnsMentioning("Cache promotion to LOCAL_DISK FAILED")).as("first failure at WARN, then DEBUG").hasSize(1);
        assertThat(appender.debugsMentioning("Cache promotion to LOCAL_DISK failed")).hasSize(4);

        var full = MemoryTier.memoryTier(32);
        var fullInstance = StorageInstance.storageInstance("full", List.of(full, MemoryTier.memoryTier(1024 * 1024, TierLevel.REMOTE)));

        fullInstance.put(block(64)).await().onFailure(cause -> fail("a full cache tier must not fail the put: " + cause.message()));

        assertThat(appender.warnsMentioning("Cache promotion to MEMORY")).as("TierFull is steady state, never WARN").isEmpty();
    }

    private static byte[] block(int size) {
        var content = new byte[size];

        Arrays.fill(content, (byte) 9);

        return content;
    }

    /// Stores the first half of the block, then fails — a disk that filled up mid-write.
    private static final class PartialWriteTier implements StorageTier {
        private final Map<BlockId, byte[]> entries = new ConcurrentHashMap<>();
        private final AtomicInteger deletes = new AtomicInteger();

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return Promise.success(Option.option(entries.get(id)));
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            entries.put(id, Arrays.copyOf(content, content.length / 2));

            return StorageError.WriteError.writeError("No space left on device").promise();
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            deletes.incrementAndGet();
            entries.remove(id);

            return Promise.success(unit());
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return Promise.success(entries.containsKey(id));
        }

        @Override
        public TierLevel level() {
            return TierLevel.LOCAL_DISK;
        }

        @Override
        public long usedBytes() {
            return entries.values().stream().mapToLong(bytes -> bytes.length).sum();
        }

        @Override
        public long maxBytes() {
            return Long.MAX_VALUE;
        }
    }

    /// Refuses every write with a non-capacity failure, stores nothing.
    private static final class BrokenTier implements StorageTier {
        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return Promise.success(Option.none());
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return StorageError.WriteError.writeError("tier closed").promise();
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return Promise.success(unit());
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return Promise.success(false);
        }

        @Override
        public TierLevel level() {
            return TierLevel.LOCAL_DISK;
        }

        @Override
        public long usedBytes() {
            return 0;
        }

        @Override
        public long maxBytes() {
            return Long.MAX_VALUE;
        }
    }

    private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
        var existing = configuration.getLoggerConfig(STORAGE_LOGGER);

        if (STORAGE_LOGGER.equals(existing.getName())) {
            return existing;
        }

        var fresh = new LoggerConfig(STORAGE_LOGGER, Level.ALL, false);

        configuration.addLogger(STORAGE_LOGGER, fresh);

        return fresh;
    }

    record Captured(Level level, String message) {}

    private static final class CapturingAppender extends AbstractAppender {
        private final List<Captured> events = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            return new CapturingAppender(name, PatternLayout.createDefaultLayout());
        }

        @Override
        public void append(LogEvent event) {
            events.add(new Captured(event.getLevel(), event.getMessage().getFormattedMessage()));
        }

        List<Captured> warnsMentioning(String fragment) {
            return events.stream().filter(e -> e.level() == Level.WARN && e.message().contains(fragment)).toList();
        }

        List<Captured> debugsMentioning(String fragment) {
            return events.stream().filter(e -> e.level() == Level.DEBUG && e.message().contains(fragment)).toList();
        }
    }
}
