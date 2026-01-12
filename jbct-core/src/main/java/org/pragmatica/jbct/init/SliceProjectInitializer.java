package org.pragmatica.jbct.init;

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

/**
 * Initializes a new Aether slice project structure.
 */
public final class SliceProjectInitializer {
    private static final String TEMPLATES_PATH = "/templates/slice/";

    // Default versions - updated on each release
    private static final String DEFAULT_JBCT_VERSION = "0.4.8";
    private static final String DEFAULT_PRAGMATICA_VERSION = "0.9.10";

    private final Path projectDir;
    private final String groupId;
    private final String artifactId;
    private final String basePackage;
    private final String sliceName;
    private final String jbctVersion;
    private final String pragmaticaVersion;

    private SliceProjectInitializer(Path projectDir, String groupId, String artifactId, String basePackage,
                                    String jbctVersion, String pragmaticaVersion) {
        this.projectDir = projectDir;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.basePackage = basePackage;
        this.sliceName = toCamelCase(artifactId);
        this.jbctVersion = jbctVersion;
        this.pragmaticaVersion = pragmaticaVersion;
    }

    /**
     * Create initializer with project parameters.
     */
    public static Result<SliceProjectInitializer> sliceProjectInitializer(Path projectDir,
                                                                          String groupId,
                                                                          String artifactId) {
        if (artifactId == null || artifactId.isBlank()) {
            return Causes.cause("artifactId must not be null or empty")
                         .result();
        }
        if (groupId == null || groupId.isBlank()) {
            return Causes.cause("groupId must not be null or empty")
                         .result();
        }
        var basePackage = groupId + "." + artifactId.replace("-", "");
        return Result.success(new SliceProjectInitializer(projectDir, groupId, artifactId, basePackage,
                                                          DEFAULT_JBCT_VERSION, DEFAULT_PRAGMATICA_VERSION));
    }

    /**
     * Create initializer with explicit base package.
     */
    public static SliceProjectInitializer sliceProjectInitializer(Path projectDir,
                                                                  String groupId,
                                                                  String artifactId,
                                                                  String basePackage) {
        return new SliceProjectInitializer(projectDir, groupId, artifactId, basePackage,
                                           DEFAULT_JBCT_VERSION, DEFAULT_PRAGMATICA_VERSION);
    }

    /**
     * Initialize the slice project structure.
     *
     * @return List of created files
     */
    public Result<List<Path>> initialize() {
        return createDirectories()
                                .flatMap(_ -> createAllFiles());
    }

    private Result<Unit> createDirectories() {
        try{
            var srcMainJava = projectDir.resolve("src/main/java");
            var srcTestJava = projectDir.resolve("src/test/java");
            var metaInfDeps = projectDir.resolve("src/main/resources/META-INF/dependencies");
            var slicesDir = projectDir.resolve("src/main/resources/slices");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);
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
                            createDeployScripts(),
                            createSliceConfigFiles())
                     .flatMap(fileLists -> createDependencyManifest()
                                                                   .map(manifest -> {
                                                                            var allFiles = fileLists.stream()
                                                                                                    .flatMap(List::stream)
                                                                                                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                                                                            allFiles.add(manifest);
                                                                            return allFiles;
                                                                        }));
    }

    private Result<List<Path>> createSliceConfigFiles() {
        var slicesDir = projectDir.resolve("src/main/resources/slices");
        return createFile("slice.toml.template", slicesDir.resolve(sliceName + ".toml"))
                                                                                        .map(path -> List.of(path));
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
                            createFile("SliceImpl.java.template",
                                       srcMainJava.resolve(packagePath)
                                                  .resolve(sliceName + "Impl.java")),
                            createFile("SampleRequest.java.template",
                                       srcMainJava.resolve(packagePath)
                                                  .resolve("SampleRequest.java")),
                            createFile("SampleResponse.java.template",
                                       srcMainJava.resolve(packagePath)
                                                  .resolve("SampleResponse.java")),
                            createFile("SliceTest.java.template",
                                       srcTestJava.resolve(packagePath)
                                                  .resolve(sliceName + "Test.java")));
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
        try (var in = getClass()
                              .getResourceAsStream(TEMPLATES_PATH + templateName)) {
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
        try{
            var content = getInlineTemplate(templateName);
            if (content == null) {
                return Causes.cause("Template not found: " + templateName)
                             .result();
            }
            content = substituteVariables(content);
            Files.writeString(targetPath, content);
            return Result.success(targetPath);
        } catch (IOException e) {
            return Causes.cause("Failed to create " + targetPath + ": " + e.getMessage())
                         .result();
        }
    }

    private String getInlineTemplate(String templateName) {
        return switch (templateName) {
            case "pom.xml.template" -> SLICE_POM_TEMPLATE;
            case "jbct.toml.template" -> JBCT_TOML_TEMPLATE;
            case "gitignore.template" -> GITIGNORE_TEMPLATE;
            case "CLAUDE.md" -> CLAUDE_MD_TEMPLATE;
            case "Slice.java.template" -> SLICE_INTERFACE_TEMPLATE;
            case "SliceImpl.java.template" -> SLICE_IMPL_TEMPLATE;
            case "SampleRequest.java.template" -> SAMPLE_REQUEST_TEMPLATE;
            case "SampleResponse.java.template" -> SAMPLE_RESPONSE_TEMPLATE;
            case "SliceTest.java.template" -> SLICE_TEST_TEMPLATE;
            case "deploy-forge.sh.template" -> DEPLOY_FORGE_TEMPLATE;
            case "deploy-test.sh.template" -> DEPLOY_TEST_TEMPLATE;
            case "deploy-prod.sh.template" -> DEPLOY_PROD_TEMPLATE;
            case "generate-blueprint.sh.template" -> GENERATE_BLUEPRINT_TEMPLATE;
            case "slice.toml.template" -> SLICE_CONFIG_TEMPLATE;
            default -> null;
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
                      .replace("{{pragmaticaVersion}}", pragmaticaVersion);
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
        } catch (UnsupportedOperationException e) {} catch (IOException e) {}
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
                <maven.compiler.release>21</maven.compiler.release>
                <pragmatica-lite.version>{{pragmaticaVersion}}</pragmatica-lite.version>
                <jbct.version>{{jbctVersion}}</jbct.version>
            </properties>

            <dependencies>
                <!-- Pragmatica Lite Core -->
                <dependency>
                    <groupId>org.pragmatica-lite</groupId>
                    <artifactId>core</artifactId>
                    <version>${pragmatica-lite.version}</version>
                </dependency>

                <!-- Slice Annotation Processor -->
                <dependency>
                    <groupId>org.pragmatica-lite</groupId>
                    <artifactId>slice-processor</artifactId>
                    <version>${jbct.version}</version>
                    <scope>provided</scope>
                </dependency>

                <!-- Add slice API dependencies here with scope=provided -->

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
            </dependencies>

            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <version>3.14.0</version>
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
        import org.pragmatica.lang.Promise;

        /**
         * {{sliceName}} slice interface.
         */
        @Slice
        public interface {{sliceName}} {

            Promise<SampleResponse> process(SampleRequest request);

            static {{sliceName}} {{factoryMethodName}}() {
                return new {{sliceName}}Impl();
            }
        }
        """;

    private static final String SLICE_IMPL_TEMPLATE = """
        package {{basePackage}};

        import org.pragmatica.lang.Promise;

        /**
         * Implementation of {{sliceName}} slice.
         */
        final class {{sliceName}}Impl implements {{sliceName}} {

            @Override
            public Promise<SampleResponse> process(SampleRequest request) {
                var response = new SampleResponse("Processed: " + request.value());
                return Promise.successful(response);
            }
        }
        """;

    private static final String SAMPLE_REQUEST_TEMPLATE = """
        package {{basePackage}};

        /**
         * Sample request for {{sliceName}} slice.
         */
        public record SampleRequest(String value) {

            public static SampleRequest sampleRequest(String value) {
                return new SampleRequest(value);
            }
        }
        """;

    private static final String SAMPLE_RESPONSE_TEMPLATE = """
        package {{basePackage}};

        /**
         * Sample response from {{sliceName}} slice.
         */
        public record SampleResponse(String result) {

            public static SampleResponse sampleResponse(String result) {
                return new SampleResponse(result);
            }
        }
        """;

    private static final String SLICE_TEST_TEMPLATE = """
        package {{basePackage}};

        import org.junit.jupiter.api.Test;

        import static org.assertj.core.api.Assertions.assertThat;

        class {{sliceName}}Test {

            private final {{sliceName}} slice = {{sliceName}}.{{factoryMethodName}}();

            @Test
            void should_process_request() {
                var request = SampleRequest.sampleRequest("test");

                var response = slice.process(request).await();

                assertThat(response.isSuccess()).isTrue();
                response.onSuccess(r -> assertThat(r.result()).contains("test"));
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
        # Number of instances to deploy
        instances = 1

        # Request timeout in milliseconds
        # timeout_ms = 30000

        # Memory allocation in MB
        # memory_mb = 512

        # Load balancing strategy: round_robin, least_connections, consistent_hash, random
        # load_balancing = "round_robin"

        # For consistent_hash load balancing, specify the request field to hash on
        # affinity_key = "customerId"

        # [transport]
        # Transport configuration (future)
        # type = "http"

        # [transport.http]
        # HTTP-specific settings (future)
        # port = 8080
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
