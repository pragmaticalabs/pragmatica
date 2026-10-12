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
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// NT-89 (a), over the new package only (R-1.6.5): the single-writer discipline of the cluster FSM, read from its source.
///
/// - Every leader write is a `LeaderTransaction`: no source constructs a bare `KVCommand.Put`.
/// - A `SliceDesiredKey` mutation that WRITES a value is built in `Outbox` only. The other users of the key remove it.
/// - A `SliceObservedKey` is never written: the files that mention `observedKey()` (departure cleanup, directive removal) only
///   remove it, and the guards in `Outbox` only read it.
class ArchitectureTest {
    private static final Path SOURCES = Path.of("..", "aether-deployment", "src", "main", "java", "org", "pragmatica", "aether", "deployment", "cluster", "fsm2");

    private static Map<String, String> sources() throws IOException {
        var out = new TreeMap<String, String>();

        try (Stream<Path> files = Files.list(SOURCES)) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                out.put(file.getFileName().toString(), Files.readString(file));
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

        for (var marker : new String[] {"desiredKey()", "observedKey()", "new SliceObservedKey", "new SliceDesiredKey"}) {
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
            } else if (c == '(' && depth-- == 0) {
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

        assertThat(real).as("the scan sees the package").containsKeys("Outbox.java", "DepartureFlow.java", "Directory.java", "Gc.java");
        assertThat(violations(real)).isEmpty();
    }

    // NT-89 (a), positive control
    // Red when: the scan cannot see a planted violation.
    @Test
    void theScan_seesPlantedViolations() throws IOException {
        var planted = new TreeMap<>(sources());

        planted.put("Planted.java", "class Planted { Object x = new KVCommand.Put<>(pair.observedKey(), row); }");
        planted.put("Planted2.java", "class Planted2 { Object x = new KVCommand.Mutation<>(pair.desiredKey(), Option.some(a), Option.some(b)); }");

        assertThat(violations(planted)).anyMatch(v -> v.startsWith("Planted.java: constructs a bare Put"))
                                       .anyMatch(v -> v.startsWith("Planted2.java: desiredKey()"));
    }
}
