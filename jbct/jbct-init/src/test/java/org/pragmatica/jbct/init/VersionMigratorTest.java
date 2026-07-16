package org.pragmatica.jbct.init;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.pragmatica.jbct.init.VersionMigrator.PropertyTarget;

import static org.assertj.core.api.Assertions.assertThat;

class VersionMigratorTest {
    @TempDir
    Path tempDir;

    private static final String MOCK_POM = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project>
            <groupId>com.example</groupId>
            <artifactId>my-slice</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <properties>
                <pragmatica-lite.version>1.0.0-rc1</pragmatica-lite.version>
                <aether.version>1.0.0-rc1</aether.version>
                <jbct.version>1.0.0-rc1</jbct.version>
            </properties>
        </project>
        """;

    @Test
    void migrate_updatesPresentProperties() throws Exception {
        var projectDir = setupProject(MOCK_POM);

        var result = VersionMigrator.migrate(projectDir, targets("1.0.0-rc2"));

        assertThat(result.isSuccess())
                  .isTrue();
        result.onSuccess(migration -> assertThat(migration.changes())
                                                .hasSize(3));
        var content = Files.readString(projectDir.resolve("pom.xml"));
        assertThat(content)
                  .contains("<pragmatica-lite.version>1.0.0-rc2</pragmatica-lite.version>")
                  .contains("<aether.version>1.0.0-rc2</aether.version>")
                  .contains("<jbct.version>1.0.0-rc2</jbct.version>")
                  .doesNotContain("1.0.0-rc1");
    }

    @Test
    void migrate_skipsAbsentProperties() throws Exception {
        var projectDir = setupProject(MOCK_POM);

        var result = VersionMigrator.migrate(projectDir, targets("1.0.0-rc2"));

        // platform.version is not present in the pom, so it is not reported as a change.
        result.onSuccess(migration -> assertThat(migration.changes())
                                                .extracting(VersionMigrator.VersionChange::property)
                                                .doesNotContain("platform.version"));
        assertThat(Files.readString(projectDir.resolve("pom.xml")))
                  .doesNotContain("platform.version");
    }

    @Test
    void migrate_alreadyAtTarget_reportsNoChanges() throws Exception {
        var projectDir = setupProject(MOCK_POM);

        var result = VersionMigrator.migrate(projectDir, targets("1.0.0-rc1"));

        result.onSuccess(migration -> assertThat(migration.isEmpty())
                                                .isTrue());
        assertThat(Files.readString(projectDir.resolve("pom.xml")))
                  .isEqualTo(MOCK_POM);
    }

    @Test
    void migrate_noPom_fails() {
        var result = VersionMigrator.migrate(tempDir.resolve("missing"), targets("1.0.0-rc2"));

        assertThat(result.isFailure())
                  .isTrue();
    }

    private static List<PropertyTarget> targets(String version) {
        return List.of(new PropertyTarget("pragmatica-lite.version", version),
                       new PropertyTarget("aether.version", version),
                       new PropertyTarget("jbct.version", version),
                       new PropertyTarget("platform.version", version));
    }

    private Path setupProject(String pom) throws Exception {
        var projectDir = tempDir.resolve("my-slice-project");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"), pom);
        return projectDir;
    }
}
