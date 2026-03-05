package org.pragmatica.jbct.init;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/// Adds a new slice to an existing Aether slice project.
public final class SliceAdder {
    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("[A-Z][a-zA-Z0-9]*");

    private final Path projectDir;
    private final String groupId;
    private final String artifactId;
    private final String basePackage;
    private final String sliceName;
    private final String slicePackage;

    private SliceAdder(Path projectDir,
                       String groupId,
                       String artifactId,
                       String basePackage,
                       String sliceName,
                       String slicePackage) {
        this.projectDir = projectDir;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.basePackage = basePackage;
        this.sliceName = sliceName;
        this.slicePackage = slicePackage;
    }

    /// Create a SliceAdder by reading the existing project's pom.xml.
    public static Result<SliceAdder> sliceAdder(Path projectDir, String sliceName) {
        return sliceAdder(projectDir, sliceName, null);
    }

    /// Create a SliceAdder with an optional package override.
    public static Result<SliceAdder> sliceAdder(Path projectDir, String sliceName, String packageOverride) {
        return validateSliceName(sliceName)
                  .flatMap(_ -> readPomProperties(projectDir))
                  .flatMap(pom -> buildSliceAdder(projectDir, sliceName, packageOverride, pom));
    }

    /// Add the slice files to the existing project.
    public Result<List<Path>> addSlice() {
        return createDirectories().flatMap(_ -> createAllFiles());
    }

    public String sliceName() {
        return sliceName;
    }

    public String slicePackage() {
        return slicePackage;
    }

    private Result<Unit> createDirectories() {
        try {
            var packagePath = slicePackage.replace(".", "/");
            var srcMainJava = projectDir.resolve("src/main/java");
            var srcTestJava = projectDir.resolve("src/test/java");
            var metaInfDeps = projectDir.resolve("src/main/resources/META-INF/dependencies");
            var slicesDir = projectDir.resolve("src/main/resources/slices");
            Files.createDirectories(srcMainJava.resolve(packagePath));
            Files.createDirectories(srcTestJava.resolve(packagePath));
            Files.createDirectories(metaInfDeps);
            Files.createDirectories(slicesDir);
            Files.createDirectories(projectDir.resolve("src/main/resources/" + packagePath));
            return Result.success(Unit.unit());
        } catch (Exception e) {
            return Causes.cause("Failed to create directories: " + e.getMessage())
                         .result();
        }
    }

    private Result<List<Path>> createAllFiles() {
        var packagePath = slicePackage.replace(".", "/");
        var srcMainJava = projectDir.resolve("src/main/java");
        var srcTestJava = projectDir.resolve("src/test/java");
        return Result.allOf(writeFile(srcMainJava.resolve(packagePath).resolve(sliceName + ".java"),
                                      substituteVariables(SLICE_INTERFACE_TEMPLATE)),
                            writeFile(projectDir.resolve("src/main/resources/" + packagePath + "/routes.toml"),
                                      substituteVariables(ROUTES_TOML_TEMPLATE)),
                            writeFile(projectDir.resolve("src/main/resources/slices/" + sliceName + ".toml"),
                                      substituteVariables(SLICE_CONFIG_TEMPLATE)),
                            writeFile(projectDir.resolve("src/main/resources/META-INF/dependencies/" + slicePackage + "." + sliceName),
                                      "# Slice dependencies (one artifact per line)\n"),
                            writeFile(srcTestJava.resolve(packagePath).resolve(sliceName + "Test.java"),
                                      substituteVariables(SLICE_TEST_TEMPLATE)));
    }

    private static Result<Unit> validateSliceName(String sliceName) {
        if (sliceName == null || sliceName.isBlank()) {
            return Causes.cause("Slice name must not be null or empty")
                         .result();
        }
        if (!JAVA_IDENTIFIER.matcher(sliceName).matches()) {
            return Causes.cause("Slice name must be a valid Java identifier starting with uppercase: " + sliceName)
                         .result();
        }
        return Result.success(Unit.unit());
    }

    private static Result<PomProperties> readPomProperties(Path projectDir) {
        var pomPath = projectDir.resolve("pom.xml");
        if (!Files.exists(pomPath)) {
            return Causes.cause("No pom.xml found in " + projectDir + ". Run this command from an existing slice project.")
                         .result();
        }
        try {
            var content = Files.readString(pomPath);
            return extractPomProperty(content, "groupId")
                      .flatMap(groupId -> extractPomProperty(content, "artifactId")
                                             .map(artifactId -> new PomProperties(groupId, artifactId)));
        } catch (IOException e) {
            return Causes.cause("Failed to read pom.xml: " + e.getMessage())
                         .result();
        }
    }

    private static Result<String> extractPomProperty(String pomContent, String propertyName) {
        var pattern = Pattern.compile("<" + propertyName + ">([^<]+)</" + propertyName + ">");
        var matcher = pattern.matcher(pomContent);
        if (matcher.find()) {
            return Result.success(matcher.group(1).trim());
        }
        return Causes.cause("Could not find <" + propertyName + "> in pom.xml")
                     .result();
    }

    private static Result<SliceAdder> buildSliceAdder(Path projectDir,
                                                       String sliceName,
                                                       String packageOverride,
                                                       PomProperties pom) {
        var basePackage = pom.groupId() + "." + pom.artifactId().replace("-", "");
        var slicePackage = Option.option(packageOverride)
                                 .filter(s -> !s.isBlank())
                                 .or(() -> basePackage + "." + sliceName.toLowerCase());
        var slicePackagePath = projectDir.resolve("src/main/java/" + slicePackage.replace(".", "/"));
        if (Files.exists(slicePackagePath)) {
            return Causes.cause("Package already exists: " + slicePackage + ". Choose a different slice name or package.")
                         .result();
        }
        return Result.success(new SliceAdder(projectDir, pom.groupId(), pom.artifactId(), basePackage, sliceName, slicePackage));
    }

    private static Result<Path> writeFile(Path targetPath, String content) {
        if (Files.exists(targetPath)) {
            return Causes.cause("File already exists: " + targetPath)
                         .result();
        }
        try {
            Files.writeString(targetPath, content);
            return Result.success(targetPath);
        } catch (IOException e) {
            return Causes.cause("Failed to write " + targetPath + ": " + e.getMessage())
                         .result();
        }
    }

    private String substituteVariables(String template) {
        var factoryMethodName = Character.toLowerCase(sliceName.charAt(0)) + sliceName.substring(1);
        var kebabCase = sliceName.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
        return template.replace("{{slicePackage}}", slicePackage)
                       .replace("{{sliceName}}", sliceName)
                       .replace("{{factoryMethodName}}", factoryMethodName)
                       .replace("{{kebabCase}}", kebabCase);
    }

    private record PomProperties(String groupId, String artifactId) {}

    // Inline templates

    private static final String SLICE_INTERFACE_TEMPLATE = """
        package {{slicePackage}};

        import org.pragmatica.aether.slice.annotation.Slice;
        import org.pragmatica.lang.Cause;
        import org.pragmatica.lang.Promise;
        import org.pragmatica.lang.Result;
        import org.pragmatica.lang.Verify;

        /// {{sliceName}} slice.
        @Slice
        public interface {{sliceName}} {
            record ValidGreetRequest(String name) {
                public static Result<ValidGreetRequest> validGreetRequest(String name) {
                    return Verify.ensure(name,
                                         Verify.Is::notBlank,
                                         GreetError.invalidName())
                                 .map(ValidGreetRequest::new);
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

            Promise<GreetResponse> greet(String name);

            static {{sliceName}} {{factoryMethodName}}() {
                return name -> ValidGreetRequest.validGreetRequest(name)
                                                .map(request -> new GreetResponse("Hello, " + request.name() + "!"))
                                                .async();
            }
        }
        """;

    private static final String SLICE_TEST_TEMPLATE = """
        package {{slicePackage}};

        import org.junit.jupiter.api.Test;

        import static org.assertj.core.api.Assertions.assertThat;
        import static org.junit.jupiter.api.Assertions.fail;

        class {{sliceName}}Test {

            private final {{sliceName}} slice = {{sliceName}}.{{factoryMethodName}}();

            @Test
            void greet_validName_returnsGreeting() {
                slice.greet("World")
                     .await()
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(r -> assertThat(r.greeting()).isEqualTo("Hello, World!"));
            }

            @Test
            void greet_emptyName_returnsError() {
                slice.greet("")
                     .await()
                     .onSuccess(r -> fail("Expected failure for empty name"))
                     .onFailure(cause -> assertThat(cause.message()).isEqualTo("Name cannot be empty"));
            }
        }
        """;

    private static final String ROUTES_TOML_TEMPLATE = """
        prefix = "/api/{{kebabCase}}"

        [routes]
        greet = "GET /hello/{name}"

        [errors]
        default = 500
        HTTP_400 = ["*invalid*", "*empty*"]
        """;

    private static final String SLICE_CONFIG_TEMPLATE = """
        # Slice configuration for {{sliceName}}
        # This file is read by the annotation processor and blueprint generator

        [blueprint]
        # Number of instances to deploy (default: 3)
        instances = 3
        """;
}
