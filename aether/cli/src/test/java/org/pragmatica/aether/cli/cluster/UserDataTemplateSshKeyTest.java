// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.config.toml.TomlDocument;

import java.util.List;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDataTemplateSshKeyTest {

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

    private static final String KEY_ED25519 =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBaa1234567890abcdefghijklmnopqrstuvwxyz/+ABCD operator@workstation";
    private static final String KEY_RSA =
        "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDExampleBlobMaterialRSA== operator@host";

    private static String renderWithKeys(List<SshPublicKey> keys) {
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");
        return UserDataTemplate.render(config,
                                       source,
                                       NodeRole.CORE,
                                       "node-1",
                                       0,
                                       "secret",
                                       clusterName("prod-cluster").unwrap(),
                                       TomlDocument.EMPTY,
                                       keys);
    }

    @Test
    void render_includesAuthorizedKeys_forSingleKey() {
        var key = SshPublicKey.sshPublicKey(KEY_ED25519).unwrap();
        var script = renderWithKeys(List.of(key));

        assertTrue(script.contains("/root/.ssh/authorized_keys"),
                   "Cloud-init must populate root authorized_keys");
        assertTrue(script.contains("/home/aether/.ssh/authorized_keys"),
                   "Cloud-init must populate aether user authorized_keys");
        assertTrue(script.contains(KEY_ED25519),
                   "The exact key line must appear in the generated script");
    }

    @Test
    void render_includesAuthorizedKeys_forMultipleKeys() {
        var k1 = SshPublicKey.sshPublicKey(KEY_ED25519).unwrap();
        var k2 = SshPublicKey.sshPublicKey(KEY_RSA).unwrap();
        var script = renderWithKeys(List.of(k1, k2));

        assertTrue(script.contains(KEY_ED25519), "First key must appear");
        assertTrue(script.contains(KEY_RSA), "Second key must appear");
    }

    @Test
    void render_omitsAuthorizedKeysBlock_whenKeyListEmpty() {
        var script = renderWithKeys(List.of());

        assertFalse(script.contains("authorized_keys"),
                    "Without keys, no SSH key plumbing should be generated");
        assertFalse(script.contains("Aether bootstrap: install operator SSH keys"),
                    "SSH-key block must not be emitted on empty list");
    }

    @Test
    void render_creates_aether_user_idempotently() {
        var key = SshPublicKey.sshPublicKey(KEY_ED25519).unwrap();
        var script = renderWithKeys(List.of(key));

        assertTrue(script.contains("id -u aether"),
                   "User creation must be idempotent (check before useradd)");
        assertTrue(script.contains("useradd -m -s /bin/bash aether"),
                   "Aether system user must be provisioned");
    }
}
