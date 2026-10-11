// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.NodeUserDataRenderer;
import org.pragmatica.config.toml.TomlDocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.ClusterName.clusterName;

/// #828 — cloud-init writes the API keys to files like the cluster secret, and the node runs with `<VAR>_FILE` instead of the
/// value. The host environment is supplied; each sentinel is a value no other field can contain, with the positive control that
/// it DID reach its file.
class UserDataTemplateCredentialFilesTest {
    private static final String SECRET = "sentinel-secret-7f3a9c";
    private static final String KEYS = "sentinel-keys-5d2e81:ops:ADMIN";
    private static final String KEY = "sentinel-key-9b4c70";
    private static final Map<String, String> HOST = Map.of("AETHER_API_KEYS", KEYS, "AETHER_API_KEY", KEY);

    private static final String CONTAINER = """
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

    private static final String JVM = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [operations.ports]
            cluster = 6000
            management = 5160
            app_http = 8070

            [runtime.bare-metal]
            type = "jvm"

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"

            [source.eu-1.core]
            count = 3
            runtime = "bare-metal"
            """;

    private static String render(String toml, Map<String, String> host) {
        var config = ClusterBootstrapConfigParser.parse(toml).unwrap();

        return NodeUserDataRenderer.render(config, config.sources().get("eu-1"), NodeRole.CORE, "eu-1-core-0", 0, SECRET,
                                           clusterName("prod-cluster").unwrap(), TomlDocument.EMPTY, List.of(), List.of(), true, host::get);
    }

    @Test
    void container_apiKeysAreWrittenToFiles_mountedReadOnly_andNeverInTheRunLine() {
        var script = render(CONTAINER, HOST);
        var run = script.substring(script.indexOf("docker run -d"), script.indexOf("\"${AETHER_IMAGE}\"\n\n", script.indexOf("docker run -d")));

        assertThat(script).contains("printf '%s' '" + KEYS + "' > /opt/aether/config/api-keys", "printf '%s' '" + KEY + "' > /opt/aether/config/api-key",
                                    "chown 1000:1000 /opt/aether/config/api-keys", "chmod 0400 /opt/aether/config/api-keys");
        assertThat(run).contains("--mount type=bind,src=/opt/aether/config/api-keys,dst=/run/secrets/aether-api-keys,readonly",
                                 "--mount type=bind,src=/opt/aether/config/api-key,dst=/run/secrets/aether-api-key,readonly",
                                 "-e AETHER_API_KEYS_FILE=\"/run/secrets/aether-api-keys\"", "-e AETHER_API_KEY_FILE=\"/run/secrets/aether-api-key\"")
                       .doesNotContain(KEYS, KEY, SECRET, "-e AETHER_API_KEY=", "-e AETHER_API_KEYS=", "--env-file");
    }

    @Test
    void container_withoutCredentialsInTheHostEnv_getsOnlyTheClusterSecretFile() {
        var script = render(CONTAINER, Map.of());

        assertThat(script).contains("/opt/aether/config/cluster-secret").doesNotContain("api-key", "API_KEY");
    }

    @Test
    void container_aCredentialHeldAsAHostFile_isWrittenByValue() throws Exception {
        var file = java.nio.file.Files.createTempFile("host-key-", ".txt");

        try {
            java.nio.file.Files.writeString(file, KEY + "\n");

            assertThat(render(CONTAINER, Map.of("AETHER_API_KEY_FILE", file.toString()))).contains("printf '%s' '" + KEY + "' > /opt/aether/config/api-key")
                                                                                          .doesNotContain(file.toString());
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void jvm_apiKeysAreWrittenToFiles_andTheUnitEnvFileNamesThem() {
        var script = render(JVM, HOST);
        var envBlock = script.substring(script.indexOf("AETHER_ENV_LITERAL"), script.indexOf("\nAETHER_ENV_RUNTIME\n"));

        assertThat(script).contains("printf '%s' '" + KEYS + "' > /etc/aether/api-keys", "printf '%s' '" + KEY + "' > /etc/aether/api-key",
                                    "chmod 0400 /etc/aether/api-keys");
        assertThat(envBlock).contains("AETHER_API_KEYS_FILE=/etc/aether/api-keys", "AETHER_API_KEY_FILE=/etc/aether/api-key",
                                      "AETHER_CLUSTER_SECRET_FILE=/etc/aether/cluster-secret")
                            .doesNotContain(KEYS, KEY, SECRET);
    }
}
