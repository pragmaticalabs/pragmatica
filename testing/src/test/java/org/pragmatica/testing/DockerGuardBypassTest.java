/*
 *  Copyright (c) 2025-2026 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.pragmatica.testing;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// Fails the build when a test guards on Docker without going through [DockerRequirement] / [RequiresDocker] (#3089):
/// a raw availability probe or the testcontainers skip switch would turn "no Docker" back into a silent pass under
/// `-Daether.requireDocker=true`.
class DockerGuardBypassTest {
    private static final Pattern BYPASS = Pattern.compile("isDockerAvailable|disabledWithoutDocker");
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("target", ".git", ".m2-local", "node_modules");
    private static final List<Path> ALLOWED_FILES = List.of(Path.of("org/pragmatica/testing/DockerRequirement.java"),
                                                            Path.of("org/pragmatica/testing/DockerGuardBypassTest.java"));

    @Test
    void repository_hasNoDockerGuardBypassingTheHelper() throws IOException {
        var root = repositoryRoot();

        assertThat(Files.isRegularFile(root.resolve("testing/src/main/java/org/pragmatica/testing/DockerRequirement.java")))
            .as("repository root %s must contain the helper (else the scan examines nothing)", root)
            .isTrue();
        assertThat(bypasses(root))
            .as("route every Docker guard through org.pragmatica.testing.DockerRequirement or @RequiresDocker")
            .isEmpty();
    }

    @Test
    void bypasses_plantedRawProbe_isReported(@TempDir Path root) throws IOException {
        var planted = Files.createDirectories(root.resolve("m/src/test/java")).resolve("PlantedTest.java");

        Files.writeString(planted, "class PlantedTest { boolean b = DockerClientFactory.instance().isDockerAvailable(); }");

        assertThat(bypasses(root)).containsExactly(planted);
    }

    @Test
    void bypasses_plantedSkipSwitch_isReported(@TempDir Path root) throws IOException {
        var planted = Files.createDirectories(root.resolve("m/src/test/java")).resolve("PlantedTest.java");

        Files.writeString(planted, "@Testcontainers(disabledWithoutDocker = true) class PlantedTest {}");

        assertThat(bypasses(root)).containsExactly(planted);
    }

    @Test
    void bypasses_helperUseAndBuildOutput_isNotReported(@TempDir Path root) throws IOException {
        Files.writeString(Files.createDirectories(root.resolve("m/src/test/java")).resolve("OkTest.java"),
                          "class OkTest { boolean b = DockerRequirement.dockerAvailable(); }");
        Files.writeString(Files.createDirectories(root.resolve("m/target/generated")).resolve("Gen.java"),
                          "class Gen { boolean b = x.isDockerAvailable(); }");

        assertThat(bypasses(root)).isEmpty();
    }

    private static Path repositoryRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null && !Files.isDirectory(dir.resolve("testing/src/main/java"))) {
            dir = dir.getParent();
        }
        return dir == null ? Path.of("").toAbsolutePath() : dir;
    }

    private static List<Path> bypasses(Path root) throws IOException {
        var found = new ArrayList<Path>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())
                       ? FileVisitResult.SKIP_SUBTREE
                       : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (isGuardedSource(file) && BYPASS.matcher(Files.readString(file)).find()) {
                    found.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found;
    }

    private static boolean isGuardedSource(Path file) {
        return file.getFileName().toString().endsWith(".java") && ALLOWED_FILES.stream().noneMatch(file::endsWith);
    }
}
