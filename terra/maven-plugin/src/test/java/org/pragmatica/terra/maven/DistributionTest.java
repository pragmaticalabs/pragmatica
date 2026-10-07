// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class DistributionTest {
    @TempDir Path directory;

    @Test void classpath_duplicateClassAcrossJars_refusesBeforeWritingDistribution() throws Exception {
        var first = library("one", "test/Clash.class");
        var second = library("two", "test/Clash.class");
        assertThat(Distribution.classpath(List.of(first, second)).isFailure()).isTrue();
    }

    @Test void classpath_serviceResourcesAndModuleDescriptors_doNotCountAsClassCollisions() throws Exception {
        var first = library("one", "META-INF/services/example.Service", "module-info.class", "test/First.class");
        var second = library("two", "META-INF/services/example.Service", "module-info.class", "test/Second.class");
        assertThat(Distribution.classpath(List.of(first, second)).isSuccess()).isTrue();
    }

    @Test void assemble_failedExternalCheck_preservesPreviousDistribution() throws Exception {
        var application = Files.createDirectory(directory.resolve("app"));
        Files.writeString(application.resolve("blueprint.toml"), "[[slices]]\nartifact=\"test:missing:1\"\n");
        var output = Files.createDirectory(directory.resolve("out"));
        Files.writeString(output.resolve(".terra-distribution"), "1");
        Files.writeString(output.resolve("previous"), "preserved");
        var result = Distribution.assemble(List.of(), application, output, Path.of(System.getProperty("java.home"), "bin", "java"), directory.resolve("out.zip"));
        assertThat(result.isFailure()).isTrue();
        assertThat(Files.readString(output.resolve("previous"))).isEqualTo("preserved");
        assertThat(Files.exists(directory.resolve("out.zip"))).isFalse();
        try (var entries = Files.list(directory)) {
            assertThat(entries.filter(Files::isDirectory).map(path -> path.getFileName().toString()).toList())
                .containsExactlyInAnyOrder("app", "out");
        }
    }

    @Test void assemble_unownedOutput_refusesReplacement() throws Exception {
        var application = Files.createDirectory(directory.resolve("app"));
        Files.writeString(application.resolve("blueprint.toml"), "[[slices]]\nartifact=\"test:missing:1\"\n");
        var output = Files.createDirectory(directory.resolve("out"));
        Files.writeString(output.resolve("important"), "preserved");
        var result = Distribution.assemble(List.of(), application, output, Path.of(System.getProperty("java.home"), "bin", "java"), directory.resolve("out.zip"));
        assertThat(result.isFailure()).isTrue();
        assertThat(Files.readString(output.resolve("important"))).isEqualTo("preserved");
    }

    private Distribution.Library library(String name, String... entries) throws Exception {
        var path = directory.resolve(name + ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries) {
                output.putNextEntry(new JarEntry(entry));
                output.write(new byte[]{1,2,3});
                output.closeEntry();
            }
        }
        return new Distribution.Library("test:" + name + ":1", path);
    }
}
