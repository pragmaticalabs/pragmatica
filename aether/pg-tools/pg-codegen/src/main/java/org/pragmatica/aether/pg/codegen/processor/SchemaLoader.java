package org.pragmatica.aether.pg.codegen.processor;

import org.pragmatica.aether.pg.schema.builder.MigrationProcessor;
import org.pragmatica.aether.pg.schema.model.Schema;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Loads migration SQL files from the classpath and builds a Schema model.
///
/// Convention: `config = "database"` maps to `schema/`, `config = "database.analytics"` maps to `schema/analytics/`.
/// Migration files are processed in Flyway order (`V001__`, `V002__`, etc.).
/// Schemas are cached per config path within a compilation unit.
public final class SchemaLoader {
    private final ProcessingEnvironment processingEnv;
    private final Map<String, Schema> cache = new HashMap<>();

    public SchemaLoader(ProcessingEnvironment processingEnv) {
        this.processingEnv = processingEnv;
    }

    /// Resolves a config path to a schema directory.
    ///
    /// `"database"` -> `schema/`
    /// `"database.analytics"` -> `schema/analytics/`
    public static String configToSchemaPath(String configPath) {
        if ( configPath.equals("database")) {
        return "schema/";}
        if ( configPath.startsWith("database.")) {
        return "schema/" + configPath.substring("database.".length()) + "/";}
        return "schema/";
    }

    /// Loads and caches a schema for the given config path.
    public Option<Schema> loadSchema(String configPath) {
        var cached = cache.get(configPath);
        if ( cached != null) {
        return Option.present(cached);}
        var schemaPath = configToSchemaPath(configPath);
        var scripts = loadMigrationScripts(schemaPath);
        if ( scripts.isEmpty()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                                                     ProcessorError.schemaLoadFailed(schemaPath,
                                                                                     "No migration files found"));
            return Option.empty();
        }
        var result = MigrationProcessor.create().processAll(scripts);
        result.onFailure(cause -> processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                                                           ProcessorError.schemaLoadFailed(schemaPath,
                                                                                                           cause.message())));
        if ( result.isFailure()) {
        return Option.empty();}
        var schema = result.unwrap();
        cache.put(configPath, schema);
        return Option.present(schema);
    }

    /// Checks if a schema is already cached for the given config path.
    public boolean isCached(String configPath) {
        return cache.containsKey(configPath);
    }

    private List<String> loadMigrationScripts(String schemaPath) {
        var scripts = new ArrayList<String>();
        // Try to load migration files using the filer
        // Convention: V001__description.sql, V002__description.sql, etc.
        // We scan for files matching the pattern in the schema directory
        var fileNames = discoverMigrationFiles(schemaPath);
        fileNames.sort(String::compareTo);
        // Flyway ordering (V001 < V002)
        for ( var fileName : fileNames) {
            var content = readResource(schemaPath + fileName);
            content.onPresent(scripts::add);
        }
        return scripts;
    }

    private List<String> discoverMigrationFiles(String schemaPath) {
        // Try reading a manifest file that lists migrations
        var manifest = readResource(schemaPath + "migrations.list");
        if ( manifest.isPresent()) {
        return new ArrayList<>(List.of(manifest.unwrap().split("\n")));}
        // Fallback: try common migration file patterns V001-V100
        var files = new ArrayList<String>();
        int consecutiveMisses = 0;
        for ( int i = 1; i <= 100; i++) {
            var prefix = String.format("V%03d__", i);
            var probeResult = probeResourcePrefix(schemaPath, prefix);
            if ( probeResult.isPresent()) {
                files.add(probeResult.unwrap());
                consecutiveMisses = 0;
            } else



            {
                consecutiveMisses++;
                if ( consecutiveMisses >= 3) {
                break;}
            }
        }
        return files;
    }

    private Option<String> probeResourcePrefix(String schemaPath, String prefix) {
        // Since the annotation processing API does not support directory listing,
        // we probe well-known migration description patterns.
        // The manifest-based approach (migrations.list) is preferred and tried first
        // in discoverMigrationFiles(); this is the fallback.
        for ( var description : MIGRATION_DESCRIPTIONS) {
            var fileName = prefix + description + ".sql";
            var resource = readResource(schemaPath + fileName);
            if ( resource.isPresent()) {
            return Option.present(fileName);}
        }
        return Option.empty();
    }

    private static final List<String> MIGRATION_DESCRIPTIONS = List.of("init",
                                                                       "initial",
                                                                       "initial_schema",
                                                                       "create_tables",
                                                                       "setup",
                                                                       "baseline",
                                                                       "schema",
                                                                       "create",
                                                                       "tables",
                                                                       "migration",
                                                                       "ddl",
                                                                       "create_schema",
                                                                       "bootstrap",
                                                                       "base",
                                                                       "foundation",
                                                                       "create_users",
                                                                       "create_orders",
                                                                       "create_accounts",
                                                                       "create_products",
                                                                       "create_customers",
                                                                       "create_items",
                                                                       "create_events",
                                                                       "create_sessions",
                                                                       "add_tables",
                                                                       "add_indexes",
                                                                       "add_constraints",
                                                                       "add_columns",
                                                                       "seed",
                                                                       "data",
                                                                       "reference_data",
                                                                       "lookup_tables");

    private Option<String> readResource(String path) {
        try {
            var fileObject = processingEnv.getFiler().getResource(StandardLocation.CLASS_PATH, "", path);
            try (var reader = fileObject.openReader(true)) {
                var content = new StringBuilder();
                var buf = new char[4096];
                int read;
                while ( (read = reader.read(buf)) >= 0) {
                content.append(buf, 0, read);}
                return Option.present(content.toString());
            }
        } catch (IOException e) {
            return Option.empty();
        }
    }

    /// Loads schema directly from SQL strings (for testing).
    public static Result<Schema> loadFromScripts(List<String> scripts) {
        return MigrationProcessor.create().processAll(scripts);
    }
}
