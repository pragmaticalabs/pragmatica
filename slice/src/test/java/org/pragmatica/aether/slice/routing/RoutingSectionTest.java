package org.pragmatica.aether.slice.routing;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Option;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingSectionTest {

    @Test
    void routingSection_succeeds_withoutConnector() {
        RoutingSection.routingSection("http", Option.none(), List.of())
                      .onFailureRun(Assertions::fail)
                      .onSuccess(section -> {
                          assertThat(section.protocol()).isEqualTo("http");
                          assertThat(section.connector().isPresent()).isFalse();
                          assertThat(section.routes()).isEmpty();
                      });
    }

    @Test
    void routingSection_succeeds_withConnector() {
        Artifact.artifact("org.example:http-connector:1.0.0")
                .flatMap(connector ->
                                 RoutingSection.routingSection("http", Option.some(connector), List.of())
                        )
                .onFailureRun(Assertions::fail)
                .onSuccess(section -> {
                    assertThat(section.protocol()).isEqualTo("http");
                    assertThat(section.connector().isPresent()).isTrue();
                    section.connector().onPresent(artifact ->
                                                          assertThat(artifact.asString()).isEqualTo(
                                                                  "org.example:http-connector:1.0.0")
                                                 );
                });
    }

    @Test
    void routingSection_succeeds_withRoutes() {
        RouteTarget.routeTarget("user-service:getUser(userId)")
                   .flatMap(target -> Route.route("GET:/api/users/{userId}", target, List.of()))
                   .map(route -> List.of(route))
                   .flatMap(routes -> RoutingSection.routingSection("http", Option.none(), routes))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(section -> {
                       assertThat(section.routes()).hasSize(1);
                       assertThat(section.routes().get(0).pattern()).isEqualTo("GET:/api/users/{userId}");
                   });
    }

    @Test
    void routingSection_succeeds_withKafkaProtocol() {
        RoutingSection.routingSection("kafka", Option.none(), List.of())
                      .onFailureRun(Assertions::fail)
                      .onSuccess(section -> assertThat(section.protocol()).isEqualTo("kafka"));
    }

    @Test
    void routingSection_succeeds_withGrpcProtocol() {
        RoutingSection.routingSection("grpc", Option.none(), List.of())
                      .onFailureRun(Assertions::fail)
                      .onSuccess(section -> assertThat(section.protocol()).isEqualTo("grpc"));
    }

    @Test
    void routingSection_fails_withInvalidProtocol() {
        RoutingSection.routingSection("HTTP", Option.none(), List.of())
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause.message()).contains("Invalid protocol name"));
    }

    @Test
    void routingSection_fails_withInvalidProtocolChars() {
        RoutingSection.routingSection("http_proto", Option.none(), List.of())
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause.message()).contains("Invalid protocol name"));
    }
}
