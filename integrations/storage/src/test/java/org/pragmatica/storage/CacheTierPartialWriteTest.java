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
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.lang.io.FileError;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


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
        var id = instance.put(content)
                         .await()
                         .fold(cause -> fail("the durable write succeeded; the put must succeed: " + cause.message()),
                               v -> v);

        assertThat(partial.entries).as("the truncated copy must not survive the failed promotion").doesNotContainKey(id);
        assertThat(partial.deletes.get()).isEqualTo(1);
        var read = instance.get(id)
                           .await()
                           .fold(cause -> fail("the read must reach the durable copy: " + cause.message()),
                                 v -> v);

        assertThat(read.isPresent()).isTrue();
        assertThat(read.unwrap()).isEqualTo(content);
    }

    /// r3 (a): a tier that refuses BEFORE writing (`TierFull`) has nothing to discard, and the id
    /// may already hold a valid copy there — the instance-level delete must not evict it.
    @Test
    void fullCacheTier_rePromotion_keepsTheValidCopy() {
        var content = block(4096);
        var id = BlockId.blockId(content).unwrap();
        var cache = MemoryTier.memoryTier(content.length);
        var durable = MemoryTier.memoryTier(1024 * 1024, TierLevel.REMOTE);

        cache.put(id, content).await().onFailure(cause -> fail("the copy fits exactly: " + cause.message()));
        var instance = StorageInstance.storageInstance("full-cache", List.of(cache, durable));

        instance.put(content).await().onFailure(cause -> fail("the durable write succeeded: " + cause.message()));
        assertThat(cache.exists(id).await().unwrap()).as("TierFull wrote nothing; the valid copy must survive")
                  .isTrue();
        assertThat(cache.get(id).await().unwrap().unwrap()).isEqualTo(content);
    }

    /// r3 (b), the mid-write shape on the real `LocalDiskTier`: the disk takes N bytes of the block
    /// and fails. The partial file is discarded, the reservation released exactly once, and the
    /// instance-level delete that follows finds nothing to subtract — `usedBytes` ends at zero,
    /// never below it.
    @Test
    void localDiskTier_midWriteFailure_discardsThePartial_usedBytesEndsAtZero() {
        var disk = new FillingDisk();
        var dir = tempDir.resolve("filling");
        var tier = diskTier(dir, disk);
        var durable = MemoryTier.memoryTier(1024 * 1024, TierLevel.REMOTE);
        var instance = StorageInstance.storageInstance("filling", List.of(tier, durable));
        var content = block(4096);

        disk.bytesBeforeFailure.set(1024);
        var id = instance.put(content)
                         .await()
                         .fold(cause -> fail("the durable write succeeded; the put must succeed: " + cause.message()),
                               v -> v);

        assertThat(disk.partialBytesSeen.get()).as("the fixture left N bytes on disk before failing").isEqualTo(1024);
        assertThat(partialFiles(dir)).as("the partial file is discarded").isEmpty();
        assertThat(tier.exists(id).await().unwrap()).isFalse();
        assertThat(tier.usedBytes()).as("reservation released once, nothing else subtracted").isZero();
        assertThat(instance.get(id).await().unwrap().unwrap()).as("the read reaches the durable copy").isEqualTo(content);
    }

    /// r3 (c): the previous copy at the block path survives a failed overwrite whatever stage
    /// failed — after N bytes (mid-write) or before any (open-time) — because the write never
    /// touches the block path until it is complete; only the partial file is discarded.
    @Test
    void localDiskTier_failedOverwrite_keepsThePreviousCopy() {
        var disk = new FillingDisk();
        var dir = tempDir.resolve("overwrite");
        var tier = diskTier(dir, disk);
        var content = block(4096);
        var id = BlockId.blockId(content).unwrap();

        tier.put(id, content).await().onFailure(cause -> fail("the first write is healthy: " + cause.message()));
        assertThat(tier.usedBytes()).isEqualTo(4096);

        for (int bytesBeforeFailure : new int[]{1024, 0}) {
            disk.bytesBeforeFailure.set(bytesBeforeFailure);
            tier.put(id, content).await().onSuccess(_ -> fail("the overwrite must fail"));
            assertThat(disk.partialBytesSeen.get()).isEqualTo(bytesBeforeFailure);
            assertThat(tier.get(id).await().unwrap().unwrap()).as("previous copy intact after failing at " + bytesBeforeFailure)
                      .isEqualTo(content);
            assertThat(partialFiles(dir)).isEmpty();
            assertThat(tier.usedBytes()).as("the previous copy stays counted, the reservation is released").isEqualTo(4096);
        }
    }

    /// SF-2 on the real `LocalDiskTier` with the real writer: a non-empty directory squatting on
    /// the block path makes the rename fail after the reservation; the reservation must be released
    /// and nothing left behind.
    @Test
    @SuppressWarnings("JBCT-EX-01")
    void localDiskTier_failedWrite_releasesTheReservation() throws Exception {
        var dir = tempDir.resolve("blocks");
        var tier = LocalDiskTier.localDiskTier(dir, 1024 * 1024).unwrap();
        var content = block(2048);
        var id = BlockId.blockId(content).unwrap();
        var squat = blockPath(dir, id);

        Files.createDirectories(squat);
        Files.writeString(squat.resolve("occupant"), "not a block");
        tier.put(id, content).await().onSuccess(_ -> fail("writing over a non-empty directory must fail"));
        assertThat(tier.usedBytes()).as("a failed write keeps no reservation").isZero();
        assertThat(partialFiles(dir)).isEmpty();
    }

    /// r3 (b): `delete` subtracts only what it removes, and it removes only blocks — a directory
    /// at the block path is neither, so the delete the instance issues after a failed promotion
    /// cannot drive `usedBytes` negative.
    @Test
    @SuppressWarnings("JBCT-EX-01")
    void localDiskTier_deleteOfADirectorySquattingTheBlockPath_subtractsNothing() throws Exception {
        var dir = tempDir.resolve("squat");
        var tier = LocalDiskTier.localDiskTier(dir, 1024 * 1024).unwrap();
        var id = BlockId.blockId(block(2048)).unwrap();

        Files.createDirectories(blockPath(dir, id));
        tier.delete(id).await().onFailure(cause -> fail("nothing to delete is not a failure: " + cause.message()));
        assertThat(tier.usedBytes()).isZero();
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

        assertThat(appender.warnsMentioning("Cache promotion to LOCAL_DISK FAILED")).as("first failure at WARN, then DEBUG")
                  .hasSize(1);
        assertThat(appender.debugsMentioning("Cache promotion to LOCAL_DISK failed")).hasSize(4);
        var full = MemoryTier.memoryTier(32);
        var fullInstance = StorageInstance.storageInstance("full",
                                                           List.of(full,
                                                                   MemoryTier.memoryTier(1024 * 1024, TierLevel.REMOTE)));

        fullInstance.put(block(64))
                    .await()
                    .onFailure(cause -> fail("a full cache tier must not fail the put: " + cause.message()));
        assertThat(appender.warnsMentioning("Cache promotion to MEMORY")).as("TierFull is steady state, never WARN")
                  .isEmpty();
    }

    private static byte[] block(int size) {
        var content = new byte[size];

        Arrays.fill(content, (byte) 9);

        return content;
    }

    private static LocalDiskTier diskTier(Path dir, FillingDisk disk) {
        return LocalDiskTier.localDiskTier(dir, 1024 * 1024, timeSpan(30).seconds(), none(), some(disk::write)).unwrap();
    }

    private static Path blockPath(Path dir, BlockId id) {
        var hex = id.hexString();

        return dir.resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4)).resolve(hex);
    }

    private static List<Path> partialFiles(Path dir) {
        return FileOps.walk(dir, path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".partial"))
                      .unwrap();
    }

    /// The tier's write seam: healthy until `bytesBeforeFailure` is set, then writes that many
    /// bytes of the block to the partial path and fails — a disk that fills mid-block. Records
    /// what it left on disk so the discard can be proven rather than assumed.
    private static final class FillingDisk {
        private final AtomicInteger bytesBeforeFailure = new AtomicInteger(-1);
        private final AtomicLong partialBytesSeen = new AtomicLong(-1);

        Result<Unit> write(Path partial, byte[] content) {
            var limit = bytesBeforeFailure.get();

            if (limit < 0) {
                return FileOps.writeBytes(partial, content);
            }

            return FileOps.writeBytes(partial, Arrays.copyOf(content, limit))
                          .onSuccess(_ -> partialBytesSeen.set(FileOps.size(partial).or(-1L)))
                          .flatMap(_ -> new FileError.WriteFailed(partial, "No space left on device").result());
        }
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
            return entries.values()
                          .stream()
                          .mapToLong(bytes -> bytes.length)
                          .sum();
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
            events.add(new Captured(event.getLevel(),
                                    event.getMessage().getFormattedMessage()));
        }

        List<Captured> warnsMentioning(String fragment) {
            return events.stream()
                         .filter(e -> e.level() == Level.WARN && e.message()
                                                                  .contains(fragment))
                         .toList();
        }

        List<Captured> debugsMentioning(String fragment) {
            return events.stream()
                         .filter(e -> e.level() == Level.DEBUG && e.message()
                                                                   .contains(fragment))
                         .toList();
        }
    }
}
