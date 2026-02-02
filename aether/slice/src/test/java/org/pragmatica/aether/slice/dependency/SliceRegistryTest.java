package org.pragmatica.aether.slice.dependency;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SliceRegistryTest {

    static class TestSlice implements Slice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    @Test
    void register_and_lookup_slice() {
        var registry = SliceRegistry.sliceRegistry();
        var artifact = Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
        var slice = new TestSlice();

        registry.register(artifact, slice)
                .onFailureRun(Assertions::fail);

        registry.lookup(artifact)
                .onEmpty(Assertions::fail)
                .onPresent(found -> assertThat(found).isSameAs(slice));
    }

    @Test
    void register_fails_for_duplicate_artifact() {
        var registry = SliceRegistry.sliceRegistry();
        var artifact = Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
        var slice1 = new TestSlice();
        var slice2 = new TestSlice();

        registry.register(artifact, slice1)
                .onFailureRun(Assertions::fail);

        registry.register(artifact, slice2)
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertThat(cause.message()).contains("already registered"));
    }

    @Test
    void lookup_returns_none_for_missing_artifact() {
        var registry = SliceRegistry.sliceRegistry();
        var artifact = Artifact.artifact("org.example:missing:1.0.0").unwrap();

        registry.lookup(artifact)
                .onPresent(_ -> Assertions.fail())
                .onEmpty(() -> {
                });
    }

    @Test
    void unregister_removes_slice() {
        var registry = SliceRegistry.sliceRegistry();
        var artifact = Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
        var slice = new TestSlice();

        registry.register(artifact, slice)
                .onFailureRun(Assertions::fail);

        registry.unregister(artifact)
                .onFailureRun(Assertions::fail);

        registry.lookup(artifact)
                .onPresent(_ -> Assertions.fail())
                .onEmpty(() -> {
                });
    }

    @Test
    void unregister_fails_for_missing_artifact() {
        var registry = SliceRegistry.sliceRegistry();
        var artifact = Artifact.artifact("org.example:missing:1.0.0").unwrap();

        registry.unregister(artifact)
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertThat(cause.message()).contains("not found"));
    }

    @Test
    void find_by_class_name_and_exact_version() {
        var registry = SliceRegistry.sliceRegistry();
        var artifact = Artifact.artifact("org.example:test-slice:1.2.3").unwrap();
        var slice = new TestSlice();

        registry.register(artifact, slice)
                .onFailureRun(Assertions::fail);

        var pattern = VersionPattern.parse("1.2.3").unwrap();

        registry.find("test-slice", pattern)
                .onEmpty(Assertions::fail)
                .onPresent(found -> assertThat(found).isSameAs(slice));
    }

    @Test
    void find_by_class_name_and_version_range() {
        var registry = SliceRegistry.sliceRegistry();
        var artifact = Artifact.artifact("org.example:test-slice:1.5.0").unwrap();
        var slice = new TestSlice();

        registry.register(artifact, slice)
                .onFailureRun(Assertions::fail);

        var pattern = VersionPattern.parse("[1.0.0,2.0.0)").unwrap();

        registry.find("test-slice", pattern)
                .onEmpty(Assertions::fail)
                .onPresent(found -> assertThat(found).isSameAs(slice));
    }

    @Test
    void find_returns_none_when_version_doesnt_match() {
        var registry = SliceRegistry.sliceRegistry();
        var artifact = Artifact.artifact("org.example:test-slice:2.0.0").unwrap();
        var slice = new TestSlice();

        registry.register(artifact, slice)
                .onFailureRun(Assertions::fail);

        var pattern = VersionPattern.parse("[1.0.0,2.0.0)").unwrap();

        registry.find("test-slice", pattern)
                .onPresent(_ -> Assertions.fail())
                .onEmpty(() -> {
                });
    }

    @Test
    void find_returns_none_when_class_name_doesnt_match() {
        var registry = SliceRegistry.sliceRegistry();
        var artifact = Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
        var slice = new TestSlice();

        registry.register(artifact, slice)
                .onFailureRun(Assertions::fail);

        var pattern = VersionPattern.parse("1.0.0").unwrap();

        registry.find("other-slice", pattern)
                .onPresent(_ -> Assertions.fail())
                .onEmpty(() -> {
                });
    }

    @Test
    void allArtifacts_returns_all_registered() {
        var registry = SliceRegistry.sliceRegistry();
        var artifact1 = Artifact.artifact("org.example:slice1:1.0.0").unwrap();
        var artifact2 = Artifact.artifact("org.example:slice2:2.0.0").unwrap();
        var slice = new TestSlice();

        registry.register(artifact1, slice);
        registry.register(artifact2, slice);

        var artifacts = registry.allArtifacts();
        assertThat(artifacts).hasSize(2);
        assertThat(artifacts).contains(artifact1, artifact2);
    }

    @Test
    void allArtifacts_returns_empty_for_empty_registry() {
        var registry = SliceRegistry.sliceRegistry();
        assertThat(registry.allArtifacts()).isEmpty();
    }
}
