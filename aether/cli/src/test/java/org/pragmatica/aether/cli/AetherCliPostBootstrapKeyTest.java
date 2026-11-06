// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #2133 — the state a successful `aether cluster bootstrap` leaves behind, driven through the real
/// entrypoint with the operator's NEXT shell: the bootstrapped cluster is the active context
/// (`BootstrapPhasePost.registerAndActivate`, pinned by `BootstrapPhasePostContextTest`), its key is in
/// `~/.aether/clusters/<name>/api-key` (`BootstrapPhaseFormation.persistApiKeyFile`), and the registry
/// entry names the env var `AETHER_<NAME>_API_KEY` — which a new shell does NOT have set. A plain
/// `aether status` / `aether cluster topology` must still carry the cluster's key; what is asserted is the
/// `X-API-Key` header the listener received, not a log line.
///
/// `AetherCliEndpointPrecedenceTest` cannot see this: it exports the context's env var into the child,
/// which a fresh shell after bootstrap never has.
class AetherCliPostBootstrapKeyTest {
    private static final String BOOTSTRAP_KEY = "bootstrap-generated-key-2133";

    @TempDir
    Path home;

    private HttpServer cluster;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void postBootstrapState() throws IOException {
        cluster = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cluster.createContext("/",
                              exchange -> {
                                  requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                                              + " key=" + exchange.getRequestHeaders().getFirst("X-API-Key"));
                                  var body = "{}".getBytes(StandardCharsets.UTF_8);

                                  exchange.sendResponseHeaders(200, body.length);
                                  exchange.getResponseBody().write(body);
                                  exchange.close();
                              });
        cluster.start();
        var dir = home.resolve(".aether");

        Files.createDirectories(dir.resolve("clusters").resolve("fresh"));
        Files.writeString(dir.resolve("clusters").resolve("fresh").resolve("api-key"), BOOTSTRAP_KEY);
        // `AETHER_FRESH_API_KEY` is what ClusterBootstrapOrchestrator.deriveApiKeyEnvName derives for "fresh".
        Files.writeString(dir.resolve("clusters.toml"),
                          "[current]\ncontext = \"fresh\"\n\n[clusters.fresh]\nendpoint = \"http://127.0.0.1:"
                         + cluster.getAddress().getPort() + "\"\napi_key_env = \"AETHER_FRESH_API_KEY\"\n");
    }

    @AfterEach
    void stop() {
        cluster.stop(0);
    }

    private void runCli(String... args) throws IOException, InterruptedException {
        var command = new ArrayList<String>();

        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Duser.home=" + home);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(AetherCli.class.getName());
        command.addAll(List.of(args));
        var output = Files.createTempFile(home, "cli-", ".out");
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());

        builder.environment().remove("AETHER_API_KEY");
        builder.environment().remove("AETHER_FRESH_API_KEY");
        var process = builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null"))).start();

        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("CLI did not exit within 60 s; output so far:\n" + Files.readString(output));
        }
    }

    @Disabled("#2133 still present at 869dcab4637f: a plain command reads only the env var the registry names, never the saved api-key file. Enable when fixed and delete the tripwires below.")
    @Test
    void status_afterBootstrap_freshShell_dialsTheActiveClusterWithItsSavedKey() throws Exception {
        runCli("status");

        assertThat(requests).as("the active context is dialled").isNotEmpty();
        assertThat(requests.getFirst()).as("the saved key travels, or the operator gets a bare 401 (#2133)")
                  .endsWith("key=" + BOOTSTRAP_KEY);
    }

    @Disabled("#2133 still present at 869dcab4637f: see status_afterBootstrap_freshShell_dialsTheActiveClusterWithItsSavedKey. Enable when fixed.")
    @Test
    void clusterTopology_afterBootstrap_freshShell_dialsTheActiveClusterWithItsSavedKey() throws Exception {
        runCli("cluster", "topology");

        assertThat(requests).as("the active context is dialled").isNotEmpty();
        assertThat(requests.getFirst()).as("the saved key travels, or the operator gets a bare 401 (#2133)")
                  .endsWith("key=" + BOOTSTRAP_KEY);
    }

    /// TRIPWIRE for #2133: asserts what happens TODAY (no key sent: the 401 of the ticket). When it goes red the bug is
    /// fixed: enable the two `@Disabled` tests above and delete both tripwires.
    @Test
    void tripwire_status_afterBootstrap_freshShell_sendsNoKeyToday() throws Exception {
        runCli("status");

        assertThat(requests).isNotEmpty();
        assertThat(requests.getFirst()).as("#2133 fixed? enable status_afterBootstrap_freshShell_dialsTheActiveClusterWithItsSavedKey")
                  .endsWith("key=null");
    }

    @Test
    void tripwire_clusterTopology_afterBootstrap_freshShell_sendsNoKeyToday() throws Exception {
        runCli("cluster", "topology");

        assertThat(requests).isNotEmpty();
        assertThat(requests.getFirst()).as("#2133 fixed? enable clusterTopology_afterBootstrap_freshShell_dialsTheActiveClusterWithItsSavedKey")
                  .endsWith("key=null");
    }
}
