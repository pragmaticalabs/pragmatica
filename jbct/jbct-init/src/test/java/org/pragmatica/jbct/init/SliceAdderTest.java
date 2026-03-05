package org.pragmatica.jbct.init;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SliceAdderTest {
    @TempDir
    Path tempDir;

    private static final String MOCK_POM = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project>
            <groupId>com.example</groupId>
            <artifactId>my-slice</artifactId>
            <version>1.0.0-SNAPSHOT</version>
        </project>
        """;

    @Test
    void addSlice_validName_createsAllFiles() throws Exception {
        var projectDir = setupProject();
        var result = SliceAdder.sliceAdder(projectDir, "Analytics")
                                .flatMap(SliceAdder::addSlice);
        assertThat(result.isSuccess())
                  .as("addSlice should succeed")
                  .isTrue();
        result.onSuccess(files -> {
                             assertThat(files)
                                       .hasSize(5);
                             assertThat(projectDir.resolve("src/main/java/com/example/myslice/analytics/Analytics.java"))
                                       .exists();
                             assertThat(projectDir.resolve("src/main/resources/com/example/myslice/analytics/routes.toml"))
                                       .exists();
                             assertThat(projectDir.resolve("src/main/resources/slices/Analytics.toml"))
                                       .exists();
                             assertThat(projectDir.resolve(
                                 "src/main/resources/META-INF/dependencies/com.example.myslice.analytics.Analytics"))
                                       .exists();
                             assertThat(projectDir.resolve("src/test/java/com/example/myslice/analytics/AnalyticsTest.java"))
                                       .exists();
                         });
    }

    @Test
    void addSlice_validName_generatesCorrectSliceInterface() throws Exception {
        var projectDir = setupProject();
        SliceAdder.sliceAdder(projectDir, "Analytics")
                   .flatMap(SliceAdder::addSlice);
        var content = Files.readString(
            projectDir.resolve("src/main/java/com/example/myslice/analytics/Analytics.java"));
        assertThat(content)
                  .contains("package com.example.myslice.analytics;");
        assertThat(content)
                  .contains("@Slice");
        assertThat(content)
                  .contains("public interface Analytics");
        assertThat(content)
                  .contains("static Analytics analytics()");
        assertThat(content)
                  .contains("Promise<GreetResponse> greet(String name)");
    }

    @Test
    void addSlice_validName_generatesCorrectRoutes() throws Exception {
        var projectDir = setupProject();
        SliceAdder.sliceAdder(projectDir, "Analytics")
                   .flatMap(SliceAdder::addSlice);
        var content = Files.readString(
            projectDir.resolve("src/main/resources/com/example/myslice/analytics/routes.toml"));
        assertThat(content)
                  .contains("prefix = \"/api/analytics\"");
        assertThat(content)
                  .contains("greet = \"GET /hello/{name}\"");
    }

    @Test
    void addSlice_camelCaseName_usesKebabCaseInRoutes() throws Exception {
        var projectDir = setupProject();
        SliceAdder.sliceAdder(projectDir, "UserProfile")
                   .flatMap(SliceAdder::addSlice);
        var content = Files.readString(
            projectDir.resolve("src/main/resources/com/example/myslice/userprofile/routes.toml"));
        assertThat(content)
                  .contains("prefix = \"/api/user-profile\"");
    }

    @Test
    void addSlice_withPackageOverride_usesCustomPackage() throws Exception {
        var projectDir = setupProject();
        var result = SliceAdder.sliceAdder(projectDir, "Analytics", "com.custom.pkg");
        assertThat(result.isSuccess())
                  .isTrue();
        result.onSuccess(adder -> assertThat(adder.slicePackage())
                                            .isEqualTo("com.custom.pkg"));
    }

    @Test
    void addSlice_noPomXml_fails() {
        var result = SliceAdder.sliceAdder(tempDir, "Analytics");
        assertThat(result.isFailure())
                  .isTrue();
    }

    @Test
    void addSlice_invalidName_fails() throws Exception {
        var projectDir = setupProject();
        var result = SliceAdder.sliceAdder(projectDir, "lowercase");
        assertThat(result.isFailure())
                  .isTrue();
    }

    @Test
    void addSlice_blankName_fails() throws Exception {
        var projectDir = setupProject();
        var result = SliceAdder.sliceAdder(projectDir, "  ");
        assertThat(result.isFailure())
                  .isTrue();
    }

    @Test
    void addSlice_existingPackage_fails() throws Exception {
        var projectDir = setupProject();
        var packageDir = projectDir.resolve("src/main/java/com/example/myslice/analytics");
        Files.createDirectories(packageDir);
        var result = SliceAdder.sliceAdder(projectDir, "Analytics");
        assertThat(result.isFailure())
                  .isTrue();
    }

    private Path setupProject() throws Exception {
        var projectDir = tempDir.resolve("my-slice-project");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"), MOCK_POM);
        return projectDir;
    }
}
