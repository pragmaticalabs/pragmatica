// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;

/// #2981 steps 4-5 registered operator codes that the lease steps (1-3) are meant to raise. This tripwire holds them in an
/// owed map (code -> owing step) and asserts, against the production sources, that none is raised yet (current behaviour).
/// The step that starts raising one fails this test and must remove the code from the map. The scan is validated inside the
/// test: a code that IS raised today must be detected by the same scan, and the unraised codes of the registered families must equal the map.
class OwedWarningCodesTripwireTest {
    private static final Map<OperatorWarningCode, String> OWED = owed();
    /// A code raised today by `SwimProtocol` (static import), the positive control of the scan.
    private static final OperatorWarningCode RAISED_CONTROL = OperatorWarningCode.SWIM_KILL_GATE_HELD;

    private static final List<String> FAMILY_PREFIXES = List.of("STREAM_OWNER_LEASE_", "NODE_CLOCK_", "STREAM_TRUNCATION_");

    private static Map<OperatorWarningCode, String> owed() {
        // Empty since S8 (step 2, truncation below visible) raised the last three; kept so a future registered-but-unraised code has a home.
        return new LinkedHashMap<>();
    }

    @Test
    void owedCodes_areNotRaisedAnywhereInProduction_yet() throws IOException {
        var sources = productionSources();
        var raisedNow = new ArrayList<String>();

        OWED.forEach((code, step) -> {
            if (isReferenced(sources, code)) {
                raisedNow.add("step(s) %s now raise(s) %s: remove it from the owed map".formatted(step, code));
            }
        });
        assertThat(raisedNow).as("a step now raises an owed code: remove it from the owed map").isEmpty();
    }

    /// Positive control: the scan finds a code that is raised today, so an empty result above is not a dead scan.
    @Test
    void scan_detectsACodeThatIsRaisedToday() throws IOException {
        var sources = productionSources();

        assertThat(sources).as("the scan must read production sources").hasSizeGreaterThan(100);
        assertThat(isReferenced(sources, RAISED_CONTROL)).as("control code %s must be detected as raised", RAISED_CONTROL)
                                                         .isTrue();
    }

    /// Count what must not be there, over the families #2981 registered (lease, clock-rate, truncation): every declared code
    /// of those families with no raise site is in the owed map, so a new unraised sibling cannot slip in unowed and a map
    /// entry cannot be dropped silently. Other subsystems raise codes by name through catalogues (`FsmWarningCatalogueTest`
    /// owns the deployment ones), so a constant-reference scan over them says nothing and they are out of scope here.
    @Test
    void everyUnraisedCode_isInTheOwedMap() throws IOException {
        var sources = productionSources();
        var unraised = EnumSet.noneOf(OperatorWarningCode.class);

        Arrays.stream(OperatorWarningCode.values())
              .filter(OwedWarningCodesTripwireTest::isOwedFamily)
              .filter(code -> !isReferenced(sources, code))
              .forEach(unraised::add);
        assertThat(unraised).as("declared codes with no production raise site must each have an owing step in the owed map")
                            .containsExactlyInAnyOrderElementsOf(OWED.keySet());
    }

    private static boolean isOwedFamily(OperatorWarningCode code) {
        return FAMILY_PREFIXES.stream().anyMatch(prefix -> code.name().startsWith(prefix));
    }

    private static boolean isReferenced(List<String> sources, OperatorWarningCode code) {
        var pattern = Pattern.compile("\\b" + code.name() + "\\b");

        return sources.stream().anyMatch(text -> pattern.matcher(text).find());
    }

    /// Comment-stripped text of every production Java file under `aether/` and `integrations/`, except the enum itself.
    private static List<String> productionSources() throws IOException {
        var root = repositoryRoot();
        var texts = new ArrayList<String>();

        for (var top : List.of("aether", "integrations")) {
            try (Stream<Path> walk = Files.walk(root.resolve(top))) {
                var files = walk.filter(path -> path.toString().endsWith(".java"))
                                .filter(path -> path.toString().contains("/src/main/java/"))
                                .filter(path -> !path.endsWith("OperatorWarningCode.java"))
                                .toList();

                for (var file : files) {
                    texts.add(stripComments(Files.readString(file)));
                }
            }
        }
        return texts;
    }

    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static Path repositoryRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null && !Files.isDirectory(dir.resolve("integrations/utility"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root above the working directory").isNotNull();
        return dir;
    }
}
