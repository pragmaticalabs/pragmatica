// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.pragmatica.testlogging.SourceWalk;

import static org.assertj.core.api.Assertions.assertThat;

/// TA8 of #3754 C1: nothing reads the leadership generation except [CapacityControlledLifecycle#activation]. The COMPILER enforces
/// almost all of it: [LeadershipGeneration] has no method that returns the count, so `get()`, `incrementAndGet()` used as a value and a
/// pass-through that reads its parameter do not compile (probed with two planted reads, on the PR). The one thing a type cannot say is
/// "this method may be called from one place only", and the type's single pairing method [LeadershipGeneration#pairedWith] is such a
/// method: a second call of it takes a generation of its own, which is what the accessor exists to prevent. This scan covers exactly that
/// remainder, over `*/src/main` of the whole tree (the lifecycle type, and so its component, is visible outside the module), with the
/// comments removed (a comment that names the method is not a call).
class CapacityGenerationReaderScanTest {
    private static final String TYPE_FILE = "LeadershipGeneration.java";
    private static final String LIFECYCLE_FILE = "CapacityControlledLifecycle.java";
    private static final String ACCESSOR_START = "Option<Activation> activation()";
    private static final String ACCESSOR_END = "private Option<LeaderValue> storedLeader()";
    /// Any mention of the pairing method, a call or a method reference alike.
    private static final Pattern OCCURRENCE = Pattern.compile("\\bpairedWith\\b");
    /// Text blocks, string and char literals are kept whole (they may hold `//`); the comments are what is dropped.
    private static final Pattern TOKEN = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|\"[^\"\\\\\\n]*+(?:\\\\.[^\"\\\\\\n]*+)*+\"|'(?:\\\\.|[^'\\\\\\n])'|//[^\\n]*|/\\*[\\s\\S]*?\\*/");

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
        try {
            return SourceWalk.files(root).stream()
                             .filter(path -> path.toString().endsWith(".java"))
                             .filter(path -> path.toString().contains("/src/main/"))
                             .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// The source with every comment replaced by blanks of the same length (offsets stay valid), strings untouched.
    static String withoutComments(String source) {
        return TOKEN.matcher(source)
                    .replaceAll(match -> !match.group().startsWith("/") ? Matcher.quoteReplacement(match.group())
                                                                         : " ".repeat(match.group().length()));
    }

    /// Every mention of the pairing method that is not the one allowed, as `file:offset`. The type's own file is the declaration. In the
    /// lifecycle the allowed one is the single mention inside the accessor body; in any other file every mention is a violation.
    static List<String> violations(String fileName, String source) {
        var code = withoutComments(source);
        var matcher = OCCURRENCE.matcher(code);
        var found = new ArrayList<String>();
        var start = code.indexOf(ACCESSOR_START);
        var end = code.indexOf(ACCESSOR_END);
        var lifecycle = fileName.endsWith(LIFECYCLE_FILE);
        var inAccessor = 0;

        if (fileName.endsWith(TYPE_FILE)) {
            return found;
        }

        while (matcher.find()) {
            if (lifecycle && start >= 0 && end > start && matcher.start() > start && matcher.start() < end && ++inAccessor == 1) {
                continue;
            }
            found.add(fileName + ":" + matcher.start());
        }
        return found;
    }

    @Test
    void noSourceOfTheTree_pairsTheLeadershipGeneration_exceptTheAccessor() {
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
        assertThat(all).as("a call of the pairing method other than the accessor's").isEmpty();
    }

    @Test
    void theLifecycle_hasExactlyOneMention_insideTheAccessor() {
        var root = treeRoot();
        var lifecycle = mainSources(root).stream().filter(path -> path.toString().endsWith(LIFECYCLE_FILE)).findFirst().orElseThrow();
        var code = withoutComments(read(lifecycle));
        var start = code.indexOf(ACCESSOR_START);
        var end = code.indexOf(ACCESSOR_END);

        assertThat(start).as("control: the accessor is found").isPositive();
        assertThat(end).as("control: its end marker is found").isGreaterThan(start);
        assertThat(OCCURRENCE.matcher(code).results().count()).as("one mention in the lifecycle").isEqualTo(1);
        assertThat(OCCURRENCE.matcher(code.substring(start, end)).results().count()).as("and it is in the accessor body").isEqualTo(1);
    }

    /// Positive control: the scan goes red on a pass-through that pairs through its parameter (the `readThrough(gen, 0)` plant), on a
    /// direct second call, on a method reference, and on a foreign file; it stays green on a comment that names the method.
    @Test
    void theScan_flagsASecondPairing_andIgnoresComments() {
        var root = treeRoot();
        var lifecycle = mainSources(root).stream().filter(path -> path.toString().endsWith(LIFECYCLE_FILE)).findFirst().orElseThrow();
        var source = read(lifecycle);

        assertThat(violations(LIFECYCLE_FILE, source)).as("the real lifecycle is clean").isEmpty();
        assertThat(violations(LIFECYCLE_FILE,
                              source.replace(ACCESSOR_END,
                                             "long readThrough(LeadershipGeneration g, long x) { return g.pairedWith(Option.none()).hashCode() + x; }\n"
                                             + ACCESSOR_END)))
            .as("a pass-through that pairs through its parameter").hasSize(1);
        assertThat(violations(LIFECYCLE_FILE, source.replace(ACCESSOR_END, "Object extra() { return leadershipGeneration.pairedWith(storedLeader()); }\n" + ACCESSOR_END)))
            .as("a direct second call after the accessor").hasSize(1);
        assertThat(violations(LIFECYCLE_FILE, source.replace(ACCESSOR_END, "Object alias() { return (Object) leadershipGeneration::pairedWith; }\n" + ACCESSOR_END)))
            .as("a method reference").hasSize(1);
        assertThat(violations(LIFECYCLE_FILE, source.replace("leadershipGeneration.pairedWith(", "leadershipGeneration.pairedWith(storedLeader()).or(leadershipGeneration.pairedWith(")))
            .as("a second call inside the accessor body").hasSize(1);
        assertThat(violations("Other.java", "var a = lifecycle.leadershipGeneration().pairedWith(none);")).as("a foreign file").hasSize(1);
        assertThat(violations("Other.java", "// pairedWith is named in a comment\n/* and pairedWith in a block */\n/// and in pairedWith docs\nint unrelated = 1;"))
            .as("control: comments are ignored (F6)").isEmpty();
        assertThat(violations("Other.java", "String u = \"http://x\"; var a = g.pairedWith(none);")).as("a `//` inside a string literal hides nothing").hasSize(1);
        assertThat(violations("Other.java", "char q = '\"'; var a = g.pairedWith(none);")).as("a quote char literal hides nothing").hasSize(1);
    }
}
