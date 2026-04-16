// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;


class IncludeResolverTest {

    @Nested
    class NoIncludes {

        @Test
        void resolve_noIncludeKey_returnsDocumentUnchanged() {
            var doc = new TomlDocument(Map.of("server", Map.of("port", 8080)));

            IncludeResolver.resolve(doc, Path.of("/tmp"))
                           .onFailureRun(Assertions::fail)
                           .onSuccess(IncludeResolverTest::assertNoIncludeKeyAndPortPreserved);
        }
    }

    @Nested
    class CycleDetection {

        @Test
        void resolve_circularInclude_returnsError(@TempDir Path tempDir) throws IOException {
            var fileA = tempDir.resolve("a.toml");
            var fileB = tempDir.resolve("b.toml");

            Files.writeString(fileA, "include = [\"b.toml\"]\n[server]\nport = 8080\n");
            Files.writeString(fileB, "include = [\"a.toml\"]\n[database]\nurl = \"pg\"\n");

            TomlParser.parse(Files.readString(fileA))
                      .flatMap(doc -> IncludeResolver.resolve(doc, tempDir))
                      .onSuccess(_ -> Assertions.fail("Expected failure"))
                      .onFailure(cause -> assertThat(cause.message()).contains("Circular include detected"));
        }
    }

    @Nested
    class DepthLimit {

        @Test
        void resolve_depthExceedsMax_returnsError(@TempDir Path tempDir) throws IOException {
            createDeepIncludeChain(tempDir);

            TomlParser.parse(Files.readString(tempDir.resolve("level-0.toml")))
                      .flatMap(doc -> IncludeResolver.resolve(doc, tempDir))
                      .onSuccess(_ -> Assertions.fail("Expected failure"))
                      .onFailure(cause -> assertThat(cause.message()).contains("Include depth limit exceeded"));
        }

        private static void createDeepIncludeChain(Path tempDir) throws IOException {
            for (int i = 0; i <= 17; i++) {
                var filename = "level-" + i + ".toml";
                var nextFilename = "level-" + (i + 1) + ".toml";

                if (i < 17) {
                    Files.writeString(tempDir.resolve(filename),
                                      "include = [\"" + nextFilename + "\"]\n[level" + i + "]\nval = " + i + "\n");
                } else {
                    Files.writeString(tempDir.resolve(filename), "[leaf]\nval = \"end\"\n");
                }
            }
        }
    }

    @Nested
    class FileBasedIncludes {

        @Test
        void resolve_simpleInclude_mergesAndMainOverrides(@TempDir Path tempDir) throws IOException {
            var baseFile = tempDir.resolve("base.toml");
            var mainContent = "include = [\"base.toml\"]\n[server]\nport = 9090\n";
            var baseContent = "[server]\nport = 8080\nhost = \"localhost\"\n";

            Files.writeString(baseFile, baseContent);

            TomlParser.parse(mainContent)
                      .flatMap(doc -> IncludeResolver.resolve(doc, tempDir))
                      .onFailureRun(Assertions::fail)
                      .onSuccess(IncludeResolverTest::assertMainOverridesIncludeAndBasePreserved);
        }
    }

    private static void assertNoIncludeKeyAndPortPreserved(TomlDocument result) {
        assertThat(result.getInt("server", "port").or(0)).isEqualTo(8080);
        assertThat(result.hasKey("", "include")).isFalse();
    }

    private static void assertMainOverridesIncludeAndBasePreserved(TomlDocument result) {
        assertThat(result.getInt("server", "port").or(0)).isEqualTo(9090);
        assertThat(result.getString("server", "host").or("")).isEqualTo("localhost");
        assertThat(result.hasKey("", "include")).isFalse();
    }
}
