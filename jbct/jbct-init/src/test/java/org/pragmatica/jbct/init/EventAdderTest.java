package org.pragmatica.jbct.init;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class EventAdderTest {
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

    private static final String MOCK_JBCT_TOML = """
        [project]
        basePackage = "com.example.myslice"

        [format]
        maxLineLength = 120
        """;

    @Test
    void addEvent_kebabCase_createsBothFiles() throws Exception {
        var projectDir = setupProject();
        var result = EventAdder.eventAdder(projectDir, "expensive-order")
                                .flatMap(EventAdder::addEvent);
        assertThat(result.isSuccess())
                  .as("addEvent should succeed")
                  .isTrue();
        result.onSuccess(files -> {
                             assertThat(files)
                                       .hasSize(2);
                             assertThat(projectDir.resolve(
                                 "src/main/java/com/example/myslice/shared/resource/ExpensiveOrderPublisher.java"))
                                       .exists();
                             assertThat(projectDir.resolve(
                                 "src/main/java/com/example/myslice/shared/resource/ExpensiveOrderSubscription.java"))
                                       .exists();
                         });
    }

    @Test
    void addEvent_kebabCase_generatesCorrectPublisher() throws Exception {
        var projectDir = setupProject();
        EventAdder.eventAdder(projectDir, "expensive-order")
                   .flatMap(EventAdder::addEvent);
        var content = Files.readString(projectDir.resolve(
            "src/main/java/com/example/myslice/shared/resource/ExpensiveOrderPublisher.java"));
        assertThat(content)
                  .contains("package com.example.myslice.shared.resource;");
        assertThat(content)
                  .contains("@ResourceQualifier(type = Publisher.class, config = \"messaging.expensive-order\")");
        assertThat(content)
                  .contains("public @interface ExpensiveOrderPublisher {}");
    }

    @Test
    void addEvent_kebabCase_generatesCorrectSubscription() throws Exception {
        var projectDir = setupProject();
        EventAdder.eventAdder(projectDir, "expensive-order")
                   .flatMap(EventAdder::addEvent);
        var content = Files.readString(projectDir.resolve(
            "src/main/java/com/example/myslice/shared/resource/ExpensiveOrderSubscription.java"));
        assertThat(content)
                  .contains("package com.example.myslice.shared.resource;");
        assertThat(content)
                  .contains("@ResourceQualifier(type = Subscriber.class, config = \"messaging.expensive-order\")");
        assertThat(content)
                  .contains("public @interface ExpensiveOrderSubscription {}");
    }

    @Test
    void addEvent_invalidName_returnsFailure() throws Exception {
        var projectDir = setupProject();
        var result = EventAdder.eventAdder(projectDir, "InvalidName");
        assertThat(result.isFailure())
                  .isTrue();
    }

    @Test
    void addEvent_blankName_returnsFailure() throws Exception {
        var projectDir = setupProject();
        var result = EventAdder.eventAdder(projectDir, "  ");
        assertThat(result.isFailure())
                  .isTrue();
    }

    @Test
    void addEvent_duplicateEvent_returnsFailure() throws Exception {
        var projectDir = setupProject();
        EventAdder.eventAdder(projectDir, "expensive-order")
                   .flatMap(EventAdder::addEvent);
        var result = EventAdder.eventAdder(projectDir, "expensive-order")
                                .flatMap(EventAdder::addEvent);
        assertThat(result.isFailure())
                  .isTrue();
    }

    @Test
    void addEvent_relativePackage_usesOverride() throws Exception {
        var projectDir = setupProject();
        var result = EventAdder.eventAdder(projectDir, "click-event", ".tracking", null)
                                .flatMap(EventAdder::addEvent);
        assertThat(result.isSuccess())
                  .as("addEvent with relative package should succeed")
                  .isTrue();
        assertThat(projectDir.resolve(
            "src/main/java/com/example/myslice/tracking/ClickEventPublisher.java"))
                  .exists();
        assertThat(projectDir.resolve(
            "src/main/java/com/example/myslice/tracking/ClickEventSubscription.java"))
                  .exists();
    }

    @Test
    void addEvent_absolutePackage_usesOverride() throws Exception {
        var projectDir = setupProject();
        var result = EventAdder.eventAdder(projectDir, "click-event", "com.custom.events", null)
                                .flatMap(EventAdder::addEvent);
        assertThat(result.isSuccess())
                  .as("addEvent with absolute package should succeed")
                  .isTrue();
        assertThat(projectDir.resolve(
            "src/main/java/com/custom/events/ClickEventPublisher.java"))
                  .exists();
    }

    @Test
    void addEvent_customConfigKey_usesOverride() throws Exception {
        var projectDir = setupProject();
        var result = EventAdder.eventAdder(projectDir, "click-event", null, "custom.key")
                                .flatMap(EventAdder::addEvent);
        assertThat(result.isSuccess())
                  .isTrue();
        var content = Files.readString(projectDir.resolve(
            "src/main/java/com/example/myslice/shared/resource/ClickEventPublisher.java"));
        assertThat(content)
                  .contains("config = \"custom.key\"");
    }

    @Test
    void addEvent_withoutJbctToml_derivesFromPom() throws Exception {
        var projectDir = setupProjectWithoutToml();
        var result = EventAdder.eventAdder(projectDir, "test-event")
                                .flatMap(EventAdder::addEvent);
        assertThat(result.isSuccess())
                  .as("addEvent should succeed with pom.xml fallback")
                  .isTrue();
        assertThat(projectDir.resolve(
            "src/main/java/com/example/myslice/shared/resource/TestEventPublisher.java"))
                  .exists();
    }

    @Test
    void addEvent_appendsMessagingConfigToAetherToml() throws Exception {
        var projectDir = setupProject();
        Files.writeString(projectDir.resolve("aether.toml"), """
            # Aether runtime configuration
            [database]
            async_url = "postgresql://localhost:5432/forge"
            """);
        EventAdder.eventAdder(projectDir, "expensive-order")
                   .flatMap(EventAdder::addEvent);
        var toml = Files.readString(projectDir.resolve("aether.toml"));
        assertThat(toml)
                  .contains("[messaging.expensive-order]");
        assertThat(toml)
                  .contains("topic_name = \"expensive-order\"");
    }

    @Test
    void addEvent_skipsConfigIfSectionAlreadyExists() throws Exception {
        var projectDir = setupProject();
        var originalToml = """
            [messaging.expensive-order]
            topic_name = "custom-topic"
            """;
        Files.writeString(projectDir.resolve("aether.toml"), originalToml);
        EventAdder.eventAdder(projectDir, "expensive-order")
                   .flatMap(EventAdder::addEvent);
        var toml = Files.readString(projectDir.resolve("aether.toml"));
        assertThat(toml)
                  .isEqualTo(originalToml);
    }

    @Test
    void addEvent_succeedsWithoutAetherToml() throws Exception {
        var projectDir = setupProject();
        var result = EventAdder.eventAdder(projectDir, "test-event")
                                .flatMap(EventAdder::addEvent);
        assertThat(result.isSuccess())
                  .isTrue();
        assertThat(projectDir.resolve("aether.toml"))
                  .doesNotExist();
    }

    private Path setupProject() throws Exception {
        var projectDir = tempDir.resolve("my-slice-project");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"), MOCK_POM);
        Files.writeString(projectDir.resolve("jbct.toml"), MOCK_JBCT_TOML);
        return projectDir;
    }

    private Path setupProjectWithoutToml() throws Exception {
        var projectDir = tempDir.resolve("pom-only-project");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"), MOCK_POM);
        return projectDir;
    }
}
