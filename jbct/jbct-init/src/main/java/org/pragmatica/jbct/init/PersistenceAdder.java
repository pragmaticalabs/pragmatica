package org.pragmatica.jbct.init;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// Adds PostgreSQL persistence support to an existing Aether slice project.
/// Creates schema directory, migration template, persistence interface,
/// and updates POM with the pg-codegen annotation-processor path and the pg-codegen `provided` dependency.
public final class PersistenceAdder {
    /// The two places `pg-codegen` goes are different elements and are checked separately (#1998): the annotation-processor `<path>`
    /// runs the generator, the `<dependency>` puts the annotations the generated code imports (`@Query`) on the compile classpath.
    private static final Pattern PG_CODEGEN_PATH_PRESENT = Pattern.compile("<path>\\s*<groupId>[^<]*</groupId>\\s*<artifactId>pg-codegen</artifactId>");

    private static final Pattern PG_CODEGEN_DEPENDENCY_PRESENT = Pattern.compile("<dependency>\\s*<groupId>[^<]*</groupId>\\s*<artifactId>pg-codegen</artifactId>");

    private static final Pattern DATABASE_SECTION_PRESENT = Pattern.compile("(?m)^\\s*\\[database(\\]|\\.)");

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
        return ProjectConfig.projectConfig(projectDir).map(config -> buildPersistenceAdder(projectDir,
                                                                                           packageOverride,
                                                                                           config));
    }

    /// Add persistence support to the project.
    public Result<List<Path>> addPersistence() {
        return updatePom().flatMap(_ -> createDirectories())
                        .flatMap(_ -> createFiles())
                        .flatMap(this::ensureDatabaseConfig);
    }

    public String persistencePackage() {
        return persistencePackage;
    }

    /// Render a `[database]`-style config stub for the given section name, reusing the
    /// local-development template. The section header and its nested `pool_config` table
    /// are adapted to the requested section so non-default datasources (e.g.
    /// `database.orders`) get a correctly-scoped stub. Exposed for add-only config
    /// fixers (e.g. `fix-slice`).
    public static String databaseConfigStub(String section) {
        return DATABASE_CONFIG_TEMPLATE.replace("[database.pool_config]", "[" + section + ".pool_config]").replace("[database]",
                                                                                                                   "[" + section
                                                                                                                  + "]");
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
            return Causes.cause("Failed to create directories: " + e.getMessage()).result();
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

            updated = addPgCodegenDependency(updated);
            if (!updated.equals(content)) {
                Files.writeString(pomPath, updated);
            }

            return Result.success(Unit.unit());
        } catch (Exception e) {
            return Causes.cause("Failed to update pom.xml: " + e.getMessage()).result();
        }
    }

    /// Ensure src/main/resources/resources.toml declares a [database] section.
    /// Creates the file when absent, appends the section when present without one,
    /// and is a no-op when a [database] table already exists (idempotent).
    private Result<List<Path>> ensureDatabaseConfig(List<Path> createdFiles) {
        var resourcesToml = projectDir.resolve("src/main/resources/resources.toml");

        try {
            if (!Files.exists(resourcesToml)) {
                Files.writeString(resourcesToml, DATABASE_CONFIG_TEMPLATE);

                return Result.success(append(createdFiles, resourcesToml));
            }

            var content = Files.readString(resourcesToml);

            if (DATABASE_SECTION_PRESENT.matcher(content).find()) {
                return Result.success(createdFiles);
            }

            Files.writeString(resourcesToml, content + sectionSeparator(content) + DATABASE_CONFIG_TEMPLATE);

            return Result.success(append(createdFiles, resourcesToml));
        } catch (Exception e) {
            return Causes.cause("Failed to update resources.toml: " + e.getMessage()).result();
        }
    }

    private static List<Path> append(List<Path> files, Path added) {
        var result = new ArrayList<>(files);

        result.add(added);

        return result;
    }

    private static String sectionSeparator(String content) {
        return content.endsWith("\n")
               ? "\n"
               : "\n\n";
    }

    private String addAnnotationProcessor(String pomContent) {
        if (PG_CODEGEN_PATH_PRESENT.matcher(pomContent).find()) {
            return pomContent;
        }

        var marker = "</annotationProcessorPaths>";

        if (!pomContent.contains(marker)) {
            return pomContent;
        }

        var insertion = PG_CODEGEN_PATH_FRAGMENT + "                ";

        return pomContent.replace(marker, insertion + marker);
    }

    /// Adds `pg-codegen` as a `provided` dependency. The generated persistence interface imports `@Query` from it, so without the
    /// dependency the project does not compile (#1998). It used to be skipped whenever `resource-api` was present, which a slice
    /// project always has.
    private String addPgCodegenDependency(String pomContent) {
        if (PG_CODEGEN_DEPENDENCY_PRESENT.matcher(pomContent).find()) {
            return pomContent;
        }

        var testing = "<!-- Testing -->";

        if (pomContent.contains(testing)) {
            return pomContent.replace(testing, PG_CODEGEN_DEPENDENCY_FRAGMENT + testing);
        }

        var end = "</dependencies>";
        var at = pomContent.indexOf(end);

        return at < 0
               ? pomContent
               : pomContent.substring(0, at) + PG_CODEGEN_DEPENDENCY_FRAGMENT + pomContent.substring(at);
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

    private static final String PG_CODEGEN_DEPENDENCY_FRAGMENT = """
                <!-- PostgreSQL Persistence (provided by Aether runtime) -->
                <dependency>
                    <groupId>org.pragmatica-lite.aether</groupId>
                    <artifactId>pg-codegen</artifactId>
                    <version>${aether.version}</version>
                    <scope>provided</scope>
                </dependency>

                """;

    // File templates
    // The examples live in the doc comment, not in the body: the formatter flushes body line comments to column 0 and drops their blank
    // lines, so a body of commented examples is rewritten by `format-check` (#1998). Two blank lines follow the imports for the same reason.
    private static final String PERSISTENCE_INTERFACE_TEMPLATE = """
        package {{persistencePackage}};

        import org.pragmatica.aether.pg.codegen.annotation.Query;
        import org.pragmatica.aether.resource.db.PgSql;
        import org.pragmatica.lang.Option;
        import org.pragmatica.lang.Promise;


        /// Sample persistence interface.
        ///
        /// Annotate a method with `@Query` for explicit SQL, or use method-name conventions. Examples (uncomment and adapt):
        ///
        /// ```
        /// @Query("SELECT id, name, email FROM users WHERE id = :id")
        /// Promise<Option<UserRow>> findById(long id);
        ///
        /// Promise<Option<UserRow>> findByEmail(String email);
        ///
        /// Promise<UserRow> insert(CreateUserRequest request);
        /// ```
        ///
        /// Inject into your slice factory:
        ///
        /// ```
        /// static MySlice mySlice(@PgSql SamplePersistence persistence) { ... }
        /// ```
        @PgSql
        public interface SamplePersistence {}
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

    private static final String DATABASE_CONFIG_TEMPLATE = """
        # Database configuration for @PgSql persistence.
        # These are placeholder values for local development. At deploy time they are
        # overridden from the node's aether.toml / environment.
        [database]
        type = "POSTGRESQL"
        name = "primary"
        host = "localhost"
        port = 5432
        database = "appdb"
        username = "postgres"
        password = "postgres"
        async_url = "postgresql://localhost:5432/appdb"

        [database.pool_config]
        min_connections = 4
        max_connections = 20
        idle_timeout = "10m"
        connection_timeout = "5s"
        max_lifetime = "30m"
        leak_detection_timeout = "0s"
        io_threads = 0
        """;
}
