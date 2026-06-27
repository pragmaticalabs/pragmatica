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

    private Path setupProject() throws Exception {
        var projectDir = tempDir.resolve("my-slice-project");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"), MOCK_POM);
        return projectDir;
    }
}
