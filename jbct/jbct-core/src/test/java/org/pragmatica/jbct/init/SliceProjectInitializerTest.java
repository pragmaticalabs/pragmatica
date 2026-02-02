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
    void should_create_slice_project_structure() {
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
                             assertThat(projectDir.resolve("src/main/java/org/example/myslice/MySlice.java"))
                                       .exists();
                             assertThat(projectDir.resolve("src/test/java/org/example/myslice/MySliceTest.java"))
                                       .exists();
                             // Slice config file
                             assertThat(projectDir.resolve("src/main/resources/slices/MySlice.toml"))
                                       .exists();
                         });
    }

    @Test
    void should_generate_valid_pom_xml() throws Exception {
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
    void should_generate_valid_slice_interface() throws Exception {
        var projectDir = tempDir.resolve("inventory-service");
        var result = SliceProjectInitializer.sliceProjectInitializer(projectDir, "org.example", "inventory-service")
                                            .flatMap(SliceProjectInitializer::initialize);
        assertThat(result.isSuccess())
                  .as("Project initialization should succeed")
                  .isTrue();
        var sliceFile = projectDir.resolve("src/main/java/org/example/inventoryservice/InventoryService.java");
        var content = Files.readString(sliceFile);
        assertThat(content)
                  .contains("@Slice");
        assertThat(content)
                  .contains("public interface InventoryService");
        assertThat(content)
                  .contains("static InventoryService inventoryService()");
        assertThat(content)
                  .contains("record Request");
        assertThat(content)
                  .contains("record Response");
        assertThat(content)
                  .contains("sealed interface ValidationError extends Cause");
        assertThat(content)
                  .contains("record inventoryService");
    }

    @Test
    void should_derive_slice_name_from_artifact_id() {
        var projectDir = tempDir.resolve("my-test-service");
        var result = SliceProjectInitializer.sliceProjectInitializer(projectDir, "org.example", "my-test-service");
        assertThat(result.isSuccess())
                  .isTrue();
        result.onSuccess(initializer -> assertThat(initializer.sliceName())
                                                  .isEqualTo("MyTestService"));
    }

    @Test
    void should_fail_with_null_artifact_id() {
        var projectDir = tempDir.resolve("test");
        var result = SliceProjectInitializer.sliceProjectInitializer(projectDir, "org.example", null);
        assertThat(result.isFailure())
                  .isTrue();
    }

    @Test
    void should_fail_with_blank_group_id() {
        var projectDir = tempDir.resolve("test");
        var result = SliceProjectInitializer.sliceProjectInitializer(projectDir, "  ", "my-slice");
        assertThat(result.isFailure())
                  .isTrue();
    }
}
