// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// W-32 (phase-3 design 8.3, D-6, G-5), the source-scan architecture test. No engine-dependent target (`NodeDeploymentManager`,
/// `ClusterDeploymentManager`, `NodeFsmInterpreter`, `ClusterFsmInterpreter`, `EngineMarkerGuard`) may be referenced from `aether/node` main
/// sources outside the wiring package (`org.pragmatica.aether.node.deployment`, where `DeploymentWiring` lives) except through the frozen
/// `wiring-allowlist.txt`. The registry in `DeploymentWiring` sees only what is bound through it, so a hook bound by a direct call would escape
/// W-15; this is what catches it.
///
/// **The list may only shrink.** A reference not on it, a new file, or a grown count is red. The FSM2-side targets have no entry at all.
/// The scan reports the file and reference counts it covered, and zero files fails it. MT-27: a direct `ndm` call planted in `AetherNode`.
/// Positive controls: a planted direct call in a file the list has never heard of, and a planted extra reference in an allow-listed file.
class WiringArchitectureTest {
    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Path WIRING_PACKAGE = Path.of("org", "pragmatica", "aether", "node", "deployment");
    private static final Path ALLOWLIST = Path.of("src", "test", "resources", "fsm2", "wiring-allowlist.txt");
    private static final Pattern TARGET = Pattern.compile("(?i)(node|cluster)DeploymentManager|(node|cluster)FsmInterpreter|EngineMarkerGuard");

    /// What a scan covered and what it found outside the list.
    private record Scan(long files, long references, List<String> violations) {}

    private record Allowed(int count, String reason) {}

    private static String key(String file, String line) {
        return file + "|" + line;
    }

    private static Map<String, Allowed> allowlist() {
        var allowed = new HashMap<String, Allowed>();

        try {
            for (var line : Files.readAllLines(ALLOWLIST, StandardCharsets.UTF_8)) {
                if (!line.startsWith("#") && !line.isBlank()) {
                    var parts = line.split("\\|", 4);

                    allowed.put(key(parts[1], parts[3]), new Allowed(Integer.parseInt(parts[0]), parts[2]));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return allowed;
    }

    private static Scan scan(Path root, Map<String, Allowed> allowed) {
        var counts = new HashMap<String, Integer>();
        long files = 0;
        long references = 0;

        try (Stream<Path> paths = Files.walk(root)) {
            for (var file : paths.filter(path -> path.toString().endsWith(".java")).filter(path -> !root.relativize(path).startsWith(WIRING_PACKAGE)).toList()) {
                files++;
                for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (TARGET.matcher(line).find()) {
                        references++;
                        counts.merge(key(file.getFileName().toString(), String.join(" ", line.trim().split("\\s+"))), 1, Integer::sum);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        var violations = new ArrayList<String>();

        counts.forEach((entry, count) -> {
            var limit = allowed.getOrDefault(entry, new Allowed(0, "not on the list")).count();

            if (count > limit) {
                violations.add(entry + "  (found " + count + ", allowed " + limit + ")");
            }
        });

        return new Scan(files, references, violations);
    }

    @Test
    void noEngineDependentTarget_isReferencedOutsideTheWiring_exceptTheFrozenList() {
        var scan = scan(MAIN, allowlist());

        assertThat(scan.files()).as("the scan covered the module's main sources outside the wiring package").isGreaterThan(100);
        assertThat(scan.references()).as("and found the references the list was frozen from").isGreaterThanOrEqualTo(60);
        assertThat(scan.violations()).as("references off the frozen allow-list").isEmpty();
    }

    /// The list may only shrink: every entry it carries is still there, with at least the count it allows. A stale entry is a hiding place.
    @Test
    void theFrozenListHasNoStaleEntries() {
        var allowed = allowlist();
        var found = new HashMap<String, Integer>();

        try (Stream<Path> paths = Files.walk(MAIN)) {
            for (var file : paths.filter(path -> path.toString().endsWith(".java")).filter(path -> !MAIN.relativize(path).startsWith(WIRING_PACKAGE)).toList()) {
                for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (TARGET.matcher(line).find()) {
                        found.merge(key(file.getFileName().toString(), String.join(" ", line.trim().split("\\s+"))), 1, Integer::sum);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        allowed.forEach((entry, limit) -> assertThat(found.getOrDefault(entry, 0)).as("allow-list entry still used: " + entry).isEqualTo(limit.count()));
    }

    @Test
    void aPlantedDirectCall_inAFileTheListNeverHeardOf_isFlagged(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve("Planted.java"), "class Planted {\n    void f() {\n        ndm.nodeDeploymentManager.onQuorumStateChange(null);\n    }\n}\n");

        var scan = scan(temp, allowlist());

        assertThat(scan.files()).isEqualTo(1);
        assertThat(scan.violations()).hasSize(1).first().asString().contains("Planted.java").contains("allowed 0");
    }

    /// MT-27: an extra reference planted in a file that IS on the list (`AetherNode`), with the same text as an allowed line: the count grew.
    @Test
    void aPlantedExtraReference_inAnAllowListedFile_isFlagged(@TempDir Path temp) throws IOException {
        var allowed = allowlist();
        var listed = allowed.keySet().stream().filter(entry -> entry.startsWith("AetherNode.java|")).findFirst().orElseThrow();
        var line = listed.substring(listed.indexOf('|') + 1);

        Files.writeString(temp.resolve("AetherNode.java"), line + "\n" + line + "\n");

        var scan = scan(temp, allowed);

        assertThat(scan.violations()).as("the second copy exceeds the allowed count").hasSize(1);
        assertThat(allowed.get(listed).count()).isEqualTo(1);
    }

    @Test
    void anFsm2SideTarget_hasNoAllowListEntry() {
        assertThat(allowlist().keySet()).noneMatch(entry -> entry.toLowerCase().matches(".*(node|cluster)fsminterpreter.*|.*enginemarkerguard.*"));
    }

    /// N14 (v-2204 F8, text-level by design): the 5 s level-heal's consensus predicate is the node's own `runtimeReady(clusterNode)` at the assembly
    /// site, the one every other readiness check uses. A behavioural pin needs a node that drops its genuine ACTIVE edge, which no harness offers; this
    /// reddens the one-token change (`() -> false`, a constant, another predicate) that nothing else sees.
    @Test
    void theLevelHealsConsensusPredicate_isTheNodesRuntimeReady() throws IOException {
        var source = Files.readString(MAIN.resolve(Path.of("org", "pragmatica", "aether", "node", "AetherNode.java")), StandardCharsets.UTF_8);
        var call = source.indexOf("deploymentWiring.activationDriver(");

        assertThat(call).as("the assembly site calls activationDriver").isGreaterThanOrEqualTo(0);
        assertThat(source.substring(call, call + 120)).as("with the node's runtimeReady as the consensus predicate")
                                                      .matches("(?s)deploymentWiring\\.activationDriver\\(\\s*\\(\\) -> runtimeReady\\(clusterNode\\),.*");
        assertThat(source.indexOf("deploymentWiring.activationDriver(", call + 1)).as("and only there").isEqualTo(-1);
    }
}
