// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.pragmatica.aether.config.JvmVulnerabilityGate;
import org.pragmatica.testlogging.SourceWalk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


/// #3806 (CVE-2026-114938, Java4Shell) — every image definition pins a Temurin at or above the floor, and fails its own build
/// below it. The definitions are DISCOVERED, not listed: [#discovered] walks the repository (through `SourceWalk`, which prunes build output) for every `Dockerfile*` and for every
/// shell script that carries a `FROM` line, so a new image definition is a subject the day it is added. A definition that is not a
/// JDK image the floor applies to goes on [#EXCLUDED], each with its reason; an entry that no longer exists fails the test. The floor has ONE Java home, [JvmVulnerabilityGate#JDK_FLOOR]; the Dockerfiles cannot import it (their build contexts
/// differ), so this test is the tie: a Dockerfile whose `ARG JDK_FLOOR` drifts from the constant, whose base tag names a JDK
/// below it, or whose build-time check line differs from its siblings, is red here. The check itself is proven by building
/// (below-floor base: the build fails), see the PR; this test pins that each definition CARRIES it and that all carry the same one.
///
/// The same file pins that the node's `Main` and `ForgeServer` call the gate before reading any config.
///
/// WHY TEXT PINS: the definitions are Dockerfiles and a heredoc inside `build-and-push.sh`, with no Java seam. The subject under
/// test is the file on disk, read from the repository, as in `NodeLauncherExitOnOomPinnedTest`.
class JdkFloorPinnedTest {
    private static final String NODE = "aether/docker/aether-node/Dockerfile";
    private static final String FORGE = "aether/docker/aether-forge/Dockerfile";
    private static final String FORGE_LEGACY = "aether/forge/Dockerfile";
    private static final String BUILD_AND_PUSH = "build-and-push.sh";
    private static final String NODE_MAIN = "aether/node/src/main/java/org/pragmatica/aether/Main.java";
    private static final String FORGE_MAIN = "aether/forge/forge-core/src/main/java/org/pragmatica/aether/forge/ForgeServer.java";
    private static final String GATE_CALL = "JvmVulnerabilityGate.enforceForThisProcess();";

    /// Image definitions that are NOT a serving JDK image under the floor, each with the reason. A listed path that does not exist fails
    /// [#exclusionsAllExist]: an exclusion must never outlive its file.
    private static final Map<String, String> EXCLUDED = Map.of("tools/quickstart-check.sh",
                                                               "clean-install verification: a throwaway `maven:3.9-eclipse-temurin-25` build container, never shipped or served (#3806 report, INFO 6)");

    private static final Pattern DOCKERFILE_NAME = Pattern.compile("(?i)(dockerfile.*|.*\\.dockerfile|containerfile.*)");
    private static final Pattern FROM_LINE = Pattern.compile("(?m)^FROM ");

    private static final Pattern BASE = Pattern.compile("(?m)^ARG JDK_BASE=eclipse-temurin:(\\d+(?:\\.\\d+)*)_\\d+-(?:jdk|jre)-\\S+$");

    private static final Pattern FLOOR = Pattern.compile("(?m)^ARG JDK_FLOOR=(\\S+)$");
    private static final Pattern CHECK = Pattern.compile("(?m)^RUN v=.*$");
    private static final Pattern FROM_LINES = Pattern.compile("(?m)^FROM .*$");

    @Test
    void discovery_findsTheDefinitions_andNothingIsSilentlySkipped() {
        var found = discovered();

        assertThat(found).as("the walk found image definitions at all").isNotEmpty();
        assertThat(found).as("the known definitions are among them")
                  .contains(NODE, FORGE, FORGE_LEGACY, BUILD_AND_PUSH);
        assertThat(subjects()).as("every discovered definition is a subject unless explicitly excluded")
                  .containsExactlyInAnyOrderElementsOf(found.stream().filter(path -> !EXCLUDED.containsKey(path)).toList());
    }

    @Test
    void exclusionsAllExist_andEachStatesItsReason() {
        EXCLUDED.forEach((path, reason) -> {
            assertThat(Files.isRegularFile(repositoryRoot().resolve(path))).as("excluded %s still exists", path).isTrue();
            assertThat(reason).as("excluded %s states why", path).isNotBlank();
            assertThat(discovered()).as("excluded %s is a discovered definition (else the entry is dead)", path).contains(path);
        });
    }

    @Test
    void everyDefinition_declaresTheSameFloorAsTheStartUpGate() {
        for (var path : subjects()) {
            var matcher = FLOOR.matcher(read(path));

            assertThat(matcher.find()).as("%s declares ARG JDK_FLOOR", path).isTrue();
            assertThat(matcher.group(1)).as("%s: ARG JDK_FLOOR equals JvmVulnerabilityGate.JDK_FLOOR", path)
                      .isEqualTo(JvmVulnerabilityGate.JDK_FLOOR);
        }
    }

    @Test
    void everyDefinition_pinsABaseTagAtOrAboveTheFloor() {
        var floor = Runtime.Version.parse(JvmVulnerabilityGate.JDK_FLOOR);

        for (var path : subjects()) {
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
        for (var path : subjects()) {
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
        var checks = subjects().stream().map(JdkFloorPinnedTest::checkLine).toList();

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

        assertThat(checks).as("all definitions carry the same check line").containsOnly(checks.getFirst());
    }

    /// The start-up gate is only an enforcer if both entry points call it, and first: a listener binds only after the config is
    /// read, so "before the config is read" is "before any listener binds". Text pin for the same reason as above: `Main.run()` and
    /// `ForgeServer.main` start whole processes, so there is no seam to call them through; deleting or moving the call reaches no
    /// other test.
    @Test
    void bothMains_callTheGateOnceBeforeTheConfigIsRead() {
        assertCalledBefore(NODE_MAIN, "publishConfigPath();");
        assertCalledBefore(FORGE_MAIN, "StartupConfig.startupConfig(args)");
    }

    private static void assertCalledBefore(String path, String firstConfigStep) {
        var source = read(path);
        var call = source.indexOf(GATE_CALL);

        assertThat(call).as("%s calls the gate", path).isGreaterThanOrEqualTo(0);
        assertThat(source.indexOf(GATE_CALL, call + 1)).as("%s calls the gate exactly once", path).isEqualTo(-1);
        assertThat(source.indexOf(firstConfigStep)).as("%s: the gate runs before `%s`", path, firstConfigStep)
                  .isGreaterThan(call);
    }

    /// Every image definition in the repository, as a repository-relative path: any `Dockerfile*`, `*.dockerfile` or `Containerfile*`,
    /// and any `*.sh` with a `FROM` line (a definition embedded in a script, as `build-and-push.sh` carries one).
    private static List<String> discovered() {
        var root = repositoryRoot();

        try {
            var found = new ArrayList<String>();

            for (var file : SourceWalk.files(root)) {
                if (isDefinition(file)) {
                    found.add(root.relativize(file).toString().replace('\\', '/'));
                }
            }

            return found;
        } catch (IOException e) {
            return fail("could not walk " + root, e);
        }
    }

    private static boolean isDefinition(Path file) throws IOException {
        var name = file.getFileName().toString();

        return DOCKERFILE_NAME.matcher(name).matches()
               || name.endsWith(".sh") && FROM_LINE.matcher(Files.readString(file)).find();
    }

    private static List<String> subjects() {
        return discovered().stream().filter(path -> !EXCLUDED.containsKey(path)).toList();
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
