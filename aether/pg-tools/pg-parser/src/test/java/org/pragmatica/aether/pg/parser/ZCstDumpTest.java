// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.parser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

/// THROWAWAY sensor for the peglib 0.6.0 -> 0.7.2 migration. Delete before committing.
///
/// Serialises the `PostgresParser.CstNode` tree — the facade's PUBLIC shape, which all 22
/// consumers read — for every SQL file in the repo. Running this before and after the migration
/// and diffing the output proves the facade is preserved, which no unit test can do on its own.
class ZCstDumpTest {
    private static final Path OUT = Path.of(System.getProperty("cstdump.out", "/tmp/cst-dump.txt"));

    @Test
    void dump_allSqlFiles() throws Exception {
        var root = repoRoot();
        var files = Files.walk(root)
                         .filter(p -> p.toString().endsWith(".sql"))
                         .filter(p -> !p.toString().contains("/target/"))
                         .filter(p -> !p.toString().contains("/.git/"))
                         .sorted(Comparator.comparing(p -> root.relativize(p).toString()))
                         .toList();
        var parser = PostgresParser.create();
        var out = new StringBuilder();

        for (var file : files) {
            var rel = root.relativize(file).toString();
            var sql = Files.readString(file);

            out.append("=== ").append(rel).append(" (").append(sql.length()).append(" chars)\n");
            parser.parseCst(sql)
                  .onFailure(cause -> out.append("  PARSE-FAILURE: ").append(cause.message()).append('\n'))
                  .onSuccess(node -> render(node, 0, out));
            // statement-level view, which pg-schema and pg-codegen actually consume
            parser.parseScript(sql)
                  .onFailure(cause -> out.append("  SCRIPT-FAILURE: ").append(cause.message()).append('\n'))
                  .onSuccess(statements -> out.append("  statements: ").append(statements.size()).append('\n'));
        }

        Files.writeString(OUT, out.toString());
        System.out.println("@@CSTDUMP files=" + files.size() + " bytes=" + out.length() + " -> " + OUT);
    }

    private static void render(PostgresParser.CstNode node, int depth, StringBuilder out) {
        out.append("  ".repeat(depth + 1));

        switch (node) {
            case PostgresParser.CstNode.NonTerminal nt -> {
                out.append("NonTerminal ").append(nt.ruleName()).append(' ').append(nt.span()).append('\n');
                nt.children().forEach(child -> render(child, depth + 1, out));
            }
            case PostgresParser.CstNode.Token tok ->
                out.append("Token ").append(tok.ruleName()).append(' ').append(tok.span())
                   .append(" [").append(tok.text()).append("]\n");
            case PostgresParser.CstNode.Terminal term ->
                out.append("Terminal ").append(term.ruleName()).append(' ').append(term.span())
                   .append(" [").append(term.text()).append("]\n");
            case PostgresParser.CstNode.Error err ->
                out.append("Error ").append(err.span()).append(" skipped=[").append(err.skippedText())
                   .append("] expected=[").append(err.expected()).append("]\n");
        }
    }

    private static Path repoRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null && !Files.exists(dir.resolve("aether/pg-tools/pom.xml"))) {
            dir = dir.getParent();
        }

        return dir;
    }

    @Test
    void dump_errorShapes() throws Exception {
        var parser = PostgresParser.create();
        var broken = List.of("SELECT FROM;",
                             "CREATE TABLE (;",
                             "SELCT 1;",
                             "CREATE TABLE t (id INT",
                             "");
        var out = new StringBuilder();

        for (var sql : broken) {
            out.append("=== [").append(sql).append("]\n");
            parser.parseCst(sql)
                  .onFailure(cause -> out.append("  FAILURE: ").append(cause.message()).append('\n'))
                  .onSuccess(node -> render(node, 0, out));
        }

        Files.writeString(Path.of(OUT + ".errors"), out.toString());
        System.out.println("@@CSTDUMP-ERRORS bytes=" + out.length());
    }
}
