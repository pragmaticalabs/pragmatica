package org.pragmatica.aether.slice.blueprint;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.routing.Route;
import org.pragmatica.aether.slice.routing.RouteTarget;
import org.pragmatica.aether.slice.routing.RoutingSection;
import org.pragmatica.aether.slice.routing.SliceSpec;
import org.pragmatica.lang.Option;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BlueprintTest {

    @Test
    void blueprint_succeeds_withMinimalConfig() {
        BlueprintId.blueprintId("my-app:1.0.0")
                   .map(id -> Blueprint.blueprint(id, List.of(), List.of()))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(blueprint -> {
                       assertThat(blueprint.id().asString()).isEqualTo("my-app:1.0.0");
                       assertThat(blueprint.slices()).isEmpty();
                       assertThat(blueprint.routing()).isEmpty();
                   });
    }

    @Test
    void blueprint_succeeds_withSlices() {
        BlueprintId.blueprintId("my-app:1.0.0")
                   .flatMap(id ->
                                    Artifact.artifact("org.example:slice:1.0.0")
                                            .flatMap(artifact -> SliceSpec.sliceSpec(artifact, 3))
                                            .map(spec -> List.of(spec))
                                            .map(slices -> Blueprint.blueprint(id, slices, List.of()))
                           )
                   .onFailureRun(Assertions::fail)
                   .onSuccess(blueprint -> {
                       assertThat(blueprint.slices()).hasSize(1);
                       assertThat(blueprint.slices().get(0).instances()).isEqualTo(3);
                   });
    }

    @Test
    void blueprint_succeeds_withRouting() {
        BlueprintId.blueprintId("my-app:1.0.0")
                   .flatMap(id ->
                                    RoutingSection.routingSection("http", Option.none(), List.of())
                                                  .map(section -> List.of(section))
                                                  .map(routing -> Blueprint.blueprint(id, List.of(), routing))
                           )
                   .onFailureRun(Assertions::fail)
                   .onSuccess(blueprint -> {
                       assertThat(blueprint.routing()).hasSize(1);
                       assertThat(blueprint.routing().get(0).protocol()).isEqualTo("http");
                   });
    }

    @Test
    void blueprint_succeeds_withCompleteConfig() {
        BlueprintId.blueprintId("my-app:1.0.0")
                   .flatMap(id ->
                                    Artifact.artifact("org.example:slice:1.0.0")
                                            .flatMap(SliceSpec::sliceSpec)
                                            .flatMap(sliceSpec ->
                                                             RouteTarget.routeTarget("user-service:getUser(userId)")
                                                                        .flatMap(target -> Route.route(
                                                                                "GET:/api/users/{userId}",
                                                                                target,
                                                                                List.of()))
                                                                        .map(route -> List.of(route))
                                                                        .flatMap(routes -> RoutingSection.routingSection(
                                                                                "http",
                                                                                Option.none(),
                                                                                routes))
                                                                        .map(section -> List.of(section))
                                                                        .map(routing -> Blueprint.blueprint(id,
                                                                                                            List.of(sliceSpec),
                                                                                                            routing))
                                                    )
                           )
                   .onFailureRun(Assertions::fail)
                   .onSuccess(blueprint -> {
                       assertThat(blueprint.id().asString()).isEqualTo("my-app:1.0.0");
                       assertThat(blueprint.slices()).hasSize(1);
                       assertThat(blueprint.routing()).hasSize(1);
                   });
    }
}
