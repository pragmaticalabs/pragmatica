// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.CloudAnswers;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.SecretAnswers;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.SshAnswers;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.TlsAnswers;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.lang.Option;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class ClusterConfigGeneratorTest {

    private static final String VERSION = "1.0.0";

    private static CoreWorkerSplit split(int core, int worker) {
        return CoreWorkerSplit.coreWorkerSplit(core, worker)
                              .fold(c -> { throw new AssertionError(c.message()); }, s -> s);
    }

    private static ClusterConfigAnswers cloudAnswers() {
        return new ClusterConfigAnswers("prod-eu",
                                         VERSION,
                                         SourceType.CLOUD,
                                         Option.some(new CloudAnswers(CloudProviderName.HETZNER, "hel1", "cx21", "HCLOUD_TOKEN")),
                                         Option.none(),
                                         split(3, 0),
                                         Option.none(),
                                         FirewallPreset.STANDARD,
                                         Option.none(),
                                         Option.none(),
                                         List.of(),
                                         new TlsAnswers.AutoGenerate(),
                                         new SecretAnswers.AutoGenerate());
    }

    private static ClusterConfigAnswers sshAnswers() {
        return new ClusterConfigAnswers("staging",
                                         VERSION,
                                         SourceType.SSH,
                                         Option.none(),
                                         Option.some(new SshAnswers(List.of("10.0.0.1", "10.0.0.2", "10.0.0.3"),
                                                                      "aether",
                                                                      Path.of("/home/aether/.ssh/id_ed25519"),
                                                                      22)),
                                         split(3, 0),
                                         Option.none(),
                                         FirewallPreset.STANDARD,
                                         Option.none(),
                                         Option.none(),
                                         List.of(),
                                         new TlsAnswers.AutoGenerate(),
                                         new SecretAnswers.AutoGenerate());
    }

    private static ClusterConfigAnswers dockerAnswers() {
        return new ClusterConfigAnswers("local-dev",
                                         VERSION,
                                         SourceType.DOCKER,
                                         Option.none(),
                                         Option.none(),
                                         split(3, 0),
                                         Option.none(),
                                         FirewallPreset.STANDARD,
                                         Option.none(),
                                         Option.none(),
                                         List.of(),
                                         new TlsAnswers.Skipped(),
                                         new SecretAnswers.Skipped());
    }

    private static ClusterConfigAnswers forgeAnswers() {
        return new ClusterConfigAnswers("forge-dev",
                                         VERSION,
                                         SourceType.FORGE,
                                         Option.none(),
                                         Option.none(),
                                         split(3, 0),
                                         Option.none(),
                                         FirewallPreset.STANDARD,
                                         Option.none(),
                                         Option.none(),
                                         List.of(),
                                         new TlsAnswers.Skipped(),
                                         new SecretAnswers.Skipped());
    }

    @Nested
    class Cloud {

        @Test
        void generate_cloud_producesNonEmptyOutput() {
            assertThat(ClusterConfigGenerator.generate(cloudAnswers())).isNotBlank();
        }

        @Test
        void generate_cloud_hasExpectedSections() {
            var toml = ClusterConfigGenerator.generate(cloudAnswers());
            assertThat(toml).contains("[cluster]");
            assertThat(toml).contains("[source.primary]");
            assertThat(toml).contains("[source.primary.core]");
            assertThat(toml).contains("[runtime.default]");
            assertThat(toml).contains("[operations.ports]");
        }

        @Test
        void generate_cloud_hasFirewallAllowIngress() {
            var toml = ClusterConfigGenerator.generate(cloudAnswers());
            assertThat(toml).contains("[[source.primary.firewall.allow_ingress]]");
        }

        @Test
        void generate_cloud_roundTripsThroughParser() {
            var toml = ClusterConfigGenerator.generate(cloudAnswers());
            ClusterBootstrapConfigParser.parse(toml)
                                          .onFailure(c -> fail("Parser rejected generated TOML: " + c.message() + "\n---\n" + toml))
                                          .onSuccess(cfg -> assertThat(cfg).isNotNull());
        }
    }

    @Nested
    class Ssh {

        @Test
        void generate_ssh_producesNonEmptyOutput() {
            assertThat(ClusterConfigGenerator.generate(sshAnswers())).isNotBlank();
        }

        @Test
        void generate_ssh_hasExpectedSections() {
            var toml = ClusterConfigGenerator.generate(sshAnswers());
            assertThat(toml).contains("[cluster]");
            assertThat(toml).contains("[source.primary]");
            assertThat(toml).contains("[source.primary.core]");
            assertThat(toml).contains("[runtime.default]");
            assertThat(toml).contains("[operations.ports]");
        }

        @Test
        void generate_ssh_roundTripsThroughParser() {
            var toml = ClusterConfigGenerator.generate(sshAnswers());
            ClusterBootstrapConfigParser.parse(toml)
                                          .onFailure(c -> fail("Parser rejected generated TOML: " + c.message() + "\n---\n" + toml))
                                          .onSuccess(cfg -> assertThat(cfg).isNotNull());
        }
    }

    @Nested
    class Docker {

        @Test
        void generate_docker_producesNonEmptyOutput() {
            assertThat(ClusterConfigGenerator.generate(dockerAnswers())).isNotBlank();
        }

        @Test
        void generate_docker_hasExpectedSections() {
            var toml = ClusterConfigGenerator.generate(dockerAnswers());
            assertThat(toml).contains("[cluster]");
            assertThat(toml).contains("[source.primary]");
            assertThat(toml).contains("[source.primary.core]");
            assertThat(toml).contains("[runtime.default]");
            assertThat(toml).contains("[operations.ports]");
        }

        @Test
        void generate_docker_omitsTlsAndFirewall() {
            var toml = ClusterConfigGenerator.generate(dockerAnswers());
            // TLS section is fully commented out for Docker (no live [operations.tls] header).
            assertThat(toml).doesNotContain("\n[operations.tls]\n");
            assertThat(toml).doesNotContain("[[source.primary.firewall.allow_ingress]]");
        }

        @Test
        void generate_docker_roundTripsThroughParser() {
            var toml = ClusterConfigGenerator.generate(dockerAnswers());
            ClusterBootstrapConfigParser.parse(toml)
                                          .onFailure(c -> fail("Parser rejected generated TOML: " + c.message() + "\n---\n" + toml))
                                          .onSuccess(cfg -> assertThat(cfg).isNotNull());
        }
    }

    @Nested
    class Forge {

        @Test
        void generate_forge_producesNonEmptyOutput() {
            assertThat(ClusterConfigGenerator.generate(forgeAnswers())).isNotBlank();
        }

        @Test
        void generate_forge_hasExpectedSections() {
            var toml = ClusterConfigGenerator.generate(forgeAnswers());
            assertThat(toml).contains("[cluster]");
            assertThat(toml).contains("[source.primary]");
            assertThat(toml).contains("[source.primary.core]");
            assertThat(toml).contains("[runtime.default]");
            assertThat(toml).contains("[operations.ports]");
        }

        @Test
        void generate_forge_omitsTlsAndFirewall() {
            var toml = ClusterConfigGenerator.generate(forgeAnswers());
            // TLS section is fully commented out for Forge (no live [operations.tls] header).
            assertThat(toml).doesNotContain("\n[operations.tls]\n");
            assertThat(toml).doesNotContain("[[source.primary.firewall.allow_ingress]]");
        }

        @Test
        void generate_forge_roundTripsThroughParser() {
            var toml = ClusterConfigGenerator.generate(forgeAnswers());
            ClusterBootstrapConfigParser.parse(toml)
                                          .onFailure(c -> fail("Parser rejected generated TOML: " + c.message() + "\n---\n" + toml))
                                          .onSuccess(cfg -> assertThat(cfg).isNotNull());
        }
    }
}
