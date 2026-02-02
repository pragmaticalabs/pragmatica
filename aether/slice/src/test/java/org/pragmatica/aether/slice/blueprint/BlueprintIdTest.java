package org.pragmatica.aether.slice.blueprint;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlueprintIdTest {

    @Test
    void blueprintId_succeeds_withValidInput() {
        BlueprintId.blueprintId("org.example:my-app:1.0.0")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(id -> {
                       assertThat(id.artifact().groupId().id()).isEqualTo("org.example");
                       assertThat(id.artifact().artifactId().id()).isEqualTo("my-app");
                       assertThat(id.artifact().version().bareVersion()).isEqualTo("1.0.0");
                   });
    }

    @Test
    void blueprintId_succeeds_withQualifier() {
        BlueprintId.blueprintId("org.example:my-app:1.0.0-SNAPSHOT")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(id -> {
                       assertThat(id.artifact().artifactId().id()).isEqualTo("my-app");
                       assertThat(id.artifact().version().withQualifier()).isEqualTo("1.0.0-SNAPSHOT");
                   });
    }

    @Test
    void blueprintId_fails_withInvalidFormat() {
        BlueprintId.blueprintId("invalid")
                   .onSuccessRun(Assertions::fail)
                   .onFailure(cause -> assertThat(cause.message()).contains("Invalid blueprint ID format"));
    }

    @Test
    void blueprintId_fails_withTwoPartsOnly() {
        BlueprintId.blueprintId("my-app:1.0.0")
                   .onSuccessRun(Assertions::fail)
                   .onFailure(cause -> assertThat(cause.message()).contains("Invalid blueprint ID format"));
    }

    @Test
    void blueprintId_fails_withInvalidVersion() {
        BlueprintId.blueprintId("org.example:my-app:invalid")
                   .onSuccessRun(Assertions::fail);
    }

    @Test
    void asString_roundtrip() {
        var original = "org.example:my-app:1.0.0";
        BlueprintId.blueprintId(original)
                   .map(BlueprintId::asString)
                   .onSuccess(result -> assertThat(result).isEqualTo(original))
                   .onFailureRun(Assertions::fail);
    }

    @Test
    void asString_roundtrip_withQualifier() {
        var original = "org.example:my-app:1.0.0-SNAPSHOT";
        BlueprintId.blueprintId(original)
                   .map(BlueprintId::asString)
                   .onSuccess(result -> assertThat(result).isEqualTo(original))
                   .onFailureRun(Assertions::fail);
    }
}
