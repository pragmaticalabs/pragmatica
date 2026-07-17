// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;


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
    void buildCloudConfig_roleImagePresent_computeMapCarriesImage() {
        // #459 seed path — the CORE role's [source...] image must be threaded into the resolved
        // cloud config's [cloud.compute] image so the bootstrap-time provider's config.image() boots
        // from the operator's prepared snapshot instead of the provider's hardcoded default.
        var config = ClusterBootstrapConfigParser.parse(CLOUD_WITH_IMAGE).unwrap();
        var source = config.sources().get("eu-1");

        var cloudConfig = ProviderResolver.buildCloudConfig("hetzner", source, List.of(), "");

        assertEquals("snapshot-174523891", cloudConfig.compute().get("image"),
                     "cloud.compute.image must come from the CORE role's [source...] image");
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
}
