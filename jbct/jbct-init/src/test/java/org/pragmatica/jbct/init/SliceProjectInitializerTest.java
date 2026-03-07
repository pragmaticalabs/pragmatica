package org.pragmatica.jbct.init;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SliceProjectInitializerTest {
    @TempDir
    Path tempDir;

    @Test
    void initialize_validParams_createsAllFiles() {
        var projectDir = tempDir.resolve("my-slice");
        var result = SliceProjectInitializer.sliceProjectInitializer(projectDir, "org.example", "my-slice")
                                            .flatMap(SliceProjectInitializer::initialize);
        assertThat(result.isSuccess())
                  .isTrue();
        result.onSuccess(files -> {
                             assertThat(files)
                                       .isNotEmpty();
                             assertThat(projectDir.resolve("pom.xml"))
                                       .exists();
                             assertThat(projectDir.resolve("jbct.toml"))
                                       .exists();
                             assertThat(projectDir.resolve(".gitignore"))
                                       .exists();
                             assertThat(projectDir.resolve("src/main/java/org/example/myslice/myslice/MySlice.java"))
                                       .exists();
                             assertThat(projectDir.resolve("src/test/java/org/example/myslice/myslice/MySliceTest.java"))
                                       .exists();
                             // Slice config file
                             assertThat(projectDir.resolve("src/main/resources/slices/MySlice.toml"))
                                       .exists();
                             // Infrastructure files
                             assertThat(projectDir.resolve("forge.toml"))
                                       .exists();
                             assertThat(projectDir.resolve("aether.toml"))
                                       .exists();
                             assertThat(projectDir.resolve("README.md"))
                                       .exists();
                             // Run scripts
                             assertThat(projectDir.resolve("run-forge.sh"))
                                       .exists();
                             assertThat(projectDir.resolve("start-postgres.sh"))
                                       .exists();
                             assertThat(projectDir.resolve("stop-postgres.sh"))
                                       .exists();
                             // Schema
                             assertThat(projectDir.resolve("schema/init.sql"))
                                       .exists();
                             // Routes
                             assertThat(projectDir.resolve("src/main/resources/org/example/myslice/myslice/routes.toml"))
                                       .exists();
                         });
    }

    @Test
    void initialize_validParams_generatesValidPom() throws Exception {
        var projectDir = tempDir.resolve("test-slice");
        var result = SliceProjectInitializer.sliceProjectInitializer(projectDir, "com.test", "test-slice")
                                            .flatMap(SliceProjectInitializer::initialize);
        assertThat(result.isSuccess())
                  .as("Project initialization should succeed")
                  .isTrue();
        var pomContent = Files.readString(projectDir.resolve("pom.xml"));
        assertThat(pomContent)
                  .contains("<groupId>com.test</groupId>");
        assertThat(pomContent)
                  .contains("<artifactId>test-slice</artifactId>");
        assertThat(pomContent)
                  .contains("slice-processor");
        assertThat(pomContent)
                  .contains("collect-slice-deps");
        assertThat(pomContent)
                  .contains("package-slices");
        assertThat(pomContent)
                  .contains("generate-blueprint");
    }

    @Test
    void initialize_validParams_generatesSliceInterface() throws Exception {
        var projectDir = tempDir.resolve("inventory-service");
        var result = SliceProjectInitializer.sliceProjectInitializer(projectDir, "org.example", "inventory-service")
                                            .flatMap(SliceProjectInitializer::initialize);
        assertThat(result.isSuccess())
                  .as("Project initialization should succeed")
                  .isTrue();
        var sliceFile = projectDir.resolve("src/main/java/org/example/inventoryservice/inventoryservice/InventoryService.java");
        var content = Files.readString(sliceFile);
        assertThat(content)
                  .contains("@Slice");
        assertThat(content)
                  .contains("public interface InventoryService");
        assertThat(content)
                  .contains("static InventoryService inventoryService()");
        assertThat(content)
                  .contains("record ValidGreetRequest");
        assertThat(content)
                  .contains("record GreetResponse");
        assertThat(content)
                  .contains("sealed interface GreetError extends Cause");
        assertThat(content)
                  .contains("Promise<GreetResponse> greet(String name)");
    }

    @Test
    void sliceProjectInitializer_hyphenatedArtifactId_derivesCamelCaseName() {
        var projectDir = tempDir.resolve("my-test-service");
        var result = SliceProjectInitializer.sliceProjectInitializer(projectDir, "org.example", "my-test-service");
        assertThat(result.isSuccess())
                  .isTrue();
        result.onSuccess(initializer -> assertThat(initializer.sliceName())
                                                  .isEqualTo("MyTestService"));
    }

    @Test
    void sliceProjectInitializer_explicitSliceName_usesGivenName() {
        var projectDir = tempDir.resolve("my-project");
        var result = SliceProjectInitializer.sliceProjectInitializer(projectDir, "org.example", "my-project", "CustomSlice");
        assertThat(result.isSuccess())
                  .isTrue();
        result.onSuccess(initializer -> assertThat(initializer.sliceName())
                                                  .isEqualTo("CustomSlice"));
    }

    @Test
    void sliceProjectInitializer_nullArtifactId_returnsFailure() {
        var projectDir = tempDir.resolve("test");
        var result = SliceProjectInitializer.sliceProjectInitializer(projectDir, "org.example", null);
        assertThat(result.isFailure())
                  .isTrue();
    }

    @Test
    void sliceProjectInitializer_blankGroupId_returnsFailure() {
        var projectDir = tempDir.resolve("test");
        var result = SliceProjectInitializer.sliceProjectInitializer(projectDir, "  ", "my-slice");
        assertThat(result.isFailure())
                  .isTrue();
    }

    @Test
    void initialize_validParams_includesBasePackageInToml() throws Exception {
        var projectDir = tempDir.resolve("config-test");
        SliceProjectInitializer.sliceProjectInitializer(projectDir, "org.example", "config-test")
                                .flatMap(SliceProjectInitializer::initialize);
        var content = Files.readString(projectDir.resolve("jbct.toml"));
        assertThat(content)
                  .contains("basePackage = \"org.example.configtest\"");
    }
}
