package org.pragmatica.jbct.init;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceAdderTest {
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
    void addPersistence_noResourcesToml_createsDatabaseConfig() throws Exception {
        var projectDir = setupProject();
        var resourcesToml = projectDir.resolve("src/main/resources/resources.toml");

        var result = PersistenceAdder.persistenceAdder(projectDir)
                                     .flatMap(PersistenceAdder::addPersistence);

        assertThat(result.isSuccess())
                  .as("addPersistence should succeed")
                  .isTrue();
        assertThat(resourcesToml)
                  .exists();
        var content = Files.readString(resourcesToml);
        assertThat(content)
                  .contains("[database]")
                  .contains("type = \"POSTGRESQL\"")
                  .contains("[database.pool_config]")
                  .contains("async_url = \"postgresql://localhost:5432/appdb\"");
        result.onSuccess(files -> assertThat(files).contains(resourcesToml));
    }

    @Test
    void addPersistence_existingResourcesTomlWithoutDatabase_appendsSection() throws Exception {
        var projectDir = setupProject();
        var resourcesToml = projectDir.resolve("src/main/resources/resources.toml");
        Files.createDirectories(resourcesToml.getParent());
        Files.writeString(resourcesToml, "[http]\nport = 8070\n");

        var result = PersistenceAdder.persistenceAdder(projectDir)
                                     .flatMap(PersistenceAdder::addPersistence);

        assertThat(result.isSuccess())
                  .isTrue();
        var content = Files.readString(resourcesToml);
        assertThat(content)
                  .contains("[http]")
                  .contains("port = 8070")
                  .contains("[database]")
                  .contains("[database.pool_config]");
        result.onSuccess(files -> assertThat(files).contains(resourcesToml));
    }

    @Test
    void addPersistence_existingDatabaseSection_isIdempotent() throws Exception {
        var projectDir = setupProject();
        var resourcesToml = projectDir.resolve("src/main/resources/resources.toml");
        Files.createDirectories(resourcesToml.getParent());
        var original = "[database]\ntype = \"POSTGRESQL\"\nname = \"custom\"\ndatabase = \"customdb\"\n";
        Files.writeString(resourcesToml, original);

        var result = PersistenceAdder.persistenceAdder(projectDir)
                                     .flatMap(PersistenceAdder::addPersistence);

        assertThat(result.isSuccess())
                  .isTrue();
        assertThat(Files.readString(resourcesToml))
                  .as("existing [database] section must be left untouched")
                  .isEqualTo(original);
        result.onSuccess(files -> assertThat(files).doesNotContain(resourcesToml));
    }

    /// #1998: a slice project always has `resource-api`, and the dependency used to be skipped whenever it was present, so the
    /// generated `SamplePersistence` (which imports `@Query` from pg-codegen) did not compile.
    @Test
    void addPersistence_sliceProject_addsPgCodegenAsProvidedDependencyAndAsProcessorPath() throws Exception {
        var projectDir = tempDir.resolve("slice");

        SliceProjectInitializer.sliceProjectInitializer(projectDir, "com.example", "slice", "Hello", "1.0.0", "1.0.0", "1.0.0")
                               .flatMap(SliceProjectInitializer::initialize)
                               .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));

        var withResourceApi = Files.readString(projectDir.resolve("pom.xml"));

        assertThat(withResourceApi).as("control: a slice pom carries resource-api, the case that hid the defect")
                                   .contains("<artifactId>resource-api</artifactId>")
                                   .doesNotContain("pg-codegen");

        PersistenceAdder.persistenceAdder(projectDir).flatMap(PersistenceAdder::addPersistence);

        var pom = Files.readString(projectDir.resolve("pom.xml"));

        assertThat(pom).containsPattern("<dependency>\\s*<groupId>org.pragmatica-lite.aether</groupId>\\s*<artifactId>pg-codegen</artifactId>\\s*<version>\\$\\{aether.version}</version>\\s*<scope>provided</scope>");
        assertThat(pom).containsPattern("<path>\\s*<groupId>org.pragmatica-lite.aether</groupId>\\s*<artifactId>pg-codegen</artifactId>");
    }

    @Test
    void addPersistence_runTwice_changesThePomOnlyTheFirstTime() throws Exception {
        var projectDir = tempDir.resolve("slice-twice");

        SliceProjectInitializer.sliceProjectInitializer(projectDir, "com.example", "slice", "Hello", "1.0.0", "1.0.0", "1.0.0")
                               .flatMap(SliceProjectInitializer::initialize)
                               .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
        PersistenceAdder.persistenceAdder(projectDir).flatMap(PersistenceAdder::addPersistence);

        var first = Files.readString(projectDir.resolve("pom.xml"));

        PersistenceAdder.persistenceAdder(projectDir).flatMap(PersistenceAdder::addPersistence);

        assertThat(Files.readString(projectDir.resolve("pom.xml"))).isEqualTo(first);
        assertThat(first.split("<artifactId>pg-codegen</artifactId>", -1).length - 1).as("one path, one dependency").isEqualTo(2);
    }

    @Test
    void addPersistence_pomWithoutTheTestingMarker_stillGetsTheDependency() throws Exception {
        var projectDir = tempDir.resolve("odd-pom");

        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"),
                          "<project><artifactId>odd</artifactId><dependencies><dependency><groupId>g</groupId><artifactId>resource-api</artifactId></dependency></dependencies></project>");
        PersistenceAdder.persistenceAdder(projectDir).flatMap(PersistenceAdder::addPersistence);

        assertThat(Files.readString(projectDir.resolve("pom.xml"))).contains("<artifactId>pg-codegen</artifactId>").contains("<scope>provided</scope>");
    }

    private Path setupProject() throws Exception {
        var projectDir = tempDir.resolve("my-slice-project");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"), MOCK_POM);
        return projectDir;
    }
}
