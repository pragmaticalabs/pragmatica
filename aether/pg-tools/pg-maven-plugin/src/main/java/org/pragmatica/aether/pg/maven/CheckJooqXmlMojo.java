// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.maven;

import org.pragmatica.aether.pg.codegen.jooq.JooqXmlConfig;
import org.pragmatica.aether.pg.codegen.jooq.JooqXmlExporter;
import org.pragmatica.aether.pg.schema.builder.MigrationProcessor;
import org.pragmatica.lang.Contract;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;


@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"}) @Contract@Mojo(name = "check-jooq-xml", threadSafe = true) public class CheckJooqXmlMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.basedir}/src/main/resources/schema", property = "pg.schemaDir") private File schemaDir;

    @Parameter(defaultValue = "${project.basedir}/src/main/resources/jooq/jooq-schema.xml", property = "pg.jooq.outputFile") private File outputFile;

    @Parameter(defaultValue = "public", property = "pg.jooq.defaultSchema") private String defaultSchemaName;

    @Parameter(defaultValue = "public", property = "pg.jooq.includedSchemas") private String includedSchemas;

    @Parameter(defaultValue = "", property = "pg.jooq.catalog") private String catalogName;

    @Parameter(defaultValue = "true", property = "pg.jooq.emitEnums") private boolean emitEnums;

    @Parameter(defaultValue = "true", property = "pg.jooq.emitIndexes") private boolean emitIndexes;

    @Parameter(defaultValue = "true", property = "pg.jooq.emitCheckConstraints") private boolean emitCheckConstraints;

    @Parameter(defaultValue = "true", property = "pg.jooq.emitComments") private boolean emitComments;

    @Parameter(defaultValue = "true", property = "pg.jooq.prettyPrint") private boolean prettyPrint;

    @Parameter(property = "pg.jooq.skip", defaultValue = "false") private boolean skip;

    private static final int MAX_DIFF_LINES = 20;

    @Contract@SuppressWarnings("JBCT-EX-01") @Override public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("pg:check-jooq-xml skipped");
            return;
        }
        if (!schemaDir.exists() || !schemaDir.isDirectory()) {
            getLog().info("Schema directory does not exist: " + schemaDir + " — nothing to check");
            return;
        }
        var sqlFiles = findMigrationFiles();
        if (sqlFiles.length == 0) {
            getLog().info("No V*.sql migration files found in " + schemaDir);
            return;
        }
        if (!outputFile.exists()) {throw new MojoExecutionException("Tracked XML not found: " + outputFile + "\nRun 'mvn pg:export-jooq-xml' to generate it.");}
        var scripts = readMigrationScripts(sqlFiles);
        var schema = MigrationProcessor.create().processAll(scripts);
        if (schema.isFailure()) {throw new MojoExecutionException("Schema parsing failed: " + schema);}
        var config = buildConfig();
        var generated = JooqXmlExporter.toXml(schema.unwrap(), config);
        if (generated.isFailure()) {throw new MojoExecutionException("XML export failed: " + generated);}
        var existing = readExistingFile();
        var generatedXml = generated.unwrap();
        if (existing.equals(generatedXml)) {getLog().info("OK — jOOQ XML schema is up to date (" + schema.unwrap().tables()
                                                                                                                .size() + " tables)");} else {
            reportDiff(existing, generatedXml);
            throw new MojoExecutionException("jOOQ XML schema is stale. Run 'mvn pg:export-jooq-xml' to regenerate.");
        }
    }

    private void reportDiff(String existing, String generated) {
        var existingLines = existing.split("\n");
        var generatedLines = generated.split("\n");
        getLog().error("jOOQ XML schema differs from tracked file:");
        var diffCount = 0;
        var maxLen = Math.max(existingLines.length, generatedLines.length);
        for (int i = 0;i <maxLen && diffCount <MAX_DIFF_LINES;i++) {
            var existingLine = i <existingLines.length
                              ? existingLines[i]
                              : "";
            var generatedLine = i <generatedLines.length
                               ? generatedLines[i]
                               : "";
            if (!existingLine.equals(generatedLine)) {
                diffCount++;
                getLog().error("  Line " + (i + 1) + ":");
                if (!existingLine.isEmpty()) getLog().error("    - " + existingLine);
                if (!generatedLine.isEmpty()) getLog().error("    + " + generatedLine);
            }
        }
        if (diffCount >= MAX_DIFF_LINES) {getLog().error("  ... (showing first " + MAX_DIFF_LINES + " differences)");}
    }

    private JooqXmlConfig buildConfig() {
        var schemas = Set.of(includedSchemas.split(","));
        return new JooqXmlConfig(catalogName,
                                 defaultSchemaName,
                                 schemas,
                                 "POSTGRES",
                                 emitEnums,
                                 emitIndexes,
                                 emitCheckConstraints,
                                 emitComments,
                                 true,
                                 prettyPrint);
    }

    @SuppressWarnings("JBCT-EX-01") private String readExistingFile() throws MojoExecutionException {
        try {
            return Files.readString(outputFile.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read tracked XML: " + outputFile, e);
        }
    }

    private File[] findMigrationFiles() {
        var files = schemaDir.listFiles((_, name) -> name.matches("V.*\\.sql"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    @SuppressWarnings("JBCT-EX-01") private java.util.List<String> readMigrationScripts(File[] sqlFiles) throws MojoExecutionException {
        try {
            return Arrays.stream(sqlFiles).map(CheckJooqXmlMojo::readFile)
                                .toList();
        } catch (RuntimeException e) {
            throw new MojoExecutionException("Failed to read migration files", e);
        }
    }

    @SuppressWarnings("JBCT-EX-01") private static String readFile(File file) {
        try {
            return Files.readString(file.toPath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + file, e);
        }
    }
}
