// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.HotWindowDeclaration;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamDeclaration;
import org.pragmatica.aether.slice.stream.RingGeometry;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The ring and its floor are sized from the hot window, not from retention (stream storage design §2.2, §2.4 items 4 and
/// 21, §4.2; T-17 ring arm, T-29, T-30, T-219, T-266). Every ring here is built by the manager and observed on the ring it
/// built: the index capacity through its control region (`64 + 24 x count`), the floor through its allocation right after
/// creation, and the data region through the allocation it grows to when appended past it.
///
/// - a user stream with no `[hot]` declaration: 16 384 entries and `max(4 MiB, max_event_size)` bytes;
/// - a declared `[hot]`: the declared values, each absent one defaulting;
/// - `entity:`: [EntityRingWindow] (10 000 entries, 64 MiB);
/// - `system:`: its own retention (R-13).
class StreamRingWindowTest {
    private static final long KIB = 1024L;
    private static final long MIB = 1024 * KIB;
    private static final long GIB = 1024 * MIB;
    private static final long HOUR_MS = 3_600_000L;
    private static final int APPEND_PAYLOAD = (int) (512 * KIB);
    /// The default window's floor at the default 1 MiB event size: `64 + 24 x 16384 + 1 MiB` (ST §2.4 item 4).
    private static final long DEFAULT_FLOOR = 1_441_856L;
    /// The default window's growth limit: `64 + 24 x 16384 + 4 MiB`.
    private static final long DEFAULT_CAP = 4_587_584L;

    @Nested
    class UserStreamDefaultWindow {
        /// T-29: retention `max_count = 10^7`, `max_bytes = 8 GiB` no longer sizes the ring. Before the flip the index alone is
        /// 240 MB, which this 64 MiB budget refuses (`STREAM_MEMORY_EXCEEDED`), so nothing that large is allocated.
        @Test
        void ringIndexAndDataRegion_comeFromTheWindow_notFromALargeRetention() {
            var manager = streamPartitionManager(64 * MIB);
            try {
                var config = userStream("t29", RetentionPolicy.retentionPolicy(10_000_000L, 8 * GIB, HOUR_MS), MIB);
                create(manager, config);
                var ring = ring(manager, "t29");

                assertThat(indexCapacity(ring)).as("T-29: index capacity is the window's").isEqualTo(RingGeometry.HOT_WINDOW_ENTRIES);
                appendPast(manager, "t29", 4 * MIB);
                assertThat(ring.allocatedBytes()).as("T-29: the data region grows to the window's 4 MiB").isEqualTo(DEFAULT_CAP);
            } finally {
                manager.close();
            }
        }

        /// T-30: the floor is 1 441 856 B per copy at the defaults, the growth limit 4 587 584 B; two streams 100x apart in
        /// retention count reserve the same.
        @Test
        void floorAndGrowthLimit_atTheDefaults_andIndependentOfRetentionCount() {
            var manager = streamPartitionManager(GIB);
            try {
                create(manager, userStream("small", RetentionPolicy.retentionPolicy(1_000L, 8 * GIB, HOUR_MS), MIB));
                create(manager, userStream("large", RetentionPolicy.retentionPolicy(100_000L, 8 * GIB, HOUR_MS), MIB));
                var small = ring(manager, "small");
                var large = ring(manager, "large");

                assertThat(small.allocatedBytes()).as("T-30: the floor at the defaults").isEqualTo(DEFAULT_FLOOR);
                assertThat(large.allocatedBytes()).as("T-30: a 100x larger retention count reserves the same").isEqualTo(DEFAULT_FLOOR);
                appendPast(manager, "large", 4 * MIB);
                assertThat(large.allocatedBytes()).as("T-30: the growth limit at the defaults").isEqualTo(DEFAULT_CAP);
            } finally {
                manager.close();
            }
        }

        /// T-266: an 8 MiB event size gives an 8 MiB data region and an 8 781 888 B floor, and a ring frozen at that floor accepts
        /// an 8 MiB event; at 1 MiB the region is 4 MiB and the floor 1 441 856 B.
        @Test
        void eventSizeTerm_widensTheDefaultWindowAndTheFloor() {
            var floor8 = 64L + 24L * 16_384 + 8 * MIB;
            assertThat(floor8).isEqualTo(8_781_888L);
            var manager = streamPartitionManager(floor8);
            try {
                create(manager, userStream("big", RetentionPolicy.retentionPolicy(10_000L, 64 * MIB, HOUR_MS), 8 * MIB));
                var ring = ring(manager, "big");

                assertThat(ring.allocatedBytes()).as("T-266: the floor at an 8 MiB event size").isEqualTo(floor8);
                publish(manager, "big", payload(8 * MIB, 1));
                assertThat(ring.allocatedBytes()).as("T-266: the data region is 8 MiB, the floor is the whole ring").isEqualTo(floor8);
                assertThat(ring.eventCount()).as("T-266: the 8 MiB event is held").isEqualTo(1);
            } finally {
                manager.close();
            }
            var small = streamPartitionManager(GIB);
            try {
                create(small, userStream("one", RetentionPolicy.retentionPolicy(10_000L, 64 * MIB, HOUR_MS), MIB));

                assertThat(ring(small, "one").allocatedBytes()).as("T-266: the floor at a 1 MiB event size").isEqualTo(DEFAULT_FLOOR);
                appendPast(small, "one", 4 * MIB);
                assertThat(ring(small, "one").allocatedBytes()).as("T-266: the data region at 1 MiB is 4 MiB").isEqualTo(DEFAULT_CAP);
            } finally {
                small.close();
            }
        }

        /// T-219: a ring frozen at its floor (the budget is exactly that floor) accepts one 1 MiB event: no `EVENT_DROPPED`.
        /// Before the flip the default retention's floor (100 000 entries) does not fit this budget at all.
        @Test
        void ringFrozenAtItsFloor_acceptsOneMaximalEvent() {
            var manager = streamPartitionManager(DEFAULT_FLOOR);
            try {
                create(manager, userStream("t219", RetentionPolicy.retentionPolicy(), MIB));
                var ring = ring(manager, "t219");

                assertThat(ring.allocatedBytes()).as("control: the ring holds exactly its floor").isEqualTo(DEFAULT_FLOOR);
                publish(manager, "t219", payload(MIB, 1));
                assertThat(ring.allocatedBytes()).as("control: the ring did not grow").isEqualTo(DEFAULT_FLOOR);
                assertThat(ring.eventCount()).as("T-219: the 1 MiB event is held").isEqualTo(1);
            } finally {
                manager.close();
            }
        }
    }

    @Nested
    class DeclaredWindow {
        @Test
        void declaredHot_givesTheDeclaredCountAndBytes() {
            var manager = streamPartitionManager(GIB);
            try {
                create(manager, HotWindowFixture.declaredHot(userStream("hot", RetentionPolicy.retentionPolicy(), MIB), 10, 64 * KIB));
                var ring = ring(manager, "hot");

                assertThat(indexCapacity(ring)).as("declared count").isEqualTo(10);
                assertThat(ring.allocatedBytes()).as("declared bytes: the floor is the whole 64 KiB region")
                                                 .isEqualTo(RingGeometry.floorBytes(10, 64 * KIB, MIB));
            } finally {
                manager.close();
            }
        }

        @Test
        void declaredCountOnly_takesTheDefaultBytes() {
            var manager = streamPartitionManager(GIB);
            try {
                create(manager, withHot(userStream("count", RetentionPolicy.retentionPolicy(), MIB), none(), some(10L)));
                var ring = ring(manager, "count");

                assertThat(indexCapacity(ring)).as("declared count").isEqualTo(10);
                assertThat(ring.allocatedBytes()).as("default bytes: the floor holds one event").isEqualTo(RingGeometry.floorBytes(10, 4 * MIB, MIB));
                appendPast(manager, "count", 4 * MIB);
                assertThat(ring.allocatedBytes()).as("default bytes: the region grows to 4 MiB").isEqualTo(64L + 24L * 10 + 4 * MIB);
            } finally {
                manager.close();
            }
        }

        @Test
        void declaredBytesOnly_takesTheDefaultCount() {
            var manager = streamPartitionManager(GIB);
            try {
                create(manager, withHot(userStream("bytes", RetentionPolicy.retentionPolicy(), MIB), some(64 * KIB), none()));
                var ring = ring(manager, "bytes");

                assertThat(indexCapacity(ring)).as("default count").isEqualTo(RingGeometry.HOT_WINDOW_ENTRIES);
                assertThat(ring.allocatedBytes()).as("declared bytes").isEqualTo(RingGeometry.floorBytes(RingGeometry.HOT_WINDOW_ENTRIES, 64 * KIB, MIB));
            } finally {
                manager.close();
            }
        }
    }

    @Nested
    class EntityAndSystemWindows {
        /// T-17, ring arm: an `entity:` stream gets [EntityRingWindow] through the manager: 10 000 entries, a 64 MiB cap and a
        /// 1 288 640 B floor, whatever its retention says.
        @Test
        void entityStream_getsTheEntityWindow() {
            var manager = streamPartitionManager(GIB);
            try {
                create(manager, userStream("entity:orders", RetentionPolicy.retentionPolicy(100_000L, GIB, HOUR_MS), EntityRingWindow.MAX_EVENT_SIZE_BYTES));
                var ring = ring(manager, "entity:orders");

                assertThat(indexCapacity(ring)).as("T-17: entity ring capacity").isEqualTo(EntityRingWindow.CAPACITY);
                assertThat(ring.allocatedBytes()).as("T-17: entity floor").isEqualTo(1_288_640L);
                appendPast(manager, "entity:orders", EntityRingWindow.MAX_BYTES);
                assertThat(ring.allocatedBytes()).as("T-17: entity cap").isEqualTo(64L + 24L * 10_000 + 64 * MIB);
            } finally {
                manager.close();
            }
        }

        /// R-13: a `system:` ring keeps its own committed retention: (10 000, 16 MiB) gives 10 000 entries and 16 MiB.
        @Test
        void systemStream_keepsItsRetentionWindow() {
            var manager = streamPartitionManager(GIB);
            try {
                create(manager, userStream("system:events", RetentionPolicy.retentionPolicy(10_000L, 16 * MIB, HOUR_MS), MIB));
                var ring = ring(manager, "system:events");

                assertThat(indexCapacity(ring)).as("R-13: system ring count is its retention's").isEqualTo(10_000);
                appendPast(manager, "system:events", 16 * MIB);
                assertThat(ring.allocatedBytes()).as("R-13: system ring bytes are its retention's").isEqualTo(64L + 24L * 10_000 + 16 * MIB);
            } finally {
                manager.close();
            }
        }
    }

    @Nested
    class CountBound {
        private static final long FIRST = RingGeometry.firstSegmentBytes(4 * MIB, MIB);

        @Test
        void declaredCountPastTheIndexableCapacity_isRefusedTyped_namingTheHotWindowCount() {
            var count = RingGeometry.maxCapacity(FIRST) + 1;
            var manager = streamPartitionManager(128 * MIB);
            try {
                manager.createStream(HotWindowFixture.declaredHot(userStream("idx", RetentionPolicy.retentionPolicy(), MIB), count, 4 * MIB))
                       .onSuccess(_ -> fail("a hot-window count past the index bound must be refused"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.RetentionCountUnindexable("idx", count, RingGeometry.maxCapacity(FIRST))))
                       .onFailure(cause -> assertThat(cause.message()).contains("hot window max_count"));
            } finally {
                manager.close();
            }
        }

        @Test
        void declaredCountAtTheIndexableCapacity_isRefusedByTheBudget_typed() {
            var manager = streamPartitionManager(128 * MIB);
            try {
                manager.createStream(HotWindowFixture.declaredHot(userStream("cap", RetentionPolicy.retentionPolicy(), MIB),
                                                                  RingGeometry.maxCapacity(FIRST),
                                                                  4 * MIB))
                       .onSuccess(_ -> fail("a ring of maxCapacity entries is not affordable"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_MEMORY_EXCEEDED));
            } finally {
                manager.close();
            }
        }

        @Test
        void unboundedRetentionCount_isAccepted_andTheRingIsTheDefaultWindow() {
            var manager = streamPartitionManager(128 * MIB);
            try {
                create(manager, userStream("unbounded", RetentionPolicy.retentionPolicy(Long.MAX_VALUE, 8 * GIB, HOUR_MS), MIB));
                var ring = ring(manager, "unbounded");

                assertThat(indexCapacity(ring)).isEqualTo(RingGeometry.HOT_WINDOW_ENTRIES);
                assertThat(ring.allocatedBytes()).isEqualTo(DEFAULT_FLOOR);
            } finally {
                manager.close();
            }
        }

        @Test
        void declaredWindowBelowOne_isRefusedNamingTheHotKey() {
            var manager = streamPartitionManager(128 * MIB);
            try {
                manager.createStream(withHot(userStream("zero", RetentionPolicy.retentionPolicy(), MIB), none(), some(0L)))
                       .onSuccess(_ -> fail("hot.max_count 0 must be refused"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.RetentionBoundInvalid("zero", "hot.max_count", 0)));
                manager.createStream(withHot(userStream("zerob", RetentionPolicy.retentionPolicy(), MIB), some(0L), none()))
                       .onSuccess(_ -> fail("hot.max_bytes 0 must be refused"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.RetentionBoundInvalid("zerob", "hot.max_bytes", 0)));
            } finally {
                manager.close();
            }
        }
    }

    /// No production source calls the ring on an arity without the event size: `OffHeapRingBuffer.MAX_CAPACITY` (the
    /// one-segment index bound) or an `offHeapRingBuffer(` call with other than the ten arguments of the `maxEventSize`
    /// factory, qualified or through a static import. Scanned: every `*.java` under `<repo>/**/src/main/java`, comments
    /// stripped first, `OffHeapRingBuffer.java` itself excluded.
    @Nested
    class NoProductionCallerOfOldRingArity {
        @Test
        void noProductionCallerOfOldRingArity() {
            var hits = productionSources().stream().flatMap(source -> oldArityHits(source.text()).stream().map(hit -> source.path() + ": " + hit)).toList();

            assertThat(hits).as("production callers of an old ring arity").isEmpty();
        }

        /// The positive control: every form the scan must catch, the static-import form included, and the forms it must not.
        @Test
        void scanner_catchesEveryOldForm() {
            assertThat(oldArityHits("class A { long c = OffHeapRingBuffer.MAX_CAPACITY; }")).hasSize(1);
            assertThat(oldArityHits("class A { void f() { OffHeapRingBuffer.offHeapRingBuffer(n, p, c, b, l, e, r, s, f); } }")).hasSize(1);
            assertThat(oldArityHits("class A { void f() { OffHeapRingBuffer.offHeapRingBuffer(c, b); } }")).hasSize(1);
            assertThat(oldArityHits("import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;\n"
                                    + "class A { void f() { offHeapRingBuffer(n, p, g(c, d), b, l, e, r, s); } }")).hasSize(1);
            assertThat(oldArityHits("import static org.pragmatica.aether.stream.OffHeapRingBuffer.MAX_CAPACITY;\n"
                                    + "class A { long c = MAX_CAPACITY; }")).hasSize(1);
            assertThat(oldArityHits("import static org.pragmatica.aether.stream.OffHeapRingBuffer.*;\n"
                                    + "class A { long c = MAX_CAPACITY; }")).hasSize(1);
            assertThat(oldArityHits("class A { void f() { OffHeapRingBuffer.offHeapRingBuffer(n, p, c, b, m, l, e, r, s, f(x, y)); } }"))
                .as("the maxEventSize arity is not a hit").isEmpty();
            assertThat(oldArityHits("class A { long c = MAX_CAPACITY; void f() { offHeapRingBuffer(a, b); } }"))
                .as("unqualified names without the static import are someone else's").isEmpty();
            assertThat(oldArityHits("class A { // OffHeapRingBuffer.MAX_CAPACITY\n }")).as("comments are stripped").isEmpty();
        }
    }

    // ---- scanner ----------------------------------------------------------------------------------------------------------

    private static final int MAX_EVENT_SIZE_ARITY = 10;
    private static final Pattern QUALIFIED_CAPACITY = Pattern.compile("\\bOffHeapRingBuffer\\s*\\.\\s*MAX_CAPACITY\\b");
    private static final Pattern QUALIFIED_FACTORY = Pattern.compile("\\bOffHeapRingBuffer\\s*\\.\\s*offHeapRingBuffer\\s*\\(");
    private static final Pattern UNQUALIFIED_CAPACITY = Pattern.compile("(?<![.\\w])MAX_CAPACITY\\b");
    private static final Pattern UNQUALIFIED_FACTORY = Pattern.compile("(?<![.\\w])offHeapRingBuffer\\s*\\(");
    private static final Pattern STATIC_IMPORT = Pattern.compile("import\\s+static\\s+[\\w.]*\\bOffHeapRingBuffer\\.(\\w+|\\*)\\s*;");
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("target", ".git", ".m2-local", "node_modules", ".bb", ".idea");

    private record Source(Path path, String text) {}

    static List<String> oldArityHits(String rawSource) {
        var source = withoutComments(rawSource);
        var hits = new ArrayList<String>();
        var imported = staticImports(source);
        // An import declaration is not a use: the static import of MAX_CAPACITY is counted at its unqualified use only.
        var body = withoutImports(source);

        QUALIFIED_CAPACITY.matcher(body).results().forEach(_ -> hits.add("OffHeapRingBuffer.MAX_CAPACITY"));
        QUALIFIED_FACTORY.matcher(body).results().forEach(m -> factoryHit(body, m.end(), hits));
        if (imported.contains("MAX_CAPACITY") || imported.contains("*")) {
            UNQUALIFIED_CAPACITY.matcher(body).results().forEach(_ -> hits.add("MAX_CAPACITY (static import)"));
        }
        if (imported.contains("offHeapRingBuffer") || imported.contains("*")) {
            UNQUALIFIED_FACTORY.matcher(body).results().forEach(m -> factoryHit(body, m.end(), hits));
        }
        return hits;
    }

    private static void factoryHit(String source, int argumentsStart, List<String> hits) {
        var arity = arity(source, argumentsStart);
        if (arity != MAX_EVENT_SIZE_ARITY) {
            hits.add("offHeapRingBuffer(...) with " + arity + " arguments");
        }
    }

    /// Top-level arguments of the call whose `(` ends just before `start`.
    private static int arity(String source, int start) {
        var depth = 0;
        var commas = 0;
        var any = false;
        for (var i = start; i < source.length(); i++) {
            var c = source.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    return any ? commas + 1 : 0;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                commas++;
            }
            if (!Character.isWhitespace(c)) {
                any = true;
            }
        }
        return -1;
    }

    private static Set<String> staticImports(String source) {
        var names = new HashSet<String>();
        STATIC_IMPORT.matcher(source).results().forEach(m -> names.add(m.group(1)));
        return names;
    }

    private static String withoutImports(String source) {
        return source.replaceAll("(?m)^\\s*import\\s[^;]*;", "");
    }

    private static List<Source> productionSources() {
        var root = repositoryRoot();
        assertThat(root.resolve("aether/aether-stream/src/main/java")).isDirectory();
        var sources = new ArrayList<Source>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return SKIPPED_DIRECTORIES.contains(String.valueOf(dir.getFileName()))
                           ? FileVisitResult.SKIP_SUBTREE
                           : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    var name = file.toString();
                    if (name.endsWith(".java") && name.contains("/src/main/java/") && !file.getFileName().toString().equals("OffHeapRingBuffer.java")) {
                        sources.add(new Source(root.relativize(file), Files.readString(file)));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new AssertionError("Cannot walk production sources under " + root, e);
        }
        assertThat(sources.stream().map(Source::path).map(Path::toString))
            .as("positive control: the scan reached the manager")
            .contains("aether/aether-stream/src/main/java/org/pragmatica/aether/stream/StreamPartitionManager.java");
        return sources;
    }

    /// Removes `//` and `/* */` comments, leaving string, char and text-block literals intact (the
    /// `ClusterEventProducerCensusTest` stripper).
    static String withoutComments(String source) {
        var out = new StringBuilder(source.length());
        var i = 0;
        while (i < source.length()) {
            var c = source.charAt(i);
            if (source.startsWith("\"\"\"", i)) {
                var end = source.indexOf("\"\"\"", i + 3);
                var stop = end < 0 ? source.length() : end + 3;
                out.append(source, i, stop);
                i = stop;
            } else if (c == '"' || c == '\'') {
                var stop = endOfLiteral(source, i, c);
                out.append(source, i, stop);
                i = stop;
            } else if (source.startsWith("//", i)) {
                var end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
            } else if (source.startsWith("/*", i)) {
                var end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int endOfLiteral(String source, int start, char quote) {
        var i = start + 1;
        while (i < source.length() && source.charAt(i) != quote && source.charAt(i) != '\n') {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        return Math.min(i + 1, source.length());
    }

    /// `<repo>/aether/aether-stream/target/test-classes` up four levels; from the class location, not the working directory.
    private static Path repositoryRoot() {
        try {
            return Path.of(StreamRingWindowTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                       .getParent()
                       .getParent()
                       .getParent()
                       .getParent();
        } catch (URISyntaxException e) {
            throw new AssertionError("Cannot locate the repository root", e);
        }
    }

    // ---- fixture ----------------------------------------------------------------------------------------------------------

    private static StreamConfig userStream(String name, RetentionPolicy retention, long maxEventSize) {
        return StreamConfig.streamConfig(name, 1, retention, "latest", maxEventSize);
    }

    /// `config` with `[hot]` = the given dimensions, each `none()` left undeclared.
    private static StreamConfig withHot(StreamConfig config, Option<Long> bytes, Option<Long> count) {
        var hot = HotWindowDeclaration.hotWindowDeclaration(bytes, count);
        return new StreamConfig(config.name(),
                                config.partitions(),
                                config.retention(),
                                config.autoOffsetReset(),
                                config.maxEventSizeBytes(),
                                config.consistencyMode(),
                                config.replicationFactor(),
                                config.confirmationFactor(),
                                config.compression(),
                                config.encryptionKeyId(),
                                config.incarnation(),
                                some(StreamDeclaration.streamDeclaration(none(), none(), some(hot), none())));
    }

    private static void create(StreamPartitionManager manager, StreamConfig config) {
        manager.createStream(config).onFailure(cause -> fail("create " + config.name() + ": " + cause.message()));
    }

    private static OffHeapRingBuffer ring(StreamPartitionManager manager, String stream) {
        return manager.partitionBuffer(stream, 0).onEmpty(() -> fail("no ring for " + stream)).unwrap();
    }

    private static long indexCapacity(OffHeapRingBuffer ring) {
        return (ring.controlBytes() - RingGeometry.HEADER_SIZE) / RingGeometry.INDEX_ENTRY_SIZE;
    }

    /// Appends twice `dataRegion` bytes in 512 KiB events, so the ring has grown to its data region and wrapped within it.
    private static void appendPast(StreamPartitionManager manager, String stream, long dataRegion) {
        for (var written = 0L; written < 2 * dataRegion; written += APPEND_PAYLOAD) {
            publish(manager, stream, payload(APPEND_PAYLOAD, written));
        }
    }

    /// A `system:` stream is written as its system publisher is (`systemWriter = true`, #1338); any other writer is refused.
    private static void publish(StreamPartitionManager manager, String stream, byte[] payload) {
        manager.publishLocalAtFloor(stream, 0, payload, System.currentTimeMillis(), 0, stream.startsWith("system:"))
               .onFailure(cause -> fail("publish " + payload.length + " B to " + stream + ": " + cause.message()));
    }

    private static byte[] payload(long size, long seed) {
        var bytes = new byte[(int) size];
        bytes[0] = (byte) seed;
        return bytes;
    }
}
