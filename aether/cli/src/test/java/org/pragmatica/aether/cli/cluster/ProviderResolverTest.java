// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;


class ProviderResolverTest {

    private static final String CLOUD_WITH_IMAGE = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"

            [source.eu-1.core]
            count = 3
            image = "snapshot-174523891"
            """;

    private static final String CLOUD_NO_IMAGE = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"

            [source.eu-1.core]
            count = 3
            """;

    @Test
    void buildCloudConfig_roleImagePresent_computeMapStillOmitsImage() {
        // RFC-0016 W2 — MECHANICAL UPDATE of a #459 test that pinned the interim core-stamps-all
        // mechanism. buildCloudConfig NO LONGER stamps [cloud.compute] image, even when the CORE role
        // sets one: the per-role VM image is threaded as tier-1 ProvisionSpec.imageId instead (see
        // BootstrapPhaseProvision.buildCloudProvisionSpec). Stamping the core image here forced every
        // role onto the core's image (the bug W2 removes), so the correct post-W2 expectation is that
        // the compute map carries no image at all.
        var config = ClusterBootstrapConfigParser.parse(CLOUD_WITH_IMAGE).unwrap();
        var source = config.sources().get("eu-1");

        var cloudConfig = ProviderResolver.buildCloudConfig("hetzner", source, List.of(), "");

        assertFalse(cloudConfig.compute().containsKey("image"),
                    "buildCloudConfig must NOT stamp [cloud.compute] image post-W2 — the per-role image "
                    + "is threaded via ProvisionSpec.imageId (tier-1), not the provider compute map");
    }

    @Test
    void buildCloudConfig_noImageAnywhere_computeMapOmitsImage() {
        // No [source...] image → the compute map omits the image key entirely, so config.image()
        // stays empty and the provider's loud hardcoded default applies (rather than an empty image
        // reaching the create request).
        var config = ClusterBootstrapConfigParser.parse(CLOUD_NO_IMAGE).unwrap();
        var source = config.sources().get("eu-1");

        var cloudConfig = ProviderResolver.buildCloudConfig("hetzner", source, List.of(), "");

        assertFalse(cloudConfig.compute().containsKey("image"),
                    "image must be omitted from the compute map when the CORE role sets none");
    }

    /// #521 — an unmapped cleanup handle used to produce a `CloudConfig` with an EMPTY credentials map and
    /// report SUCCESS (the missing-env-var loop never ran, so nothing looked missing). The provider factory
    /// then failed with "Cloud credentials missing for provider 'hetzner': set HCLOUD_TOKEN" — naming an env
    /// var that WAS set. A handle that names no credential must say so where it is knowable.
    @Test
    void resolveCloudComputeFromHandle_failsLoudly_whenHandleNamesNoCredentialEnvVar() {
        var unmapped = SourceCleanupHandle.sourceCleanupHandle("hetzner", Option.some("fsn1"), Map.of());

        var result = ProviderResolver.resolveCloudComputeFromHandle(unmapped);

        result.onSuccess(_ -> fail("a handle carrying no credential env var cannot yield a credentialed provider"))
              .onFailure(cause -> assertTrue(cause.message().contains("names no credential env var"),
                                             "the failure must name the real problem — the handle, not a "
                                             + "supposedly-unset env var: " + cause.message()));
    }

    private static final String DOCKER_WITH_BACKUP = """
            config_version = "1.0.0"

            [cluster]
            name = "dock"
            version = "1.0.0"

            [source.local]
            type = "docker"

            [source.local.node_config.backup]
            enabled = true
            path = "/data/backups"
            remote = "/data/backup-remote/kv.git"
            restore = "fresh"
            """;

    private static final String DOCKER_BACKUP_DISABLED = DOCKER_WITH_BACKUP.replace("enabled = true", "enabled = false");

    private static final String DOCKER_NO_BACKUP = """
            config_version = "1.0.0"

            [cluster]
            name = "dock"
            version = "1.0.0"

            [source.local]
            type = "docker"
            """;

    /// #1968: CLI-side Docker provisioning hands the provider the source's `[backup]` as AETHER_BACKUP_* (the CTM path does the same
    /// from the leader's effective backup), so the nodes it starts run the backup the operator declared. Read from the provider
    /// the resolver actually builds, not from the helper.
    @Test
    void resolveDockerCompute_forASourceWithBackup_givesTheProviderTheSourcesBackupEnvironment() {
        var source = ClusterBootstrapConfigParser.parse(DOCKER_WITH_BACKUP).unwrap().sources().get("local");

        var provider = ProviderResolver.resolveDockerCompute(source).unwrap();

        assertTrue(provider instanceof org.pragmatica.aether.environment.docker.DockerComputeProvider, "CONTROL: the Docker provider was built");
        assertEquals(Map.of("AETHER_BACKUP_ENABLED", "true",
                            "AETHER_BACKUP_PATH", "/data/backups",
                            "AETHER_BACKUP_REMOTE", "/data/backup-remote/kv.git",
                            "AETHER_BACKUP_RESTORE", "fresh"),
                     ((org.pragmatica.aether.environment.docker.DockerComputeProvider) provider).config().backupEnv());
    }

    @Test
    void resolveDockerCompute_forASourceWithoutAnEnabledBackup_givesTheProviderNone() {
        for (var toml : List.of(DOCKER_NO_BACKUP, DOCKER_BACKUP_DISABLED)) {
            var source = ClusterBootstrapConfigParser.parse(toml).unwrap().sources().get("local");
            var provider = (org.pragmatica.aether.environment.docker.DockerComputeProvider) ProviderResolver.resolveDockerCompute(source).unwrap();

            assertTrue(provider.config().backupEnv().isEmpty(), "no enabled [backup]: nothing to forward; got " + provider.config().backupEnv());
        }
    }
}
