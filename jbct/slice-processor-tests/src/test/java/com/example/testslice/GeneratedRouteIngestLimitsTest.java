// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package com.example.testslice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The ingest declaration the slice-processor emits into the generated `TestSliceRoutes` for the node that accepts the
/// request: `routes.toml` `max_size` / `max_duration` become the route's `UploadSpec`, and a binary or multipart `consumes`
/// marks the route an upload route. A route that is neither emits no `withUpload` at all, so every other generated route
/// is unchanged.
class GeneratedRouteIngestLimitsTest {
    private static List<String> lines;

    @BeforeAll
    static void readGeneratedSource() throws IOException {
        lines = Files.readAllLines(locateGeneratedRoutes());
    }

    private static Path locateGeneratedRoutes() {
        var moduleDir = Paths.get(System.getProperty("user.dir"));
        var relative = Paths.get("target", "generated-sources", "annotations", "com", "example", "testslice", "TestSliceRoutes.java");
        var candidate = moduleDir.resolve(relative);

        return Files.exists(candidate)
               ? candidate
               : moduleDir.resolve(Paths.get("jbct", "slice-processor-tests")).resolve(relative);
    }

    /// The emitted chain of the route `name`: the `.named("name")` line to its terminal `.as...` call.
    private static String chainOf(String name) {
        var start = lines.indexOf(lines.stream().filter(line -> line.contains(".named(\"" + name + "\")")).findFirst().orElseThrow());

        return lines.get(start).strip();
    }

    @Test
    void multipartRouteWithBothKeys_isAnUploadRouteWithBothLimits() {
        assertThat(chainOf("uploadForm")).contains(".withUpload(org.pragmatica.http.routing.UploadSpec.uploadSpec(true, 52428800L, 1800000L))");
    }

    @Test
    void binaryRouteWithNoKeys_isAnUploadRouteWithNoDeclaredLimits() {
        assertThat(chainOf("uploadBlob")).contains(".withUpload(org.pragmatica.http.routing.UploadSpec.uploadSpec(true, 0L, 0L))");
    }

    @Test
    void textRouteWithMaxSize_declaresTheLimit_butIsNotAnUploadRoute() {
        assertThat(chainOf("uploadText")).contains(".withUpload(org.pragmatica.http.routing.UploadSpec.uploadSpec(false, 1048576L, 0L))");
    }

    @Test
    void everyOtherRoute_emitsNoUploadDeclaration() {
        var withUpload = lines.stream()
                              .filter(line -> line.contains(".withUpload("))
                              .map(line -> line.substring(line.indexOf(".named(\"") + 8, line.indexOf("\")", line.indexOf(".named(\""))))
                              .collect(Collectors.toSet());

        assertThat(withUpload).containsExactlyInAnyOrder("uploadForm", "uploadBlob", "uploadText", "uploadParts");
    }

    @Test
    void multipartRouteWithPartKeys_carriesAllFourPartLimits() {
        assertThat(chainOf("uploadParts")).contains(".withUpload(org.pragmatica.http.routing.UploadSpec.uploadSpec(true, 8388608L, 0L, 3L, 2097152L, 10L, 4096L))");
    }

    @Test
    void routeWithoutPartKeys_generatesTheShortForm() {
        assertThat(chainOf("uploadForm")).as("no part limits declared: the three-argument form, as before")
                                         .doesNotContain("0L, 0L, 0L, 0L))");
    }
}
