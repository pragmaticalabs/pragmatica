// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;
import static org.pragmatica.lang.Option.empty;
import static org.pragmatica.lang.Option.some;

class BootstrapPhaseProvisionTest {

    private static SourceProfile cloudSource(String name, Option<CloudProviderName> provider) {
        return SourceProfile.sourceProfile(sourceNameOrDefault(name),
                                           SourceType.CLOUD,
                                           provider,
                                           empty(),
                                           empty(),
                                           empty(),
                                           empty(),
                                           empty(),
                                           empty(),
                                           LoadBalancerMode.NONE,
                                           List.of(),
                                           empty(),
                                           Map.of(),
                                           Map.of(),
                                           List.of());
    }

    @Nested
    class ResolveProviderName {

        @Test
        void resolveProviderName_returnsCloudProviderValue_forHetznerCloudSource() {
            var source = cloudSource("hetzner-source", some(CloudProviderName.HETZNER));

            var name = BootstrapPhaseProvision.resolveProviderName(source);

            assertEquals("hetzner", name,
                         "Hetzner cloud source must surface 'hetzner' so BootstrapCleanup can resolve the SPI factory; "
                         + "the source TYPE 'cloud' has no factory registered and would orphan VMs on rollback");
        }

        @Test
        void resolveProviderName_returnsCloudProviderValue_forAwsCloudSource() {
            var source = cloudSource("aws-source", some(CloudProviderName.AWS));

            var name = BootstrapPhaseProvision.resolveProviderName(source);

            assertEquals("aws", name);
        }

        @Test
        void resolveProviderName_returnsCloudProviderValue_forGcpCloudSource() {
            var source = cloudSource("gcp-source", some(CloudProviderName.GCP));

            var name = BootstrapPhaseProvision.resolveProviderName(source);

            assertEquals("gcp", name);
        }

        @Test
        void resolveProviderName_returnsCloudProviderValue_forAzureCloudSource() {
            var source = cloudSource("azure-source", some(CloudProviderName.AZURE));

            var name = BootstrapPhaseProvision.resolveProviderName(source);

            assertEquals("azure", name);
        }

        @Test
        void resolveProviderName_fallsBackToSourceType_whenProviderAbsent() {
            // Docker / SSH / Forge sources have no separate `provider` attribute — type IS the provider.
            var dockerSource = SourceProfile.sourceProfile(sourceNameOrDefault("docker-source"),
                                                           SourceType.DOCKER,
                                                           empty(),
                                                           empty(),
                                                           empty(),
                                                           empty(),
                                                           empty(),
                                                           empty(),
                                                           empty(),
                                                           LoadBalancerMode.NONE,
                                                           List.of(),
                                                           empty(),
                                                           Map.of(),
                                                           Map.of(),
                                                           List.of());

            var name = BootstrapPhaseProvision.resolveProviderName(dockerSource);

            assertEquals("docker", name);
        }
    }
}
