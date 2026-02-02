package org.pragmatica.aether.infra.secrets;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecretsManagerTest {
    private SecretsManager manager;

    @BeforeEach
    void setUp() {
        manager = SecretsManager.secretsManager();
    }

    // ========== Create Tests ==========

    @Test
    void createSecret_succeeds_withValidName() {
        manager.createSecret("my-secret", SecretValue.secretValue("password123"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(metadata -> {
                   assertThat(metadata.name()).isEqualTo("my-secret");
                   assertThat(metadata.version()).isEqualTo(1);
               });
    }

    @Test
    void createSecret_succeeds_withTags() {
        var tags = Map.of("env", "production", "owner", "team-a");

        manager.createSecret("tagged-secret", SecretValue.secretValue("value"), tags)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(metadata -> {
                   assertThat(metadata.tags()).containsEntry("env", "production");
                   assertThat(metadata.tags()).containsEntry("owner", "team-a");
               });
    }

    @Test
    void createSecret_fails_whenSecretExists() {
        manager.createSecret("duplicate", SecretValue.secretValue("first")).await();

        manager.createSecret("duplicate", SecretValue.secretValue("second"))
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(SecretsError.SecretAlreadyExists.class));
    }

    @Test
    void createSecret_fails_withInvalidName() {
        manager.createSecret("123-invalid", SecretValue.secretValue("value"))
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(SecretsError.InvalidSecretName.class));
    }

    @Test
    void createSecret_fails_withEmptyName() {
        manager.createSecret("", SecretValue.secretValue("value"))
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(SecretsError.InvalidSecretName.class));
    }

    // ========== Get Tests ==========

    @Test
    void getSecret_returnsValue_whenSecretExists() {
        manager.createSecret("get-test", SecretValue.secretValue("secret-value")).await();

        manager.getSecret("get-test")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(value -> assertThat(value.asString()).isEqualTo("secret-value"));
    }

    @Test
    void getSecret_fails_whenSecretNotExists() {
        manager.getSecret("non-existent")
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(SecretsError.SecretNotFound.class));
    }

    @Test
    void getSecretVersion_returnsSpecificVersion() {
        manager.createSecret("versioned", SecretValue.secretValue("v1")).await();
        manager.updateSecret("versioned", SecretValue.secretValue("v2")).await();
        manager.updateSecret("versioned", SecretValue.secretValue("v3")).await();

        manager.getSecretVersion("versioned", 1)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(value -> assertThat(value.asString()).isEqualTo("v1"));

        manager.getSecretVersion("versioned", 2)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(value -> assertThat(value.asString()).isEqualTo("v2"));

        manager.getSecretVersion("versioned", 3)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(value -> assertThat(value.asString()).isEqualTo("v3"));
    }

    @Test
    void getSecretVersion_fails_whenVersionNotExists() {
        manager.createSecret("single-version", SecretValue.secretValue("value")).await();

        manager.getSecretVersion("single-version", 99)
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(SecretsError.VersionNotFound.class));
    }

    // ========== Update Tests ==========

    @Test
    void updateSecret_incrementsVersion() {
        manager.createSecret("update-test", SecretValue.secretValue("v1")).await();

        manager.updateSecret("update-test", SecretValue.secretValue("v2"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(metadata -> assertThat(metadata.version()).isEqualTo(2));

        manager.getSecret("update-test")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(value -> assertThat(value.asString()).isEqualTo("v2"));
    }

    @Test
    void updateSecret_fails_whenSecretNotExists() {
        manager.updateSecret("non-existent", SecretValue.secretValue("value"))
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(SecretsError.SecretNotFound.class));
    }

    // ========== Delete Tests ==========

    @Test
    void deleteSecret_returnsTrue_whenSecretExists() {
        manager.createSecret("to-delete", SecretValue.secretValue("value")).await();

        manager.deleteSecret("to-delete")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(deleted -> assertThat(deleted).isTrue());

        manager.secretExists("to-delete")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(exists -> assertThat(exists).isFalse());
    }

    @Test
    void deleteSecret_returnsFalse_whenSecretNotExists() {
        manager.deleteSecret("non-existent")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(deleted -> assertThat(deleted).isFalse());
    }

    // ========== Metadata Tests ==========

    @Test
    void getMetadata_returnsMetadata_whenSecretExists() {
        manager.createSecret("metadata-test", SecretValue.secretValue("value")).await();

        manager.getMetadata("metadata-test")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(optMetadata -> {
                   assertThat(optMetadata.isPresent()).isTrue();
                   optMetadata.onPresent(metadata -> {
                       assertThat(metadata.name()).isEqualTo("metadata-test");
                       assertThat(metadata.version()).isEqualTo(1);
                   });
               });
    }

    @Test
    void getMetadata_returnsEmpty_whenSecretNotExists() {
        manager.getMetadata("non-existent")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(optMetadata -> assertThat(optMetadata.isPresent()).isFalse());
    }

    @Test
    void listSecrets_returnsAllSecretNames() {
        manager.createSecret("secret-a", SecretValue.secretValue("a")).await();
        manager.createSecret("secret-b", SecretValue.secretValue("b")).await();
        manager.createSecret("secret-c", SecretValue.secretValue("c")).await();

        manager.listSecrets()
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(names -> {
                   assertThat(names).hasSize(3);
                   assertThat(names).contains("secret-a", "secret-b", "secret-c");
               });
    }

    @Test
    void listSecrets_filtersByPattern() {
        manager.createSecret("db-password", SecretValue.secretValue("a")).await();
        manager.createSecret("db-username", SecretValue.secretValue("b")).await();
        manager.createSecret("api-key", SecretValue.secretValue("c")).await();

        manager.listSecrets("db-*")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(names -> {
                   assertThat(names).hasSize(2);
                   assertThat(names).contains("db-password", "db-username");
               });
    }

    @Test
    void listSecretsByTag_filtersCorrectly() {
        manager.createSecret("prod-secret", SecretValue.secretValue("a"), Map.of("env", "production")).await();
        manager.createSecret("dev-secret", SecretValue.secretValue("b"), Map.of("env", "development")).await();
        manager.createSecret("staging-secret", SecretValue.secretValue("c"), Map.of("env", "staging")).await();

        manager.listSecretsByTag("env", "production")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(names -> {
                   assertThat(names).hasSize(1);
                   assertThat(names).contains("prod-secret");
               });
    }

    @Test
    void updateTags_modifiesTags() {
        manager.createSecret("tag-update", SecretValue.secretValue("value"), Map.of("old", "tag")).await();

        manager.updateTags("tag-update", Map.of("new", "tag"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(metadata -> {
                   assertThat(metadata.tags()).containsEntry("new", "tag");
                   assertThat(metadata.tags()).doesNotContainKey("old");
               });
    }

    // ========== Version Management Tests ==========

    @Test
    void listVersions_returnsAllVersionsNewestFirst() {
        manager.createSecret("multi-version", SecretValue.secretValue("v1")).await();
        manager.updateSecret("multi-version", SecretValue.secretValue("v2")).await();
        manager.updateSecret("multi-version", SecretValue.secretValue("v3")).await();

        manager.listVersions("multi-version")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(versions -> assertThat(versions).containsExactly(3, 2, 1));
    }

    @Test
    void deleteVersion_removesSpecificVersion() {
        manager.createSecret("delete-version", SecretValue.secretValue("v1")).await();
        manager.updateSecret("delete-version", SecretValue.secretValue("v2")).await();
        manager.updateSecret("delete-version", SecretValue.secretValue("v3")).await();

        manager.deleteVersion("delete-version", 2)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(deleted -> assertThat(deleted).isTrue());

        manager.listVersions("delete-version")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(versions -> {
                   assertThat(versions).hasSize(2);
                   assertThat(versions).doesNotContain(2);
               });
    }

    @Test
    void deleteVersion_returnsFalse_whenOnlyOneVersion() {
        manager.createSecret("single", SecretValue.secretValue("only")).await();

        manager.deleteVersion("single", 1)
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(deleted -> assertThat(deleted).isFalse());
    }

    // ========== Rotation Tests ==========

    @Test
    void rotateSecret_incrementsVersionAndMarksRotated() {
        manager.createSecret("rotate-test", SecretValue.secretValue("old-value")).await();

        manager.rotateSecret("rotate-test", SecretValue.secretValue("new-value"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(metadata -> {
                   assertThat(metadata.version()).isEqualTo(2);
                   assertThat(metadata.rotatedAt().isPresent()).isTrue();
               });

        manager.getSecret("rotate-test")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(value -> assertThat(value.asString()).isEqualTo("new-value"));
    }

    // ========== SecretValue Tests ==========

    @Test
    void secretValue_clearsValue() {
        var secret = SecretValue.secretValue("sensitive");
        assertThat(secret.asString()).isEqualTo("sensitive");
        assertThat(secret.isCleared()).isFalse();

        secret.clear();

        assertThat(secret.isCleared()).isTrue();
        assertThat(secret.asString()).isEmpty();
        assertThat(secret.asBytes()).isEmpty();
    }

    @Test
    void secretValue_toString_redactsValue() {
        var secret = SecretValue.secretValue("password");
        assertThat(secret.toString()).isEqualTo("SecretValue[REDACTED]");
    }

    // ========== Config Tests ==========

    @Test
    void secretsConfig_validatesName() {
        SecretsConfig.secretsConfig("")
                     .onSuccessRun(Assertions::fail)
                     .onFailure(cause -> assertThat(cause).isInstanceOf(SecretsError.InvalidConfiguration.class));
    }

    // ========== Lifecycle Tests ==========

    @Test
    void stop_clearsAllSecrets() {
        manager.createSecret("stop-test", SecretValue.secretValue("value")).await();

        manager.stop()
               .await()
               .onFailureRun(Assertions::fail);

        manager.listSecrets()
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(names -> assertThat(names).isEmpty());
    }
}
