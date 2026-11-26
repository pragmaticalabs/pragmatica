// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// TA8 of #3754 C1: nothing reads `leadershipGeneration` except the one accessor, [CapacityControlledLifecycle#activation]. The concrete
/// lifecycle type is visible outside its module (the record accessor `leadershipGeneration()` is public), so the scan covers `*/src/main`
/// of the WHOLE tree, not one module. A second reader would be a continuation that pairs "present" with a generation of its own taking,
/// which is exactly what the accessor exists to prevent. The scan is a tripwire on the text, with a positive control: the same scan, run
/// over the lifecycle with a direct read added and over a foreign file that calls the accessor, goes red.
class CapacityGenerationReaderScanTest {
    private static final String NAME = "leadershipGeneration";
    private static final String LIFECYCLE_FILE = "CapacityControlledLifecycle.java";
    private static final String ACCESSOR_START = "synchronized Option<Activation> activation()";
    private static final String ACCESSOR_END = "private Option<LeaderValue> storedLeader()";
    private static final Pattern OCCURRENCE = Pattern.compile("\\bleadershipGeneration\\b");
    /// What may follow the name inside the lifecycle: the component declaration or a pass-through (`,`), the gain's write, and the one read.
    private static final Pattern ALLOWED_WRITE = Pattern.compile("\\G\\s*(?:,|\\.incrementAndGet\\(\\))");
    private static final Pattern THE_READ = Pattern.compile("\\G\\s*\\.get\\(\\)");

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path treeRoot() {
        var directory = Path.of("").toAbsolutePath();

        while (directory != null && !(Files.isDirectory(directory.resolve("aether")) && Files.isDirectory(directory.resolve("core"))
                                      && Files.isDirectory(directory.resolve("integrations")))) {
            directory = directory.getParent();
        }
        return directory;
    }

    private static List<Path> mainSources(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> path.toString().contains("/src/main/"))
                        .filter(path -> !path.toString().contains("/target/") && !path.toString().contains("/.m2-local/")
                                        && !path.toString().contains("/.bb/"))
                        .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Every occurrence of the name that is not allowed, as `file:offset`. In any file but the lifecycle, every occurrence is a violation.
    /// In the lifecycle the allowed ones are the declaration/pass-through, the increment, and a single `.get()` inside the accessor body.
    static List<String> violations(String fileName, String source) {
        var matcher = OCCURRENCE.matcher(source);
        var found = new java.util.ArrayList<String>();
        var start = source.indexOf(ACCESSOR_START);
        var end = source.indexOf(ACCESSOR_END);
        var lifecycle = fileName.endsWith(LIFECYCLE_FILE);
        var reads = 0;

        while (matcher.find()) {
            var after = matcher.end();
            var inAccessor = start >= 0 && end > start && matcher.start() > start && matcher.start() < end;
            var allowedWrite = ALLOWED_WRITE.matcher(source).region(after, source.length()).lookingAt();
            var theRead = THE_READ.matcher(source).region(after, source.length()).lookingAt();

            if (lifecycle && allowedWrite) {
                continue;
            }

            if (lifecycle && theRead && inAccessor && ++reads == 1) {
                continue;
            }
            found.add(fileName + ":" + matcher.start());
        }
        return found;
    }

    @Test
    void noSourceOfTheTree_readsTheLeadershipGeneration_exceptTheAccessor() {
        var root = treeRoot();
        var sources = mainSources(root);
        var names = sources.stream().map(path -> root.relativize(path).toString()).toList();
        var all = sources.stream().flatMap(path -> violations(path.toString(), read(path)).stream()).toList();

        assertThat(root).as("control: the tree root is found").isNotNull();
        assertThat(names).as("control: the scan covers the lifecycle, core and integrations").anyMatch(name -> name.endsWith(LIFECYCLE_FILE))
                         .anyMatch(name -> name.startsWith("core/"))
                         .anyMatch(name -> name.startsWith("integrations/"))
                         .anyMatch(name -> name.startsWith("aether/node/"));
        assertThat(sources.size()).as("control: a whole tree, not one module").isGreaterThan(1000);
        assertThat(all).as("a reader of the leadership generation other than the accessor").isEmpty();
    }

    @Test
    void theLifecycle_hasExactlyOneRead_insideTheAccessor() {
        var root = treeRoot();
        var lifecycle = mainSources(root).stream().filter(path -> path.toString().endsWith(LIFECYCLE_FILE)).findFirst().orElseThrow();
        var source = read(lifecycle);
        var start = source.indexOf(ACCESSOR_START);
        var end = source.indexOf(ACCESSOR_END);

        assertThat(start).as("control: the accessor is found").isPositive();
        assertThat(end).as("control: its end marker is found").isGreaterThan(start);
        assertThat(OCCURRENCE.matcher(source).results().count()).as("control: the name occurs").isGreaterThan(2);
        assertThat(Pattern.compile("\\bleadershipGeneration\\s*\\.\\s*get\\(\\)").matcher(source.substring(start, end)).results().count())
            .as("one read in the accessor body").isEqualTo(1);
    }

    /// Positive control: the scan goes red on an added direct read, on a second read inside the accessor, on an aliased read, and on a
    /// foreign file that calls the public record accessor.
    @Test
    void theScan_flagsAnAddedDirectRead_andAForeignAccessorCall() {
        var root = treeRoot();
        var lifecycle = mainSources(root).stream().filter(path -> path.toString().endsWith(LIFECYCLE_FILE)).findFirst().orElseThrow();
        var source = read(lifecycle);

        assertThat(violations(LIFECYCLE_FILE, source)).as("the real lifecycle is clean").isEmpty();
        assertThat(violations(LIFECYCLE_FILE, source.replace(ACCESSOR_END, "long extra() { return leadershipGeneration.get(); }\n" + ACCESSOR_END)))
            .as("a direct read added after the accessor").hasSize(1);
        assertThat(violations(LIFECYCLE_FILE, source.replace(ACCESSOR_END, "AtomicLong alias() { return leadershipGeneration; }\n" + ACCESSOR_END)))
            .as("an aliased read").hasSize(1);
        assertThat(violations(LIFECYCLE_FILE, source.replace("leadershipGeneration.get()", "leadershipGeneration.get() + leadershipGeneration.get()")))
            .as("a second read inside the accessor").hasSize(1);
        assertThat(violations("Other.java", "long g = lifecycle.leadershipGeneration().get();")).as("a foreign file").hasSize(1);
        assertThat(violations("Other.java", "int unrelated = 1;")).as("control: a clean foreign file").isEmpty();
    }
}
