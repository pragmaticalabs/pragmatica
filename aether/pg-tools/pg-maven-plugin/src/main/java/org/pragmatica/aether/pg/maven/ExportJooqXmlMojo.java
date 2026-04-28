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


@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"}) @Contract@Mojo(name = "export-jooq-xml", threadSafe = true) public class ExportJooqXmlMojo extends AbstractMojo {
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

    @Contract@SuppressWarnings("JBCT-EX-01") @Override public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("pg:export-jooq-xml skipped");
            return;
        }
        if (!schemaDir.exists() || !schemaDir.isDirectory()) {
            getLog().info("Schema directory does not exist: " + schemaDir + " — nothing to export");
            return;
        }
        var sqlFiles = findMigrationFiles();
        if (sqlFiles.length == 0) {
            getLog().info("No V*.sql migration files found in " + schemaDir);
            return;
        }
        var scripts = readMigrationScripts(sqlFiles);
        var schema = MigrationProcessor.create().processAll(scripts);
        if (schema.isFailure()) {throw new MojoExecutionException("Schema parsing failed: " + schema);}
        var config = buildConfig();
        var result = JooqXmlExporter.writeXml(schema.unwrap(), config, outputFile.toPath());
        if (result.isFailure()) {throw new MojoExecutionException("XML export failed: " + result);}
        logStats(schema.unwrap());
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

    private void logStats(org.pragmatica.aether.pg.schema.model.Schema schema) {
        getLog().info("Exported jOOQ XML: " + schema.tables().size() + " tables, " + schema.sequences().size() + " sequences, " + schema.enumTypes()
                                                                                                                                                  .size() + " enums → " + outputFile);
    }

    private File[] findMigrationFiles() {
        var files = schemaDir.listFiles((_, name) -> name.matches("V.*\\.sql"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    @SuppressWarnings("JBCT-EX-01") private java.util.List<String> readMigrationScripts(File[] sqlFiles) throws MojoExecutionException {
        try {
            return Arrays.stream(sqlFiles).map(ExportJooqXmlMojo::readFile)
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
