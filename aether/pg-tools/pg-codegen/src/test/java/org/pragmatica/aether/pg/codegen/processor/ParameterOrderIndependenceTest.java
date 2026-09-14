// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #648 — a `@Query` method's parameters are bound in SQL placeholder order, and the generated
/// implementation used to REORDER ITS OWN SIGNATURE to that order too. With differently typed
/// parameters that is a "does not override" error inside generated code, three steps from the
/// cause; with same-typed parameters it compiles and binds the caller's arguments to the wrong
/// placeholders. The generated signature must be the interface's declaration order; only the bind
/// list follows the placeholders.
class ParameterOrderIndependenceTest {
    @TempDir
    Path tempDir;

    private static final String SOURCE = """
        package test;

        import org.pragmatica.aether.pg.codegen.annotation.Query;
        import org.pragmatica.aether.resource.db.PgSql;
        import org.pragmatica.lang.Promise;
        import org.pragmatica.lang.Unit;

        @PgSql
        public interface UserRepo {

            @Query("UPDATE users SET name = :name WHERE id = :id")
            Promise<Unit> rename(long id, String name);

            @Query("UPDATE users SET email = :email WHERE name = :name")
            Promise<Unit> reassign(String name, String email);
        }
        """;

    @Test
    void placeholdersInReverseDeclarationOrder_generatedFactoryOverridesTheInterface_andBindsByName() throws Exception {
        var result = TestCompilationHelper.compileAndCompileGenerated(SOURCE, "test/UserRepo.java", tempDir);

        assertThat(result.success())
                .as("#648: the generated factory must compile against the interface; a reordered signature is "
                    + "'does not override': " + result.diagnostics())
                .isTrue();

        var generated = result.generatedSource("test.UserRepoFactory");

        assertThat(generated).isNotNull();
        assertThat(generated).as("signature keeps the declaration order (differently typed pair)")
                             .contains("rename(long id, String name)");
        assertThat(generated).as("bind list follows the placeholders: $1 = name, $2 = id")
                             .contains(", name, id)");
        assertThat(generated).as("same-typed pair: the signature is NOT swapped, so the caller's first argument is still name")
                             .contains("reassign(String name, String email)")
                             .doesNotContain("reassign(String email, String name)");
        assertThat(generated).as("same-typed pair binds $1 = email, $2 = name")
                             .contains(", email, name)");
    }
}
