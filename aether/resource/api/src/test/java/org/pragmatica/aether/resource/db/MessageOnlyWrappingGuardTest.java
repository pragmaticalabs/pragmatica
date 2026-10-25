// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2408 rule: a database failure carries its typed cause; `DatabaseFailure(new RuntimeException(message))` is not
/// used anywhere. Typed causes (row-decode failures, SQLSTATE-carrying driver errors) were once replaced by an exception
/// built from `cause.message()`, so the caller could no longer tell a row-decode error from a constraint violation or a
/// lost connection and could not read the SQLSTATE or decide on a retry.
///
/// This scans the production sources of every database connector and driver adapter for the two shapes that wrote the
/// message-only wrapping, so reintroducing one anywhere fails here instead of surfacing as an untyped failure in a slice.
/// The behavioural pins live beside each connector (`*TypedErrorsTest`); this one covers sites no test reaches.
class MessageOnlyWrappingGuardTest {
    /// `databaseFailure(new RuntimeException(..))`: a fresh exception standing in for the failure.
    private static final Pattern FRESH_EXCEPTION_AS_FAILURE = Pattern.compile("atabaseFailure\\(\\s*new\\s+\\w*Exception\\(");
    /// `new RuntimeException(cause.message())`: an exception built from a cause's text, whatever wraps it.
    private static final Pattern EXCEPTION_FROM_CAUSE_TEXT = Pattern.compile("new\\s+\\w*Exception\\([^;]*\\.message\\(\\)\\s*\\)");
    private static final List<String> SCANNED_TREES = List.of("aether/resource", "integrations/db");
    private static final int MINIMUM_FILES_SCANNED = 100;

    @Test
    void connectorSources_neverWrapATypedCauseAsItsMessage() throws IOException {
        var root = repositoryRoot();
        var violations = SCANNED_TREES.stream()
                                      .flatMap(tree -> productionSources(root.resolve(tree)))
                                      .flatMap(MessageOnlyWrappingGuardTest::violationsIn)
                                      .toList();

        assertThat(violations).as("message-only wrapping of a typed database cause").isEmpty();
    }

    @Test
    void connectorSources_scanReachesTheConnectorsItGuards() throws IOException {
        var root = repositoryRoot();
        var scanned = SCANNED_TREES.stream().flatMap(tree -> productionSources(root.resolve(tree))).toList();

        assertThat(scanned).as("a scan that finds almost nothing would pass vacuously").hasSizeGreaterThan(MINIMUM_FILES_SCANNED);
        assertThat(scanned).anyMatch(path -> path.endsWith("PgAsyncSqlConnector.java"))
                           .anyMatch(path -> path.endsWith("AsyncJooqConnector.java"))
                           .anyMatch(path -> path.endsWith("R2dbcJooqConnector.java"))
                           .anyMatch(path -> path.endsWith("R2dbcSqlConnector.java"))
                           .anyMatch(path -> path.endsWith("JdbcSqlConnector.java"))
                           .anyMatch(path -> path.endsWith("R2dbcAdapterException.java"));
    }

    @Test
    void detector_flagsBothShapesOfTheMessageOnlyWrapping() {
        assertThat(isViolation("return DatabaseConnectorError.databaseFailure(new RuntimeException(cause.message()));")).isTrue();
        assertThat(isViolation("return DatabaseConnectorError.databaseFailure(new RuntimeException(\"boom\"));")).isTrue();
        assertThat(isViolation("return DatabaseConnectorError.databaseFailure(\n    new IllegalStateException(\"boom\"));")).as("split across lines").isTrue();
        assertThat(isViolation("sink.error(new R2dbcAdapterException(cause.message()));")).isTrue();
        assertThat(isViolation("return DatabaseConnectorError.databaseFailure(throwable);")).isFalse();
        assertThat(isViolation("sink.error(R2dbcAdapterException.from(cause));")).isFalse();
    }

    private static boolean isViolation(String source) {
        return FRESH_EXCEPTION_AS_FAILURE.matcher(source).find() || EXCEPTION_FROM_CAUSE_TEXT.matcher(source).find();
    }

    /// Reads the whole file, not line by line: the wrapping is routinely split across lines by the formatter.
    private static Stream<String> violationsIn(Path file) {
        try {
            return isViolation(Files.readString(file))
                   ? Stream.of(file.toString())
                   : Stream.empty();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    private static Stream<Path> productionSources(Path tree) {
        try (var walk = Files.walk(tree)) {
            return walk.filter(path -> path.toString().endsWith(".java"))
                       .filter(path -> path.toString().contains("/src/main/java/"))
                       .toList()
                       .stream();
        } catch (IOException e) {
            throw new IllegalStateException("cannot scan " + tree, e);
        }
    }

    /// The repository root, found by walking up from the module directory the test runs in.
    private static Path repositoryRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null && !Files.isDirectory(dir.resolve("aether/resource"))) {
            dir = dir.getParent();
        }

        assertThat(dir).as("repository root containing aether/resource").isNotNull();

        return dir;
    }
}
