// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.jsonoptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.validation.ConfigSectionPreflightValidator;
import org.pragmatica.aether.deployment.validation.ConfigSectionPreflightValidator.SliceJar;
import org.pragmatica.aether.slice.topology.SliceTopology;
import org.pragmatica.aether.slice.topology.TopologyParser;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #2378: the implicit `[json]` section of a routed slice is outside the deploy pre-flight. The slice processor's own
/// manifest for a routed slice, read by the production topology parser from a jar, declares no `json` resource, and the
/// real `ConfigSectionPreflightValidator` admits the slice with no `[json]` anywhere. The control shows the validator
/// is live in this setup: a demanded section that no layer holds is refused.
class GeneratedRoutedSlicePreflightTest {
    private static final Artifact ARTIFACT = Artifact.artifact("com.example:json-options:1.0.0").unwrap();
    private static final String MANIFEST = "META-INF/slice/JsonOptionsSlice.manifest";

    @Test
    void routedSlice_passesThePreflight_withNoJsonSectionAnywhere(@TempDir Path dir) throws IOException {
        var topologies = TopologyParser.parseFromJar(sliceJarWithGeneratedManifest(dir).toUri().toURL(), ARTIFACT.asString())
                                       .unwrap();

        assertThat(topologies).hasSize(1);
        assertThat(topologies.getFirst().routes()).as("the slice is routed").isNotEmpty();
        assertThat(topologies.getFirst().resources()).noneMatch(resource -> resource.config().equals("json"));

        var result = ConfigSectionPreflightValidator.validate(List.of(SliceJar.sliceJar(ARTIFACT,
                                                                                         topologies,
                                                                                         Option.some("[orders]\npool = 1\n"))),
                                                              Option.some(nodeComposite()));

        assertThat(result.isSuccess()).as("pre-flight result: " + result).isTrue();
    }

    @Test
    void control_aDemandedSectionNoLayerHolds_isRefused(@TempDir Path dir) throws IOException {
        var parsed = TopologyParser.parseFromJar(sliceJarWithGeneratedManifest(dir).toUri().toURL(), ARTIFACT.asString())
                                   .unwrap()
                                   .getFirst();
        var demanding = new SliceTopology(parsed.sliceName(),
                                          parsed.artifact(),
                                          parsed.routes(),
                                          parsed.dependencies(),
                                          List.of(new SliceTopology.ResourceDep("org.pragmatica.aether.slice.JsonCodecSettings", "json")),
                                          parsed.publishes(),
                                          parsed.subscribes());

        var result = ConfigSectionPreflightValidator.validate(List.of(SliceJar.sliceJar(ARTIFACT,
                                                                                         List.of(demanding),
                                                                                         Option.some("[orders]\npool = 1\n"))),
                                                              Option.some(nodeComposite()));

        assertThat(result.isFailure()).isTrue();
    }

    private static ConfigurationProvider nodeComposite() {
        return ConfigurationProvider.builder()
                                    .withSource(MapConfigSource.mapConfigSource("node", Map.of("server.port", "8080")).unwrap())
                                    .build();
    }

    // A slice jar holding the manifest the slice processor generated for JsonOptionsSlice in this module.
    private static Path sliceJarWithGeneratedManifest(Path dir) throws IOException {
        var jar = dir.resolve("json-options.jar");

        try (var manifest = GeneratedRoutedSlicePreflightTest.class.getClassLoader().getResourceAsStream(MANIFEST);
             var out = new JarOutputStream(Files.newOutputStream(jar))) {
            assertThat(manifest).as(MANIFEST).isNotNull();
            out.putNextEntry(new JarEntry(MANIFEST));
            manifest.transferTo(out);
            out.closeEntry();
        }

        return jar;
    }
}
