// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.parser;

import org.pragmatica.peg.PegParser;
import org.pragmatica.peg.generator.ErrorReporting;

import java.nio.file.Files;
import java.nio.file.Path;

/// Generator tool: reads postgres.peg and produces a standalone PgSqlParser.java.
public class GenerateStandaloneParser {

    public static void main(String[] args) throws Exception {
        var grammar = PostgresGrammar.GRAMMAR;
        var source = PegParser.generateCstParser(grammar,
                "org.pragmatica.aether.pg.parser", "PgSqlParser",
                ErrorReporting.ADVANCED)
            .unwrap();

        var outputDir = Path.of("pg-parser/src/main/java/org/pragmatica/aether/pg/parser");
        Files.createDirectories(outputDir);
        var outputFile = outputDir.resolve("PgSqlParser.java");
        Files.writeString(outputFile, source);

        System.out.println("Generated " + outputFile + " (" + source.lines().count() + " lines)");
    }
}
