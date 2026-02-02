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
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initializes a new Aether slice project structure.
 */
public final class SliceProjectInitializer {
    private static final Logger LOG = LoggerFactory.getLogger(SliceProjectInitializer.class);
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
                                    String jbctVersion,
                                    String pragmaticaVersion,
                                    String aetherVersion) {
        this.projectDir = projectDir;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.basePackage = basePackage;
        this.sliceName = toCamelCase(artifactId);
        this.jbctVersion = jbctVersion;
        this.pragmaticaVersion = pragmaticaVersion;
        this.aetherVersion = aetherVersion;
    }

    /**
     * Create initializer with project parameters, fetching latest versions from GitHub.
     */
    public static Result<SliceProjectInitializer> sliceProjectInitializer(Path projectDir,
                                                                          String groupId,
                                                                          String artifactId) {
        var resolver = GitHubVersionResolver.gitHubVersionResolver();
        return sliceProjectInitializer(projectDir, groupId, artifactId, resolver);
    }

    /**
     * Create initializer with project parameters and version resolver.
     */
    public static Result<SliceProjectInitializer> sliceProjectInitializer(Path projectDir,
                                                                          String groupId,
                                                                          String artifactId,
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
        return Result.success(new SliceProjectInitializer(projectDir,
                                                          groupId,
                                                          artifactId,
                                                          basePackage,
                                                          resolver.jbctVersion(),
                                                          resolver.pragmaticaLiteVersion(),
                                                          resolver.aetherVersion()));
    }

    /**
     * Create initializer with explicit base package (uses fallback versions).
     */
    public static SliceProjectInitializer sliceProjectInitializer(Path projectDir,
                                                                  String groupId,
                                                                  String artifactId,
                                                                  String basePackage) {
        return new SliceProjectInitializer(projectDir,
                                           groupId,
                                           artifactId,
                                           basePackage,
                                           DEFAULT_JBCT_VERSION,
                                           DEFAULT_PRAGMATICA_VERSION,
                                           DEFAULT_AETHER_VERSION);
    }

    /**
     * Initialize the slice project structure.
     *
     * @return List of created files
     */
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
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);
            Files.createDirectories(srcTestResources);
            Files.createDirectories(metaInfDeps);
            Files.createDirectories(slicesDir);
            var packagePath = basePackage.replace(".", "/");
            Files.createDirectories(srcMainJava.resolve(packagePath));
            Files.createDirectories(srcTestJava.resolve(packagePath));
            return Result.success(Unit.unit());
        } catch (Exception e) {
            return Causes.cause("Failed to create directories: " + e.getMessage())
                         .result();
        }
    }

    private Result<List<Path>> createAllFiles() {
        // Fork-Join: Create all independent file groups in parallel
        return Result.allOf(createProjectFiles(),
                            createSourceFiles(),
                            createTestResources(),
                            createDeployScripts(),
                            createSliceConfigFiles())
                     .flatMap(this::combineWithDependencyManifest);
    }

    private Result<List<Path>> combineWithDependencyManifest(List<List<Path>> fileLists) {
        return createDependencyManifest()
                   .map(manifest -> combineFileLists(fileLists, manifest));
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
        // Fork-Join: Create project config files in parallel
        return Result.allOf(createFile("pom.xml.template", projectDir.resolve("pom.xml")),
                            createFile("jbct.toml.template", projectDir.resolve("jbct.toml")),
                            createFile("gitignore.template", projectDir.resolve(".gitignore")),
                            createClaudeMd());
    }

    private Result<Path> createClaudeMd() {
        var targetPath = projectDir.resolve("CLAUDE.md");
        if (Files.exists(targetPath)) {
            System.out.println("  Skipped: CLAUDE.md (already exists)");
            return Result.success(targetPath);
        }
        return createFile("CLAUDE.md", targetPath);
    }

    private Result<List<Path>> createSourceFiles() {
        var packagePath = basePackage.replace(".", "/");
        var srcMainJava = projectDir.resolve("src/main/java");
        var srcTestJava = projectDir.resolve("src/test/java");
        // Fork-Join: Create source files in parallel
        return Result.allOf(createFile("Slice.java.template",
                                       srcMainJava.resolve(packagePath)
                                                  .resolve(sliceName + ".java")),
                            createFile("SliceTest.java.template",
                                       srcTestJava.resolve(packagePath)
                                                  .resolve(sliceName + "Test.java")));
    }

    private Result<List<Path>> createTestResources() {
        var srcTestResources = projectDir.resolve("src/test/resources");
        return createFile("tinylog.properties.template", srcTestResources.resolve("tinylog.properties")).map(path -> List.of(path));
    }

    private Result<List<Path>> createDeployScripts() {
        // Fork-Join: Create deploy and utility scripts in parallel
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
                // Fall back to inline templates if resource not found
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
        return Option.option(switch (templateName) {
            case "pom.xml.template" -> SLICE_POM_TEMPLATE;
            case "jbct.toml.template" -> JBCT_TOML_TEMPLATE;
            case "gitignore.template" -> GITIGNORE_TEMPLATE;
            case "CLAUDE.md" -> CLAUDE_MD_TEMPLATE;
            case "Slice.java.template" -> SLICE_INTERFACE_TEMPLATE;
            case "SliceTest.java.template" -> SLICE_TEST_TEMPLATE;
            case "tinylog.properties.template" -> TINYLOG_PROPERTIES_TEMPLATE;
            case "deploy-forge.sh.template" -> DEPLOY_FORGE_TEMPLATE;
            case "deploy-test.sh.template" -> DEPLOY_TEST_TEMPLATE;
            case "deploy-prod.sh.template" -> DEPLOY_PROD_TEMPLATE;
            case "generate-blueprint.sh.template" -> GENERATE_BLUEPRINT_TEMPLATE;
            case "slice.toml.template" -> SLICE_CONFIG_TEMPLATE;
            default -> null;
        });
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
        // Best-effort to make file executable - may not be supported on all platforms
        try{
            var perms = Files.getPosixFilePermissions(path);
            perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException e) {
            LOG.debug("POSIX permissions not supported on this platform for {}", path);
        } catch (IOException e) {
            LOG.debug("Failed to set executable permission on {}: {}", path, e.getMessage());
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
                    <groupId>org.tinylog</groupId>
                    <artifactId>tinylog-api</artifactId>
                    <version>2.7.0</version>
                    <scope>test</scope>
                </dependency>
                <dependency>
                    <groupId>org.tinylog</groupId>
                    <artifactId>tinylog-impl</artifactId>
                    <version>2.7.0</version>
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
        businessPackages = ["{{basePackage}}.**"]
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

    private static final String CLAUDE_MD_TEMPLATE = """
        # JBCT Project

        ## AI Agent Policy (MANDATORY)

        **Use `jbct-coder` agent for ALL coding and fixing tasks.** This ensures code follows JBCT patterns and conventions.

        To invoke: Use `/jbct` skill or spawn `jbct-coder` agent via Task tool.

        ## Implementation Workflow

        1. **Clarify** - Ask questions if requirements are ambiguous or multiple approaches exist
        2. **Plan** - Create implementation plan before coding
        3. **Implement** - Execute plan stages using `jbct-coder`
        4. **Commit** - Commit after each plan stage completion
        5. **Review** - After plan completion, review ALL updated files using `/jbct-review`
        6. **Fix** - Fix all found issues using `/fix-all`

        ### Review Strategy

        - **Small/medium plans** (1-5 stages): Review after all stages complete
        - **Large plans** (6+ stages): Review after every 2-3 stages, then final review after all complete

        ## Git Commits

        - **Format**: Conventional commits (`feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`)
        - **Style**: Single line, imperative mood, no period at end
        - **Examples**:
          - `feat: add user authentication endpoint`
          - `fix: handle null response in API client`
          - `refactor: extract validation logic to separate class`

        ## Conversation Style

        **Core Principles:**
        1. **Extreme brevity** - Answer directly without preamble. No "Let me help you" or "Great question!". Just the answer.
        2. **Action-first** - Execute immediately. Explain only when necessary for safety or clarity.
        3. **No fluff** - Skip politeness markers, acknowledgments, and summaries unless requested.
        4. **Ask when needed** - If requirements are ambiguous or multiple valid approaches exist, ask before acting.

        **When to Ask:**
        - Ambiguous requirements with multiple valid interpretations
        - Missing critical information (file paths, values, choices)
        - Destructive operations with risk of data loss
        - Technical decisions requiring user preference

        **When NOT to Ask:**
        - Clear, unambiguous requests
        - Standard patterns following project conventions
        - Recoverable operations (git, file edits)
        - Obvious next steps (99% certain of intent)

        **Execution Pattern:**
        - Read → Act → Verify (show work incrementally)
        - Parallel operations when independent
        - Immediate verification after significant actions
        """;

    private static final String SLICE_INTERFACE_TEMPLATE = """
        package {{basePackage}};

        import org.pragmatica.aether.slice.annotation.Slice;
        import org.pragmatica.lang.Cause;
        import org.pragmatica.lang.Promise;
        import org.pragmatica.lang.Result;

        /**
         * {{sliceName}} slice interface.
         */
        @Slice
        public interface {{sliceName}} {

            /**
             * Request record.
             */
            record Request(String value) {
                public static Result<Request> request(String value) {
                    if (value == null || value.isBlank()) {
                        return Result.failure(ValidationError.emptyValue());
                    }
                    return Result.success(new Request(value));
                }
            }

            /**
             * Response record.
             */
            record Response(String result) {}

            /**
             * Validation error.
             */
            sealed interface ValidationError extends Cause {
                record EmptyValue() implements ValidationError {
                    @Override
                    public String message() {
                        return "Value cannot be empty";
                    }
                }

                static ValidationError emptyValue() {
                    return new EmptyValue();
                }
            }

            Promise<Response> process(Request request);

            static {{sliceName}} {{factoryMethodName}}() {
                record {{factoryMethodName}}() implements {{sliceName}} {
                    @Override
                    public Promise<Response> process(Request request) {
                        var response = new Response("Processed: " + request.value());
                        return Promise.success(response);
                    }
                }
                return new {{factoryMethodName}}();
            }
        }
        """;

    private static final String SLICE_TEST_TEMPLATE = """
        package {{basePackage}};

        import org.junit.jupiter.api.Assertions;
        import org.junit.jupiter.api.Test;

        import static org.assertj.core.api.Assertions.assertThat;

        class {{sliceName}}Test {

            private final {{sliceName}} slice = {{sliceName}}.{{factoryMethodName}}();

            @Test
            void should_process_request() {
                {{sliceName}}.Request.request("test")
                    .onFailure(Assertions::fail)
                    .onSuccess(request -> slice.process(request)
                        .await()
                        .onFailure(Assertions::fail)
                        .onSuccess(r -> assertThat(r.result()).isEqualTo("Processed: test")));
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

    private static final String TINYLOG_PROPERTIES_TEMPLATE = """
        # Tinylog configuration for test logging
        # Documentation: https://tinylog.org/v2/configuration/

        # Global logging level (trace, debug, info, warn, error)
        level = info

        # Writer configuration - output to console with colored output
        writer        = console
        writer.format = {date: HH:mm:ss.SSS} [{thread}] {class}.{method}() {level}: {message}
        writer.stream = out

        # Package-specific logging levels
        # Uncomment and adjust as needed:
        # level@{{basePackage}} = debug

        # Show SQL queries (if using JDBC):
        # level@org.pragmatica.jdbc = debug

        # Reduce noise from libraries:
        # level@org.apache.http = warn
        # level@io.netty = warn
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
