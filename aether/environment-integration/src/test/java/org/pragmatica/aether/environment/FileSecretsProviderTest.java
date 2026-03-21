package org.pragmatica.aether.environment;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileSecretsProviderTest {

    @TempDir
    Path tempDir;

    @Nested
    class ResolutionTests {

        @Test
        void resolveSecret_existingFile_returnsContent() throws IOException {
            Files.writeString(tempDir.resolve("database_password"), "  s3cret  \n");
            var provider = FileSecretsProvider.fileSecretsProvider(tempDir);

            provider.resolveSecret("database/password")
                    .await()
                    .onFailure(cause -> assertThat(cause).as("Expected success").isNull())
                    .onSuccess(value -> assertThat(value).isEqualTo("s3cret"));
        }

        @Test
        void resolveSecret_missingFile_returnsFailure() {
            var provider = FileSecretsProvider.fileSecretsProvider(tempDir);

            provider.resolveSecret("nonexistent/secret")
                    .await()
                    .onSuccess(_ -> assertThat(true).as("Expected failure").isFalse())
                    .onFailure(cause -> assertThat(cause.message()).contains("Secret resolution failed"));
        }

        @Test
        void resolveSecret_nestedPath_convertsSlashesToUnderscores() throws IOException {
            Files.writeString(tempDir.resolve("app_db_password"), "nested-secret");
            var provider = FileSecretsProvider.fileSecretsProvider(tempDir);

            provider.resolveSecret("app/db/password")
                    .await()
                    .onFailure(cause -> assertThat(cause).as("Expected success").isNull())
                    .onSuccess(value -> assertThat(value).isEqualTo("nested-secret"));
        }
    }
}
