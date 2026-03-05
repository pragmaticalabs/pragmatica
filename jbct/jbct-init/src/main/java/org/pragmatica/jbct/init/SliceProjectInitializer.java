package org.pragmatica.jbct.init;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Initializes a new Aether slice project structure.
public final class SliceProjectInitializer {
    private static final Logger log = LoggerFactory.getLogger(SliceProjectInitializer.class);
    private static final String TEMPLATES_PATH = "/templates/slice/";

    // Default versions - used as fallback when offline
    private static final String DEFAULT_JBCT_VERSION = GitHubVersionResolver.defaultJbctVersion();
    private static final String DEFAULT_PRAGMATICA_VERSION = GitHubVersionResolver.defaultPragmaticaVersion();
    private static final String DEFAULT_AETHER_VERSION = GitHubVersionResolver.defaultAetherVersion();

    private final Path projectDir;
    private final String groupId;
    private final String artifactId;
    private final String basePackage;
    private final String sliceName;
    private final String jbctVersion;
    private final String pragmaticaVersion;
    private final String aetherVersion;

    private SliceProjectInitializer(Path projectDir,
                                    String groupId,
                                    String artifactId,
                                    String basePackage,
                                    String sliceName,
                                    String jbctVersion,
                                    String pragmaticaVersion,
                                    String aetherVersion) {
        this.projectDir = projectDir;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.basePackage = basePackage;
        this.sliceName = sliceName;
        this.jbctVersion = jbctVersion;
        this.pragmaticaVersion = pragmaticaVersion;
        this.aetherVersion = aetherVersion;
    }

    /// Create initializer with project parameters, fetching latest versions from GitHub.
    /// Slice name is derived from artifact ID.
    public static Result<SliceProjectInitializer> sliceProjectInitializer(Path projectDir,
                                                                          String groupId,
                                                                          String artifactId) {
        return sliceProjectInitializer(projectDir, groupId, artifactId, null, GitHubVersionResolver.gitHubVersionResolver());
    }

    /// Create initializer with project parameters and version resolver.
    /// Slice name is derived from artifact ID.
    public static Result<SliceProjectInitializer> sliceProjectInitializer(Path projectDir,
                                                                          String groupId,
                                                                          String artifactId,
                                                                          GitHubVersionResolver resolver) {
        return sliceProjectInitializer(projectDir, groupId, artifactId, null, resolver);
    }

    /// Create initializer with explicit slice name, fetching latest versions from GitHub.
    public static Result<SliceProjectInitializer> sliceProjectInitializer(Path projectDir,
                                                                          String groupId,
                                                                          String artifactId,
                                                                          String sliceName) {
        return sliceProjectInitializer(projectDir, groupId, artifactId, sliceName, GitHubVersionResolver.gitHubVersionResolver());
    }

    /// Create initializer with explicit slice name and version resolver.
    public static Result<SliceProjectInitializer> sliceProjectInitializer(Path projectDir,
                                                                          String groupId,
                                                                          String artifactId,
                                                                          String sliceName,
                                                                          GitHubVersionResolver resolver) {
        if (artifactId == null || artifactId.isBlank()) {
            return Causes.cause("artifactId must not be null or empty")
                         .result();
        }
        if (groupId == null || groupId.isBlank()) {
            return Causes.cause("groupId must not be null or empty")
                         .result();
        }
        var basePackage = groupId + "." + artifactId.replace("-", "");
        var effectiveName = (sliceName != null && !sliceName.isBlank())
                            ? sliceName
                            : toCamelCase(artifactId);
        return Result.success(new SliceProjectInitializer(projectDir,
                                                          groupId,
                                                          artifactId,
                                                          basePackage,
                                                          effectiveName,
                                                          resolver.jbctVersion(),
                                                          resolver.pragmaticaLiteVersion(),
                                                          resolver.aetherVersion()));
    }

    /// Initialize the slice project structure.
    ///
    /// @return List of created files
    public Result<List<Path>> initialize() {
        return createDirectories().flatMap(_ -> createAllFiles());
    }

    private Result<Unit> createDirectories() {
        try{
            var srcMainJava = projectDir.resolve("src/main/java");
            var srcTestJava = projectDir.resolve("src/test/java");
            var srcTestResources = projectDir.resolve("src/test/resources");
            var metaInfDeps = projectDir.resolve("src/main/resources/META-INF/dependencies");
            var slicesDir = projectDir.resolve("src/main/resources/slices");
            var schemaDir = projectDir.resolve("schema");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);
            Files.createDirectories(srcTestResources);
            Files.createDirectories(metaInfDeps);
            Files.createDirectories(slicesDir);
            Files.createDirectories(schemaDir);
            var packagePath = basePackage.replace(".", "/");
            Files.createDirectories(srcMainJava.resolve(packagePath));
            Files.createDirectories(srcTestJava.resolve(packagePath));
            Files.createDirectories(projectDir.resolve("src/main/resources/" + packagePath));
            return Result.success(Unit.unit());
        } catch (Exception e) {
            return Causes.cause("Failed to create directories: " + e.getMessage())
                         .result();
        }
    }

    private Result<List<Path>> createAllFiles() {
        return Result.allOf(createProjectFiles(),
                            createSourceFiles(),
                            createTestResources(),
                            createDeployScripts(),
                            createSliceConfigFiles(),
                            createInfraFiles(),
                            createRunScripts(),
                            createRoutes(),
                            createSchema())
                     .flatMap(this::combineWithDependencyManifest);
    }

    private Result<List<Path>> combineWithDependencyManifest(List<List<Path>> fileLists) {
        return createDependencyManifest().map(manifest -> combineFileLists(fileLists, manifest));
    }

    private List<Path> combineFileLists(List<List<Path>> fileLists, Path manifest) {
        var allFiles = fileLists.stream()
                                .flatMap(List::stream)
                                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        allFiles.add(manifest);
        return allFiles;
    }

    private Result<List<Path>> createSliceConfigFiles() {
        var slicesDir = projectDir.resolve("src/main/resources/slices");
        return createFile("slice.toml.template", slicesDir.resolve(sliceName + ".toml")).map(path -> List.of(path));
    }

    private Result<List<Path>> createProjectFiles() {
        return Result.allOf(createFile("pom.xml.template", projectDir.resolve("pom.xml")),
                            createFile("jbct.toml.template", projectDir.resolve("jbct.toml")),
                            createFile("gitignore.template", projectDir.resolve(".gitignore")));
    }

    private Result<List<Path>> createSourceFiles() {
        var packagePath = basePackage.replace(".", "/");
        var srcMainJava = projectDir.resolve("src/main/java");
        var srcTestJava = projectDir.resolve("src/test/java");
        return Result.allOf(createFile("Slice.java.template",
                                       srcMainJava.resolve(packagePath)
                                                  .resolve(sliceName + ".java")),
                            createFile("SliceTest.java.template",
                                       srcTestJava.resolve(packagePath)
                                                  .resolve(sliceName + "Test.java")));
    }

    private Result<List<Path>> createTestResources() {
        var srcTestResources = projectDir.resolve("src/test/resources");
        return createFile("log4j2-test.xml.template", srcTestResources.resolve("log4j2-test.xml")).map(path -> List.of(path));
    }

    private Result<List<Path>> createDeployScripts() {
        return Result.allOf(createFile("deploy-forge.sh.template",
                                       projectDir.resolve("deploy-forge.sh")),
                            createFile("deploy-test.sh.template",
                                       projectDir.resolve("deploy-test.sh")),
                            createFile("deploy-prod.sh.template",
                                       projectDir.resolve("deploy-prod.sh")),
                            createFile("generate-blueprint.sh.template",
                                       projectDir.resolve("generate-blueprint.sh")))
                     .onSuccess(scripts -> scripts.forEach(SliceProjectInitializer::makeExecutable));
    }

    private Result<List<Path>> createInfraFiles() {
        return Result.allOf(createFile("forge.toml.template", projectDir.resolve("forge.toml")),
                            createFile("aether.toml.template", projectDir.resolve("aether.toml")),
                            createFile("README.md.template", projectDir.resolve("README.md")));
    }

    private Result<List<Path>> createRunScripts() {
        return Result.allOf(createFile("run-forge.sh.template", projectDir.resolve("run-forge.sh")),
                            createFile("start-postgres.sh.template", projectDir.resolve("start-postgres.sh")),
                            createFile("stop-postgres.sh.template", projectDir.resolve("stop-postgres.sh")))
                     .onSuccess(scripts -> scripts.forEach(SliceProjectInitializer::makeExecutable));
    }

    private Result<List<Path>> createRoutes() {
        var packagePath = basePackage.replace(".", "/");
        var routesDir = projectDir.resolve("src/main/resources/" + packagePath);
        return createFile("routes.toml.template", routesDir.resolve("routes.toml")).map(path -> List.of(path));
    }

    private Result<List<Path>> createSchema() {
        return createFile("init.sql.template", projectDir.resolve("schema/init.sql")).map(path -> List.of(path));
    }

    private Result<Path> createDependencyManifest() {
        try{
            var resources = projectDir.resolve("src/main/resources/META-INF/dependencies");
            var dependencyFile = resources.resolve(basePackage + "." + sliceName);
            Files.writeString(dependencyFile, "# Slice dependencies (one artifact per line)\n");
            return Result.success(dependencyFile);
        } catch (Exception e) {
            return Causes.cause("Failed to create dependency manifest: " + e.getMessage())
                         .result();
        }
    }

    private Result<Path> createFile(String templateName, Path targetPath) {
        if (Files.exists(targetPath)) {
            return Result.success(targetPath);
        }
        try (var in = getClass().getResourceAsStream(TEMPLATES_PATH + templateName)) {
            if (in == null) {
                return createFromInlineTemplate(templateName, targetPath);
            }
            var content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            content = substituteVariables(content);
            Files.writeString(targetPath, content);
            return Result.success(targetPath);
        } catch (IOException e) {
            return Causes.cause("Failed to create " + targetPath + ": " + e.getMessage())
                         .result();
        }
    }

    private Result<Path> createFromInlineTemplate(String templateName, Path targetPath) {
        return getInlineTemplate(templateName).toResult(Causes.cause("Template not found: " + templateName))
                                .flatMap(template -> writeTemplate(template, targetPath));
    }

    private Result<Path> writeTemplate(String template, Path targetPath) {
        try{
            var content = substituteVariables(template);
            Files.writeString(targetPath, content);
            return Result.success(targetPath);
        } catch (IOException e) {
            return Causes.cause("Failed to create " + targetPath + ": " + e.getMessage())
                         .result();
        }
    }

    private Option<String> getInlineTemplate(String templateName) {
        return switch (templateName) {
            case "pom.xml.template" -> Option.some(SLICE_POM_TEMPLATE);
            case "jbct.toml.template" -> Option.some(JBCT_TOML_TEMPLATE);
            case "gitignore.template" -> Option.some(GITIGNORE_TEMPLATE);
            case "Slice.java.template" -> Option.some(SLICE_INTERFACE_TEMPLATE);
            case "SliceTest.java.template" -> Option.some(SLICE_TEST_TEMPLATE);
            case "log4j2-test.xml.template" -> Option.some(LOG4J2_TEST_XML_TEMPLATE);
            case "deploy-forge.sh.template" -> Option.some(DEPLOY_FORGE_TEMPLATE);
            case "deploy-test.sh.template" -> Option.some(DEPLOY_TEST_TEMPLATE);
            case "deploy-prod.sh.template" -> Option.some(DEPLOY_PROD_TEMPLATE);
            case "generate-blueprint.sh.template" -> Option.some(GENERATE_BLUEPRINT_TEMPLATE);
            case "slice.toml.template" -> Option.some(SLICE_CONFIG_TEMPLATE);
            case "forge.toml.template" -> Option.some(FORGE_TOML_TEMPLATE);
            case "aether.toml.template" -> Option.some(AETHER_TOML_TEMPLATE);
            case "run-forge.sh.template" -> Option.some(RUN_FORGE_SH_TEMPLATE);
            case "start-postgres.sh.template" -> Option.some(START_POSTGRES_SH_TEMPLATE);
            case "stop-postgres.sh.template" -> Option.some(STOP_POSTGRES_SH_TEMPLATE);
            case "init.sql.template" -> Option.some(SCHEMA_INIT_SQL_TEMPLATE);
            case "README.md.template" -> Option.some(README_MD_TEMPLATE);
            case "routes.toml.template" -> Option.some(ROUTES_TOML_TEMPLATE);
            default -> Option.none();
        };
    }

    private String substituteVariables(String content) {
        return content.replace("{{groupId}}", groupId)
                      .replace("{{artifactId}}", artifactId)
                      .replace("{{sliceName}}", sliceName)
                      .replace("{{basePackage}}", basePackage)
                      .replace("{{factoryMethodName}}",
                               Character.toLowerCase(sliceName.charAt(0)) + sliceName.substring(1))
                      .replace("{{jbctVersion}}", jbctVersion)
                      .replace("{{pragmaticaVersion}}", pragmaticaVersion)
                      .replace("{{aetherVersion}}", aetherVersion);
    }

    private static String toCamelCase(String s) {
        var words = s.split("-");
        var sb = new StringBuilder();
        for (var word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
                sb.append(word.substring(1));
            }
        }
        return sb.toString();
    }

    private static void makeExecutable(Path path) {
        try{
            var perms = Files.getPosixFilePermissions(path);
            perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException e) {
            log.debug("POSIX permissions not supported on this platform for {}", path);
        } catch (IOException e) {
            log.debug("Failed to set executable permission on {}: {}", path, e.getMessage());
        }
    }

    // Inline templates
    private static final String SLICE_POM_TEMPLATE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                                     http://maven.apache.org/xsd/maven-4.0.0.xsd">
            <modelVersion>4.0.0</modelVersion>

            <groupId>{{groupId}}</groupId>
            <artifactId>{{artifactId}}</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <packaging>jar</packaging>

            <name>{{sliceName}} Slice</name>
            <description>Aether slice: {{sliceName}}</description>

            <properties>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                <maven.compiler.release>25</maven.compiler.release>
                <pragmatica-lite.version>{{pragmaticaVersion}}</pragmatica-lite.version>
                <aether.version>{{aetherVersion}}</aether.version>
                <jbct.version>{{jbctVersion}}</jbct.version>
            </properties>

            <dependencies>
                <!-- Pragmatica Lite Core (provided by Aether runtime) -->
                <dependency>
                    <groupId>org.pragmatica-lite</groupId>
                    <artifactId>core</artifactId>
                    <version>${pragmatica-lite.version}</version>
                    <scope>provided</scope>
                </dependency>

                <!-- Aether Slice API (provided by Aether runtime) -->
                <dependency>
                    <groupId>org.pragmatica-lite.aether</groupId>
                    <artifactId>slice-annotations</artifactId>
                    <version>${aether.version}</version>
                    <scope>provided</scope>
                </dependency>
                <dependency>
                    <groupId>org.pragmatica-lite.aether</groupId>
                    <artifactId>slice-api</artifactId>
                    <version>${aether.version}</version>
                    <scope>provided</scope>
                </dependency>

                <!-- Slice Annotation Processor -->
                <dependency>
                    <groupId>org.pragmatica-lite</groupId>
                    <artifactId>slice-processor</artifactId>
                    <version>${jbct.version}</version>
                    <scope>provided</scope>
                </dependency>

                <!-- HTTP Routing Adapter (required for routes.toml) -->
                <dependency>
                    <groupId>org.pragmatica-lite.aether</groupId>
                    <artifactId>http-routing-adapter</artifactId>
                    <version>${aether.version}</version>
                    <scope>provided</scope>
                </dependency>

                <!-- Add other slice API dependencies here (use 'provided' scope for Aether runtime libs) -->

                <!-- Testing -->
                <dependency>
                    <groupId>org.junit.jupiter</groupId>
                    <artifactId>junit-jupiter</artifactId>
                    <version>5.11.0</version>
                    <scope>test</scope>
                </dependency>
                <dependency>
                    <groupId>org.assertj</groupId>
                    <artifactId>assertj-core</artifactId>
                    <version>3.26.3</version>
                    <scope>test</scope>
                </dependency>
                <dependency>
                    <groupId>org.apache.logging.log4j</groupId>
                    <artifactId>log4j-slf4j2-impl</artifactId>
                    <version>2.24.3</version>
                    <scope>test</scope>
                </dependency>
                <dependency>
                    <groupId>org.apache.logging.log4j</groupId>
                    <artifactId>log4j-core</artifactId>
                    <version>2.24.3</version>
                    <scope>test</scope>
                </dependency>
            </dependencies>

            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <version>3.14.0</version>
                        <configuration>
                            <annotationProcessorPaths>
                                <path>
                                    <groupId>org.pragmatica-lite</groupId>
                                    <artifactId>slice-processor</artifactId>
                                    <version>${jbct.version}</version>
                                </path>
                            </annotationProcessorPaths>
                            <compilerArgs>
                                <arg>-Aslice.groupId={{groupId}}</arg>
                                <arg>-Aslice.artifactId={{artifactId}}</arg>
                            </compilerArgs>
                        </configuration>
                    </plugin>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <version>3.5.2</version>
                    </plugin>
                    <plugin>
                        <groupId>org.pragmatica-lite</groupId>
                        <artifactId>jbct-maven-plugin</artifactId>
                        <version>${jbct.version}</version>
                        <executions>
                            <execution>
                                <id>jbct-check</id>
                                <goals>
                                    <goal>format-check</goal>
                                    <goal>lint</goal>
                                </goals>
                            </execution>
                            <execution>
                                <id>slice-deps</id>
                                <goals>
                                    <goal>collect-slice-deps</goal>
                                </goals>
                            </execution>
                            <execution>
                                <id>slice-package</id>
                                <goals>
                                    <goal>package-slices</goal>
                                    <goal>generate-blueprint</goal>
                                </goals>
                            </execution>
                            <execution>
                                <id>slice-install</id>
                                <goals>
                                    <goal>install-slices</goal>
                                </goals>
                            </execution>
                            <execution>
                                <id>slice-verify</id>
                                <goals>
                                    <goal>verify-slice</goal>
                                </goals>
                            </execution>
                        </executions>
                    </plugin>
                </plugins>
            </build>
        </project>
        """;

    private static final String JBCT_TOML_TEMPLATE = """
        [format]
        maxLineLength = 120
        indentSize = 4

        [lint]
        failOnWarning = false
        # excludePackages = ["some.generated.**"]
        """;

    private static final String GITIGNORE_TEMPLATE = """
        target/
        *.class
        *.jar
        *.log
        .idea/
        *.iml
        .DS_Store
        """;

    private static final String SLICE_INTERFACE_TEMPLATE = """
        package {{basePackage}};

        import org.pragmatica.aether.slice.annotation.Slice;
        import org.pragmatica.lang.Cause;
        import org.pragmatica.lang.Option;
        import org.pragmatica.lang.Promise;
        import org.pragmatica.lang.Result;

        /// {{sliceName}} slice - greeting service.
        @Slice
        public interface {{sliceName}} {
            record GreetRequest(String name) {
                public static Result<GreetRequest> greetRequest(String name) {
                    return Option.option(name)
                                 .filter(s -> !s.isBlank())
                                 .map(GreetRequest::new)
                                 .toResult(GreetError.invalidName());
                }
            }

            record GreetResponse(String greeting) {}

            sealed interface GreetError extends Cause {
                record InvalidName() implements GreetError {
                    @Override
                    public String message() {
                        return "Name cannot be empty";
                    }
                }

                static GreetError invalidName() {
                    return new InvalidName();
                }
            }

            Promise<GreetResponse> greet(GreetRequest request);

            static {{sliceName}} {{factoryMethodName}}() {
                record impl() implements {{sliceName}} {
                    @Override
                    public Promise<GreetResponse> greet(GreetRequest request) {
                        return Promise.success(new GreetResponse("Hello, " + request.name() + "!"));
                    }
                }
                return new impl();
            }
        }
        """;

    private static final String SLICE_TEST_TEMPLATE = """
        package {{basePackage}};

        import org.junit.jupiter.api.Test;

        import static org.assertj.core.api.Assertions.assertThat;
        import static org.junit.jupiter.api.Assertions.fail;

        class {{sliceName}}Test {

            private final {{sliceName}} slice = {{sliceName}}.{{factoryMethodName}}();

            @Test
            void greet_validName_returnsGreeting() {
                {{sliceName}}.GreetRequest.greetRequest("World")
                    .onFailure(cause -> fail(cause.message()))
                    .onSuccess(request -> slice.greet(request)
                        .await()
                        .onFailure(cause -> fail(cause.message()))
                        .onSuccess(r -> assertThat(r.greeting()).isEqualTo("Hello, World!")));
            }

            @Test
            void greet_emptyName_returnsError() {
                var result = {{sliceName}}.GreetRequest.greetRequest("");
                assertThat(result.isFailure()).isTrue();
            }
        }
        """;

    private static final String DEPLOY_FORGE_TEMPLATE = """
        #!/bin/bash
        # Deploy slice to local Aether Forge (development)
        # Uses local Maven repository - Forge reads from ~/.m2/repository
        set -e

        echo "Building and installing to local repository..."
        mvn clean install -DskipTests

        echo ""
        echo "Slice installed to local Maven repository."
        echo "Forge (with repositories=[\"local\"]) will automatically pick up changes."
        echo ""
        echo "If Forge is running, the slice is now available."
        """;

    private static final String DEPLOY_TEST_TEMPLATE = """
        #!/bin/bash
        # Deploy slice to test Aether cluster
        # Requires: aether CLI configured for test environment
        set -e

        echo "Building and installing..."
        mvn clean install -DskipTests

        BLUEPRINT="target/blueprint.toml"
        if [ ! -f "$BLUEPRINT" ]; then
            echo "ERROR: Blueprint not found. Run: mvn package jbct:generate-blueprint"
            exit 1
        fi

        echo ""
        echo "Pushing artifacts to test cluster..."
        aether artifact push --env test

        echo ""
        echo "Deployed to test environment."
        """;

    private static final String DEPLOY_PROD_TEMPLATE = """
        #!/bin/bash
        # Deploy slice to production Aether cluster
        # Requires: aether CLI configured for production environment
        set -e

        echo "WARNING: Deploying to PRODUCTION"
        echo ""
        read -p "Are you sure? (yes/no): " confirm

        if [ "$confirm" != "yes" ]; then
            echo "Deployment cancelled."
            exit 1
        fi

        echo ""
        echo "Building and verifying..."
        mvn clean verify

        BLUEPRINT="target/blueprint.toml"
        if [ ! -f "$BLUEPRINT" ]; then
            echo "ERROR: Blueprint not found."
            exit 1
        fi

        echo ""
        echo "Pushing artifacts to production cluster..."
        aether artifact push --env prod

        echo ""
        echo "Deployed to production."
        """;

    private static final String SLICE_CONFIG_TEMPLATE = """
        # Slice configuration for {{sliceName}}
        # This file is read by the annotation processor and blueprint generator

        [blueprint]
        # Number of instances to deploy (default: 3)
        instances = 3
        """;

    private static final String GENERATE_BLUEPRINT_TEMPLATE = """
        #!/bin/bash
        # Generate blueprint.toml from slice manifests
        set -e

        echo "Generating blueprint..."
        mvn package jbct:generate-blueprint -DskipTests

        BLUEPRINT="target/blueprint.toml"

        if [ -f "$BLUEPRINT" ]; then
            echo ""
            echo "Blueprint generated: $BLUEPRINT"
            echo ""
            cat "$BLUEPRINT"
        else
            echo "ERROR: Blueprint generation failed"
            exit 1
        fi
        """;

    private static final String LOG4J2_TEST_XML_TEMPLATE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Configuration status="WARN">
            <Appenders>
                <Console name="Console" target="SYSTEM_OUT">
                    <PatternLayout pattern="%d{HH:mm:ss.SSS} [%t] %-5level %c{1.}.%M() - %msg%n"/>
                </Console>
            </Appenders>
            <Loggers>
                <Root level="info"><AppenderRef ref="Console"/></Root>
                <Logger name="io.netty" level="error"/>

                <Logger name="org.h2" level="error"/>
            </Loggers>
        </Configuration>
        """;

    private static final String FORGE_TOML_TEMPLATE = """
        # Forge local development cluster
        [cluster]
        nodes = 3
        management_port = 5150
        dashboard_port = 8888
        app_http_port = 8070

        [observability]
        depth_threshold = -1

        [database]
        enabled = false
        """;

    private static final String AETHER_TOML_TEMPLATE = """
        # Aether runtime configuration
        # Uncomment to enable database access (requires running PostgreSQL):
        # [database]
        # type = "POSTGRESQL"
        # async_url = "postgresql://localhost:5432/forge"
        # [database.pool_config]
        # min_connections = 5
        # max_connections = 20
        """;

    private static final String RUN_FORGE_SH_TEMPLATE = """
        #!/bin/bash
        # Start local Aether Forge cluster
        set -e
        SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

        echo "Building {{artifactId}} slice..."
        mvn clean install -DskipTests -q

        # Find aether-forge
        if command -v aether-forge >/dev/null 2>&1; then
            FORGE_CMD="aether-forge"
        elif [ -f "$HOME/.aether/bin/aether-forge" ]; then
            FORGE_CMD="$HOME/.aether/bin/aether-forge"
        else
            echo "ERROR: aether-forge not found."
            echo "Install: curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/install.sh | sh"
            exit 1
        fi

        echo ""
        echo "Starting Aether Forge..."
        echo "  Dashboard:  http://localhost:8888"
        echo "  App HTTP:   http://localhost:8070"
        echo "  Management: http://localhost:5150"
        echo ""
        echo "Test: curl http://localhost:8070/api/hello/World"
        echo ""

        exec $FORGE_CMD --config "$SCRIPT_DIR/forge.toml" --blueprint "$SCRIPT_DIR/target/blueprint.toml"
        """;

    private static final String START_POSTGRES_SH_TEMPLATE = """
        #!/bin/bash
        # Start PostgreSQL for local development
        set -e

        CONTAINER_NAME="{{artifactId}}-postgres"
        VOLUME_NAME="{{artifactId}}-pgdata"
        PG_PORT="${PG_PORT:-5432}"
        PG_PASSWORD="${PG_PASSWORD:-postgres}"

        # Auto-detect container runtime
        if command -v podman >/dev/null 2>&1; then
            RUNTIME="podman"
        elif command -v docker >/dev/null 2>&1; then
            RUNTIME="docker"
        else
            echo "ERROR: Neither podman nor docker found."
            echo "Install podman: https://podman.io/getting-started/installation"
            exit 1
        fi

        # Check if already running
        if $RUNTIME ps --format '{{.Names}}' 2>/dev/null | grep -q "^${CONTAINER_NAME}$"; then
            echo "PostgreSQL is already running (container: $CONTAINER_NAME)"
            exit 0
        fi

        # Check if stopped container exists
        if $RUNTIME ps -a --format '{{.Names}}' 2>/dev/null | grep -q "^${CONTAINER_NAME}$"; then
            echo "Starting existing container..."
            $RUNTIME start "$CONTAINER_NAME"
        else
            echo "Creating PostgreSQL container..."
            $RUNTIME run -d \\
                --name "$CONTAINER_NAME" \\
                -v "$VOLUME_NAME:/var/lib/postgresql/data" \\
                -e POSTGRES_PASSWORD="$PG_PASSWORD" \\
                -e POSTGRES_DB=forge \\
                -p "$PG_PORT:5432" \\
                postgres:17

            # Wait for PostgreSQL to be ready
            echo "Waiting for PostgreSQL..."
            for i in $(seq 1 30); do
                if $RUNTIME exec "$CONTAINER_NAME" pg_isready -U postgres >/dev/null 2>&1; then
                    break
                fi
                sleep 1
            done

            # Run init.sql if present
            SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
            if [ -f "$SCRIPT_DIR/schema/init.sql" ]; then
                echo "Running schema/init.sql..."
                $RUNTIME exec -i "$CONTAINER_NAME" psql -U postgres -d forge < "$SCRIPT_DIR/schema/init.sql"
            fi
        fi

        echo ""
        echo "PostgreSQL running on port $PG_PORT"
        echo "  Connection: postgresql://postgres:$PG_PASSWORD@localhost:$PG_PORT/forge"
        """;

    private static final String STOP_POSTGRES_SH_TEMPLATE = """
        #!/bin/bash
        # Stop PostgreSQL development container
        set -e

        CONTAINER_NAME="{{artifactId}}-postgres"
        VOLUME_NAME="{{artifactId}}-pgdata"

        # Auto-detect container runtime
        if command -v podman >/dev/null 2>&1; then
            RUNTIME="podman"
        elif command -v docker >/dev/null 2>&1; then
            RUNTIME="docker"
        else
            echo "ERROR: Neither podman nor docker found."
            exit 1
        fi

        $RUNTIME stop "$CONTAINER_NAME" 2>/dev/null && echo "Stopped $CONTAINER_NAME" || echo "Container not running"
        $RUNTIME rm "$CONTAINER_NAME" 2>/dev/null && echo "Removed $CONTAINER_NAME" || true

        if [ "$1" = "--purge" ]; then
            $RUNTIME volume rm "$VOLUME_NAME" 2>/dev/null && echo "Removed volume $VOLUME_NAME" || true
        fi
        """;

    private static final String SCHEMA_INIT_SQL_TEMPLATE = """
        -- Database schema for {{sliceName}}
        -- Run: ./start-postgres.sh
        """;

    private static final String README_MD_TEMPLATE = """
        # {{sliceName}}

        An Aether slice project.

        ## Prerequisites

        - Java 25+
        - Maven 3.9+
        - Aether tools: `curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/install.sh | sh`

        ## Quick Start

            # Build and start local cluster
            ./run-forge.sh

            # Test the API
            curl http://localhost:8070/api/hello/World

            # Dashboard
            open http://localhost:8888

        ## Development

            mvn clean install    # Build
            mvn test             # Run tests
            jbct check           # Format and lint

        ## Deploy

            ./deploy-forge.sh    # Local development
            ./deploy-test.sh     # Test environment
            ./deploy-prod.sh     # Production
        """;

    private static final String ROUTES_TOML_TEMPLATE = """
        prefix = "/api"

        [routes]
        greet = "GET /hello/{name}"

        [errors]
        default = 500
        HTTP_400 = ["*invalid*", "*empty*"]
        """;

    public Path projectDir() {
        return projectDir;
    }

    public String groupId() {
        return groupId;
    }

    public String artifactId() {
        return artifactId;
    }

    public String basePackage() {
        return basePackage;
    }

    public String sliceName() {
        return sliceName;
    }
}
