// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterIdentity;
import org.pragmatica.aether.config.cluster.CoreTopology;
import org.pragmatica.aether.config.cluster.InfrastructureConfig;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NetworkingType;
import org.pragmatica.aether.config.cluster.OperationsConfig;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.config.cluster.SshDeploymentConfig;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

class SshKeyResolverTest {

    private static final String VALID_KEY_LINE =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBccdef1234567890abcdefghijklmnopqrstuvwxyzABCD operator@host";

    private Path tempDir;
    private Path cliKeyFile;
    private Path tomlKeyFile;
    private Path envPubFile;
    private Path envPrivFile;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("ssh-resolver-test");
        cliKeyFile = writePubFile("cli.pub", "ssh-ed25519 AAAAC3CLI1234567890abc operator-cli");
        tomlKeyFile = writePubFile("toml.pub", "ssh-ed25519 AAAAC3TOML1234567890abc operator-toml");
        envPrivFile = tempDir.resolve("env_id");
        envPubFile = writePubFile("env_id.pub", "ssh-ed25519 AAAAC3ENV1234567890abc operator-env");
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteRecursively(tempDir);
    }

    private Path writePubFile(String name, String contents) throws IOException {
        var path = tempDir.resolve(name);
        Files.writeString(path, contents + "\n");
        return path;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {return;}
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                  .forEach(p -> {
                      try {Files.deleteIfExists(p);} catch (IOException ignored) {}
                  });
        }
    }

    private static ClusterBootstrapConfig configWithoutSsh() {
        return configWith(InfrastructureConfig.infrastructureConfig(NetworkingType.MANUAL));
    }

    private static ClusterBootstrapConfig configWithTomlSsh(List<String> keyFiles) {
        var ssh = SshDeploymentConfig.sshDeploymentConfig(keyFiles);
        var infra = InfrastructureConfig.infrastructureConfig(NetworkingType.MANUAL, Option.some(ssh));
        return configWith(infra);
    }

    private static ClusterBootstrapConfig configWith(InfrastructureConfig infra) {
        var cloudSource = SourceProfile.sourceProfile(sourceNameOrDefault("cloud-1"),
                                                       SourceType.CLOUD,
                                                       Option.empty(),
                                                       Option.empty(),
                                                       Option.empty(),
                                                       Option.empty(),
                                                       Option.empty(),
                                                       Option.empty(),
                                                       Option.empty(),
                                                       LoadBalancerMode.NONE,
                                                       List.of(),
                                                       Option.empty(),
                                                       Map.of(),
                                                       Map.of(NodeRole.CORE,
                                                              RoleSubTable.roleSubTable(NodeRole.CORE,
                                                                                          Option.some(3),
                                                                                          Option.empty(),
                                                                                          Option.empty(),
                                                                                          "default")),
                                                       List.of());
        return ClusterBootstrapConfig.clusterBootstrapConfig("1.0.0",
                                                              ClusterIdentity.clusterIdentity("test", "1.0.0").unwrap(),
                                                              CoreTopology.defaultCoreTopology(),
                                                              Map.of("cloud-1", cloudSource),
                                                              Map.of(),
                                                              infra,
                                                              OperationsConfig.defaultOperationsConfig());
    }

    private static ClusterBootstrapConfig configWithoutCloudSource() {
        var forgeSource = SourceProfile.sourceProfile(sourceNameOrDefault("forge-1"),
                                                       SourceType.FORGE,
                                                       Option.empty(),
                                                       Option.empty(),
                                                       Option.empty(),
                                                       Option.empty(),
                                                       Option.empty(),
                                                       Option.empty(),
                                                       Option.empty(),
                                                       LoadBalancerMode.NONE,
                                                       List.of(),
                                                       Option.empty(),
                                                       Map.of(),
                                                       Map.of(NodeRole.CORE,
                                                              RoleSubTable.roleSubTable(NodeRole.CORE,
                                                                                          Option.some(3),
                                                                                          Option.empty(),
                                                                                          Option.empty(),
                                                                                          "ember")),
                                                       List.of());
        return ClusterBootstrapConfig.clusterBootstrapConfig("1.0.0",
                                                              ClusterIdentity.clusterIdentity("test", "1.0.0").unwrap(),
                                                              CoreTopology.defaultCoreTopology(),
                                                              Map.of("forge-1", forgeSource),
                                                              Map.of(),
                                                              InfrastructureConfig.infrastructureConfig(NetworkingType.MANUAL),
                                                              OperationsConfig.defaultOperationsConfig());
    }

    private static Fn1<String, String> envWith(String key, String value) {
        return name -> key.equals(name) ? value : null;
    }

    private static Fn1<String, String> envEmpty() {
        return name -> null;
    }

    @Nested
    class PriorityOrder {
        @Test
        void resolve_picksCli_overTomlAndEnv() throws IOException {
            // CLI > TOML > env
            var config = configWithTomlSsh(List.of(tomlKeyFile.toString()));
            Files.writeString(envPrivFile, "fake");

            var keys = SshKeyResolver.resolve(config,
                                              Option.some(cliKeyFile.toString()),
                                              envWith("AETHER_SSH_KEY", envPrivFile.toString()))
                                     .unwrap();

            assertEquals(1, keys.size());
            assertTrue(keys.getFirst().comment().contains("operator-cli"),
                       "CLI override must win over TOML and env (got: " + keys.getFirst().comment() + ")");
        }

        @Test
        void resolve_picksToml_whenCliAbsent() throws IOException {
            var config = configWithTomlSsh(List.of(tomlKeyFile.toString()));
            Files.writeString(envPrivFile, "fake");

            var keys = SshKeyResolver.resolve(config,
                                              Option.empty(),
                                              envWith("AETHER_SSH_KEY", envPrivFile.toString()))
                                     .unwrap();

            assertEquals(1, keys.size());
            assertTrue(keys.getFirst().comment().contains("operator-toml"));
        }

        @Test
        void resolve_picksEnvFallback_whenCliAndTomlAbsent() throws IOException {
            var config = configWithoutSsh();
            Files.writeString(envPrivFile, "fake");

            var keys = SshKeyResolver.resolve(config,
                                              Option.empty(),
                                              envWith("AETHER_SSH_KEY", envPrivFile.toString()))
                                     .unwrap();

            assertEquals(1, keys.size());
            assertTrue(keys.getFirst().comment().contains("operator-env"));
        }

        @Test
        void resolve_returnsEmpty_whenAllSourcesAbsent() {
            var config = configWithoutSsh();

            var keys = SshKeyResolver.resolve(config, Option.empty(), envEmpty()).unwrap();

            assertTrue(keys.isEmpty());
        }
    }

    @Nested
    class CloudGuard {
        @Test
        void resolveOrFailIfCloud_failsFast_whenCloudButNoKeys() {
            var config = configWithoutSsh();

            var result = SshKeyResolver.resolveOrFailIfCloud(config, Option.empty(), envEmpty());

            assertTrue(result.isFailure(), "Cloud source + no keys must fail fast");
            var msg = result.fold(c -> c.message(), _ -> "");
            assertTrue(msg.contains("--ssh-public-key"),
                       "Error must name the CLI flag remediation path: " + msg);
            assertTrue(msg.contains("infrastructure.ssh"),
                       "Error must name the TOML remediation path: " + msg);
            assertTrue(msg.contains("AETHER_SSH_KEY"),
                       "Error must name the env remediation path: " + msg);
        }

        @Test
        void resolveOrFailIfCloud_succeedsEmpty_whenNoCloudSource() {
            var config = configWithoutCloudSource();

            var result = SshKeyResolver.resolveOrFailIfCloud(config, Option.empty(), envEmpty());

            assertTrue(result.isSuccess(),
                       "Forge-only cluster does not need SSH keys");
        }

        @Test
        void resolveOrFailIfCloud_succeeds_whenCliKeyProvided() {
            var config = configWithoutSsh();

            var result = SshKeyResolver.resolveOrFailIfCloud(config,
                                                              Option.some(cliKeyFile.toString()),
                                                              envEmpty());

            assertTrue(result.isSuccess());
            assertEquals(1, result.unwrap().size());
        }
    }
}
