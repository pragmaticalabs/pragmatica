// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.container;

import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.testkit.TestKitError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;


/// Applies a slice's `schema/` migrations to a freshly-started container so `@PgSql`
/// compile-validated queries run against the real schema (spec §5.2 step 4). Reads `*.sql` files
/// from the given classpath directory (sorted), splits on `;`, and executes them in order via the
/// provisioned connector — a single-section migration runner (spec §7.2 defers extraction).
public sealed interface SchemaMigrations {
    static Promise<Unit> apply(SqlConnector connector, String location) {
        return loadStatements(location).async()
                             .flatMap(statements -> applyFrom(connector, statements, 0));
    }

    private static Result<List<String>> loadStatements(String location) {
        return Result.lift(throwable -> schemaFailure(location, throwable), () -> readStatements(location));
    }

    private static Cause schemaFailure(String location, Throwable throwable) {
        return new TestKitError.SchemaApplicationFailed(location, Causes.fromThrowable(throwable));
    }

    // classpath directory lookup is a nullable JDK boundary; checked IO is lifted by loadStatements
    @SuppressWarnings({"JBCT-NULL-01", "JBCT-EX-01"})
    private static List<String> readStatements(String location) throws IOException, URISyntaxException {
        var resource = SchemaMigrations.class.getClassLoader().getResource(location);

        if (resource == null) {
            return List.of();
        }

        return splitStatements(readDirectory(Path.of(resource.toURI())));
    }

    // checked file IO is lifted to a Result by loadStatements
    @SuppressWarnings("JBCT-EX-01")
    private static String readDirectory(Path directory) throws IOException {
        var script = new StringBuilder();

        try (var files = Files.list(directory)) {
            for (var file : files.filter(SchemaMigrations::isSqlFile).sorted().toList()) {
                script.append(Files.readString(file)).append('\n');
            }
        }

        return script.toString();
    }

    private static boolean isSqlFile(Path path) {
        return path.toString()
                   .endsWith(".sql");
    }

    private static List<String> splitStatements(String script) {
        return Arrays.stream(script.split(";"))
                     .map(String::trim)
                     .filter(statement -> !statement.isEmpty())
                     .filter(statement -> !statement.startsWith("--"))
                     .toList();
    }

    private static Promise<Unit> applyFrom(SqlConnector connector, List<String> statements, int index) {
        return index >= statements.size()
               ? Promise.unitPromise()
               : connector.update(statements.get(index))
                          .flatMap(_ -> applyFrom(connector, statements, index + 1));
    }

    record unused() implements SchemaMigrations {}
}
