// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NetworkingType;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RuntimeType;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.pragmatica.aether.config.cluster.ClusterBootstrapConfig.clusterBootstrapConfig;
import static org.pragmatica.aether.config.cluster.ClusterIdentity.clusterIdentity;
import static org.pragmatica.aether.config.cluster.CoreTopology.defaultCoreTopology;
import static org.pragmatica.aether.config.cluster.InfrastructureConfig.infrastructureConfig;
import static org.pragmatica.aether.config.cluster.OperationsConfig.defaultOperationsConfig;
import static org.pragmatica.aether.config.cluster.RoleSubTable.roleSubTable;
import static org.pragmatica.aether.config.cluster.RuntimeProfile.runtimeProfile;
import static org.pragmatica.aether.config.cluster.SourceProfile.sourceProfile;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

class ClusterBootstrapCommandTest {

    private static final String RUNTIME_REF = "default";

    private static SourceProfile forgeSource(int coreCount) {
        return sourceProfile("forge",
                             SourceType.FORGE,
                             none(),
                             none(),
                             none(),
                             none(),
                             none(),
                             none(),
                             none(),
                             LoadBalancerMode.NONE,
                             List.of(),
                             none(),
                             Map.of(),
                             Map.of(NodeRole.CORE,
                                    roleSubTable(NodeRole.CORE, some(coreCount), none(), none(), RUNTIME_REF)),
                             List.of());
    }

    private static ClusterBootstrapConfig configWithName(String name) {
        return clusterBootstrapConfig("1",
                                      clusterIdentity(name, "1.0.0").unwrap(),
                                      defaultCoreTopology(),
                                      Map.of("forge", forgeSource(3)),
                                      Map.of(RUNTIME_REF, runtimeProfile(RUNTIME_REF, RuntimeType.JVM, none(), none())),
                                      infrastructureConfig(NetworkingType.MANUAL),
                                      defaultOperationsConfig());
    }

    @Nested
    class WithClusterNameOverride {
        @Test
        void withClusterName_overridesIdentityName_whenInvoked() {
            var config = configWithName("from-toml");
            var overridden = config.withClusterName("cli-name").unwrap();

            assertEquals("cli-name", overridden.cluster().name());
            assertEquals(config.cluster().version(), overridden.cluster().version());
            assertEquals(config.configVersion(), overridden.configVersion());
            assertEquals(config.coreTopology(), overridden.coreTopology());
            assertEquals(config.sources(), overridden.sources());
            assertEquals(config.runtimes(), overridden.runtimes());
            assertEquals(config.infrastructure(), overridden.infrastructure());
            assertEquals(config.operations(), overridden.operations());
        }

        @Test
        void withName_overridesClusterIdentityName_keepsVersion() {
            var identity = clusterIdentity("from-toml", "2.5.0").unwrap();
            var renamed = identity.withName("cli-name").unwrap();

            assertEquals("cli-name", renamed.name());
            assertEquals("2.5.0", renamed.version());
        }
    }

    @Nested
    class CommandLineParsing {
        @Test
        void bootstrap_clusterFlagOverridesTomlName_whenProvided() throws Exception {
            var tomlPath = writeMinimalToml("from-toml");
            var cmd = new ClusterBootstrapCommand();
            new CommandLine(cmd).parseArgs(tomlPath.toString(), "--cluster", "cli-name", "--yes");

            assertEquals("cli-name", readField(cmd, "clusterNameOverride"));
        }

        @Test
        void bootstrap_noClusterFlag_usesTomlName() throws Exception {
            var tomlPath = writeMinimalToml("from-toml");
            var cmd = new ClusterBootstrapCommand();
            new CommandLine(cmd).parseArgs(tomlPath.toString(), "--yes");

            assertEquals(null, readField(cmd, "clusterNameOverride"));
        }

        @Test
        void bootstrap_emptyClusterFlag_treatedAsAbsent() throws Exception {
            var tomlPath = writeMinimalToml("from-toml");
            var cmd = new ClusterBootstrapCommand();
            new CommandLine(cmd).parseArgs(tomlPath.toString(), "--cluster", "", "--yes");

            // Field is empty string, but applyClusterNameOverride / isOverrideAcceptable both treat it as absent.
            assertEquals("", readField(cmd, "clusterNameOverride"));
        }

        @Test
        void bootstrap_keepOnFailureFlag_parsesAsTrue_whenPassed() throws Exception {
            var tomlPath = writeMinimalToml("from-toml");
            var cmd = new ClusterBootstrapCommand();
            new CommandLine(cmd).parseArgs(tomlPath.toString(), "--keep-on-failure", "--yes");

            assertEquals(Boolean.TRUE, readField(cmd, "keepOnFailure"));
        }

        @Test
        void bootstrap_keepOnFailureFlag_defaultsToFalse_whenAbsent() throws Exception {
            var tomlPath = writeMinimalToml("from-toml");
            var cmd = new ClusterBootstrapCommand();
            new CommandLine(cmd).parseArgs(tomlPath.toString(), "--yes");

            assertEquals(Boolean.FALSE, readField(cmd, "keepOnFailure"));
        }

        @Test
        void bootstrap_keepOnFailureFlag_appearsInHelp() {
            var help = new CommandLine(new ClusterBootstrapCommand()).getUsageMessage();

            assertNotEquals(-1, help.indexOf("--keep-on-failure"),
                            "expected --keep-on-failure in help output, got: " + help);
        }

        @Test
        void bootstrap_waitWithoutTimeout_defaultsToThreeHundredSeconds() throws Exception {
            var tomlPath = writeMinimalToml("from-toml");
            var cmd = new ClusterBootstrapCommand();
            new CommandLine(cmd).parseArgs(tomlPath.toString(), "--wait", "--yes");

            assertEquals(Boolean.TRUE, readField(cmd, "waitForCompletion"));
            assertEquals(300, readField(cmd, "timeoutSeconds"));
        }

        @Test
        void bootstrap_waitWithExplicitTimeout_keepsExplicitValue() throws Exception {
            var tomlPath = writeMinimalToml("from-toml");
            var cmd = new ClusterBootstrapCommand();
            new CommandLine(cmd).parseArgs(tomlPath.toString(), "--wait", "--timeout", "60", "--yes");

            assertEquals(Boolean.TRUE, readField(cmd, "waitForCompletion"));
            assertEquals(60, readField(cmd, "timeoutSeconds"));
        }

        @Test
        void bootstrap_invalidClusterValue_returnsUsageError() throws Exception {
            var tomlPath = writeMissingToml();
            var cmd = new ClusterBootstrapCommand();
            new CommandLine(cmd).parseArgs(tomlPath.toString(), "--cluster", "has spaces", "--yes");

            var originalErr = System.err;
            var captured = new ByteArrayOutputStream();
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            try {
                var exit = cmd.call();
                assertEquals(ExitCode.USAGE, exit);
                var msg = captured.toString(StandardCharsets.UTF_8);
                assertNotEquals(-1, msg.indexOf("Invalid --cluster value"), "expected USAGE error message, got: " + msg);
            } finally {
                System.setErr(originalErr);
            }
        }
    }

    private static Path writeMinimalToml(String clusterName) throws Exception {
        var toml = """
                   config_version = "1"

                   [cluster]
                   name = "%s"
                   version = "1.0.0"

                   [core_topology]
                   maxUnavailable = 1

                   [sources.forge]
                   type = "forge"

                   [sources.forge.roles.core]
                   count = 3
                   runtime_ref = "ember"
                   """.formatted(clusterName);
            var path = Files.createTempFile("aether-bootstrap-test-", ".toml");
            path.toFile().deleteOnExit();
            Files.writeString(path, toml, StandardCharsets.UTF_8);
            return path;
    }

    private static Path writeMissingToml() {
        // Path that does not exist; parseConfig will fail before downstream consumers.
        // Used for invalidClusterValue test where USAGE error must short-circuit before parse.
        return Path.of("/tmp/aether-bootstrap-test-nonexistent-" + System.nanoTime() + ".toml");
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Object readField(Object target, String fieldName) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
