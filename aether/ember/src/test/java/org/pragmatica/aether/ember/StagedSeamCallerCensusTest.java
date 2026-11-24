// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.pragmatica.testlogging.SourceWalk;

import static org.assertj.core.api.Assertions.assertThat;

/// #3031 fix round 1 (v3031-report A6): the test seams of #3021 steps 0-1 live in shipped classes, so their reach is pinned.
/// `HttpRoutePublisher.holdRoutePublication` / `releaseRoutePublication` are called from one main class, `EmberCluster`;
/// `StagedPreCheck.bypassed()` from none. A new main caller fails here and has to be a deliberate, reviewed decision.
///
/// Scanned: every `*.java` under any `src/main/java` of the repository (test sources, `target/` and `.m2-local/` excluded),
/// comments stripped first so a mention in prose is not a caller. The one file that DECLARES a seam is excluded for it.
/// Limit: the comment stripper is a regex, so a `//` inside a string literal drops the rest of that line; it can hide a
/// call written after such a literal on the same line, and nothing else.
class StagedSeamCallerCensusTest {
    private static final Pattern HOLD_CALL = Pattern.compile("\\bholdRoutePublication\\b");
    private static final Pattern RELEASE_CALL = Pattern.compile("\\breleaseRoutePublication\\b");
    private static final Pattern BYPASSED_CALL = Pattern.compile("StagedPreCheck\\s*(\\.\\s*|::\\s*)bypassed\\b");
    private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");

    @Test
    void holdRoutePublication_hasOneMainCaller_emberCluster() {
        assertThat(callers(HOLD_CALL, "HttpRoutePublisher.java")).as("main files calling holdRoutePublication")
                  .containsExactly("EmberCluster.java");
    }

    @Test
    void releaseRoutePublication_hasOneMainCaller_emberCluster() {
        assertThat(callers(RELEASE_CALL, "HttpRoutePublisher.java")).as("main files calling releaseRoutePublication")
                  .containsExactly("EmberCluster.java");
    }

    @Test
    void stagedPreCheckBypassed_hasNoMainCaller() {
        assertThat(callers(BYPASSED_CALL, "StagedPreCheck.java")).as("main files calling StagedPreCheck.bypassed")
                  .isEmpty();
    }

    /// Positive controls: the scan reaches the real corpus (a vacuous empty scan would pass the third test), the patterns
    /// match the three call forms they exist for, and a mention in a comment is not a caller.
    @Test
    void census_readsTheMainCorpus_andItsPatternsMatchTheCallForms() {
        assertThat(mainSources().size()).as("main source files scanned").isGreaterThan(500);
        assertThat(HOLD_CALL.matcher("publisher.holdRoutePublication();").find()).isTrue();
        assertThat(HOLD_CALL.matcher("HttpRoutePublisher::holdRoutePublication").find()).isTrue();
        assertThat(BYPASSED_CALL.matcher("var c = StagedPreCheck.bypassed();").find()).isTrue();
        assertThat(BYPASSED_CALL.matcher("Supplier<X> s = StagedPreCheck::bypassed;").find()).isTrue();
        assertThat(COMMENTS.matcher("a(); // publisher.holdRoutePublication()\nb();").replaceAll(" ")).doesNotContain("hold");
        assertThat(COMMENTS.matcher("/* StagedPreCheck.bypassed() */ c();").replaceAll(" ")).doesNotContain("bypassed");
    }

    private static List<String> callers(Pattern call, String declaringFile) {
        return mainSources().entrySet()
                            .stream()
                            .filter(entry -> !entry.getKey().equals(declaringFile))
                            .filter(entry -> call.matcher(entry.getValue()).find())
                            .map(Map.Entry::getKey)
                            .sorted()
                            .toList();
    }

    private static Map<String, String> mainSources() {
        var root = Path.of("").toAbsolutePath().getParent().getParent();

        assertThat(root.resolve("aether/ember/src/main/java")).isDirectory();

        try {
            return SourceWalk.files(root)
                             .stream()
                             .filter(path -> path.toString().endsWith(".java") && path.toString().contains("/src/main/java/"))
                             .collect(Collectors.toMap(path -> path.getFileName().toString(),
                                                       StagedSeamCallerCensusTest::strippedContent,
                                                       (first, second) -> first + "\n" + second));
        } catch (IOException e) {
            throw new AssertionError("Cannot walk main sources under " + root, e);
        }
    }

    private static String strippedContent(Path path) {
        try {
            return COMMENTS.matcher(Files.readString(path)).replaceAll(" ");
        } catch (IOException e) {
            throw new AssertionError("Cannot read " + path, e);
        }
    }
}
