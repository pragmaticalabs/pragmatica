// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.cluster.state.kvstore.KVCommand;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// NT-89 (a), over the new package only (R-1.6.5): the single-writer discipline of the cluster FSM, read from its source.
///
/// - Every leader write is a `LeaderTransaction`: no source constructs a bare `KVCommand.Put`.
/// - A `SliceDesiredKey` mutation that WRITES a value is built in `Outbox` only. The other users of the key remove it.
/// - A `SliceObservedKey` is never written: the files that mention `observedKey()` (departure cleanup, directive removal) only
///   remove it, and the guards in `Outbox` only read it.
class ArchitectureTest {
    private static final Path SOURCES = Path.of("..",
                                                "aether-deployment",
                                                "src",
                                                "main",
                                                "java",
                                                "org",
                                                "pragmatica",
                                                "aether",
                                                "deployment",
                                                "cluster",
                                                "fsm2");

    private static Map<String, String> sources() throws IOException {
        var out = new TreeMap<String, String>();

        try (Stream<Path> files = Files.list(SOURCES)) {
            for (var file : files.filter(f -> f.toString()
                                               .endsWith(".java")).toList()) {
                out.put(file.getFileName().toString(),
                        Files.readString(file));
            }
        }

        return out;
    }

    /// The violations of the single-writer rules in `sources` (file name to text).
    static List<String> violations(Map<String, String> sources) {
        var found = new ArrayList<String>();

        sources.forEach((name, text) -> {
            if (text.contains("new KVCommand.Put") || text.contains("KVCommand.Put<")) {
                found.add(name + ": constructs a bare Put");
            }

            if (!name.equals("Outbox.java") && !name.equals("SlicePair.java")) {
                found.addAll(writesOutsideOutbox(name, text));
            }
        });

        return found;
    }

    /// In every file but `Outbox`, each use of a pair's desired or observed key sits in a removal: a `removal(...)` call, a
    /// `Mutation` whose replacement is `Option.none()`, or the key argument of a `draft(...)` whose mutations are checked on their own.
    private static List<String> writesOutsideOutbox(String name, String text) {
        var found = new ArrayList<String>();
        var flat = text.replaceAll("\\s+", " ");

        for (var marker : new String[]{"desiredKey()", "observedKey()", "new SliceObservedKey", "new SliceDesiredKey"}) {
            var from = 0;

            while ((from = flat.indexOf(marker, from)) >= 0) {
                if (!inRemoval(flat, from)) {
                    found.add(name + ": " + marker + " is not part of a removal");
                }

                from += marker.length();
            }
        }

        return found;
    }

    private static boolean inRemoval(String flat, int at) {
        var open = enclosingOpen(flat, at);
        var head = flat.substring(Math.max(0, open - 60), open);
        var call = flat.substring(open, matching(flat, open) + 1);

        return head.endsWith("removal") || head.endsWith("draft") || head.contains("Mutation<") && call.endsWith("Option.none())");
    }

    private static int enclosingOpen(String flat, int at) {
        var depth = 0;

        for (var i = at - 1; i >= 0; i--) {
            var c = flat.charAt(i);

            if (c == ')') {
                depth++;
            } else if (c == '(' && depth--== 0) {
                return i;
            }
        }

        return 0;
    }

    private static int matching(String flat, int open) {
        var depth = 0;

        for (var i = open; i < flat.length(); i++) {
            var c = flat.charAt(i);

            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }

        return flat.length() - 1;
    }

    // NT-89 (a)
    // Red when: a desired Put is built outside the cluster interpreter, an observed Put is built at all, or a leader mutation replaces an observed key.
    @Test
    void theClusterFsm_writesDesiredOnlyInTheOutbox_andNeverWritesObserved() throws IOException {
        var real = sources();

        assertThat(real).as("the scan sees the package")
                  .containsKeys("Outbox.java", "DepartureFlow.java", "Directory.java", "Gc.java");
        assertThat(violations(real)).isEmpty();
    }

    // NT-89 (a), positive control
    // Red when: the scan cannot see a planted violation.
    @Test
    void theScan_seesPlantedViolations() throws IOException {
        var planted = new TreeMap<>(sources());

        planted.put("Planted.java", "class Planted { Object x = new KVCommand.Put<>(pair.observedKey(), row); }");
        planted.put("Planted2.java",
                    "class Planted2 { Object x = new KVCommand.Mutation<>(pair.desiredKey(), Option.some(a), Option.some(b)); }");
        assertThat(violations(planted)).anyMatch(v -> v.startsWith("Planted.java: constructs a bare Put"))
                  .anyMatch(v -> v.startsWith("Planted2.java: desiredKey()"));
    }

    /// Cluster-assessment delta 6 (`TransportObservation.SelfShutdown` is never emitted; CTO ruling 9f854db8b, parity with LEGACY: the
    /// leader acts on `NodeRemoved`). Red when: a cluster FSM source consumes a self-shutdown input again.
    @Test
    void theClusterFsm_consumesNoSelfShutdownInput() throws IOException {
        var real = sources();

        assertThat(real).as("the scan sees the package").containsKeys("ClusterInput.java", "Handlers.java");
        assertThat(real).allSatisfy((name, text) -> assertThat(text).as(name).doesNotContain("SelfShutdown"));
    }

    // -- CF-1 (#2759): every leader deployment write leaves through the Outbox -------------------------------------------------
    private static final Path WIRING = SOURCES.getParent().resolveSibling("wiring");
    /// The leader-side files of the wiring that carry a cluster FSM write: the interpreter and the write path it submits through.
    private static final List<String> LEADER_WIRING = List.of("ClusterFsmInterpreter.java", "Submitter.java");

    /// The package's sources plus the leader-side wiring, comments and string literals blanked.
    private static Map<String, String> leaderWriteSources() throws IOException {
        var out = new TreeMap<String, String>();

        sources().forEach((name, text) -> out.put(name, strip(text)));
        for (var name : LEADER_WIRING) {
            out.put("wiring/" + name, strip(Files.readString(WIRING.resolve(name))));
        }

        return out;
    }

    /// Every `KVCommand` that can be handed to the cluster: the permitted subclasses of the sealed interface, read by reflection,
    /// so a command type added later is covered without editing this test.
    private static List<String> commandTypes() {
        return Stream.of(KVCommand.class.getPermittedSubclasses())
                     .map(Class::getSimpleName)
                     .sorted()
                     .toList();
    }

    /// Each construction of a `KVCommand` (`new X(..)`, `new X<..>(..)`, `X::new`, qualified or imported) and of a
    /// `ClusterEffect.Submit`, as `file: Type`.
    static List<String> commandConstructions(Map<String, String> stripped) {
        var found = new ArrayList<String>();

        stripped.forEach((name, text) -> {
            for (var type : commandTypes()) {
                var imported = Pattern.compile("import\\s+[\\w.]*KVCommand\\s*\\.\\s*(" + type + "|\\*)\\s*;").matcher(text).find();
                var qualifier = imported
                                ? "(?:KVCommand\\s*\\.\\s*)?"
                                : "KVCommand\\s*\\.\\s*";
                found.addAll(matches(name, type, text, qualifier + type));
            }

            var submitImported = Pattern.compile("import\\s+[\\w.]*ClusterEffect\\s*\\.\\s*(Submit|\\*)\\s*;").matcher(text).find();
            found.addAll(matches(name,
                                 "Submit",
                                 text,
                                 (submitImported
                                  ? "(?:ClusterEffect\\s*\\.\\s*)?"
                                  : "ClusterEffect\\s*\\.\\s*") + "Submit"));
        });

        return found;
    }

    private static List<String> matches(String name, String type, String text, String typePattern) {
        var found = new ArrayList<String>();
        var construction = Pattern.compile("new\\s+" + typePattern + "\\s*(?:<[^()]*>)?\\s*\\(|\\b" + typePattern + "\\s*(?:<[^()]*>)?\\s*::\\s*new\\b");
        var matcher = construction.matcher(text);

        while (matcher.find()) {
            found.add(name + ": " + type);
        }

        return found;
    }

    /// Blanks comments, string literals, text blocks and char literals (a mention there is not a construction).
    static String strip(String source) {
        var out = new StringBuilder(source.length());
        var i = 0;

        while (i < source.length()) {
            if (source.startsWith("//", i)) {
                i = blank(source, out, i, source.indexOf('\n', i), 0);
            } else if (source.startsWith("/*", i)) {
                i = blank(source, out, i, source.indexOf("*/", i + 2), 2);
            } else if (source.startsWith("\"\"\"", i)) {
                i = blank(source, out, i, source.indexOf("\"\"\"", i + 3), 3);
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                i = blank(source, out, i, literalEnd(source, i), 1);
            } else {
                out.append(source.charAt(i++));
            }
        }

        return out.toString();
    }

    private static int literalEnd(String source, int open) {
        var quote = source.charAt(open);

        for (var i = open + 1; i < source.length(); i++) {
            if (source.charAt(i) == '\\') {
                i++;
            } else if (source.charAt(i) == quote) {
                return i;
            }
        }

        return source.length();
    }

    private static int blank(String source, StringBuilder out, int from, int found, int closerLength) {
        var end = found < 0
                  ? source.length()
                  : Math.min(source.length(), found + closerLength);

        for (var i = from; i < end; i++) {
            out.append(source.charAt(i) == '\n'
                       ? '\n'
                       : ' ');
        }

        return end;
    }

    // CF-1 (#2759, mirror of #2614's S1-S5 scan)
    // Red when: any cluster FSM class or the leader-side wiring other than `Outbox` builds a KVCommand or a Submit, or `Outbox` builds
    // anything but its one LeaderTransaction and its one Submit.
    @Test
    void everyKvCommandLeavesThroughOutbox() throws IOException {
        var real = leaderWriteSources();

        assertThat(real).as("the scan sees the package and the leader wiring")
                        .containsKeys("Outbox.java", "Blueprints.java", "wiring/ClusterFsmInterpreter.java", "wiring/Submitter.java");
        assertThat(commandTypes()).as("the reflection sees the command types").contains("LeaderTransaction", "Put", "Remove");
        assertThat(commandConstructions(real)).containsExactlyInAnyOrder("Outbox.java: LeaderTransaction", "Outbox.java: Submit");
    }

    // CF-1, the reflection half (mirror of #2614's F1-F4)
    // Red when: a Submit can carry a command that is not a LeaderTransaction, so a write could leave without the leader witness.
    @Test
    void aSubmitCarriesOnlyALeaderTransaction() {
        var components = ClusterEffect.Submit.class.getRecordComponents();

        assertThat(Stream.of(components)
                         .filter(component -> KVCommand.class.isAssignableFrom(component.getType()))
                         .map(component -> component.getType()
                                                    .getName())
                         .toList())
                  .as("the one command a Submit carries")
                  .containsExactly(KVCommand.LeaderTransaction.class.getName());
    }

    // CF-1, positive control
    // Red when: the scan misses a planted construction in any of its shapes, or flags a mention in a comment or a string.
    @Test
    void theOutboxScan_seesPlantedConstructions_andIgnoresComments() {
        var planted = new TreeMap<String, String>();

        planted.put("Qualified.java", strip("class Q { Object a = new KVCommand.Put<>(k, v); Object b = new KVCommand.Remove<AetherKey>(k, w); }"));
        planted.put("Imported.java",
                    strip("import org.pragmatica.cluster.state.kvstore.KVCommand.LeaderTransaction;\n"
                          + "class I { Object t = new LeaderTransaction<>(k, c, l, g, m); }"));
        planted.put("Reference.java", strip("class R { Fn f = KVCommand.Put::new; Object s = new ClusterEffect.Submit(a, b, c); }"));
        planted.put("Comment.java",
                    strip("class C { // new KVCommand.Put<>(k, v)\n /* new ClusterEffect.Submit( */ String s = \"new KVCommand.Remove<>(\"; }"));
        planted.put("Unimported.java", strip("class U { Object p = new Put(k, v); }"));

        assertThat(commandConstructions(planted)).containsExactlyInAnyOrder("Qualified.java: Put",
                                                                            "Qualified.java: Remove",
                                                                            "Imported.java: LeaderTransaction",
                                                                            "Reference.java: Put",
                                                                            "Reference.java: Submit");
    }
}
