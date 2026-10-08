// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #2058: `Main.resolveEnvironment` turned a `[cloud]` section whose integration could not be created into `Option.empty()` plus one ERROR
/// line, and the node booted without it (no provisioning, replacement or scaling until the first incident). It must now REFUSE the boot:
/// exit 69, a FATAL line on stderr naming the provider, the cause and the `[cloud.credentials]` keys to add. No `[cloud]` section, and a
/// docker one (whose integration cannot fail), are unchanged.
///
/// The child JVM is real `Main`. `[streams] wal_path` points under a regular FILE so the very next gate after the cloud step (the stream
/// WAL directory check) refuses deterministically on every machine; a run that gets PAST the cloud step stops there with exit 1 and that
/// gate's message, which is the control proving the cloud step let it through.
class MainCloudIntegrationBootTest {
    private static final int CLOUD_REFUSED = 69;
    private static final int NEXT_GATE_EXIT = 1;
    private static final String NEXT_GATE = "stream WAL directory";
    private static final long DEADLINE_SECONDS = 90;

    private record Run(int exit, String out, String err) {}

    private static CloudConfig cloud(String provider, Map<String, String> credentials) {
        return CloudConfig.cloudConfig(provider, credentials, Map.of()).unwrap();
    }

    // --- the decision, per provider, with the real factories ---

    @Test
    void noCloudSection_isNoIntegration_notAFailure() {
        assertThat(Main.resolveCloudIntegration(Option.none(), EnvironmentIntegrationFactory::createFromConfig).map(Option::isEmpty).or(false)).isTrue();
    }

    @Test
    void aDockerSection_createsTheIntegration() {
        var result = Main.resolveCloudIntegration(Option.some(cloud("docker", Map.of())), EnvironmentIntegrationFactory::createFromConfig);

        assertThat(result.map(Option::isPresent).or(false)).as("CONTROL: a section whose integration can be created is present").isTrue();
    }

    @Test
    void everyProviderWithoutCredentials_isARefusal_namingTheProviderTheCauseAndTheKeysToAdd() {
        var expectedKeys = Map.of("hetzner", "api_token",
                                  "aws", "access_key_id, secret_access_key, region",
                                  "gcp", "project_id, service_account_email, private_key_pem, zone",
                                  "azure", "tenant_id, client_id, client_secret, subscription_id");

        expectedKeys.forEach((provider, keys) -> {
            var result = Main.resolveCloudIntegration(Option.some(cloud(provider, Map.of())), EnvironmentIntegrationFactory::createFromConfig);

            assertThat(result.isFailure()).as(provider + " without credentials").isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("provider '" + provider + "'", "could not be created", keys));
        });
    }

    @Test
    void hetznerWithABlankToken_isARefusal() {
        var result = Main.resolveCloudIntegration(Option.some(cloud("hetzner", Map.of("api_token", "  "))), EnvironmentIntegrationFactory::createFromConfig);

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void anUnknownProvider_isARefusal_withoutAKeyHint() {
        var result = Main.resolveCloudIntegration(Option.some(cloud("nowhere", Map.of())), EnvironmentIntegrationFactory::createFromConfig);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("provider 'nowhere'").doesNotContain("must provide"));
    }

    // --- the process: a real Main ---

    @Test
    void anAwsSectionWithoutCredentials_refusesToStart_withExit69_andNamesProviderAndKeysOnStderr(@TempDir Path dir) throws Exception {
        var run = boot(dir, "[cloud]\nprovider = \"aws\"\n");

        assertRefused(run, "aws");
        assertThat(run.err()).contains("access_key_id, secret_access_key, region");
    }

    @Test
    void aHetznerSectionWithABlankToken_refusesToStart(@TempDir Path dir) throws Exception {
        var run = boot(dir, "[cloud]\nprovider = \"hetzner\"\n[cloud.credentials]\napi_token = \"\"\n");

        assertRefused(run, "hetzner");
        assertThat(run.err()).contains("api_token");
    }

    @Test
    void aDockerSection_getsPastTheCloudStep(@TempDir Path dir) throws Exception {
        assertPastCloudStep(boot(dir, "[cloud]\nprovider = \"docker\"\n"));
    }

    @Test
    void noCloudSection_getsPastTheCloudStep(@TempDir Path dir) throws Exception {
        assertPastCloudStep(boot(dir, ""));
    }

    private static void assertRefused(Run run, String provider) {
        assertThat(run.exit()).as("exit code; stderr:\n%s\nstdout:\n%s", run.err(), run.out()).isEqualTo(CLOUD_REFUSED);
        assertThat(run.err()).as("the operator-visible signal: a FATAL line on stderr naming the provider")
                             .contains("FATAL: refusing to start")
                             .contains("provider '" + provider + "'")
                             .contains("could not be created");
        assertThat(run.out() + run.err()).as("it must not have gone on to a later gate").doesNotContain(NEXT_GATE);
    }

    private static void assertPastCloudStep(Run run) {
        assertThat(run.exit()).as("stdout:\n%s\nstderr:\n%s", run.out(), run.err()).isEqualTo(NEXT_GATE_EXIT);
        assertThat(run.out() + run.err()).as("stopped at the NEXT gate, so the cloud step let it through").contains(NEXT_GATE);
        assertThat(run.err()).doesNotContain("could not be created");
    }

    private static Run boot(Path dir, String cloudSection) throws Exception {
        var file = Files.writeString(dir.resolve("node.toml"), "[cluster]\nenvironment = \"docker\"\n" + cloudSection + walUnderAFile(dir));
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = new ArrayList<>(List.of(java, "-Xmx256m", "-cp", System.getProperty("java.class.path"), Main.class.getName(),
                                              "--config=" + file, "--node-id=node-1", "--port=18990", "--management-port=18991"));
        var out = dir.resolve("out.txt");
        var err = dir.resolve("err.txt");
        var builder = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());

        // The child's environment is stated, not inherited: without a cluster secret TLS setup fails before the WAL gate (found on bigboy,
        // whose environment differs from a developer shell that exports one).
        builder.environment().put("AETHER_CLUSTER_NAME", "test-cluster");
        builder.environment().put("AETHER_CLUSTER_SECRET", "test-cluster-secret-for-2058");
        builder.environment().remove("AETHER_INSECURE_DEV_MODE");
        var child = builder.start();

        if (!child.waitFor(DEADLINE_SECONDS, TimeUnit.SECONDS)) {
            child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            return new Run(-1, Files.readString(out), Files.readString(err) + "\n[TIMED OUT: the child kept running]");
        }

        return new Run(child.exitValue(), Files.readString(out), Files.readString(err));
    }

    /// `[streams] wal_path` under a regular file: the directory cannot be created anywhere, so the WAL gate refuses on every machine.
    private static String walUnderAFile(Path dir) throws Exception {
        var blocker = Files.writeString(dir.resolve("blocker"), "x");

        return "[streams]\nwal_path = \"" + blocker.resolve("wal") + "\"\n";
    }
}
