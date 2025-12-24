package org.pragmatica.aether.slice.routing;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteTest {

    @Test
    void route_succeeds_withValidPattern() {
        RouteTarget.routeTarget("user-service:getUser(userId)")
                   .flatMap(target -> Route.route("GET:/api/users/{userId}", target, List.of()))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(route -> {
                       assertThat(route.pattern()).isEqualTo("GET:/api/users/{userId}");
                       assertThat(route.target().methodName()).isEqualTo("getUser");
                       assertThat(route.bindings()).isEmpty();
                   });
    }

    @Test
    void route_succeeds_withBindings() {
        RouteTarget.routeTarget("user-service:getUser(userId)")
                   .flatMap(target ->
                                    Binding.binding("userId", new BindingSource.PathVar("userId"))
                                           .map(binding -> Route.route("GET:/api/users/{userId}",
                                                                       target,
                                                                       List.of(binding)))
                           )
                   .flatMap(result -> result)
                   .onFailureRun(Assertions::fail)
                   .onSuccess(route -> {
                       assertThat(route.bindings()).hasSize(1);
                       assertThat(route.bindings().get(0).param()).isEqualTo("userId");
                   });
    }

    @Test
    void route_succeeds_withMultipleMethods() {
        RouteTarget.routeTarget("user-service:createUser()")
                   .flatMap(target -> Route.route("POST:/api/users", target, List.of()))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(route -> assertThat(route.pattern()).isEqualTo("POST:/api/users"));
    }

    @Test
    void route_succeeds_withPathSegments() {
        RouteTarget.routeTarget("user-service:getOrders(userId)")
                   .flatMap(target -> Route.route("GET:/api/users/{userId}/orders", target, List.of()))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(route -> assertThat(route.pattern()).isEqualTo("GET:/api/users/{userId}/orders"));
    }

    @Test
    void route_succeeds_withKafkaTopicPattern() {
        RouteTarget.routeTarget("user-service:handleEvent(value)")
                   .flatMap(target -> Route.route("user-events", target, List.of()))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(route -> assertThat(route.pattern()).isEqualTo("user-events"));
    }

    @Test
    void route_succeeds_withGrpcServiceMethodPattern() {
        RouteTarget.routeTarget("user-service:getUser(request)")
                   .flatMap(target -> Route.route("UserService.GetUser", target, List.of()))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(route -> assertThat(route.pattern()).isEqualTo("UserService.GetUser"));
    }
}
