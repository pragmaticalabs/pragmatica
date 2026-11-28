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
/// operator `--force` clear (B19), through `TerminationRetirement`, and FSM2's C17b for a departed worker (`DepartureFlow#cleanup`, #3193, CTO
/// ruling 6b6de53d51 item 3), which removes both at their exact values. #1842 (the node source owning the instance lifecycle) has no
/// directive-deleting writer yet.
///
/// The scan: every main source of `aether-deployment` and `aether/node` that names `ActivationDirectiveKey` next to a removal construct. The
/// set is frozen as of the binding, each file with the reason it is there. A NEW file is red: if it is #1842's writer, it must retire the
/// directive and the record together (`TerminationRetirement.directiveAndRecord`) before it joins the list. **Limit:** a new deleting site
/// inside a listed file is not seen; the per-writer W-tests cover the bound ones.
///
/// **The receipt scan** (#3828, F2-1): the same question for `DirectiveReceiptKey`, over EVERY main source under `aether/` (each module's
/// `src/main/java`), not the two roots above and not only files that name `ActivationDirectiveKey`: a helper in any file, listed or not,
/// that names the receipt key next to a removal construct is red unless the file is in [#RECEIPT_FROZEN] with its reason. Today the only
/// deleter is `RetirementUnit#removals`, which removes it with the record and the directive at their exact values.
class DirectiveDeletersTripwireTest {
    private static final List<Path> ROOTS = List.of(Path.of("src", "main", "java"),
                                                    Path.of("..", "aether-deployment", "src", "main", "java"));
    private static final Pattern REMOVAL = Pattern.compile("Remove<|addRemoval\\(|Option\\.none\\(\\)\\)|removal\\(");
    private static final Path AETHER = Path.of("..");
    private static final Map<String, String> RECEIPT_FROZEN = Map.ofEntries(Map.entry("RetirementUnit.java", "the one deleter: the receipt leaves with the record and the directive, each at its exact value (#3828)"),
                                                                             Map.entry("Indexer.java", "fsm2: the mirror of committed removes of the receipt, a reader"),
                                                                             Map.entry("Outbox.java", "fsm2: re-reads the expected value of a draft's receipt key, a reader"),
                                                                             Map.entry("Fsm2Routes.java", "the FSM2 KV routes, a reader"),
                                                                             Map.entry("AetherKey.java", "the key type's declaration, no removal of its own"),
                                                                             Map.entry("AetherValue.java", "the value type's declaration, no removal of its own"));
    private static final Map<String, String> FROZEN = Map.ofEntries(Map.entry("ClusterDeploymentState.java", "LEGACY: deletes the directive at NodeRemoved (B9), unbound under FSM2"),
                                                                     Map.entry("ClusterDeploymentManager.java", "LEGACY: the onActivationDirectiveRemove route target (B1), a reader"),
                                                                     Map.entry("ClusterDeploymentEvents.java", "LEGACY: the remove event type, a reader"),
                                                                     Map.entry("CommunityPlacementReconciler.java", "CPR COMPLETE: directive, placement and record in one transaction (4b)"),
                                                                     Map.entry("TerminationRetirement.java", "B18/B19: directive and record in one transaction (4b)"),
                                                                     Map.entry("Directory.java", "fsm2: the directive writer (J5) and its R-4.3.3 directive-removal with the record"),
                                                                     Map.entry("DepartureFlow.java", "fsm2 C17b: a departed WORKER's directive and its record in one transaction, the record's exact-value removal the guard (4b, #3193)"),
                                                                     Map.entry("RetirementUnit.java", "fsm2: an id's record, directive and receipt removed in one transaction at their exact values (#3828)"),
                                                                     Map.entry("Indexer.java", "fsm2: the mirror of committed removes, a reader"),
                                                                     Map.entry("Outbox.java", "fsm2: re-reads the expected values of its drafts, a reader"),
                                                                     Map.entry("ClusterConfigRoutes.java", "management: a read witness, a reader"),
                                                                     Map.entry("NodeLifecycleRoutes.java", "management: reads the directive, a reader"),
                                                                     Map.entry("AetherNode.java", "the LEGACY route registration (B1), a reader"),
                                                                     Map.entry("OwnDirectiveRetirement.java", "the worker's halt on the removal of its own directive (#3193), a reader"),
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

    /// The files of every module's `src/main/java` under `root` that name the receipt key next to a removal construct.
    private static TreeSet<String> receiptDeleters(Path root) {
        var found = new TreeSet<String>();

        try (Stream<Path> files = Files.walk(root)) {
            files.filter(file -> file.toString().endsWith(".java"))
                 .filter(file -> file.toString().replace('\\', '/').contains("/src/main/java/"))
                 .filter(file -> namesAndRemoves(file, "DirectiveReceiptKey"))
                 .forEach(file -> found.add(file.getFileName().toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return found;
    }

    private static boolean namesAndRemoves(Path file) {
        return namesAndRemoves(file, "ActivationDirectiveKey");
    }

    private static boolean namesAndRemoves(Path file, String key) {
        try {
            var text = Files.readString(file, StandardCharsets.UTF_8);

            return text.contains(key) && REMOVAL.matcher(text).find();
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

    @Test
    void noReceiptDeletingWriter_appearsInAnyMainSource_outsideTheListedFiles() {
        var found = receiptDeleters(AETHER);

        assertThat(found).as("the scan reaches the deleter and a file of another module").contains("RetirementUnit.java", "AetherKey.java");
        assertThat(found).as("#3828 F2-1: a main source under aether/ names DirectiveReceiptKey next to a removal. The receipt leaves only with its "
                             + "record and directive, each at its exact value (RetirementUnit#removals); if this file is a new reader or a new "
                             + "deleter, add it to RECEIPT_FROZEN with its reason")
                         .isSubsetOf(RECEIPT_FROZEN.keySet());
    }

    @Test
    void aPlantedReceiptDeleter_inAnUnlistedFileThatNamesNoDirective_isFlagged(@TempDir Path temp) throws IOException {
        var main = Files.createDirectories(temp.resolve("src").resolve("main").resolve("java"));

        Files.writeString(main.resolve("Lifecycle.java"),
                          "class Lifecycle {\n    Object c = new KVCommand.Mutation<>(new DirectiveReceiptKey(node), Option.some(v), Option.none());\n}\n");

        assertThat(receiptDeleters(temp)).containsExactly("Lifecycle.java");
        assertThat(RECEIPT_FROZEN).doesNotContainKey("Lifecycle.java");
    }
}
