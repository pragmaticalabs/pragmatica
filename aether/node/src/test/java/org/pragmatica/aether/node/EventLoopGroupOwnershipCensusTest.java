// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


/// #3635 census (4th instance of the class after #3319 and #3558): every place under `integrations/**` and `aether/**`
/// `src/main` that creates an event-loop group is listed in `event-loop-group-allowlist.txt` with the method that releases
/// the group when the owner's connect/bind fails and the method that releases it on close. The invariant it keeps visible:
/// a client-owned group is released on EVERY exit path of its owner, and a supplied shared group is never released.
///
/// The scan is lexical over comment- and string-stripped source. A creation site is a `new` of a type named
/// `*EventLoopGroup`, `*EventLoop`, or `Nio*`/`Epoll*`/`KQueue*`/`IoUring*`. The search space is asserted: both roots
/// exist, the scan covers at least a pinned file count, and every allow-listed file must still hold exactly the listed
/// number of sites (a stale row fails, so a deleted or renamed site cannot hide).
class EventLoopGroupOwnershipCensusTest {
    static final List<String> ROOTS = List.of("integrations", "aether");
    /// Scanned files at the commit that introduced the census, rounded down.
    static final int FILE_FLOOR = 1500;
    static final String ALLOW_LIST = "event-loop-group-allowlist.txt";

    static final Pattern CREATION = Pattern.compile("\\bnew\\s+(?:\\w+\\.)*(?:\\w*EventLoopGroup|\\w*EventLoop|(?:Nio|Epoll|KQueue|IoUring)\\w*)\\s*(?:<[^>]*>)?\\(");

    static final Pattern PENDING = Pattern.compile("pending #3635 quic:(\\S+)");

    /// The two methods the open #3635 QUIC fix introduces (QuicClusterServer's bind-failure release, QuicClusterNetwork's
    /// failed-start cleanup). Remove with the markers once that fix is merged.
    static final Set<String> PENDING_QUIC = Set.of("QuicClusterServer.releaseOwnedGroupAfterFailedBind",
                                                   "QuicClusterNetwork.onStartFailed");

    static final Pattern CELL = Pattern.compile("([\\w./-]+\\.java)@(.+)");

    @Test
    void searchSpace_isScanned() {
        var root = repoRoot();

        assertThat(ROOTS).allSatisfy(dir -> assertThat(root.resolve(dir)).isDirectory());
        assertThat(mainFiles(root)).as("java main files scanned").hasSizeGreaterThanOrEqualTo(FILE_FLOOR);
    }

    @Test
    void everyEventLoopGroupCreationSite_isListedWithItsReleases() {
        var root = repoRoot();
        var sites = siteCounts(root, mainFiles(root));
        var entries = parse(readResource(ALLOW_LIST));

        assertThat(violations(sites, entries, root)).as("event-loop group creation sites without a matching allow-list row")
                  .isEmpty();
    }

    /// The instrument against fixtures: a scanner that misses a shape, or a check that accepts an unnamed release, fails here.
    @Nested
    class Fixture {
        @Test
        void groupCreation_isASite_inAnyShape() {
            assertThat(sites("class A { Object f() { return new NioEventLoopGroup(1); } }")).isEqualTo(1);
            assertThat(sites("class A { Object f() { return new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory()); } }")).isEqualTo(1);
            assertThat(sites("class A { Object f() { return supply(() -> new MultiThreadIoEventLoopGroup(1, f)); } }")).isEqualTo(1);
            assertThat(sites("class A { Object f() { return new EpollEventLoopGroup(); } }")).isEqualTo(1);
            assertThat(sites("class A { Object f() { return new io.netty.channel.nio.NioEventLoopGroup(1); } }")).isEqualTo(1);
            assertThat(sites("class A { Object f() { var a = new MultiThreadIoEventLoopGroup(1, f); var b = new MultiThreadIoEventLoopGroup(f); } }")).isEqualTo(2);
        }

        @Test
        void commentsStringsAndConsumers_areNotSites() {
            assertThat(sites("class A { // new NioEventLoopGroup(1)\n String s = \"new NioEventLoopGroup(1)\"; }")).isZero();
            assertThat(sites("class A { void f(EventLoopGroup g) { use(g); var m = new EventLoopMetricsCollector(g); } }")).isZero();
        }

        @Test
        void unlistedSite_isRejected() {
            assertThat(violations(Map.of("a/B.java", 1), Map.of(), Path.of("."))).anyMatch(v -> v.startsWith("a/B.java") && v.contains("no allow-list row"));
        }

        @Test
        void staleCount_andStaleRow_areRejected() {
            var entries = parse("a/B.java | 1 | pending #3635 quic:QuicClusterServer.releaseOwnedGroupAfterFailedBind | pending #3635 quic:QuicClusterNetwork.onStartFailed | n\nc/D.java | 1 | pending #3635 quic:QuicClusterServer.releaseOwnedGroupAfterFailedBind | pending #3635 quic:QuicClusterNetwork.onStartFailed | n\n");
            var found = violations(Map.of("a/B.java", 2), entries, Path.of("."));

            assertThat(found).anyMatch(v -> v.startsWith("a/B.java") && v.contains("2 creation site"))
                      .anyMatch(v -> v.startsWith("c/D.java") && v.contains("0 creation site"));
        }

        @Test
        void pendingMarker_toleratesOnlyTheTwoNamedMethods() {
            var tolerated = parse("a/B.java | 1 | pending #3635 quic:QuicClusterServer.releaseOwnedGroupAfterFailedBind | pending #3635 quic:QuicClusterNetwork.onStartFailed | n\n");
            var other = parse("a/B.java | 1 | pending #3635 quic:Anything.else | pending #3635 quic:QuicClusterNetwork.onStartFailed | n\n");

            assertThat(violations(Map.of("a/B.java", 1), tolerated, Path.of("."))).isEmpty();
            assertThat(violations(Map.of("a/B.java", 1), other, Path.of("."))).anyMatch(v -> v.contains("Anything.else"));
        }

        @Test
        void emptyOrMalformedReleaseCell_isRejected() {
            var empty = parse("a/B.java | 1 |  | pending #3635 quic:QuicClusterNetwork.onStartFailed | n\n");
            var malformed = parse("a/B.java | 1 | the close method | pending #3635 quic:QuicClusterNetwork.onStartFailed | n\n");

            assertThat(violations(Map.of("a/B.java", 1), empty, Path.of("."))).anyMatch(v -> v.contains("names no release"));
            assertThat(violations(Map.of("a/B.java", 1), malformed, Path.of("."))).anyMatch(v -> v.contains("malformed"));
        }

        @Test
        void releaseThatMovedOrWasDeleted_isDecidedByTheAnchor(@TempDir Path root) throws IOException {
            var entries = parse("a/B.java | 1 | C.java@void release( | C.java@void release( | n\n");

            Files.writeString(root.resolve("C.java"), "class C { void release() {} }\n");
            assertThat(violations(Map.of("a/B.java", 1), entries, root)).isEmpty();
            Files.writeString(root.resolve("C.java"), "class C { void other() {} }\n");
            assertThat(violations(Map.of("a/B.java", 1), entries, root)).anyMatch(v -> v.contains("no longer contains"));
        }

        @Test
        void releaseInAFileThatIsGone_isRejected(@TempDir Path root) {
            var entries = parse("a/B.java | 1 | Gone.java@void release( | Gone.java@void release( | n\n");

            assertThat(violations(Map.of("a/B.java", 1), entries, root)).anyMatch(v -> v.contains("names no such file"));
        }
    }

    // --- the instrument ---
    record Row(String path, int count, String onFailure, String onClose) {}

    static Map<String, Row> parse(String text) {
        var rows = new TreeMap<String, Row>();

        text.lines()
            .map(String::strip)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .forEach(line -> {
                         var fields = line.split("\\|", -1);

                         assertThat(fields).as("allow-list line has 5 fields: " + line)
                                   .hasSize(5);
                         rows.put(fields[0].strip(),
                                  new Row(fields[0].strip(),
                                          Integer.parseInt(fields[1].strip()),
                                          fields[2].strip(),
                                          fields[3].strip()));
                     });

        return rows;
    }

    static List<String> violations(Map<String, Integer> sites, Map<String, Row> rows, Path root) {
        var found = new ArrayList<String>();
        var paths = new TreeSet<String>(sites.keySet());

        paths.addAll(rows.keySet());
        paths.forEach(path -> checkPath(path, sites.getOrDefault(path, 0), rows.get(path), root, found));

        return found;
    }

    private static void checkPath(String path, int count, Row row, Path root, List<String> found) {
        if (row == null) {
            found.add(path + ": " + count + " creation site(s), no allow-list row");

            return;
        }

        if (row.count() != count) {
            found.add(path + ": " + count + " creation site(s), the allow-list row says " + row.count());
        }

        checkCell(path, "failure", row.onFailure(), root, found);
        checkCell(path, "close", row.onClose(), root, found);
    }

    private static void checkCell(String path, String which, String cell, Path root, List<String> found) {
        if (cell.isEmpty()) {
            found.add(path + ": " + which + " cell names no release");

            return;
        }

        var pending = PENDING.matcher(cell);

        if (pending.matches()) {
            if (!PENDING_QUIC.contains(pending.group(1))) {
                found.add(path
                         + ": " + which
                         + " cell tolerates only " + PENDING_QUIC
                         + ", not '" + pending.group(1)
                         + "'");
            }

            return;
        }

        var named = CELL.matcher(cell);

        if (!named.matches()) {
            found.add(path
                     + ": " + which
                     + " cell '" + cell
                     + "' is malformed (want path.java@anchor or pending #3635 quic:<name>)");

            return;
        }

        var file = root.resolve(named.group(1));

        if (!Files.isRegularFile(file)) {
            found.add(path + ": " + which + " cell '" + cell + "' names no such file");
        } else if (!read(file).contains(named.group(2))) {
            found.add(path + ": " + which + " cell '" + cell + "' no longer contains '" + named.group(2) + "'");
        }
    }

    static int sites(String source) {
        var stripped = TransportOutcomeDisciplineTest.SendScanner.stripCommentsAndStrings(source);
        var matcher = CREATION.matcher(stripped);
        var count = 0;

        while (matcher.find()) {
            count++;
        }

        return count;
    }

    private static Map<String, Integer> siteCounts(Path root, List<Path> files) {
        var counts = new TreeMap<String, Integer>();

        files.forEach(file -> {
            var count = sites(read(file));

            if (count > 0) {
                counts.put(root.relativize(file).toString().replace('\\', '/'),
                           count);
            }
        });

        return counts;
    }

    /// Every `src/main/java` file under the roots, outside build output and the per-checkout maven repo.
    private static List<Path> mainFiles(Path root) {
        return ROOTS.stream()
                    .flatMap(dir -> javaFiles(root.resolve(dir)).stream())
                    .filter(file -> {
                                var relative = root.relativize(file)
                                                   .toString()
                                                   .replace('\\', '/');

                                return relative.contains("/src/main/java/")
                                       && !relative.contains("/target/")
                                       && !relative.contains(".m2-local/");
                            })
                    .toList();
    }

    private static List<Path> javaFiles(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(file -> file.toString()
                                            .endsWith(".java"))
                        .sorted()
                        .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null && !Files.isRegularFile(dir.resolve("integrations/consensus/pom.xml"))) {
            dir = dir.getParent();
        }

        assertThat(dir).as("repository root above " + Path.of("").toAbsolutePath()).isNotNull();

        return dir;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readResource(String name) {
        try (var in = EventLoopGroupOwnershipCensusTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("test resource " + name).isNotNull();

            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
