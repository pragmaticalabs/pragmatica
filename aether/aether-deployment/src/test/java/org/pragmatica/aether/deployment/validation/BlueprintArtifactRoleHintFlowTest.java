// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.blueprint.BlueprintArtifact;
import org.pragmatica.aether.slice.blueprint.BlueprintArtifactParser;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


/// End-to-end test for spec event-stream-namespaces §11.1.2 wiring: a blueprint artifact JAR
/// that bundles slice manifests with stream-publisher/access bindings produces role hints that
/// the deploy-time `StreamResourceValidator` honours.
///
/// Concrete scenario from Wave 4B's pipe-through follow-up: a slice declares a `StreamAccess`
/// for `streams.inventory`, the manifest records `consumer` for that config section, the
/// validator infers `consumer` from the role hint, and `version = "latest"` (which would be
/// rejected for an inferred producer per §11.1.3) is accepted because consumers may pin to
/// `latest` per spec §11.1.1.
class BlueprintArtifactRoleHintFlowTest {

    private static final Artifact APP_ARTIFACT = Artifact.artifact("com.example:my-app:1.0.0").unwrap();

    @Test
    void consumerRoleHintFromBundledManifestEnablesLatestVersion() {
        var manifestProps = new Properties();
        manifestProps.setProperty("slice.name", "Inventory");
        manifestProps.setProperty("stream.access.count", "1");
        manifestProps.setProperty("stream.access.0.config", "streams.inventory");

        var resourcesToml = """
                [streams.inventory]
                version = "latest"
                """;

        var jarBytes = buildJar(simpleBlueprintToml(), resourcesToml, "Inventory.manifest", manifestProps);

        BlueprintArtifact artifact = BlueprintArtifactParser.parse(jarBytes)
                                                            .onFailure(cause -> fail(cause.message()))
                                                            .unwrap();

        // Sanity: the parser projected `streams.inventory` → `consumer`.
        assertThat(artifact.roleHints()).containsExactly(java.util.Map.entry("inventory", "consumer"));

        // The validator pipe-through accepts `version = "latest"` because the inferred role is consumer.
        // Without the role hint Wave 1's producer-default would have rejected it.
        var validation = StreamResourceValidator.validate(artifact.resourcesConfig(),
                                                          APP_ARTIFACT,
                                                          artifact.roleHints());

        validation.onFailure(cause -> fail("Expected validation success but got: " + cause.message()))
                  .onSuccess(validated -> assertThat(validated.resources()).containsKey("inventory"));
    }

    @Test
    void emptyManifestSetMatchesLegacyBehaviour() {
        var jarBytes = buildJar(simpleBlueprintToml(), null, null, null);

        var artifact = BlueprintArtifactParser.parse(jarBytes).unwrap();

        assertThat(artifact.roleHints()).isEmpty();

        // No resources.toml → trivial validator success.
        var validation = StreamResourceValidator.validate(artifact.resourcesConfig(),
                                                          APP_ARTIFACT,
                                                          artifact.roleHints());

        validation.onFailure(cause -> fail(cause.message()))
                  .onSuccess(validated -> assertThat(validated.resources()).isEmpty());
    }

    private static String simpleBlueprintToml() {
        return """
                id = "com.example:my-app:1.0.0"

                [[slices]]
                artifact = "com.example:my-slice:1.0.0"
                """;
    }

    private static byte[] buildJar(String blueprintToml,
                                    String resourcesToml,
                                    String manifestName,
                                    Properties manifestProps) {
        try {
            var bos = new ByteArrayOutputStream();
            try (var zos = new ZipOutputStream(bos)) {
                writeEntry(zos, "META-INF/blueprint.toml", blueprintToml.getBytes(StandardCharsets.UTF_8));
                if (resourcesToml != null) {
                    writeEntry(zos, "META-INF/resources.toml", resourcesToml.getBytes(StandardCharsets.UTF_8));
                }
                if (manifestName != null && manifestProps != null) {
                    var pbos = new ByteArrayOutputStream();
                    manifestProps.store(pbos, null);
                    writeEntry(zos, "META-INF/slice/" + manifestName, pbos.toByteArray());
                }
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeEntry(ZipOutputStream zos, String name, byte[] content) throws java.io.IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content);
        zos.closeEntry();
    }
}
