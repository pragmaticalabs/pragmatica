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
