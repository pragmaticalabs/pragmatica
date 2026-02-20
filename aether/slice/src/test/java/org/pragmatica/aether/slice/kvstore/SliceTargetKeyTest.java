package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;

import static org.assertj.core.api.Assertions.assertThat;

class SliceTargetKeyTest {
    @Test
    void sliceTargetKey_from_string_succeeds() {
        SliceTargetKey.sliceTargetKey("slice-target/org.example:test-slice")
                      .onSuccess(key -> assertThat(key.artifactBase().asString()).isEqualTo("org.example:test-slice"))
                      .onFailureRun(Assertions::fail);
    }

    @Test
    void sliceTargetKey_from_artifactBase_succeeds() {
        var artifactBase = ArtifactBase.artifactBase("org.example:test-slice").unwrap();
        var key = SliceTargetKey.sliceTargetKey(artifactBase);
        assertThat(key.artifactBase()).isEqualTo(artifactBase);
    }

    @Test
    void sliceTargetKey_asString_produces_correct_format() {
        var artifactBase = ArtifactBase.artifactBase("org.example:test-slice").unwrap();
        var key = SliceTargetKey.sliceTargetKey(artifactBase);
        assertThat(key.asString()).isEqualTo("slice-target/org.example:test-slice");
    }

    @Test
    void sliceTargetKey_roundtrip_consistency() {
        var artifactBase = ArtifactBase.artifactBase("org.example:test-slice").unwrap();
        var originalKey = SliceTargetKey.sliceTargetKey(artifactBase);
        var keyString = originalKey.asString();
        SliceTargetKey.sliceTargetKey(keyString)
                      .onSuccess(parsedKey -> assertThat(parsedKey).isEqualTo(originalKey))
                      .onFailureRun(Assertions::fail);
    }

    @Test
    void sliceTargetKey_from_string_with_invalid_prefix_fails() {
        SliceTargetKey.sliceTargetKey("invalid/org.example:test-slice")
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause.message()).contains("Invalid slice-target key format"));
    }

    @Test
    void sliceTargetKey_from_string_with_invalid_artifact_base_fails() {
        SliceTargetKey.sliceTargetKey("slice-target/invalid-artifact")
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause.message()).isNotEmpty());
    }

    @Test
    void sliceTargetKey_equality_works() {
        var artifactBase = ArtifactBase.artifactBase("org.example:test-slice").unwrap();
        var key1 = SliceTargetKey.sliceTargetKey(artifactBase);
        var key2 = SliceTargetKey.sliceTargetKey(artifactBase);
        assertThat(key1).isEqualTo(key2);
        assertThat(key1.hashCode()).isEqualTo(key2.hashCode());
    }

    @Test
    void sliceTargetKey_inequality_with_different_artifact() {
        var artifactBase1 = ArtifactBase.artifactBase("org.example:slice-a").unwrap();
        var artifactBase2 = ArtifactBase.artifactBase("org.example:slice-b").unwrap();
        var key1 = SliceTargetKey.sliceTargetKey(artifactBase1);
        var key2 = SliceTargetKey.sliceTargetKey(artifactBase2);
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void sliceTargetKey_toString_returns_asString() {
        var artifactBase = ArtifactBase.artifactBase("org.example:test-slice").unwrap();
        var key = SliceTargetKey.sliceTargetKey(artifactBase);
        assertThat(key.toString()).isEqualTo(key.asString());
    }
}
