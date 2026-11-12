// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.example;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.routing.RouteMountMode;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.terra.TerraApplication;
import org.pragmatica.terra.TerraBlueprint;
import org.pragmatica.terra.http.TerraHttpConfig;
import org.pragmatica.terra.http.TerraHttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2157 part b on Terra, through the generated route and factories of three slices (`caller/edge`, `caller/mid`,
/// `caller/leaf`): the context the edge authenticated reaches a `SecurityContext` parameter at the edge, one hop and two
/// hops away (Terra hands slices to each other directly, so the argument is the caller's own value). A public route
/// called with no credential hands every hop the anonymous context.
class TerraCallerPropagationTest {
    private static final Map<String, ApiKeyEntry> KEYS = Map.of("reader-secret",
                                                                ApiKeyEntry.apiKeyEntry("reader", Set.of("viewer"), "VIEWER"));
    private static final List<String> SLICES = List.of("org.pragmatica.example:terra-caller-leaf:1.0.0-rc5",
                                                       "org.pragmatica.example:terra-caller-mid:1.0.0-rc5",
                                                       "org.pragmatica.example:terra-caller-edge:1.0.0-rc5");

    @Test
    void edgeToMidToLeaf_theLeafSeesTheContextTheEdgeAuthenticated_andAnUnauthenticatedCallerIsAnonymous() throws Exception {
        var app = TerraApplication.start(new TerraBlueprint(SLICES), _ -> ConfigurationProvider.builder().build())
                                  .await(timeSpan(10).seconds())
                                  .unwrap();
        var config = TerraHttpConfig.terraHttpConfig(HttpServerConfig.httpServerConfig("caller", 0),
                                                     RouteMountMode.pathMode(),
                                                     SecurityPolicy.authenticated())
                                    .unwrap();
        var host = TerraHttpServer.start(app, config, HttpAuthenticator.apiKeyValidator(KEYS))
                                  .await(timeSpan(10).seconds())
                                  .unwrap();

        try (var client = HttpClient.newHttpClient()) {
            var prefix = "http://127.0.0.1:" + host.port() + "/api/caller";
            var authenticated = client.send(request(prefix + "/enter").header("X-API-Key", "reader-secret").build(),
                                            HttpResponse.BodyHandlers.ofString());
            var refused = client.send(request(prefix + "/enter").build(), HttpResponse.BodyHandlers.ofString());
            var open = client.send(request(prefix + "/open").build(), HttpResponse.BodyHandlers.ofString());

            assertThat(authenticated.statusCode()).as(authenticated.body()).isEqualTo(200);
            assertThat(authenticated.body()).contains("\"principal\":\"api-key:reader\"")
                                            .contains("\"roles\":[\"viewer\"]")
                                            .contains("\"path\":\"edge>mid>leaf\"");
            assertThat(refused.statusCode()).as("control: the authenticated route refuses a request with no credential")
                                            .isEqualTo(401);
            assertThat(open.statusCode()).as(open.body()).isEqualTo(200);
            assertThat(open.body()).contains("\"principal\":\"anonymous\"")
                                   .contains("\"roles\":[]")
                                   .contains("\"path\":\"open-edge>mid>leaf\"");
        } finally {
            assertThat(host.close().await(timeSpan(10).seconds()).isSuccess()).isTrue();
        }
    }

    private static HttpRequest.Builder request(String uri) {
        return HttpRequest.newBuilder(URI.create(uri))
                          .timeout(Duration.ofSeconds(5));
    }
}
