// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.container;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.testkit.TestKitError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


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

    /// A LOOP over the statements, not a `flatMap` per statement (the #1392 / #1395 shape). A statement whose update
    /// is already settled when it returns (an in-process connector answers synchronously) used to run the next one
    /// inline, so a script of k statements nested k frame groups, measured at 6 frames per statement: 600 statements
    /// is about 3,600 frames, the depth that overflowed CI's 1 MB stack in #1392. A settled statement is consumed in
    /// place; only a pending one suspends the loop, which resumes on the thread that settles it.
    private static Promise<Unit> applyFrom(SqlConnector connector, List<String> statements, int index) {
        var output = Promise.<Unit> promise();

        applyLoop(connector, statements, index, output);

        return output;
    }

    @Contract
    private static void applyLoop(SqlConnector connector,
                                  List<String> statements,
                                  int firstIndex,
                                  Promise<Unit> output) {
        var index = firstIndex;

        while (index < statements.size()) {
            var step = containedUpdate(connector, statements.get(index));

            if (!step.isResolved()) {
                var next = index + 1;

                step.onResult(result -> resumeApply(result, connector, statements, next, output));

                return;
            }

            if (settledResult(step) instanceof Result.Failure<?>(var cause)) {
                output.fail(cause);

                return;
            }

            index++;
        }

        output.succeed(Unit.unit());
    }

    @Contract
    private static void resumeApply(Result<?> result,
                                    SqlConnector connector,
                                    List<String> statements,
                                    int next,
                                    Promise<Unit> output) {
        if (result instanceof Result.Failure<?>(var cause)) {
            output.fail(cause);
        } else {
            applyLoop(connector, statements, next, output);
        }
    }

    /// A connector that THROWS instead of returning a promise is a failed statement, so the loop ends exactly once on
    /// both the inline and the resumed path.
    private static Promise<Integer> containedUpdate(SqlConnector connector, String statement) {
        return Result.lift(() -> connector.update(statement)).fold(Cause::promise, step -> step);
    }

    /// The result of a promise the caller has checked is resolved: `Promise.onResult` runs its consumer inline on a
    /// settled promise, so the holder is filled before this returns. Not `await()`: that is the blocking join.
    private static <T> Result<T> settledResult(Promise<T> resolved) {
        var holder = new AtomicReference<Result<T>>();

        resolved.onResult(holder::set);

        return holder.get();
    }

    record unused() implements SchemaMigrations {}
}
