// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.aether.config.JvmVulnerabilityGate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


/// #3806 (CVE-2026-114938, Java4Shell) — every image definition pins a Temurin at or above the floor, and fails its own build
/// below it. The floor has ONE Java home, [JvmVulnerabilityGate#JDK_FLOOR]; the Dockerfiles cannot import it (their build contexts
/// differ), so this test is the tie: a Dockerfile whose `ARG JDK_FLOOR` drifts from the constant, whose base tag names a JDK
/// below it, or whose build-time check line differs from its siblings, is red here. The check itself is proven by building
/// (below-floor base: the build fails), see the PR; this test pins that each definition CARRIES it and that the four carry the same one.
///
/// WHY TEXT PINS: the definitions are Dockerfiles and a heredoc inside `build-and-push.sh`, with no Java seam. The subject under
/// test is the file on disk, read from the repository, as in `NodeLauncherExitOnOomPinnedTest`.
class JdkFloorPinnedTest {
    private static final String NODE = "aether/docker/aether-node/Dockerfile";
    private static final String FORGE = "aether/docker/aether-forge/Dockerfile";
    private static final String FORGE_LEGACY = "aether/forge/Dockerfile";
    private static final String BUILD_AND_PUSH = "build-and-push.sh";
    private static final List<String> DEFINITIONS = List.of(NODE, FORGE, FORGE_LEGACY, BUILD_AND_PUSH);

    private static final Pattern BASE = Pattern.compile("(?m)^ARG JDK_BASE=eclipse-temurin:(\\d+(?:\\.\\d+)*)_\\d+-(?:jdk|jre)-\\S+$");

    private static final Pattern FLOOR = Pattern.compile("(?m)^ARG JDK_FLOOR=(\\S+)$");
    private static final Pattern CHECK = Pattern.compile("(?m)^RUN v=.*$");
    private static final Pattern FROM_LINES = Pattern.compile("(?m)^FROM .*$");

    @Test
    void everyDefinition_declaresTheSameFloorAsTheStartUpGate() {
        for (var path : DEFINITIONS) {
            var matcher = FLOOR.matcher(read(path));

            assertThat(matcher.find()).as("%s declares ARG JDK_FLOOR", path).isTrue();
            assertThat(matcher.group(1)).as("%s: ARG JDK_FLOOR equals JvmVulnerabilityGate.JDK_FLOOR", path)
                      .isEqualTo(JvmVulnerabilityGate.JDK_FLOOR);
        }
    }

    @Test
    void everyDefinition_pinsABaseTagAtOrAboveTheFloor() {
        var floor = Runtime.Version.parse(JvmVulnerabilityGate.JDK_FLOOR);

        for (var path : DEFINITIONS) {
            var matcher = BASE.matcher(read(path));

            assertThat(matcher.find()).as("%s pins ARG JDK_BASE=eclipse-temurin:<exact version>_<build>-<jdk|jre>-<variant>, not a floating tag",
                                          path)
                      .isTrue();
            assertThat(Runtime.Version.parse(matcher.group(1)).compareToIgnoreOptional(floor)).as("%s: the pinned JDK %s is at or above the floor %s",
                                                                                                  path,
                                                                                                  matcher.group(1),
                                                                                                  floor)
                      .isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void everyDefinition_buildsFromTheArgAndFromNoFloatingTemurinTag() {
        for (var path : DEFINITIONS) {
            var froms = FROM_LINES.matcher(read(path))
                                  .results()
                                  .map(result -> result.group()
                                                       .replace("\\$", "$"))
                                  .toList();

            assertThat(froms).as("%s: the JDK stage is FROM ${JDK_BASE}", path).contains("FROM ${JDK_BASE}");
            assertThat(froms).as("%s: no FROM names an eclipse-temurin tag directly", path)
                      .noneMatch(line -> line.contains("eclipse-temurin"));
        }
    }

    @Test
    void everyDefinition_failsItsOwnBuildBelowTheFloor_withTheSameCheck() {
        var checks = DEFINITIONS.stream().map(JdkFloorPinnedTest::checkLine).toList();

        for (var check : checks) {
            assertThat(check).as("the check reads the JDK's own java.version")
                      .contains("java -XshowSettings:properties -version")
                      .as("compares against the declared floor with a version sort")
                      .contains("sort -V")
                      .contains("\"$JDK_FLOOR\"")
                      .as("refuses on unreadable (-n guard) and below floor")
                      .contains("[ -n \"$v\" ]")
                      .contains("exit 1");
        }

        assertThat(checks).as("the four definitions carry the same check line").containsOnly(checks.getFirst());
    }

    /// The check line with the one escaping difference removed: inside the unquoted heredoc of `build-and-push.sh` every `$` is `\$`.
    private static String checkLine(String path) {
        var matcher = CHECK.matcher(read(path));

        assertThat(matcher.find()).as("%s carries the build-time JDK check (RUN v=...)", path).isTrue();

        return matcher.group()
                      .replace("\\$", "$");
    }

    private static String read(String relativePath) {
        try {
            return Files.readString(repositoryRoot().resolve(relativePath));
        } catch (Exception e) {
            return fail("could not read " + relativePath, e);
        }
    }

    private static Path repositoryRoot() {
        var current = codeSourceLocation();

        while (current != null) {
            if (Files.isRegularFile(current.resolve(NODE)) && Files.isRegularFile(current.resolve(BUILD_AND_PUSH))) {
                return current;
            }

            current = current.getParent();
        }

        return fail("repository root not found above " + codeSourceLocation()
                   + ": no ancestor holds both " + NODE
                   + " and " + BUILD_AND_PUSH);
    }

    private static Path codeSourceLocation() {
        try {
            return Path.of(JdkFloorPinnedTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            return fail("cannot locate the test's own code source", e);
        }
    }
}
