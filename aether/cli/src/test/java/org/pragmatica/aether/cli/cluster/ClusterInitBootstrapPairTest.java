// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.CloudAnswers;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.SecretAnswers;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.TlsAnswers;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigGenerator;
import org.pragmatica.aether.cli.cluster.init.CoreWorkerSplit;
import org.pragmatica.aether.cli.cluster.init.FirewallPreset;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Checks `cluster init` and `cluster bootstrap` as a **pair**, because the defect lived in their
/// composition and not in either half.
///
/// `init` wrote no SSH reference anywhere in a cloud config; `SshKeyResolver.resolveOrFailIfCloud`
/// refuses any cloud cluster whose key it cannot resolve. Each side was individually correct and
/// individually tested, and `init` still printed "Next: run bootstrap" for a file that bootstrap
/// then rejected. Testing the halves separately is exactly what missed it.
///
/// The env lookup is STUBBED to return nothing. `SshKeyResolver` falls back to `$AETHER_SSH_KEY.pub`,
/// so on a developer machine that happens to export it this test would pass without the generated
/// config contributing anything — a vacuous green. The stub makes the generated config the only
/// possible source of the key.
class ClusterInitBootstrapPairTest {

    private static final String NO_ENV = null;

    private static ClusterConfigAnswers cloudAnswers(String sshPublicKeyPath) {
        return new ClusterConfigAnswers("prod-eu",
                                        "1.0.0",
                                        SourceType.CLOUD,
                                        Option.some(new CloudAnswers(CloudProviderName.HETZNER,
                                                                     "hel1",
                                                                     "cpx32",
                                                                     "HCLOUD_TOKEN",
                                                                     sshPublicKeyPath)),
                                        Option.none(),
                                        CoreWorkerSplit.coreWorkerSplit(5, 0).unwrap(),
                                        Option.none(),
                                        FirewallPreset.STANDARD,
                                        Option.some("203.0.113.0/24"),
                                        Option.none(),
                                        List.of(),
                                        new TlsAnswers.AutoGenerate(),
                                        new SecretAnswers.AutoGenerate());
    }

    private static Path writePublicKey(Path dir) throws IOException {
        var key = dir.resolve("id_ed25519.pub");

        Files.writeString(key, "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleKeyForTestingOnly test@example\n");

        return key;
    }

    @Nested
    class GeneratedCloudConfigIsBootstrappable {

        /// The whole point: generate with `init`, parse it, and hand it to the resolver `bootstrap`
        /// actually uses. Nothing here reads the answers object — only the emitted TOML.
        @Test
        void generatedCloudConfig_satisfiesTheResolverBootstrapUses(@TempDir Path dir) throws IOException {
            var key = writePublicKey(dir);
            var toml = ClusterConfigGenerator.generate(cloudAnswers(key.toString()));

            ClusterBootstrapConfigParser.parse(toml)
                                        .onFailure(c -> fail("generated config did not parse: " + c.message()))
                                        .flatMap(config -> SshKeyResolver.resolveOrFailIfCloud(config,
                                                                                               Option.none(),
                                                                                               _ -> NO_ENV))
                                        .onFailure(c -> fail("bootstrap would refuse the config init just wrote: " + c.message()))
                                        .onSuccess(keys -> assertThat(keys).hasSize(1));
        }

        /// Calibration: the same pipeline must FAIL when the key line is absent, otherwise the test
        /// above could be passing for some unrelated reason. Removing the emitted section reproduces
        /// exactly the pre-fix behaviour.
        @Test
        void generatedCloudConfig_withoutTheSshSection_isRefused_provingTheAssertionBites(@TempDir Path dir) throws IOException {
            var key = writePublicKey(dir);
            var toml = ClusterConfigGenerator.generate(cloudAnswers(key.toString()));
            var stripped = stripInfrastructureSsh(toml);

            assertThat(stripped).doesNotContain("public_key_file");

            ClusterBootstrapConfigParser.parse(stripped)
                                        .onFailure(c -> fail("stripped config did not parse: " + c.message()))
                                        .flatMap(config -> SshKeyResolver.resolveOrFailIfCloud(config,
                                                                                               Option.none(),
                                                                                               _ -> NO_ENV))
                                        .onSuccess(keys -> fail("expected refusal, resolved " + keys.size() + " key(s)"));
        }

        /// The section is emitted under the exact name the parser reads — `[infrastructure.ssh]`,
        /// key `public_key_file` — rather than something that merely looks right.
        @Test
        void generatedCloudConfig_namesTheSectionAndKeyTheParserReads(@TempDir Path dir) throws IOException {
            var key = writePublicKey(dir);
            var toml = ClusterConfigGenerator.generate(cloudAnswers(key.toString()));

            assertThat(toml).contains("[infrastructure.ssh]")
                            .contains("public_key_file = \"" + key + "\"");
        }

        /// A docker target has no cloud source, so no SSH section is emitted and the resolver does
        /// not demand one — the requirement is scoped to cloud, not applied to everything.
        @Test
        void generatedDockerConfig_carriesNoSshSection() {
            var toml = ClusterConfigGenerator.generate(dockerAnswers());

            assertThat(toml).doesNotContain("[infrastructure.ssh]");
        }
    }

    private static ClusterConfigAnswers dockerAnswers() {
        return new ClusterConfigAnswers("local",
                                        "1.0.0",
                                        SourceType.DOCKER,
                                        Option.none(),
                                        Option.none(),
                                        CoreWorkerSplit.coreWorkerSplit(5, 0).unwrap(),
                                        Option.none(),
                                        FirewallPreset.OPEN,
                                        Option.none(),
                                        Option.none(),
                                        List.of(),
                                        new TlsAnswers.Skipped(),
                                        new SecretAnswers.Skipped());
    }

    private static String stripInfrastructureSsh(String toml) {
        return toml.lines()
                   .filter(line -> !line.startsWith("public_key_file"))
                   .filter(line -> !line.equals("[infrastructure.ssh]"))
                   .reduce(new StringBuilder(), (sb, line) -> sb.append(line).append('\n'), StringBuilder::append)
                   .toString();
    }
}
