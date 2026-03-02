package org.pragmatica.aether.repository.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.repository.maven.MavenSettingsCredentials;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class MavenSettingsCredentialsTest {

    @TempDir
    Path tempDir;

    @Test
    void forServer_returnsCredentials_whenServerExists() throws IOException {
        var settingsFile = createSettingsFile("""
            <settings>
              <servers>
                <server>
                  <id>nexus</id>
                  <username>admin</username>
                  <password>secret123</password>
                </server>
              </servers>
            </settings>
            """);

        var result = MavenSettingsCredentials.forServer("nexus", settingsFile);

        assertThat(result.isPresent()).isTrue();
        result.onPresent(creds -> {
            assertThat(creds.username()).isEqualTo("admin");
            assertThat(creds.password()).isEqualTo("secret123");
        });
    }

    @Test
    void forServer_returnsEmpty_whenServerNotFound() throws IOException {
        var settingsFile = createSettingsFile("""
            <settings>
              <servers>
                <server>
                  <id>other-server</id>
                  <username>user</username>
                  <password>pass</password>
                </server>
              </servers>
            </settings>
            """);

        var result = MavenSettingsCredentials.forServer("nexus", settingsFile);

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void forServer_returnsEmpty_whenFileNotFound() {
        var result = MavenSettingsCredentials.forServer("nexus", new File("/nonexistent/settings.xml"));

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void forServer_matchesCorrectServer_whenMultipleExist() throws IOException {
        var settingsFile = createSettingsFile("""
            <settings>
              <servers>
                <server>
                  <id>central</id>
                  <username>central-user</username>
                  <password>central-pass</password>
                </server>
                <server>
                  <id>nexus</id>
                  <username>nexus-user</username>
                  <password>nexus-pass</password>
                </server>
              </servers>
            </settings>
            """);

        var result = MavenSettingsCredentials.forServer("nexus", settingsFile);

        assertThat(result.isPresent()).isTrue();
        result.onPresent(creds -> {
            assertThat(creds.username()).isEqualTo("nexus-user");
            assertThat(creds.password()).isEqualTo("nexus-pass");
        });
    }

    @Test
    void toBasicAuthHeader_encodesCorrectly() throws IOException {
        var settingsFile = createSettingsFile("""
            <settings>
              <servers>
                <server>
                  <id>test</id>
                  <username>user</username>
                  <password>pass</password>
                </server>
              </servers>
            </settings>
            """);

        MavenSettingsCredentials.forServer("test", settingsFile)
            .onPresent(creds -> {
                var header = creds.toBasicAuthHeader();
                var expected = "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes());
                assertThat(header).isEqualTo(expected);
            });
    }

    @Test
    void forServer_returnsEmpty_whenInvalidXml() throws IOException {
        var settingsFile = createSettingsFile("not-xml-at-all");

        var result = MavenSettingsCredentials.forServer("nexus", settingsFile);

        assertThat(result.isEmpty()).isTrue();
    }

    private File createSettingsFile(String content) throws IOException {
        var file = tempDir.resolve("settings.xml");
        Files.writeString(file, content);
        return file.toFile();
    }
}
