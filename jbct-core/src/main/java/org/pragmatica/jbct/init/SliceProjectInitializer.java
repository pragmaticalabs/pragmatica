package org.pragmatica.jbct.init;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Initializes a new Aether slice project structure.
 */
public final class SliceProjectInitializer {

    private static final String TEMPLATES_PATH = "/templates/slice/";

    private final Path projectDir;
    private final String groupId;
    private final String artifactId;
    private final String basePackage;
    private final String sliceName;

    private SliceProjectInitializer(Path projectDir, String groupId, String artifactId, String basePackage) {
        this.projectDir = projectDir;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.basePackage = basePackage;
        this.sliceName = toCamelCase(artifactId);
    }

    /**
     * Create initializer with project parameters.
     */
    public static Result<SliceProjectInitializer> sliceProjectInitializer(Path projectDir, String groupId, String artifactId) {
        if (artifactId == null || artifactId.isBlank()) {
            return Causes.cause("artifactId must not be null or empty").result();
        }
        if (groupId == null || groupId.isBlank()) {
            return Causes.cause("groupId must not be null or empty").result();
        }
        var basePackage = groupId + "." + artifactId.replace("-", "");
        return Result.success(new SliceProjectInitializer(projectDir, groupId, artifactId, basePackage));
    }

    /**
     * Create initializer with explicit base package.
     */
    public static SliceProjectInitializer sliceProjectInitializer(Path projectDir, String groupId, String artifactId, String basePackage) {
        return new SliceProjectInitializer(projectDir, groupId, artifactId, basePackage);
    }

    /**
     * Initialize the slice project structure.
     *
     * @return List of created files
     */
    public Result<List<Path>> initialize() {
        try {
            // Create directories
            var srcMainJava = projectDir.resolve("src/main/java");
            var srcTestJava = projectDir.resolve("src/test/java");
            var resources = projectDir.resolve("src/main/resources/META-INF/dependencies");

            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);
            Files.createDirectories(resources);

            // Create package directories
            var packagePath = basePackage.replace(".", "/");
            Files.createDirectories(srcMainJava.resolve(packagePath));
            Files.createDirectories(srcTestJava.resolve(packagePath));

            var createdFiles = new ArrayList<Path>();
            var errors = new ArrayList<String>();

            // Create pom.xml
            createFile("pom.xml.template", projectDir.resolve("pom.xml"))
                .onSuccess(createdFiles::add)
                .onFailure(cause -> errors.add(cause.message()));

            // Create jbct.toml
            createFile("jbct.toml.template", projectDir.resolve("jbct.toml"))
                .onSuccess(createdFiles::add)
                .onFailure(cause -> errors.add(cause.message()));

            // Create .gitignore
            createFile("gitignore.template", projectDir.resolve(".gitignore"))
                .onSuccess(createdFiles::add)
                .onFailure(cause -> errors.add(cause.message()));

            // Create slice interface
            var slicePath = srcMainJava.resolve(packagePath).resolve(sliceName + ".java");
            createFile("Slice.java.template", slicePath)
                .onSuccess(createdFiles::add)
                .onFailure(cause -> errors.add(cause.message()));

            // Create slice implementation
            var implPath = srcMainJava.resolve(packagePath).resolve(sliceName + "Impl.java");
            createFile("SliceImpl.java.template", implPath)
                .onSuccess(createdFiles::add)
                .onFailure(cause -> errors.add(cause.message()));

            // Create sample request
            var requestPath = srcMainJava.resolve(packagePath).resolve("SampleRequest.java");
            createFile("SampleRequest.java.template", requestPath)
                .onSuccess(createdFiles::add)
                .onFailure(cause -> errors.add(cause.message()));

            // Create sample response
            var responsePath = srcMainJava.resolve(packagePath).resolve("SampleResponse.java");
            createFile("SampleResponse.java.template", responsePath)
                .onSuccess(createdFiles::add)
                .onFailure(cause -> errors.add(cause.message()));

            // Create test
            var testPath = srcTestJava.resolve(packagePath).resolve(sliceName + "Test.java");
            createFile("SliceTest.java.template", testPath)
                .onSuccess(createdFiles::add)
                .onFailure(cause -> errors.add(cause.message()));

            // Check for accumulated errors
            if (!errors.isEmpty()) {
                return Causes.cause("File creation errors: " + String.join("; ", errors)).result();
            }

            // Create dependency manifest placeholder
            var dependencyFile = resources.resolve(basePackage + "." + sliceName);
            Files.writeString(dependencyFile, "# Slice dependencies (one artifact per line)\n");
            createdFiles.add(dependencyFile);

            return Result.success(createdFiles);
        } catch (Exception e) {
            return Causes.cause("Failed to initialize slice project: " + e.getMessage()).result();
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
            return Causes.cause("Failed to create " + targetPath + ": " + e.getMessage()).result();
        }
    }

    private Result<Path> createFromInlineTemplate(String templateName, Path targetPath) {
        try {
            var content = getInlineTemplate(templateName);
            if (content == null) {
                return Causes.cause("Template not found: " + templateName).result();
            }

            content = substituteVariables(content);
            Files.writeString(targetPath, content);
            return Result.success(targetPath);
        } catch (IOException e) {
            return Causes.cause("Failed to create " + targetPath + ": " + e.getMessage()).result();
        }
    }

    private String getInlineTemplate(String templateName) {
        return switch (templateName) {
            case "pom.xml.template" -> SLICE_POM_TEMPLATE;
            case "jbct.toml.template" -> JBCT_TOML_TEMPLATE;
            case "gitignore.template" -> GITIGNORE_TEMPLATE;
            case "Slice.java.template" -> SLICE_INTERFACE_TEMPLATE;
            case "SliceImpl.java.template" -> SLICE_IMPL_TEMPLATE;
            case "SampleRequest.java.template" -> SAMPLE_REQUEST_TEMPLATE;
            case "SampleResponse.java.template" -> SAMPLE_RESPONSE_TEMPLATE;
            case "SliceTest.java.template" -> SLICE_TEST_TEMPLATE;
            default -> null;
        };
    }

    private String substituteVariables(String content) {
        return content
            .replace("{{groupId}}", groupId)
            .replace("{{artifactId}}", artifactId)
            .replace("{{sliceName}}", sliceName)
            .replace("{{basePackage}}", basePackage)
            .replace("{{factoryMethodName}}", Character.toLowerCase(sliceName.charAt(0)) + sliceName.substring(1));
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

    // Inline templates
    private static final String SLICE_POM_TEMPLATE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                                     http://maven.apache.org/xsd/maven-4.0.0.xsd">
            <modelVersion>4.0.0</modelVersion>

            <parent>
                <groupId>org.pragmatica-lite.aether</groupId>
                <artifactId>slice-parent</artifactId>
                <version>0.1.0</version>
            </parent>

            <groupId>{{groupId}}</groupId>
            <artifactId>{{artifactId}}</artifactId>
            <version>1.0.0-SNAPSHOT</version>

            <properties>
                <slice.class>{{basePackage}}.{{sliceName}}</slice.class>
            </properties>

            <dependencies>
                <!-- Add slice API dependencies here with scope=provided, classifier=api -->
            </dependencies>

            <build>
                <plugins>
                    <plugin>
                        <groupId>org.pragmatica-lite</groupId>
                        <artifactId>jbct-maven-plugin</artifactId>
                        <version>0.3.12</version>
                        <executions>
                            <execution>
                                <id>collect-deps</id>
                                <goals>
                                    <goal>collect-slice-deps</goal>
                                </goals>
                            </execution>
                            <execution>
                                <id>verify-slice</id>
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
