// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.management;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #3651 (owner, 2026-11-24): the Actuator endpoints are Terra-only. Source side of the ban: no main source under
/// `aether/` names `/actuator` or the Actuator media type, and no pom under `aether/` declares a Terra artifact (the
/// enforcer rule in aether/pom.xml covers transitive dependencies of its children; this scan also reaches the
/// standalone blueprint poms outside that parent). Each scan has an in-suite positive control on a planted tree.
class TerraOnlyManagementTripwireTest {
    private static final List<String> MARKERS = List.of("/actuator", "application/vnd.spring-boot.actuator");

    @Test
    void aetherMainSources_containNoActuatorPathOrMediaType() throws IOException {
        assertThat(actuatorHits(repositoryRoot().resolve("aether"))).isEmpty();
    }

    @Test
    void aetherPoms_declareNoTerraArtifact() throws IOException {
        assertThat(terraDependents(repositoryRoot().resolve("aether"))).isEmpty();
    }

    @Test
    void actuatorHits_plantedMainSource_reported(@TempDir Path root) throws IOException {
        var source = root.resolve("node/src/main/java/Planted.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Planted { String path = \"/actuator/health\"; }");
        Files.createDirectories(root.resolve("node/src/test/java"));
        Files.writeString(root.resolve("node/src/test/java/Ok.java"), "class Ok { String path = \"/actuator\"; }");

        assertThat(actuatorHits(root)).containsExactly(source);
    }

    @Test
    void terraDependents_plantedPom_reported(@TempDir Path root) throws IOException {
        var pom = root.resolve("tests/blueprints/x/pom.xml");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, "<project><dependencies><dependency><groupId>org.pragmatica-lite</groupId>"
                               + "<artifactId>terra-runtime</artifactId></dependency></dependencies></project>");

        assertThat(terraDependents(root)).containsExactly(pom);
    }

    static List<Path> actuatorHits(Path aether) throws IOException {
        try (var files = sources(aether)) {
            return files.filter(path -> path.toString().contains("/src/main/"))
                        .filter(path -> MARKERS.stream().anyMatch(read(path)::contains))
                        .toList();
        }
    }

    static List<Path> terraDependents(Path aether) throws IOException {
        try (var files = sources(aether)) {
            return files.filter(path -> path.getFileName().toString().equals("pom.xml"))
                        .filter(path -> read(path).matches("(?s).*<artifactId>\\s*terra[^<]*</artifactId>.*"))
                        .toList();
        }
    }

    private static Stream<Path> sources(Path aether) throws IOException {
        return Files.walk(aether)
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.toString().contains("/target/") && !path.toString().contains("/node_modules/"));
    }

    private static String read(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new IllegalStateException(path.toString(), e);
        }
    }

    private static Path repositoryRoot() {
        var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();

        while (dir != null && !Files.isRegularFile(dir.resolve("aether/pom.xml"))) {
            dir = dir.getParent();
        }

        assertThat(dir).as("repository root above " + System.getProperty("user.dir")).isNotNull();
        return dir;
    }
}
