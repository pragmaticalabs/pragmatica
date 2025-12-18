package org.pragmatica.aether.slice.blueprint;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Version;

import static org.assertj.core.api.Assertions.assertThat;

class BlueprintIdTest {

    @Test
    void blueprintId_succeeds_withValidInput() {
        BlueprintId.blueprintId("my-app:1.0.0")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(id -> {
                       assertThat(id.name()).isEqualTo("my-app");
                       assertThat(id.version().bareVersion()).isEqualTo("1.0.0");
                   });
    }

    @Test
    void blueprintId_succeeds_withQualifier() {
        BlueprintId.blueprintId("my-app:1.0.0-SNAPSHOT")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(id -> {
                       assertThat(id.name()).isEqualTo("my-app");
                       assertThat(id.version().withQualifier()).isEqualTo("1.0.0-SNAPSHOT");
                   });
    }

    @Test
    void blueprintId_fails_withInvalidFormat() {
        BlueprintId.blueprintId("invalid")
                   .onSuccessRun(Assertions::fail)
                   .onFailure(cause -> assertThat(cause.message()).contains("Invalid blueprint ID format"));
    }

    @Test
    void blueprintId_fails_withInvalidName() {
        BlueprintId.blueprintId("My-App:1.0.0")
                   .onSuccessRun(Assertions::fail);
    }

    @Test
    void blueprintId_fails_withInvalidVersion() {
        BlueprintId.blueprintId("my-app:invalid")
                   .onSuccessRun(Assertions::fail);
    }

    @Test
    void asString_roundtrip() {
        var original = "my-app:1.0.0";
        BlueprintId.blueprintId(original)
                   .map(BlueprintId::asString)
                   .onSuccess(result -> assertThat(result).isEqualTo(original))
                   .onFailureRun(Assertions::fail);
    }

    @Test
    void asString_roundtrip_withQualifier() {
        var original = "my-app:1.0.0-SNAPSHOT";
        BlueprintId.blueprintId(original)
                   .map(BlueprintId::asString)
                   .onSuccess(result -> assertThat(result).isEqualTo(original))
                   .onFailureRun(Assertions::fail);
    }

    @Test
    void factory_creates_blueprintId() {
        Version.version("2.1.0")
               .map(version -> BlueprintId.blueprintId("test-app", version))
               .onSuccess(id -> {
                   assertThat(id.name()).isEqualTo("test-app");
                   assertThat(id.version().bareVersion()).isEqualTo("2.1.0");
               })
               .onFailureRun(Assertions::fail);
    }
}
