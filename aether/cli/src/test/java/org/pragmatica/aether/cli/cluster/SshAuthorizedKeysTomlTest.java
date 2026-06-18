// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.SshDeploymentConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


/// Proves the persisted cluster config TOML carries the operator SSH key(s) so the CTM auto-heal
/// replacement path (which re-parses that TOML on the leader VM) can inject them into a replacement
/// node's cloud-init authorized_keys — the bootstrap/replacement SSH-key parity that lets an
/// operator reach a replacement VM's logs.
class SshAuthorizedKeysTomlTest {
    private static final String KEY_ED25519 = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOperatorBlob operator@laptop";
    private static final String KEY_RSA = "ssh-rsa AAAAB3NzaC1yc2EAAAABlobMaterial== ci@runner";

    private static final String CLOUD_BASE = """
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
    void withAuthorizedKeys_noSection_appendsParseableSection() {
        var result = SshAuthorizedKeysToml.withAuthorizedKeys(CLOUD_BASE, List.of(KEY_ED25519, KEY_RSA));

        assertThat(authorizedKeysOf(result)).containsExactly(KEY_ED25519, KEY_RSA);
    }

    @Test
    void withAuthorizedKeys_existingSectionWithoutKey_injectsIntoSection() {
        var toml = CLOUD_BASE + """

            [infrastructure.ssh]
            public_key_file = "~/.ssh/id_ed25519.pub"
            """;

        var result = SshAuthorizedKeysToml.withAuthorizedKeys(toml, List.of(KEY_ED25519));

        assertThat(authorizedKeysOf(result)).containsExactly(KEY_ED25519);
        assertThat(publicKeyFilesOf(result)).containsExactly("~/.ssh/id_ed25519.pub");
    }

    @Test
    void withAuthorizedKeys_alreadyHasAuthorizedKeys_returnsUnchanged() {
        var toml = CLOUD_BASE + """

            [infrastructure.ssh]
            authorized_keys = ["ssh-ed25519 AAAAOperatorKept operator@kept"]
            """;

        var result = SshAuthorizedKeysToml.withAuthorizedKeys(toml, List.of(KEY_RSA));

        assertThat(result).isEqualTo(toml);
        assertThat(authorizedKeysOf(result)).containsExactly("ssh-ed25519 AAAAOperatorKept operator@kept");
    }

    @Test
    void withAuthorizedKeys_emptyKeys_returnsUnchanged() {
        assertThat(SshAuthorizedKeysToml.withAuthorizedKeys(CLOUD_BASE, List.of())).isEqualTo(CLOUD_BASE);
    }

    @Test
    void withAuthorizedKeys_nullToml_returnsEmpty() {
        assertThat(SshAuthorizedKeysToml.withAuthorizedKeys(null, List.of(KEY_ED25519))).isEmpty();
    }

    private static List<String> authorizedKeysOf(String toml) {
        return ClusterBootstrapConfigParser.parse(toml)
                                           .unwrap()
                                           .infrastructure()
                                           .ssh()
                                           .or(SshDeploymentConfig.empty())
                                           .authorizedKeys();
    }

    private static List<String> publicKeyFilesOf(String toml) {
        return ClusterBootstrapConfigParser.parse(toml)
                                           .unwrap()
                                           .infrastructure()
                                           .ssh()
                                           .or(SshDeploymentConfig.empty())
                                           .publicKeyFiles();
    }
}
