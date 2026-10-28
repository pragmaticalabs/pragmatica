// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.jbct.slice.routing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// The per-route ingest keys of an inline-table route, `max_size` and `max_duration`, and the refusal of every other key:
/// the loader used to ignore a key it did not know, so `max_sise = "5GB"` left the route at the default limit with
/// nothing to say so. A key that is specified but not enforced yet (`resumable`, `unclaimed_ttl`) is refused too, rather than accepted and ignored.
class RouteConfigLoaderIngestLimitsTest {
    @TempDir
    Path tempDir;

    private Result<RouteConfig> load(String routes) throws IOException {
        var path = tempDir.resolve("routes.toml");

        Files.writeString(path, "prefix = \"/api\"\n\n[routes]\n" + routes + "\n");

        return RouteConfigLoader.load(path);
    }

    private static String failureOf(Result<RouteConfig> result) {
        assertThat(result.isFailure()).as("the load is refused").isTrue();

        return result.fold(cause -> cause.message(), _ -> "");
    }

    @Test
    void maxSizeAsAString_andMaxDuration_areParsed() throws IOException {
        var config = load("up = { route = \"POST /up\", consumes = \"multipart/form-data\", max_size = \"5GB\", max_duration = \"6h\" }")
                     .unwrap();
        var limits = config.routes().get("up").limits();

        assertThat(limits.maxSizeBytes()).isEqualTo(5L * 1024 * 1024 * 1024);
        assertThat(limits.maxDurationMillis()).isEqualTo(TimeUnit.HOURS.toMillis(6));
    }

    @Test
    void maxSizeAsAnInteger_isBytes() throws IOException {
        var config = load("up = { route = \"POST /up\", max_size = 1048576 }").unwrap();

        assertThat(config.routes().get("up").limits().maxSizeBytes()).isEqualTo(1_048_576L);
        assertThat(config.routes().get("up").limits().maxDurationMillis()).isZero();
    }

    @Test
    void aRouteWithNoKeys_declaresNoLimits() throws IOException {
        var config = load("up = { route = \"POST /up\" }\nplain = \"GET /plain\"").unwrap();

        assertThat(config.routes().get("up").limits()).isEqualTo(RouteLimits.NONE);
        assertThat(config.routes().get("plain").limits()).isEqualTo(RouteLimits.NONE);
    }

    @Test
    void aMistypedKey_isRefused_namingItAndTheValidKeys() throws IOException {
        var message = failureOf(load("up = { route = \"POST /up\", max_sise = \"5GB\" }"));

        assertThat(message).contains("max_sise").contains("max_size").contains("max_duration");
    }

    @Test
    void aSpecifiedButNotYetEnforcedKey_isRefused_notIgnored() throws IOException {
        for (var key : new String[] {"resumable", "unclaimed_ttl"}) {
            var message = failureOf(load("up = { route = \"POST /up\", " + key + " = 1 }"));

            assertThat(message).as(key).contains(key).contains("does not enforce");
        }
    }

    @Test
    void badValues_areRefused_namingTheRouteAndTheKey() throws IOException {
        assertThat(failureOf(load("up = { route = \"POST /up\", max_size = \"5XB\" }"))).contains("'up'").contains("max_size");
        assertThat(failureOf(load("up = { route = \"POST /up\", max_size = 0 }"))).contains("greater than zero");
        assertThat(failureOf(load("up = { route = \"POST /up\", max_size = -5 }"))).contains("greater than zero");
        assertThat(failureOf(load("up = { route = \"POST /up\", max_duration = 30 }"))).contains("duration string");
        assertThat(failureOf(load("up = { route = \"POST /up\", max_duration = \"soon\" }"))).contains("max_duration");
        assertThat(failureOf(load("up = { route = \"POST /up\", max_duration = \"0s\" }"))).contains("greater than zero");
    }

    @Test
    void versionedRoute_keepsItsLimits() throws IOException {
        var path = tempDir.resolve("versioned.toml");

        Files.writeString(path, """
                          [api]
                          prefix = "/api"

                          [v1.routes]
                          up = { route = "POST /up", max_size = "2MB", max_duration = "90s" }
                          """);

        var config = RouteConfigLoader.load(path).unwrap();
        var limits = config.routes().get("upV1").limits();

        assertThat(limits.maxSizeBytes()).isEqualTo(2L * 1024 * 1024);
        assertThat(limits.maxDurationMillis()).isEqualTo(90_000L);
    }

    @Test
    void partKeys_areParsed_countsAsIntegersAndSizesAsStringsOrBytes() throws IOException {
        var config = load("up = { route = \"POST /up\", consumes = \"multipart/form-data\", max_files = 3, max_file_size = \"2MB\", max_fields = 10, max_field_size = 4096 }")
                     .unwrap();
        var limits = config.routes().get("up").limits();

        assertThat(limits.maxFiles()).isEqualTo(3);
        assertThat(limits.maxFileBytes()).isEqualTo(2L * 1024 * 1024);
        assertThat(limits.maxFields()).isEqualTo(10);
        assertThat(limits.maxFieldBytes()).isEqualTo(4096);
        assertThat(limits.maxSizeBytes()).as("the part keys do not imply a body limit").isZero();
        assertThat(limits.declared()).isTrue();
    }

    @Test
    void eachPartKey_alone_isParsedIntoItsOwnComponent() throws IOException {
        assertThat(load("up = { route = \"POST /up\", max_files = 7 }").unwrap().routes().get("up").limits())
            .isEqualTo(RouteLimits.routeLimits(0, 0, 7, 0, 0, 0));
        assertThat(load("up = { route = \"POST /up\", max_file_size = 700 }").unwrap().routes().get("up").limits())
            .isEqualTo(RouteLimits.routeLimits(0, 0, 0, 700, 0, 0));
        assertThat(load("up = { route = \"POST /up\", max_fields = 9 }").unwrap().routes().get("up").limits())
            .isEqualTo(RouteLimits.routeLimits(0, 0, 0, 0, 9, 0));
        assertThat(load("up = { route = \"POST /up\", max_field_size = 90 }").unwrap().routes().get("up").limits())
            .isEqualTo(RouteLimits.routeLimits(0, 0, 0, 0, 0, 90));
    }

    @Test
    void badPartValues_areRefused_namingTheRouteAndTheKey() throws IOException {
        assertThat(failureOf(load("up = { route = \"POST /up\", max_files = 0 }"))).contains("'up'").contains("max_files").contains("greater than zero");
        assertThat(failureOf(load("up = { route = \"POST /up\", max_files = -2 }"))).contains("max_files").contains("greater than zero");
        assertThat(failureOf(load("up = { route = \"POST /up\", max_files = 2.5 }"))).contains("max_files").contains("whole number");
        assertThat(failureOf(load("up = { route = \"POST /up\", max_files = \"3\" }"))).contains("max_files").contains("whole number");
        assertThat(failureOf(load("up = { route = \"POST /up\", max_fields = 0 }"))).contains("max_fields").contains("greater than zero");
        assertThat(failureOf(load("up = { route = \"POST /up\", max_fields = 1.5 }"))).contains("max_fields").contains("whole number");
        assertThat(failureOf(load("up = { route = \"POST /up\", max_file_size = \"5XB\" }"))).contains("max_file_size");
        assertThat(failureOf(load("up = { route = \"POST /up\", max_file_size = 0 }"))).contains("max_file_size").contains("greater than zero");
        assertThat(failureOf(load("up = { route = \"POST /up\", max_field_size = \"lots\" }"))).contains("max_field_size");
        assertThat(failureOf(load("up = { route = \"POST /up\", max_field_size = -1 }"))).contains("max_field_size").contains("greater than zero");
    }

    @Test
    void versionedRoute_keepsItsPartLimits() throws IOException {
        var path = tempDir.resolve("versioned-parts.toml");

        Files.writeString(path, """
                          [api]
                          prefix = "/api"

                          [v1.routes]
                          up = { route = "POST /up", max_files = 2, max_fields = 5 }
                          """);

        var limits = RouteConfigLoader.load(path).unwrap().routes().get("upV1").limits();

        assertThat(limits.maxFiles()).isEqualTo(2);
        assertThat(limits.maxFields()).isEqualTo(5);
    }
}
