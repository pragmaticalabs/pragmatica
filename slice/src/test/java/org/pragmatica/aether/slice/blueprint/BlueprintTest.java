package org.pragmatica.aether.slice.blueprint;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.routing.SliceSpec;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BlueprintTest {

    @Test
    void blueprint_fails_withEmptySlices() {
        BlueprintId.blueprintId("my-app:1.0.0")
                   .flatMap(id -> Blueprint.blueprint(id, List.of()))
                   .onSuccessRun(Assertions::fail)
                   .onFailure(cause -> assertThat(cause.message()).contains("empty"));
    }

    @Test
    void blueprint_succeeds_withSlices() {
        BlueprintId.blueprintId("my-app:1.0.0")
                   .flatMap(id ->
                                    Artifact.artifact("org.example:slice:1.0.0")
                                            .flatMap(artifact -> SliceSpec.sliceSpec(artifact, 3))
                                            .map(spec -> List.of(spec))
                                            .flatMap(slices -> Blueprint.blueprint(id, slices))
                           )
                   .onFailureRun(Assertions::fail)
                   .onSuccess(blueprint -> {
                       assertThat(blueprint.slices()).hasSize(1);
                       assertThat(blueprint.slices().get(0).instances()).isEqualTo(3);
                   });
    }

    @Test
    void blueprint_succeeds_withMultipleSlices() {
        BlueprintId.blueprintId("my-app:1.0.0")
                   .flatMap(id ->
                                    Artifact.artifact("org.example:slice-a:1.0.0")
                                            .flatMap(SliceSpec::sliceSpec)
                                            .flatMap(specA ->
                                                             Artifact.artifact("org.example:slice-b:2.0.0")
                                                                     .flatMap(artifact -> SliceSpec.sliceSpec(artifact, 2))
                                                                     .map(specB -> List.of(specA, specB))
                                                    )
                                            .flatMap(slices -> Blueprint.blueprint(id, slices))
                           )
                   .onFailureRun(Assertions::fail)
                   .onSuccess(blueprint -> {
                       assertThat(blueprint.id().asString()).isEqualTo("my-app:1.0.0");
                       assertThat(blueprint.slices()).hasSize(2);
                       assertThat(blueprint.slices().get(0).instances()).isEqualTo(1);
                       assertThat(blueprint.slices().get(1).instances()).isEqualTo(2);
                   });
    }
}
