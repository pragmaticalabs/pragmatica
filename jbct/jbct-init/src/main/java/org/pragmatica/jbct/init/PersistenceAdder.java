package org.pragmatica.jbct.init;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/// Adds PostgreSQL persistence support to an existing Aether slice project.
/// Creates schema directory, migration template, persistence interface,
/// and updates POM with pg-codegen annotation processor and resource-api dependency.
public final class PersistenceAdder {
    private static final Pattern PG_CODEGEN_PRESENT = Pattern.compile("<artifactId>pg-codegen</artifactId>");
    private static final Pattern RESOURCE_API_PRESENT = Pattern.compile("<artifactId>resource-api</artifactId>");

    private final Path projectDir;
    private final String basePackage;
    private final String groupId;
    private final String artifactId;
    private final String persistencePackage;

    private PersistenceAdder(Path projectDir,
                             String basePackage,
                             String groupId,
                             String artifactId,
                             String persistencePackage) {
        this.projectDir = projectDir;
        this.basePackage = basePackage;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.persistencePackage = persistencePackage;
    }

    /// Create a PersistenceAdder by reading the existing project configuration.
    public static Result<PersistenceAdder> persistenceAdder(Path projectDir) {
        return persistenceAdder(projectDir, null);
    }

    /// Create a PersistenceAdder with an optional package override.
    public static Result<PersistenceAdder> persistenceAdder(Path projectDir, String packageOverride) {
        return ProjectConfig.projectConfig(projectDir)
                            .map(config -> buildPersistenceAdder(projectDir, packageOverride, config));
    }

    /// Add persistence support to the project.
    public Result<List<Path>> addPersistence() {
        return updatePom()
                  .flatMap(_ -> createDirectories())
                  .flatMap(_ -> createFiles());
    }

    public String persistencePackage() {
        return persistencePackage;
    }

    private static PersistenceAdder buildPersistenceAdder(Path projectDir,
                                                           String packageOverride,
                                                           ProjectConfig config) {
        var persistencePackage = resolvePersistencePackage(config, packageOverride);
        return new PersistenceAdder(projectDir,
                                    config.basePackage(),
                                    config.groupId(),
                                    config.artifactId(),
                                    persistencePackage);
    }

    private static String resolvePersistencePackage(ProjectConfig config, String packageOverride) {
        if (packageOverride == null || packageOverride.isBlank()) {
            return config.basePackage() + ".persistence";
        }
        return config.resolvePackage(packageOverride);
    }

    private Result<Unit> createDirectories() {
        try {
            var packagePath = persistencePackage.replace(".", "/");
            var srcMainJava = projectDir.resolve("src/main/java");
            var schemaDir = projectDir.resolve("src/main/resources/schema");
            Files.createDirectories(srcMainJava.resolve(packagePath));
            Files.createDirectories(schemaDir);
            return Result.success(Unit.unit());
        } catch (Exception e) {
            return Causes.cause("Failed to create directories: " + e.getMessage())
                         .result();
        }
    }

    private Result<List<Path>> createFiles() {
        var packagePath = persistencePackage.replace(".", "/");
        var srcMainJava = projectDir.resolve("src/main/java");
        var schemaDir = projectDir.resolve("src/main/resources/schema");
        var interfacePath = srcMainJava.resolve(packagePath).resolve("SamplePersistence.java");
        var migrationPath = schemaDir.resolve("V001__initial.sql");
        return Result.allOf(ProjectFiles.writeNewFile(interfacePath, substituteVariables(PERSISTENCE_INTERFACE_TEMPLATE)),
                            ProjectFiles.writeNewFile(migrationPath, MIGRATION_TEMPLATE));
    }

    private Result<Unit> updatePom() {
        var pomPath = projectDir.resolve("pom.xml");
        try {
            var content = Files.readString(pomPath);
            var updated = addAnnotationProcessor(content);
            updated = addResourceApiDependency(updated);
            if (!updated.equals(content)) {
                Files.writeString(pomPath, updated);
            }
            return Result.success(Unit.unit());
        } catch (Exception e) {
            return Causes.cause("Failed to update pom.xml: " + e.getMessage())
                         .result();
        }
    }

    private String addAnnotationProcessor(String pomContent) {
        if (PG_CODEGEN_PRESENT.matcher(pomContent).find()) {
            return pomContent;
        }
        var marker = "</annotationProcessorPaths>";
        if (!pomContent.contains(marker)) {
            return pomContent;
        }
        var insertion = PG_CODEGEN_PATH_FRAGMENT + "                ";
        return pomContent.replace(marker, insertion + marker);
    }

    private String addResourceApiDependency(String pomContent) {
        if (RESOURCE_API_PRESENT.matcher(pomContent).find()) {
            return pomContent;
        }
        var marker = "<!-- Testing -->";
        if (!pomContent.contains(marker)) {
            return pomContent;
        }
        return pomContent.replace(marker, RESOURCE_API_DEPENDENCY_FRAGMENT + marker);
    }

    private String substituteVariables(String template) {
        return template.replace("{{persistencePackage}}", persistencePackage);
    }

    // POM fragments

    private static final String PG_CODEGEN_PATH_FRAGMENT = """
                                    <path>
                                        <groupId>org.pragmatica-lite.aether</groupId>
                                        <artifactId>pg-codegen</artifactId>
                                        <version>${aether.version}</version>
                                    </path>
    """;

    private static final String RESOURCE_API_DEPENDENCY_FRAGMENT = """
                <!-- PostgreSQL Persistence (provided by Aether runtime) -->
                <dependency>
                    <groupId>org.pragmatica-lite.aether</groupId>
                    <artifactId>pg-codegen</artifactId>
                    <version>${aether.version}</version>
                    <scope>provided</scope>
                </dependency>

                """;

    // File templates

    private static final String PERSISTENCE_INTERFACE_TEMPLATE = """
        package {{persistencePackage}};

        import org.pragmatica.aether.pg.codegen.annotation.Query;
        import org.pragmatica.aether.resource.db.PgSql;
        import org.pragmatica.lang.Option;
        import org.pragmatica.lang.Promise;

        /// Sample persistence interface.
        /// Annotate with @Query for explicit SQL or use method-name conventions.
        ///
        /// Inject into your slice factory:
        ///   static MySlice mySlice(@PgSql SamplePersistence persistence) { ... }
        @PgSql
        public interface SamplePersistence {

            // Example: explicit query
            // @Query("SELECT id, name, email FROM users WHERE id = :id")
            // Promise<Option<UserRow>> findById(long id);

            // Example: convention-based (auto-generated from method name)
            // Promise<Option<UserRow>> findByEmail(String email);

            // Example: insert
            // Promise<UserRow> insert(CreateUserRequest request);
        }
        """;

    private static final String MIGRATION_TEMPLATE = """
        -- Initial database schema
        -- Add your CREATE TABLE statements here.
        -- The annotation processor validates queries against this schema at compile time.
        --
        -- Example:
        -- CREATE TABLE IF NOT EXISTS users (
        --     id        BIGSERIAL PRIMARY KEY,
        --     name      TEXT      NOT NULL,
        --     email     TEXT      NOT NULL UNIQUE,
        --     created   TIMESTAMPTZ NOT NULL DEFAULT now()
        -- );
        """;
}
