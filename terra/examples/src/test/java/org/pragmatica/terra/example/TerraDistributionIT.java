// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.example;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class TerraDistributionIT {
    @TempDir Path directory;

    @Test void launch_extractedDistribution_servesCatalogOutsideCheckoutAndStopsOnSignal() throws Exception {
        var bundle = extract();
        var output = directory.resolve("process.log");
        var process = command(bundle).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try (var client = HttpClient.newHttpClient()) {
            var port = waitForPort(process, output);
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/catalog/v2/items"))
                .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("items");
            process.destroy();
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).as(Files.readString(output)).isTrue();
            assertThat(Files.readString(output)).doesNotContain("Terra shutdown failed", "Terra startup failed");
        } finally { process.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
    }

    @Test void check_extractedDistribution_validatesWithoutOpeningListener() throws Exception {
        var bundle = extract();
        var output = directory.resolve("check.log");
        var command = command(bundle);
        command.command().add("--check");
        var process = command.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(Files.readString(output)).isZero();
            assertThat(Files.readString(output)).contains("Terra check passed: 6 selected slices").doesNotContain("Terra ready on port");
        } finally { process.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
    }

    private ProcessBuilder command(Path bundle) {
        var command = new ProcessBuilder("sh", bundle.resolve("bin/terra").toString()).directory(directory.toFile());
        command.environment().put("JAVA_HOME", System.getProperty("java.home"));
        command.environment().put("TERRA_HTTP__PORT", "0");
        return command;
    }

    private Path extract() throws Exception {
        Path archive;
        try (var files = Files.list(Path.of("target"))) {
            archive = files.filter(path -> path.toString().endsWith("-terra.zip")).findFirst().orElseThrow();
        }
        var bundle = Files.createDirectories(directory.resolve("bundle"));
        try (var zip = new ZipFile(archive.toFile())) {
            for (var entry : zip.stream().toList()) {
                var target = bundle.resolve(entry.getName()).normalize();
                assertThat(target.startsWith(bundle)).isTrue();
                Files.createDirectories(target.getParent());
                try (var input = zip.getInputStream(entry)) { Files.copy(input, target); }
            }
        }
        return bundle;
    }

    private static int waitForPort(Process process, Path output) throws Exception {
        var ready = Pattern.compile("Terra ready on port (\\d+)");
        var until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < until && process.isAlive()) {
            var matcher = ready.matcher(Files.readString(output));
            if (matcher.find()) { return Integer.parseInt(matcher.group(1)); }
            Thread.sleep(25);
        }
        throw new AssertionError("Terra did not start:\n" + Files.readString(output));
    }
}
