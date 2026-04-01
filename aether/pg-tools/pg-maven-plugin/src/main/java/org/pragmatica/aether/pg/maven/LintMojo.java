package org.pragmatica.aether.pg.maven;

import org.pragmatica.lang.Contract;
import org.pragmatica.aether.pg.schema.builder.MigrationProcessor;
import org.pragmatica.aether.pg.schema.event.SchemaEvent;
import org.pragmatica.aether.pg.schema.linter.LintDiagnostic;
import org.pragmatica.aether.pg.schema.linter.LintEngine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/// Maven goal that lints PostgreSQL migration SQL files for anti-patterns.
///
/// Reads V*.sql files from the schema directory, builds the schema model,
/// and runs the LintEngine to detect issues like missing indexes, lock hazards,
/// and naming convention violations.
@Contract
@Mojo(name = "lint", defaultPhase = LifecyclePhase.VALIDATE)
public class LintMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.basedir}/src/main/resources/schema", property = "pg.schemaDir")
    private File schemaDir;

    @Parameter(property = "pg.skip", defaultValue = "false")
    private boolean skip;

    @Override public void execute() throws MojoExecutionException, MojoFailureException {
        if ( skip) {
            getLog().info("pg-maven-plugin lint: skipped");
            return;
        }
        if ( !schemaDir.exists() || !schemaDir.isDirectory()) {
            getLog().info("Schema directory does not exist: " + schemaDir + " — nothing to lint");
            return;
        }
        var scripts = readMigrationScripts();
        if ( scripts.isEmpty()) {
            getLog().info("No V*.sql migration files found in " + schemaDir);
            return;
        }
        var processor = MigrationProcessor.create();
        var allEvents = collectEvents(processor, scripts);
        var diagnostics = LintEngine.create().lint(allEvents);
        reportDiagnostics(diagnostics);
        failOnErrors(diagnostics);
    }

    private List<String> readMigrationScripts() throws MojoExecutionException {
        var sqlFiles = schemaDir.listFiles((dir, name) -> name.matches("V.*\\.sql"));
        if ( sqlFiles == null || sqlFiles.length == 0) {
        return List.of();}
        Arrays.sort(sqlFiles, Comparator.comparing(File::getName));
        try {
            return Arrays.stream(sqlFiles).map(File::toPath)
                                .map(LintMojo::readFileContent)
                                .toList();
        }
        catch (RuntimeException e) {
            throw new MojoExecutionException("Failed to read migration files", unwrapIoException(e));
        }
    }

    private List<SchemaEvent> collectEvents(
    MigrationProcessor processor,
    List<String> scripts) throws MojoExecutionException {
        var allEvents = new ArrayList<SchemaEvent>();
        for (var sql : scripts) {
            var result = processor.analyzeScript(sql);
            if (result.isFailure()) {
                var errorMessage = result.fold(cause -> cause.message(), _ -> "");
                throw new MojoExecutionException("Failed to parse migration: " + errorMessage);
            }
            allEvents.addAll(result.unwrap());
        }
        return allEvents;
    }

    private void reportDiagnostics(List<LintDiagnostic> diagnostics) {
        for ( var diag : diagnostics) {
        switch ( diag.severity()) {
            case ERROR -> getLog().error(diag.toString());
            case WARNING -> getLog().warn(diag.toString());
            case INFO -> getLog().info(diag.toString());
        }}
        if ( diagnostics.isEmpty()) {
        getLog().info("pg:lint — no issues found in " + schemaDir);} else
        {
        getLog().info("pg:lint — " + diagnostics.size() + " diagnostic(s) reported");}
    }

    private static void failOnErrors(List<LintDiagnostic> diagnostics) throws MojoFailureException {
        var errorCount = diagnostics.stream().filter(d -> d.severity() == LintDiagnostic.Severity.ERROR)
                                           .count();
        if ( errorCount > 0) {
        throw new MojoFailureException("pg:lint found " + errorCount + " error(s) — build failed");}
    }

    private static String readFileContent(Path path) {
        try {
            return Files.readString(path);
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to read " + path, e);
        }
    }

    private static Exception unwrapIoException(RuntimeException e) {
        return e.getCause() instanceof IOException io
               ? io
               : e;
    }
}
