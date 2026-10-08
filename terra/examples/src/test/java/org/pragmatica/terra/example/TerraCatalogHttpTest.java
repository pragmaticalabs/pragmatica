// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.example;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
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

class TerraCatalogHttpTest {
    @Test void serve_unchangedCatalog_supportsVersionsCsvBinaryAndTypedErrors() throws Exception {
        for (var mode : List.of(RouteMountMode.pathMode(), RouteMountMode.headerMode("API-Version"))) {
            var app = TerraApplication.start(new TerraBlueprint(List.of("org.pragmatica.example:terra-catalog:1.0.0-rc4")),
                _ -> ConfigurationProvider.builder().build()).await(timeSpan(10).seconds()).unwrap();
            var config = TerraHttpConfig.terraHttpConfig(HttpServerConfig.httpServerConfig("catalog", 0), mode,
                SecurityPolicy.authenticated()).unwrap();
            var host = TerraHttpServer.start(app, config, HttpAuthenticator.denyUnlessPublicValidator())
                .await(timeSpan(10).seconds()).unwrap();
            try (var client = HttpClient.newHttpClient()) {
                var prefix = "http://127.0.0.1:" + host.port() + "/api/catalog" + (mode.isHeaderMode() ? "" : "/v2");
                var list = client.send(request(prefix + "/items"), HttpResponse.BodyHandlers.ofString());
                assertThat(list.statusCode()).isEqualTo(200);
                assertThat(list.body()).contains("items");
                var csv = client.send(request(prefix + "/items.csv"), HttpResponse.BodyHandlers.ofString());
                assertThat(csv.statusCode()).isEqualTo(200);
                assertThat(csv.headers().firstValue("Content-Type").orElseThrow()).contains("text/csv");
                var image = client.send(request(prefix + "/items/1/image"), HttpResponse.BodyHandlers.ofByteArray());
                assertThat(image.statusCode()).isEqualTo(200);
                assertThat(image.headers().firstValue("Content-Type").orElseThrow()).contains("application/octet-stream");
                assertThat(image.body()).isNotEmpty();
                assertThat(client.send(request(prefix + "/items/not-a-number"), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
                assertThat(client.send(request(prefix + "/items/999999"), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
                var post = HttpRequest.newBuilder(URI.create(prefix + "/import")).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "text/csv").POST(HttpRequest.BodyPublishers.ofString("bad csv")).build();
                assertThat(client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
            } finally {
                assertThat(host.close().await(timeSpan(10).seconds()).isSuccess()).isTrue();
            }
        }
    }
    private static HttpRequest request(String uri) {
        return HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(5)).build();
    }
}
