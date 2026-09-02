// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.cli.cluster.ClusterTargetMixin.ClusterTargetError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Supplier;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


class ClusterTargetMixinTest {

    @TempDir
    Path tempHome;

    private Supplier<org.pragmatica.lang.Result<ClusterRegistry>> originalRegistryLoader;

    private Function<String, Path> originalApiKeyPathResolver;

    private String originalEndpoint;

    private String originalApiKey;

    private Path registryPath;

    @BeforeEach
    void setUp() {
        registryPath = tempHome.resolve("clusters.toml");
        originalRegistryLoader = ClusterTargetMixin.registryLoader;
        originalApiKeyPathResolver = ClusterTargetMixin.apiKeyPathResolver;
        ClusterTargetMixin.registryLoader = () -> ClusterRegistry.load(registryPath);
        ClusterTargetMixin.apiKeyPathResolver = name -> tempHome.resolve(name).resolve("api-key");
        originalEndpoint = ClusterHttpClient.ENDPOINT_OVERRIDE.get();
        originalApiKey = ClusterHttpClient.API_KEY_OVERRIDE.get();
        ClusterHttpClient.ENDPOINT_OVERRIDE.set(null);
        ClusterHttpClient.API_KEY_OVERRIDE.set(null);
    }

    @AfterEach
    void tearDown() {
        ClusterTargetMixin.registryLoader = originalRegistryLoader;
        ClusterTargetMixin.apiKeyPathResolver = originalApiKeyPathResolver;
        ClusterHttpClient.ENDPOINT_OVERRIDE.set(originalEndpoint);
        ClusterHttpClient.API_KEY_OVERRIDE.set(originalApiKey);
    }

    private void writeRegistryWith(String name, String endpoint) {
        ClusterRegistry.load(registryPath)
                       .map(reg -> reg.add(name, endpoint, none()))
                       .flatMap(reg -> reg.save().map(_ -> reg))
                       .onFailure(c -> fail("registry setup failed: " + c.message()));
    }

    private void writeApiKeyFile(String clusterName, String key) throws IOException {
        var path = ClusterTargetMixin.apiKeyPathResolver.apply(clusterName);
        Files.createDirectories(path.getParent());
        Files.writeString(path, key);
    }

    @Nested
    class ApplyOverrides {

        @Test
        void applyOverrides_noOp_whenNameUnset() {
            var mixin = new ClusterTargetMixin();

            mixin.applyOverrides()
                 .onFailure(c -> fail("Expected success, got: " + c.message()));

            assertNull(ClusterHttpClient.ENDPOINT_OVERRIDE.get());
            assertNull(ClusterHttpClient.API_KEY_OVERRIDE.get());
        }

        @Test
        void applyOverrides_noOp_whenNameBlank() {
            var mixin = new ClusterTargetMixin();
            mixin.setClusterName("   ");

            mixin.applyOverrides()
                 .onFailure(c -> fail("Expected success, got: " + c.message()));

            assertNull(ClusterHttpClient.ENDPOINT_OVERRIDE.get());
            assertNull(ClusterHttpClient.API_KEY_OVERRIDE.get());
        }

        @Test
        void applyOverrides_failsWithInvalidName_whenNameViolatesRegex() {
            var mixin = new ClusterTargetMixin();
            mixin.setClusterName("INVALID@NAME");

            mixin.applyOverrides()
                 .onSuccess(_ -> fail("Expected failure for invalid name"))
                 .onFailure(cause -> assertTrue(cause instanceof ClusterTargetError.InvalidClusterName,
                                                "Expected InvalidClusterName, got: " + cause.getClass().getSimpleName()));

            assertNull(ClusterHttpClient.ENDPOINT_OVERRIDE.get());
        }

        @Test
        void applyOverrides_failsWithInvalidName_whenStartsWithDigit() {
            var mixin = new ClusterTargetMixin();
            mixin.setClusterName("9bad");

            mixin.applyOverrides()
                 .onSuccess(_ -> fail("Expected failure"))
                 .onFailure(cause -> assertTrue(cause instanceof ClusterTargetError.InvalidClusterName));
        }

        @Test
        void applyOverrides_failsWithInvalidName_whenUppercase() {
            var mixin = new ClusterTargetMixin();
            mixin.setClusterName("UpperCase");

            mixin.applyOverrides()
                 .onSuccess(_ -> fail("Expected failure"))
                 .onFailure(cause -> assertTrue(cause instanceof ClusterTargetError.InvalidClusterName));
        }

        @Test
        void applyOverrides_failsWithUnknownCluster_whenNoEntryInRegistry() {
            writeRegistryWith("known-cluster", "https://known:5150");
            var mixin = new ClusterTargetMixin();
            mixin.setClusterName("unknown-cluster");

            mixin.applyOverrides()
                 .onSuccess(_ -> fail("Expected failure for unknown cluster"))
                 .onFailure(cause -> {
                     assertTrue(cause instanceof ClusterTargetError.UnknownCluster);
                     assertTrue(cause.message().contains("unknown-cluster"),
                                "Expected message to name the missing cluster, got: " + cause.message());
                 });

            assertNull(ClusterHttpClient.ENDPOINT_OVERRIDE.get());
        }

        @Test
        void applyOverrides_setsEndpoint_whenClusterExists() {
            writeRegistryWith("my-cluster", "https://endpoint.example:5150");
            var mixin = new ClusterTargetMixin();
            mixin.setClusterName("my-cluster");

            mixin.applyOverrides()
                 .onFailure(c -> fail("Expected success, got: " + c.message()));

            assertEquals("https://endpoint.example:5150", ClusterHttpClient.ENDPOINT_OVERRIDE.get());
        }

        @Test
        void applyOverrides_setsApiKey_whenKeyFileExists() throws IOException {
            writeRegistryWith("keyed-cluster", "https://k:5150");
            writeApiKeyFile("keyed-cluster", "secret-token-xyz");
            var mixin = new ClusterTargetMixin();
            mixin.setClusterName("keyed-cluster");

            mixin.applyOverrides()
                 .onFailure(c -> fail("Expected success, got: " + c.message()));

            assertEquals("https://k:5150", ClusterHttpClient.ENDPOINT_OVERRIDE.get());
            assertEquals("secret-token-xyz", ClusterHttpClient.API_KEY_OVERRIDE.get());
        }

        @Test
        void applyOverrides_setsEndpointButNotKey_whenKeyFileMissing() {
            writeRegistryWith("no-key-cluster", "https://nokey:5150");
            var mixin = new ClusterTargetMixin();
            mixin.setClusterName("no-key-cluster");

            mixin.applyOverrides()
                 .onFailure(c -> fail("Expected success even when API key file is absent, got: " + c.message()));

            assertEquals("https://nokey:5150", ClusterHttpClient.ENDPOINT_OVERRIDE.get());
            assertNull(ClusterHttpClient.API_KEY_OVERRIDE.get(),
                       "Missing API key file should leave override unset (best-effort)");
        }

        @Test
        void applyOverrides_skipsKeyOverride_whenKeyFileEmpty() throws IOException {
            writeRegistryWith("empty-key-cluster", "https://e:5150");
            writeApiKeyFile("empty-key-cluster", "   \n");
            var mixin = new ClusterTargetMixin();
            mixin.setClusterName("empty-key-cluster");

            mixin.applyOverrides()
                 .onFailure(c -> fail("Expected success even with blank api-key file, got: " + c.message()));

            assertEquals("https://e:5150", ClusterHttpClient.ENDPOINT_OVERRIDE.get());
            assertNull(ClusterHttpClient.API_KEY_OVERRIDE.get(),
                       "Blank api-key contents must not install an empty override");
        }
    }

    @Nested
    class IsOverrideAcceptable {

        @Test
        void isOverrideAcceptable_returnsTrue_whenNameUnset() {
            assertTrue(new ClusterTargetMixin().isOverrideAcceptable());
        }

        @Test
        void isOverrideAcceptable_returnsTrue_whenNameValid() {
            var mixin = new ClusterTargetMixin();
            mixin.setClusterName("good-name-1");
            assertTrue(mixin.isOverrideAcceptable());
        }

        @Test
        void isOverrideAcceptable_returnsFalse_whenNameMalformed() {
            var mixin = new ClusterTargetMixin();
            mixin.setClusterName("Bad Name!");
            assertFalse(mixin.isOverrideAcceptable());
        }
    }

    @Nested
    class PicocliIntegration {

        @Test
        void parsesClusterFlagViaPicocli_intoStatusCommand() {
            var command = new ClusterStatusCommand();
            new CommandLine(command).parseArgs("--cluster", "named-cluster");

            assertEquals("named-cluster", command.clusterTarget.clusterName());
        }

        @Test
        void parsesClusterFlagViaPicocli_intoScaleCommand() {
            var command = new ClusterScaleCommand();
            new CommandLine(command).parseArgs("--cluster", "scale-target", "--role", "core", "--count", "5");

            assertEquals("scale-target", command.clusterTarget.clusterName());
        }

        @Test
        void picocli_invalidClusterNameStillParses_validatedAtRun() {
            // Picocli accepts the string regardless of regex; validation happens at applyOverrides()
            var command = new ClusterStatusCommand();
            new CommandLine(command).parseArgs("--cluster", "INVALID@NAME");

            assertEquals("INVALID@NAME", command.clusterTarget.clusterName());
            assertFalse(command.clusterTarget.isOverrideAcceptable());
        }

        @Test
        void parsesClusterFlagViaPicocli_intoStatusCommand_someOption() {
            var command = new ClusterStatusCommand();
            new CommandLine(command).parseArgs();

            assertNull(command.clusterTarget.clusterName(),
                       "Without --cluster, the mixin's clusterName must be null (no-op path)");
            // Sanity check that some() / none() are still importable in this test (compile-level).
            assertTrue(some(1).isPresent());
            assertTrue(none().isEmpty());
        }
    }
}
