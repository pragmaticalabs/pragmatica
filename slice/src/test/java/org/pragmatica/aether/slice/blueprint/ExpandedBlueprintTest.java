package org.pragmatica.aether.slice.blueprint;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.routing.RoutingSection;
import org.pragmatica.lang.Option;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExpandedBlueprintTest {

    @Test
    void expandedBlueprint_succeeds_withLoadOrder() {
        BlueprintId.blueprintId("my-app:1.0.0")
                   .flatMap(id ->
                                    Artifact.artifact("org.example:slice:1.0.0")
                                            .map(artifact -> ResolvedSlice.resolvedSlice(artifact, 2, false))
                                            .flatMap(resolved ->
                                                             Artifact.artifact("org.example:dependency:2.0.0")
                                                                     .map(depArtifact -> ResolvedSlice.resolvedSlice(
                                                                             depArtifact,
                                                                             1,
                                                                             true))
                                                                     .map(dep -> List.of(dep, resolved))
                                                                     .map(loadOrder -> ExpandedBlueprint.expandedBlueprint(
                                                                             id,
                                                                             loadOrder,
                                                                             List.of()))
                                                    )
                           )
                   .onFailureRun(Assertions::fail)
                   .onSuccess(expanded -> {
                       assertThat(expanded.id().asString()).isEqualTo("my-app:1.0.0");
                       assertThat(expanded.loadOrder()).hasSize(2);
                       assertThat(expanded.loadOrder().get(0).isDependency()).isTrue();
                       assertThat(expanded.loadOrder().get(1).isDependency()).isFalse();
                   });
    }

    @Test
    void expandedBlueprint_succeeds_withRouting() {
        BlueprintId.blueprintId("my-app:1.0.0")
                   .flatMap(id ->
                                    RoutingSection.routingSection("http", Option.none(), List.of())
                                                  .map(section -> List.of(section))
                                                  .map(routing -> ExpandedBlueprint.expandedBlueprint(id,
                                                                                                      List.of(),
                                                                                                      routing))
                           )
                   .onFailureRun(Assertions::fail)
                   .onSuccess(expanded -> {
                       assertThat(expanded.routing()).hasSize(1);
                       assertThat(expanded.routing().get(0).protocol()).isEqualTo("http");
                   });
    }

    @Test
    void expandedBlueprint_succeeds_withEmptyConfig() {
        BlueprintId.blueprintId("my-app:1.0.0")
                   .map(id -> ExpandedBlueprint.expandedBlueprint(id, List.of(), List.of()))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(expanded -> {
                       assertThat(expanded.id().asString()).isEqualTo("my-app:1.0.0");
                       assertThat(expanded.loadOrder()).isEmpty();
                       assertThat(expanded.routing()).isEmpty();
                   });
    }
}
