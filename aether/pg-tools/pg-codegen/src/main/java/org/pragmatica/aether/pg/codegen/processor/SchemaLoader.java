// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import org.pragmatica.aether.pg.schema.builder.MigrationProcessor;
import org.pragmatica.aether.pg.schema.model.Schema;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;


public final class SchemaLoader {
    private final ProcessingEnvironment processingEnv;
    private final Map<String, Schema> cache = new HashMap<>();

    public SchemaLoader(ProcessingEnvironment processingEnv) {
        this.processingEnv = processingEnv;
    }

    public static String configToSchemaPath(String configPath) {
        if (configPath.equals("database")) {
            return "schema/";
        }

        if (configPath.startsWith("database.")) {
            return "schema/" + configPath.substring("database.".length()) + "/";
        }

        return "schema/";
    }

    public Option<Schema> loadSchema(String configPath) {
        var cached = cache.get(configPath);

        if (cached != null) {
            return Option.present(cached);
        }

        var schemaPath = configToSchemaPath(configPath);
        var scripts = loadMigrationScripts(schemaPath);

        if (scripts.isEmpty()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                                                     ProcessorError.schemaLoadFailed(schemaPath,
                                                                                     "No migration files found"));

            return Option.empty();
        }

        var result = MigrationProcessor.create().processAll(scripts);

        result.onFailure(cause -> processingEnv.getMessager()
                                               .printMessage(Diagnostic.Kind.ERROR,
                                                             ProcessorError.schemaLoadFailed(schemaPath,
                                                                                             cause.message())));
        if (result.isFailure()) {
            return Option.empty();
        }

        var schema = result.expect("checked with isFailure above");

        cache.put(configPath, schema);

        return Option.present(schema);
    }

    public boolean isCached(String configPath) {
        return cache.containsKey(configPath);
    }

    public List<String> loadMigrations(String configPath) {
        var schemaPath = configToSchemaPath(configPath);

        return loadMigrationScripts(schemaPath);
    }

    private List<String> loadMigrationScripts(String schemaPath) {
        var scripts = new ArrayList<String>();

        for (var fileName : discoverMigrationFiles(schemaPath)) {
            readSchemaResource(schemaPath, fileName).onPresent(scripts::add);
        }

        return scripts;
    }

    /// Discovers migration files for the given schema path by globbing the schema directory
    /// (or directories) for files named `V<digits>__<name>.sql`, ordered by numeric version.
    /// The optional `migrations.list` manifest is advisory only: a migration present on disk
    /// but absent from the manifest is reported as a WARNING, never silently skipped. When no
    /// directory can be enumerated (for example schema packaged inside a jar), discovery falls
    /// back to the manifest if one is present.
    private List<String> discoverMigrationFiles(String schemaPath) {
        return orderMigrations(collectRawNames(schemaPath))
            .onFailure(cause -> reportDiscoveryError(schemaPath, cause))
            .map(names -> resolveDiscovered(schemaPath, names))
            .or(List.of());
    }

    private List<String> resolveDiscovered(String schemaPath, List<String> globbed) {
        if (globbed.isEmpty()) {
            return manifestFileNames(schemaPath);
        }

        warnUnlistedMigrations(schemaPath, globbed);

        return globbed;
    }

    /// Orders and validates a flat list of candidate file names. Files matching
    /// `V<digits>__<name>.sql` are kept and ordered by numeric version; the same file reachable
    /// through several class-path roots is de-duplicated. A migration-shaped name with a
    /// non-numeric version is a malformed-version failure; two distinct files sharing a version
    /// is a duplicate-version failure. Any other name is ignored. Package-private so the pure
    /// ordering logic can be unit-tested without a compilation environment.
    static Result<List<String>> orderMigrations(List<String> fileNames) {
        var byVersion = new TreeMap<Integer, String>();

        for (var fileName : fileNames) {
            var outcome = classifyMigration(fileName, byVersion);

            if (outcome.isFailure()) {
                return outcome.map(_ -> List.<String>of());
            }
        }

        return Result.success(List.copyOf(byVersion.values()));
    }

    private static Result<Unit> classifyMigration(String fileName, Map<Integer, String> byVersion) {
        var strict = STRICT_MIGRATION.matcher(fileName);

        if (strict.matches()) {
            return parseVersion(strict.group(1), fileName)
                .flatMap(version -> recordMigration(version, fileName, byVersion));
        }

        if (MIGRATION_SHAPED.matcher(fileName).matches()) {
            return new MigrationDiscoveryError.MalformedVersion(fileName).result();
        }

        return Result.unitResult();
    }

    private static Result<Integer> parseVersion(String digits, String fileName) {
        return Number.parseInt(digits)
                     .mapError(_ -> new MigrationDiscoveryError.MalformedVersion(fileName));
    }

    private static Result<Unit> recordMigration(int version, String fileName, Map<Integer, String> byVersion) {
        var conflict = Option.option(byVersion.get(version))
                             .filter(existing -> !existing.equals(fileName));

        if (conflict.isPresent()) {
            return new MigrationDiscoveryError.DuplicateVersion(version,
                                                               conflict.expect("checked with isPresent above"),
                                                               fileName).result();
        }

        byVersion.put(version, fileName);

        return Result.unitResult();
    }

    private List<String> collectRawNames(String schemaPath) {
        var names = new ArrayList<String>();

        for (var dir : candidateDirs(schemaPath)) {
            names.addAll(listDirectoryEntries(dir));
        }

        return names;
    }

    /// Candidate filesystem directories that may hold the schema's migration files: the
    /// compiler output directory (where Maven copies `src/main/resources/schema`) and every
    /// class-path root. Non-directories are tolerated downstream, so they are not filtered here.
    private List<Path> candidateDirs(String schemaPath) {
        var dirs = new ArrayList<Path>();

        classOutputDir(schemaPath).onPresent(dirs::add);
        for (var root : classpathRoots()) {
            dirs.add(root.resolve(relativeSchemaPath(schemaPath)));
        }

        return dirs;
    }

    private Option<Path> classOutputDir(String schemaPath) {
        try {
            var anchor = processingEnv.getFiler()
                                      .getResource(StandardLocation.CLASS_OUTPUT, "", schemaPath + PROBE_NAME);

            return Option.option(Paths.get(anchor.toUri()).getParent());
        } catch (IOException | RuntimeException e) {
            return Option.empty();
        }
    }

    private static List<Path> classpathRoots() {
        var roots = new ArrayList<Path>();

        for (var entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                roots.add(Paths.get(entry));
            }
        }

        return roots;
    }

    private static List<String> listDirectoryEntries(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }

        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                         .map(path -> path.getFileName().toString())
                         .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<String> manifestFileNames(String schemaPath) {
        return readSchemaResource(schemaPath, MANIFEST_NAME)
            .map(SchemaLoader::parseManifestLines)
            .or(List.of());
    }

    private void warnUnlistedMigrations(String schemaPath, List<String> discovered) {
        readSchemaResource(schemaPath, MANIFEST_NAME)
            .onPresent(content -> warnAgainstManifest(schemaPath, discovered, content));
    }

    private void warnAgainstManifest(String schemaPath, List<String> discovered, String manifestContent) {
        var listed = parseManifestLines(manifestContent);

        for (var fileName : discovered) {
            if (!listed.contains(fileName)) {
                warnUnlisted(schemaPath, fileName);
            }
        }
    }

    private void warnUnlisted(String schemaPath, String fileName) {
        processingEnv.getMessager()
                     .printMessage(Diagnostic.Kind.WARNING,
                                   ProcessorError.unlistedMigration(fileName, schemaPath));
    }

    private void reportDiscoveryError(String schemaPath, Cause cause) {
        processingEnv.getMessager()
                     .printMessage(Diagnostic.Kind.ERROR,
                                   ProcessorError.schemaLoadFailed(schemaPath, cause.message()));
    }

    private static List<String> parseManifestLines(String content) {
        return Arrays.stream(content.split("\n"))
                     .map(String::trim)
                     .filter(line -> !line.isEmpty())
                     .toList();
    }

    /// Reads a schema resource by name, preferring the discovered filesystem directories and
    /// falling back to the annotation-processor class-path (which can read resources packaged
    /// inside jars).
    private Option<String> readSchemaResource(String schemaPath, String fileName) {
        var fromDir = readFromCandidateDirs(schemaPath, fileName);

        return fromDir.isPresent()
               ? fromDir
               : readResource(schemaPath + fileName);
    }

    private Option<String> readFromCandidateDirs(String schemaPath, String fileName) {
        for (var dir : candidateDirs(schemaPath)) {
            var content = readFile(dir.resolve(fileName));

            if (content.isPresent()) {
                return content;
            }
        }

        return Option.empty();
    }

    private Option<String> readFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return Option.empty();
        }

        try {
            return Option.present(Files.readString(file));
        } catch (IOException e) {
            return Option.empty();
        }
    }

    private static String relativeSchemaPath(String schemaPath) {
        return schemaPath.endsWith("/")
               ? schemaPath.substring(0, schemaPath.length() - 1)
               : schemaPath;
    }

    private static final String MANIFEST_NAME = "migrations.list";
    private static final String PROBE_NAME = "__pg_migration_probe__";
    private static final Pattern STRICT_MIGRATION = Pattern.compile("V(\\d+)__.+\\.sql");
    private static final Pattern MIGRATION_SHAPED = Pattern.compile("V.+__.+\\.sql");

    private Option<String> readResource(String path) {
        try {
            var fileObject = processingEnv.getFiler().getResource(StandardLocation.CLASS_PATH, "", path);

            try (var reader = fileObject.openReader(true)) {
                var content = new StringBuilder();
                var buf = new char[4096];
                int read;

                while ((read = reader.read(buf)) >= 0) {
                    content.append(buf, 0, read);
                }

                return Option.present(content.toString());
            }
        } catch (IOException e) {
            return Option.empty();
        }
    }

    public static Result<Schema> loadFromScripts(List<String> scripts) {
        return MigrationProcessor.create().processAll(scripts);
    }
}
