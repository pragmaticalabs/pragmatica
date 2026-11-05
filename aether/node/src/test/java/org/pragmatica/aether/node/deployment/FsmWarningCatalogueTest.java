// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.WarningLevel;

import static org.assertj.core.api.Assertions.assertThat;

/// Every code the deployment FSMs raise as a `Warn` effect is in the `OperatorWarningCode` catalogue, with subsystem `deployment` and level
/// WARNING (owner ruling s37; report 3.7 item 1, PR C obligation 4). The codes are read from the FSM sources: the first argument of `ctx.warn`,
/// the code of a `refuse(...)`, the `Warn` constructors, and the string constants those sites name. A new FSM warning without a catalogue entry
/// is red here, and the translator's unmapped path (`EffectTranslatorTest`) stays for a code that is not in the catalogue.
class FsmWarningCatalogueTest {
    private static final Path DEPLOYMENT = Path.of("..", "aether-deployment", "src", "main", "java", "org", "pragmatica", "aether", "deployment");
    private static final List<Path> FSM_SOURCES = List.of(DEPLOYMENT.resolve("cluster").resolve("fsm2"), DEPLOYMENT.resolve("node").resolve("machine"));
    private static final Pattern RAISED = Pattern.compile("(?:ctx\\.warn|ctx\\.warnResolved|refuse|new (?:Cluster|NodeFsm)Effect\\.Warn(?:Resolved)?)\\([^\"]*?\"([a-z0-9]+(?:-[a-z0-9]+)+)\"");
    /// Any kebab-case string constant, and (separately) a warn site, whose next 240 characters name the constant: a constant is a raised code when a
    /// warn site names it, whatever the constant is called (v-2204 N2: the list of three names this replaced hid a fourth).
    private static final Pattern CONSTANT = Pattern.compile("String\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*\"([a-z0-9]+(?:-[a-z0-9]+)+)\"");
    private static final Pattern WARN_SITE = Pattern.compile("(?:ctx\\.warn|ctx\\.warnResolved|refuse|new (?:Cluster|NodeFsm)Effect\\.Warn(?:Resolved)?)\\(");
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Z][A-Z0-9_]+\\b");

    /// The codes the sources under `roots` raise, and the number of files read.
    private record Scan(Set<String> codes, long files) {}

    private static Scan scan(List<Path> roots) {
        var codes = new TreeSet<String>();
        var constants = new HashMap<String, String>();
        var namedAtWarnSites = new HashSet<String>();
        long files = 0;

        for (var root : roots) {
            try (Stream<Path> paths = Files.walk(root)) {
                for (var file : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                    files++;
                    var text = Files.readString(file);

                    RAISED.matcher(text).results().forEach(match -> codes.add(match.group(1)));
                    CONSTANT.matcher(text).results().forEach(match -> constants.put(match.group(1), match.group(2)));
                    WARN_SITE.matcher(text)
                             .results()
                             .forEach(site -> IDENTIFIER.matcher(text.substring(site.end(), Math.min(text.length(), site.end() + 240)))
                                                        .results()
                                                        .forEach(name -> namedAtWarnSites.add(name.group())));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        namedAtWarnSites.stream().filter(constants::containsKey).forEach(name -> codes.add(constants.get(name)));

        return new Scan(codes, files);
    }

    @Test
    void everyCodeTheFsmsRaise_isInTheCatalogue_asADeploymentWarning() {
        var scan = scan(FSM_SOURCES);
        var catalogue = Arrays.stream(OperatorWarningCode.values()).collect(Collectors.toMap(OperatorWarningCode::code, code -> code));

        assertThat(scan.files()).as("the scan covered the FSM sources").isGreaterThan(40);
        assertThat(scan.codes()).as("the scan found the codes the FSMs raise").hasSizeGreaterThanOrEqualTo(24);
        assertThat(scan.codes()).as("codes raised by a FSM and missing from OperatorWarningCode").allMatch(catalogue::containsKey);
        scan.codes().forEach(code -> {
            assertThat(catalogue.get(code).subsystem()).as(code).isEqualTo("deployment");
            // A code that is the recovery of another keeps its INFO level (the catalogue's own convention, e.g. slice-start-deferral-ended).
            assertThat(catalogue.get(code).level()).as(code).isEqualTo(catalogue.get(code).recoveryOf().isPresent() ? WarningLevel.INFO : WarningLevel.WARNING);
        });
    }

    /// The engine marker guard's own warning (design 8.2): not raised by a FSM, so the scan above does not see it, and equally a deployment WARNING
    /// with the text an operator searches for.
    @Test
    void theEngineMismatchWarning_isADeploymentWarning_underItsStableCode() {
        var code = OperatorWarningCode.DEPLOYMENT_ENGINE_MISMATCH;

        assertThat(code.code()).isEqualTo("deployment-engine-mismatch");
        assertThat(code.subsystem()).isEqualTo("deployment");
        assertThat(code.level()).isEqualTo(WarningLevel.WARNING);
    }

    /// The positive control for constants: a warn site naming a constant with a name the scan has never heard of is found.
    @Test
    void aPlantedConstant_namedAtAWarnSite_isFound(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve("Planted.java"), "class Planted { static final String SOME_NEW_CODE = \"planted-new-constant\"; void f(Ctx ctx) { ctx.warn(SOME_NEW_CODE, \"x\", \"y\"); } }");

        assertThat(scan(List.of(temp)).codes()).containsExactly("planted-new-constant");
    }

    /// The positive control: a planted raise of a code no catalogue entry has is found by the same scan.
    @Test
    void aPlantedUncataloguedCode_isFound(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve("Planted.java"), "class Planted { void f(Ctx ctx) { ctx.warn(\"planted-not-in-catalogue\", \"x\", \"y\"); } }");

        var scan = scan(List.of(temp));

        assertThat(scan.codes()).containsExactly("planted-not-in-catalogue");
        assertThat(Arrays.stream(OperatorWarningCode.values()).map(OperatorWarningCode::code)).doesNotContain("planted-not-in-catalogue");
    }
}
