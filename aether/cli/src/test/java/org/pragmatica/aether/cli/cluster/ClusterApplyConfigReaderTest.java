// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.config.cluster.CloudCredentialSchema;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// `aether cluster apply --resume` ACTS on the config it reads, and the scalar `credentials` of a Hetzner source is the provider's
/// `api_token` verbatim: a `${env:...}` reference the reader leaves in place is sent to the provider as the token (a 401 at the
/// first call). The reader therefore resolves references exactly as `aether cluster bootstrap` does, for the desired config and
/// for the stored one (the cluster stores the TOML as the operator sent it, references unresolved).
class ClusterApplyConfigReaderTest {
    private static final String UNSET = "${env:AETHER_TEST_NO_SUCH_VARIABLE_7f3a}";

    private static String config(String credentials) {
        return """
               config_version = "1.0.0"
               [cluster]
               name = "reader-test"
               version = "1.0.0"
               [source.eu-1]
               type = "cloud"
               provider = "hetzner"
               region = "eu-central"
               credentials = "%s"
               zone = "fsn1"
               [source.eu-1.worker]
               count = 1
               """.formatted(credentials);
    }

    private static String apiToken(ClusterBootstrapConfig parsed) {
        return CloudCredentialSchema.credentials(parsed.sources().get("eu-1"), "hetzner").get("api_token");
    }

    @Test
    void theAppliesReader_givenAnEnvReference_yieldsTheEnvironmentsValueAsTheApiToken() {
        var parsed = ClusterApplyCommand.parseResolved(config("${env:PATH}")).unwrap();

        assertThat(apiToken(parsed)).isEqualTo(System.getenv("PATH")).isNotEqualTo("${env:PATH}");
    }

    /// The control: a plain parse, which is what `--resume` did, keeps the reference text as the token.
    @Test
    void aPlainParse_keepsTheReferenceTextAsTheApiToken_whichIsWhatProduced401() {
        var parsed = ClusterBootstrapConfigParser.parse(config("${env:PATH}")).unwrap();

        assertThat(apiToken(parsed)).isEqualTo("${env:PATH}");
    }

    @Test
    void anUnresolvableReference_refusesTyped_insteadOfBeingSentAsTheToken() {
        ClusterApplyCommand.parseResolved(config(UNSET))
                           .onSuccess(_ -> fail("must refuse"))
                           .onFailure(cause -> assertThat(cause.message()).contains("AETHER_TEST_NO_SUCH_VARIABLE_7f3a"));
    }

    // ---------------------------------------------------------------- through the command itself

    @RegisterExtension
    final LedgerDirExtension ledger = new LedgerDirExtension();

    @TempDir
    Path dir;

    private HttpServer cluster;
    private String originalEndpoint;
    private String storedToml;

    private record Outcome(int exitCode, String stdout, String stderr) {}

    @BeforeEach
    @SuppressWarnings("JBCT-EX-01")
    void fakeCluster() throws Exception {
        cluster = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cluster.createContext("/", exchange -> {
            var body = ("{\"tomlContent\": \"" + storedToml.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\", \"configVersion\": 1}").getBytes(StandardCharsets.UTF_8);

            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        cluster.start();
        originalEndpoint = ClusterHttpClient.ENDPOINT_OVERRIDE.get();
        ClusterHttpClient.ENDPOINT_OVERRIDE.set("http://127.0.0.1:" + cluster.getAddress().getPort());
        storedToml = config("${env:PATH}");
    }

    @AfterEach
    void stop() {
        ClusterHttpClient.ENDPOINT_OVERRIDE.set(originalEndpoint);
        cluster.stop(0);
    }

    private Outcome resume(String desiredToml) throws Exception {
        var file = Files.writeString(dir.resolve("aether-cluster.toml"), desiredToml);
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var originalOut = System.out;
        var originalErr = System.err;

        try (var capturedOut = new PrintStream(out, true, StandardCharsets.UTF_8);
             var capturedErr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            System.setErr(capturedErr);

            var exit = new CommandLine(new ClusterApplyCommand()).execute("--resume", file.toString());

            return new Outcome(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    /// Both configs resolve, so the command reaches the orchestrator, which finds no ledger.
    @Test
    void resume_resolvesBothConfigs_andReachesTheOrchestrator() throws Exception {
        var outcome = resume(config("${env:PATH}"));

        assertThat(outcome.exitCode()).isEqualTo(ExitCode.ERROR);
        assertThat(outcome.stderr()).contains("Nothing to resume");
    }

    @Test
    void resume_aDesiredReferenceThatDoesNotResolve_refusesBeforeAnythingElse() throws Exception {
        var outcome = resume(config(UNSET));

        assertThat(outcome.exitCode()).isEqualTo(ExitCode.ERROR);
        assertThat(outcome.stderr()).contains("AETHER_TEST_NO_SUCH_VARIABLE_7f3a").doesNotContain("Nothing to resume");
    }

    @Test
    void resume_aStoredReferenceThatDoesNotResolve_refuses_theStoredConfigIsResolvedToo() throws Exception {
        storedToml = config(UNSET);

        var outcome = resume(config("${env:PATH}"));

        assertThat(outcome.exitCode()).isEqualTo(ExitCode.ERROR);
        assertThat(outcome.stderr()).contains("AETHER_TEST_NO_SUCH_VARIABLE_7f3a").doesNotContain("Nothing to resume");
    }
}
