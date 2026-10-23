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
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #1842 absence tripwire (phase-3 design 12 item 4b; CTO ruling 9f854db8b). Every termination authority must remove a node's
/// `NodeDepartureKey` in the SAME transaction as its `ActivationDirectiveKey`. Bound today: CPR COMPLETE, the CTM's confirmed reap (B18) and the
/// operator `--force` clear (B19), through `TerminationRetirement`. #1842 (the node source owning the instance lifecycle) has no
/// directive-deleting writer yet.
///
/// The scan: every main source of `aether-deployment` and `aether/node` that names `ActivationDirectiveKey` next to a removal construct. The
/// set is frozen as of the binding, each file with the reason it is there. A NEW file is red: if it is #1842's writer, it must retire the
/// directive and the record together (`TerminationRetirement.directiveAndRecord`) before it joins the list. **Limit:** a new deleting site
/// inside a listed file is not seen; the per-writer W-tests cover the bound ones.
class DirectiveDeletersTripwireTest {
    private static final List<Path> ROOTS = List.of(Path.of("src", "main", "java"),
                                                    Path.of("..", "aether-deployment", "src", "main", "java"));
    private static final Pattern REMOVAL = Pattern.compile("Remove<|addRemoval\\(|Option\\.none\\(\\)\\)|removal\\(");
    private static final Map<String, String> FROZEN = Map.ofEntries(Map.entry("ClusterDeploymentState.java", "LEGACY: deletes the directive at NodeRemoved (B9), unbound under FSM2"),
                                                                     Map.entry("ClusterDeploymentManager.java", "LEGACY: the onActivationDirectiveRemove route target (B1), a reader"),
                                                                     Map.entry("ClusterDeploymentEvents.java", "LEGACY: the remove event type, a reader"),
                                                                     Map.entry("CommunityPlacementReconciler.java", "CPR COMPLETE: directive, placement and record in one transaction (4b)"),
                                                                     Map.entry("TerminationRetirement.java", "B18/B19: directive and record in one transaction (4b)"),
                                                                     Map.entry("Directory.java", "fsm2: the directive writer (J5) and its R-4.3.3 directive-removal with the record"),
                                                                     Map.entry("Indexer.java", "fsm2: the mirror of committed removes, a reader"),
                                                                     Map.entry("Outbox.java", "fsm2: re-reads the expected values of its drafts, a reader"),
                                                                     Map.entry("ClusterConfigRoutes.java", "management: a read witness, a reader"),
                                                                     Map.entry("NodeLifecycleRoutes.java", "management: reads the directive, a reader"),
                                                                     Map.entry("AetherNode.java", "the LEGACY route registration (B1), a reader"),
                                                                     Map.entry("WorkerHierarchyAssembly.java", "the worker's own onRemove reaction, a reader"),
                                                                     Map.entry("Fsm2Routes.java", "the FSM2 KV routes, a reader"),
                                                                     Map.entry("GovernorAuthority.java", "a read witness, a reader"));

    private static TreeSet<String> deleters(List<Path> roots) {
        var found = new TreeSet<String>();

        for (var root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(file -> file.toString().endsWith(".java"))
                     .filter(DirectiveDeletersTripwireTest::namesAndRemoves)
                     .forEach(file -> found.add(file.getFileName().toString()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        return found;
    }

    private static boolean namesAndRemoves(Path file) {
        try {
            var text = Files.readString(file, StandardCharsets.UTF_8);

            return text.contains("ActivationDirectiveKey") && REMOVAL.matcher(text).find();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void noDirectiveDeletingWriter_appearsOutsideTheBoundAuthorities_until1842BindsItsRetirement() {
        var found = deleters(ROOTS);

        assertThat(found).as("the scan reaches both modules").contains("CommunityPlacementReconciler.java", "AetherNode.java");
        assertThat(found).as("#1842: a new file removes an ActivationDirectiveKey. If it is the node-source termination authority, retire the "
                             + "directive and the NodeDepartureKey in ONE transaction (TerminationRetirement.directiveAndRecord, design 12 item 4b), "
                             + "then add it here with its reason")
                         .isSubsetOf(FROZEN.keySet());
    }

    @Test
    void aPlantedDeleter_isFlagged(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve("SourceTermination.java"),
                          "class SourceTermination {\n    Object c = new KVCommand.Remove<>(ActivationDirectiveKey.activationDirectiveKey(node));\n}\n");

        assertThat(deleters(List.of(temp))).containsExactly("SourceTermination.java");
        assertThat(FROZEN).doesNotContainKey("SourceTermination.java");
    }
}
