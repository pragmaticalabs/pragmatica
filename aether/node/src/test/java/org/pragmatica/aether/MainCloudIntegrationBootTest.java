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
import org.pragmatica.aether.node.ClusterTestPorts;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #2058: `Main.resolveEnvironment` turned a `[cloud]` section whose integration could not be created into `Option.empty()` plus one ERROR
/// line, and the node booted without it (no provisioning, replacement or scaling until the first incident). It must now REFUSE the boot:
/// exit 69, a FATAL line on stderr naming the provider, the factory's cause and the `[cloud.credentials]` keys to add. No `[cloud]` section, and a
/// docker one whose integration can be created, are unchanged. (A docker section can ALSO be refused: its provider rejects a `[backup]
/// path` outside `/data`.)
///
/// The child JVM is real `Main`. `[storage.streams] wal_path` points under a regular FILE so the very next gate after the cloud step (the stream
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

    /// The key hint is only useful if it is EXACT. It is a second copy of each factory's `validateCredentials`, so it is pinned in both
    /// directions against the real factories: with every listed key present the integration is created; with any one of them
    /// blank it is refused. A key added to (or dropped from) a factory without the table fails here (the azure list once missed two).
    @Test
    void theKeyHints_areExactlyWhatEachFactoryRequires() {
        assertThat(Main.REQUIRED_CREDENTIAL_KEYS.keySet()).containsExactlyInAnyOrder("hetzner", "aws", "gcp", "azure");

        Main.REQUIRED_CREDENTIAL_KEYS.forEach((provider, csv) -> {
            var keys = List.of(csv.split(",\\s*"));
            var all = new java.util.HashMap<String, String>();

            keys.forEach(key -> all.put(key, "v-" + key));
            assertThat(EnvironmentIntegrationFactory.createFromConfig(cloud(provider, all)).isSuccess())
                .as(provider + " with exactly the listed keys " + keys + " is created").isTrue();

            for (var missing : keys) {
                var without = new java.util.HashMap<>(all);

                without.put(missing, " ");
                assertThat(EnvironmentIntegrationFactory.createFromConfig(cloud(provider, without)).isFailure())
                    .as(provider + " without " + missing + " is refused (so the key is really required)").isTrue();
            }
        });
    }

    @Test
    void everyProviderWithoutCredentials_isARefusal_namingTheProviderTheFactoryCauseAndTheKeysToAdd() {
        Main.REQUIRED_CREDENTIAL_KEYS.forEach((provider, keys) -> {
            var result = Main.resolveCloudIntegration(Option.some(cloud(provider, Map.of())), EnvironmentIntegrationFactory::createFromConfig);

            assertThat(result.isFailure()).as(provider + " without credentials").isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("provider '" + provider + "'", "could not be created", keys));
        });
    }

    /// The factory's own cause is part of the operator message (it names the env-style variable): dropping it must redden something.
    @Test
    void theFactoryCauseText_isCarriedIntoTheRefusal() {
        var result = Main.resolveCloudIntegration(Option.some(cloud("aws", Map.of())), EnvironmentIntegrationFactory::createFromConfig);
        var direct = EnvironmentIntegrationFactory.createFromConfig(cloud("aws", Map.of()));

        assertThat(direct.isFailure()).isTrue();
        direct.onFailure(factoryCause -> result.onFailure(refusal -> assertThat(refusal.message()).contains(factoryCause.message())));
        assertThat(result.isFailure()).isTrue();
    }

    /// A factory that THROWS (the real hetzner one does on a non-numeric id list) is lifted into the same refusal, not an uncaught exception.
    @Test
    void aFactoryThatThrows_isARefusal_notAnException() {
        var thrown = Main.resolveCloudIntegration(Option.some(cloud("hetzner", Map.of("api_token", "t"))),
                                                  _ -> {
                                                      throw new IllegalStateException("boom-from-the-factory");
                                                  });

        assertThat(thrown.isFailure()).isTrue();
        thrown.onFailure(cause -> assertThat(cause.message()).contains("provider 'hetzner'", "boom-from-the-factory"));

        var real = Main.resolveCloudIntegration(Option.some(CloudConfig.cloudConfig("hetzner", Map.of("api_token", "t"), Map.of("ssh_key_ids", "abc")).unwrap()),
                                                EnvironmentIntegrationFactory::createFromConfig);

        assertThat(real.isFailure()).as("hetzner ssh_key_ids=\"abc\" through the real factory").isTrue();
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

    private static final String EXPLICIT_DOCKER = "[cluster]\nenvironment = \"docker\"\n";

    @Test
    void anAwsSectionWithoutCredentials_refusesToStart_withExit69_andNamesProviderCauseAndKeysOnStderr(@TempDir Path dir) throws Exception {
        var run = boot(dir, EXPLICIT_DOCKER + "[cloud]\nprovider = \"aws\"\n");

        assertRefused(run, "aws");
        assertThat(run.err()).contains("access_key_id, secret_access_key, region").as("the factory's own cause is carried").contains("AWS_ACCESS_KEY_ID");
    }

    @Test
    void aHetznerSectionWithABlankToken_refusesToStart(@TempDir Path dir) throws Exception {
        var run = boot(dir, EXPLICIT_DOCKER + "[cloud]\nprovider = \"hetzner\"\n[cloud.credentials]\napi_token = \"\"\n");

        assertRefused(run, "hetzner");
        assertThat(run.err()).contains("api_token").contains("HCLOUD_TOKEN");
    }

    /// The real hetzner factory throws NumberFormatException on a non-numeric id list; before the lift this was an uncaught exception (exit 1, no FATAL).
    @Test
    void aMalformedProviderSetting_isTheSameRefusal_notAnUncaughtException(@TempDir Path dir) throws Exception {
        var run = boot(dir, EXPLICIT_DOCKER + "[cloud]\nprovider = \"hetzner\"\n[cloud.credentials]\napi_token = \"t\"\n[cloud.compute]\nssh_key_ids = \"abc\"\n");

        assertRefused(run, "hetzner");
        assertThat(run.err()).doesNotContain("Exception in thread");
    }

    /// A docker section can be refused too: the provider rejects a `[backup] path` outside /data (named volume, #1968). The baked-image
    /// shape: no explicit `[cluster] environment`, so the node-load rule does not fire and the provisioning-side check is what refuses.
    @Test
    void aDockerSectionWithABackupPathOutsideData_isRefused(@TempDir Path dir) throws Exception {
        var run = boot(dir, "[cloud]\nprovider = \"docker\"\n[backup]\nenabled = true\npath = \"/var/backups\"\n");

        assertRefused(run, "docker");
        assertThat(run.err()).contains("[backup] path").contains("under /data");
    }

    @Test
    void aDockerSection_getsPastTheCloudStep(@TempDir Path dir) throws Exception {
        assertPastCloudStep(boot(dir, EXPLICIT_DOCKER + "[cloud]\nprovider = \"docker\"\n"));
    }

    @Test
    void aDockerSectionWithABackupUnderData_getsPastTheCloudStep(@TempDir Path dir) throws Exception {
        assertPastCloudStep(boot(dir, "[cloud]\nprovider = \"docker\"\n[backup]\nenabled = true\npath = \"/data/backups\"\n"));
    }

    @Test
    void noCloudSection_getsPastTheCloudStep(@TempDir Path dir) throws Exception {
        assertPastCloudStep(boot(dir, EXPLICIT_DOCKER));
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

    /// Everything the child sees is stated: no `AETHER_*` / `CLUSTER_*` variable is inherited (a developer shell that exports a cluster
    /// secret or `AETHER_ALLOW_NON_DURABLE_STREAMS` changes which gate stops the run), and the ports are free ones, never fixed.
    private static Run boot(Path dir, String nodeToml) throws Exception {
        var file = Files.writeString(dir.resolve("node.toml"), nodeToml + walUnderAFile(dir));
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = new ArrayList<>(List.of(java, "-Xmx256m", "-cp", System.getProperty("java.class.path"), Main.class.getName(),
                                              "--config=" + file, "--node-id=node-1", "--port=" + freePort(), "--management-port=" + freePort()));
        var out = dir.resolve("out.txt");
        var err = dir.resolve("err.txt");
        var builder = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());

        builder.environment().keySet().removeIf(name -> name.startsWith("AETHER_") || name.startsWith("CLUSTER_"));
        builder.environment().put("AETHER_CLUSTER_NAME", "test-cluster");
        builder.environment().put("AETHER_CLUSTER_SECRET", "test-cluster-secret-for-2058");
        var child = builder.start();

        if (!child.waitFor(DEADLINE_SECONDS, TimeUnit.SECONDS)) {
            child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            return new Run(-1, Files.readString(out), Files.readString(err) + "\n[TIMED OUT: the child kept running]");
        }

        return new Run(child.exitValue(), Files.readString(out), Files.readString(err));
    }

    private static int freePort() {
        return ClusterTestPorts.freeTcpAndUdpPort();
    }

    /// `[storage.streams] wal_path` (the section the loader reads) under a regular file: the WAL directory cannot be created anywhere, so
    /// the gate that follows the cloud step refuses on every machine, whether or not the default `/data/aether/...` is creatable.
    private static String walUnderAFile(Path dir) throws Exception {
        var blocker = Files.writeString(dir.resolve("blocker"), "x");

        return "[storage.streams]\nwal_path = \"" + blocker.resolve("wal") + "\"\n";
    }
}
