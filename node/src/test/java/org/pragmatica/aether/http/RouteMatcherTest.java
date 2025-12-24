package org.pragmatica.aether.http;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.routing.Binding;
import org.pragmatica.aether.slice.routing.BindingSource;
import org.pragmatica.aether.slice.routing.Route;
import org.pragmatica.aether.slice.routing.RouteTarget;
import org.pragmatica.aether.slice.routing.RoutingSection;
import org.pragmatica.lang.Option;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteMatcherTest {

    @Test
    void match_singleRoute_findsRoute() {
        var route = new Route(
            "GET:/api/orders",
            new RouteTarget("order-service", "listOrders", List.of()),
            List.of()
        );
        var section = new RoutingSection("http", Option.empty(), List.of(route));
        var matcher = RouteMatcher.routeMatcher(List.of(section));

        var result = matcher.match(HttpMethod.GET, "/api/orders");

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap().route()).isEqualTo(route);
    }

    @Test
    void match_routeWithPathVariable_extractsVariable() {
        var route = new Route(
            "GET:/api/orders/{orderId}",
            new RouteTarget("order-service", "getOrder", List.of()),
            List.of(new Binding("orderId", new BindingSource.PathVar("orderId")))
        );
        var section = new RoutingSection("http", Option.empty(), List.of(route));
        var matcher = RouteMatcher.routeMatcher(List.of(section));

        var result = matcher.match(HttpMethod.GET, "/api/orders/ORD-12345");

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap().pathVariables()).containsEntry("orderId", "ORD-12345");
    }

    @Test
    void match_multipleRoutes_findsCorrectRoute() {
        var getRoute = new Route(
            "GET:/api/orders/{orderId}",
            new RouteTarget("order-service", "getOrder", List.of()),
            List.of()
        );
        var postRoute = new Route(
            "POST:/api/orders",
            new RouteTarget("order-service", "createOrder", List.of()),
            List.of()
        );
        var section = new RoutingSection("http", Option.empty(), List.of(getRoute, postRoute));
        var matcher = RouteMatcher.routeMatcher(List.of(section));

        var getResult = matcher.match(HttpMethod.GET, "/api/orders/123");
        var postResult = matcher.match(HttpMethod.POST, "/api/orders");

        assertThat(getResult.isPresent()).isTrue();
        assertThat(getResult.unwrap().route().target().methodName()).isEqualTo("getOrder");

        assertThat(postResult.isPresent()).isTrue();
        assertThat(postResult.unwrap().route().target().methodName()).isEqualTo("createOrder");
    }

    @Test
    void match_noMatchingRoute_returnsEmpty() {
        var route = new Route(
            "GET:/api/orders",
            new RouteTarget("order-service", "listOrders", List.of()),
            List.of()
        );
        var section = new RoutingSection("http", Option.empty(), List.of(route));
        var matcher = RouteMatcher.routeMatcher(List.of(section));

        var result = matcher.match(HttpMethod.GET, "/api/users");

        assertThat(result.isPresent()).isFalse();
    }

    @Test
    void match_wrongProtocol_ignoredInMatcher() {
        var route = new Route(
            "GET:/api/orders",
            new RouteTarget("order-service", "listOrders", List.of()),
            List.of()
        );
        var grpcSection = new RoutingSection("grpc", Option.empty(), List.of(route));
        var matcher = RouteMatcher.routeMatcher(List.of(grpcSection));

        var result = matcher.match(HttpMethod.GET, "/api/orders");

        assertThat(result.isPresent()).isFalse();
    }

    @Test
    void match_multipleSections_combinesRoutes() {
        var route1 = new Route(
            "GET:/api/orders",
            new RouteTarget("order-service", "listOrders", List.of()),
            List.of()
        );
        var route2 = new Route(
            "GET:/api/users",
            new RouteTarget("user-service", "listUsers", List.of()),
            List.of()
        );
        var section1 = new RoutingSection("http", Option.empty(), List.of(route1));
        var section2 = new RoutingSection("https", Option.empty(), List.of(route2));
        var matcher = RouteMatcher.routeMatcher(List.of(section1, section2));

        var ordersResult = matcher.match(HttpMethod.GET, "/api/orders");
        var usersResult = matcher.match(HttpMethod.GET, "/api/users");

        assertThat(ordersResult.isPresent()).isTrue();
        assertThat(usersResult.isPresent()).isTrue();
    }
}
