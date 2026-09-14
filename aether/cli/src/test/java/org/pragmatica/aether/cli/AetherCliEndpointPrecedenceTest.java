// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #584 (2), the half a registry-level test could not see: `AetherCli.main` installed `localhost:8080`
/// as the endpoint override UNCONDITIONALLY when no `--connect`/`--config` was given, so
/// `ClusterHttpClient.resolveEndpoint` never fell through to `registry.current()` and the active
/// context steered nothing but `destroy`/`rotate-key` (which install their own override). A fresh
/// bootstrap could activate its context all it liked — `aether cluster scale` still dialled
/// `127.0.0.1:8080`, which with nothing listening is exactly the ticket's bare `ConnectException`.
///
/// Precedence pinned here, through the REAL entrypoint (a child JVM running `AetherCli.main` under a
/// redirected `user.home`, the only way `ClusterRegistry.DEFAULT_REGISTRY_PATH` — a static read of
/// `user.home` — can be pointed at a scratch registry without touching the operator's):
/// explicit `--connect`/`--endpoint` (and `--config`) > active cluster context > built-in localhost
/// default. What is asserted is which listener received the request — the dialled host, not a log line.
class AetherCliEndpointPrecedenceTest {
    @TempDir
    Path home;

    private HttpServer context;
    private HttpServer explicit;
    private final List<String> contextRequests = new CopyOnWriteArrayList<>();
    private final List<String> explicitRequests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void listeners() throws IOException {
        context = listener(contextRequests);
        explicit = listener(explicitRequests);
    }

    @AfterEach
    void stopListeners() {
        context.stop(0);
        explicit.stop(0);
    }

    private static HttpServer listener(List<String> requests) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/",
                             exchange -> {
                                 requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                                              + " key=" + exchange.getRequestHeaders().getFirst("X-API-Key"));
                                 var body = "{}".getBytes(StandardCharsets.UTF_8);

                                 exchange.sendResponseHeaders(200, body.length);
                                 exchange.getResponseBody()
                                         .write(body);
                                 exchange.close();
                             });
        server.start();

        return server;
    }

    private String endpointOf(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress()
                                           .getPort();
    }

    /// The post-bootstrap registry exactly as `BootstrapPhasePost.registerAndActivate` writes it: the
    /// bootstrapped cluster is the current context; a stale entry sits beside it.
    private void writeRegistry(String current) throws IOException {
        var dir = home.resolve(".aether");

        Files.createDirectories(dir);
        Files.writeString(dir.resolve("clusters.toml"),
                          "[current]\ncontext = \"" + current
                         + "\"\n\n"
                         + "[clusters.old-dead]\nendpoint = \"http://127.0.0.1:1\"\n\n"
                         + "[clusters.fresh]\nendpoint = \"" + endpointOf(context)
                         + "\"\napi_key_env = \"PROBE_FRESH_KEY\"\n");
    }

    /// Runs the real entrypoint in a child JVM: `main` calls `System.exit`, and the registry path is a
    /// static read of `user.home`, so neither can be driven in-process.
    private String runCli(String... args) throws IOException, InterruptedException {
        var command = new ArrayList<String>();

        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Duser.home=" + home);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(AetherCli.class.getName());
        command.addAll(List.of(args));
        // Output goes to a file and stdin comes from /dev/null so `waitFor` is the FIRST blocking call:
        // reading the pipe before waiting made the 60 s guard unreachable (a child hung on the network
        // or on stdin hung the whole surefire fork — review of #1079, SF-4).
        var output = Files.createTempFile(home, "cli-", ".out");
        var builder = new ProcessBuilder(command).redirectErrorStream(true)
                                                 .redirectOutput(output.toFile());
        builder.environment().put("PROBE_FRESH_KEY", "fresh-secret-123");
        builder.environment().remove("AETHER_API_KEY");
        var process = builder
                                                 .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                                                 .start();

        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("CLI did not exit within 60 s; output so far:\n" + Files.readString(output));
        }

        return Files.readString(output);
    }

    @Test
    void clusterScale_noFlags_dialsTheActiveContext_notLocalhost() throws Exception {
        writeRegistry("fresh");
        runCli("cluster", "scale", "--count", "3", "--yes");
        assertThat(contextRequests).as("the active context is what routes a context-routed command — the "
                                      + "ticket's `cluster scale` after bootstrap")
                  .isNotEmpty();
        assertThat(contextRequests.getFirst()).startsWith("GET /api/v1/cluster/config");
        assertThat(contextRequests.getFirst()).as("the context supplied the endpoint, so the context's credential travels with it")
                                              .endsWith("key=fresh-secret-123");
        assertThat(explicitRequests).isEmpty();
    }

    @Test
    void topLevelCommand_noFlags_dialsTheActiveContext_too() throws Exception {
        writeRegistry("fresh");
        runCli("status");
        assertThat(contextRequests).as("the legacy top-level commands resolve through the same precedence, "
                                      + "not a private localhost default")
                  .isNotEmpty();
        assertThat(contextRequests.getFirst()).endsWith("key=fresh-secret-123");
    }

    @Test
    void explicitConnect_winsOverTheActiveContext() throws Exception {
        writeRegistry("fresh");
        runCli("--connect", endpointOf(explicit), "cluster", "scale", "--count", "3", "--yes");
        assertThat(explicitRequests).isNotEmpty();
        assertThat(explicitRequests.getFirst()).as("an explicit endpoint gets NO credential from the context — a typo'd "
                                                   + "or hostile --connect must not receive the active cluster's key")
                                               .endsWith("key=null");
        assertThat(contextRequests).as("an explicit endpoint is the operator's word; the context must not be consulted")
                  .isEmpty();
    }

    @Test
    void explicitConnect_withExplicitKey_sendsThatKey() throws Exception {
        writeRegistry("fresh");

        runCli("--connect", endpointOf(explicit), "--api-key", "explicit-key", "status");

        assertThat(explicitRequests.getFirst()).endsWith("key=explicit-key");
    }

    @Test
    void topLevelCommand_explicitConnect_sendsNoContextKey() throws Exception {
        writeRegistry("fresh");

        runCli("--connect", endpointOf(explicit), "status");

        assertThat(explicitRequests).isNotEmpty();
        assertThat(explicitRequests.getFirst()).as("the top-level family follows the same rule as the cluster family")
                                               .endsWith("key=null");
    }

    /// SF-3: a `--config` path that does not exist used to fall through to the context, so an operator
    /// who mistyped a LOCAL config file ran the command against the cloud. It now warns and takes the
    /// config-failure path (the localhost default), never the context.
    @Test
    void missingConfigPath_warnsAndNeverRoutesToTheContext() throws Exception {
        writeRegistry("fresh");

        var output = runCli("--config", home.resolve("does-not-exist.toml").toString(), "status");

        assertThat(contextRequests).as("a mistyped local config must not become a cloud request").isEmpty();
        assertThat(output).contains("does-not-exist.toml");
    }

    @Test
    void clusterFlag_winsOverTheActiveContext() throws Exception {
        writeRegistry("fresh");
        Files.writeString(home.resolve(".aether").resolve("clusters.toml"),
                          Files.readString(home.resolve(".aether").resolve("clusters.toml"))
                         + "\n[clusters.other]\nendpoint = \"" + endpointOf(explicit)
                         + "\"\n");
        runCli("cluster", "scale", "--cluster", "other", "--count", "3", "--yes");
        assertThat(explicitRequests).isNotEmpty();
        assertThat(explicitRequests.getFirst()).as("--cluster X carries X's key or nothing — never the context's")
                                               .endsWith("key=null");
        assertThat(contextRequests).isEmpty();
    }
}
