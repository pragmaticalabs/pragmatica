package org.pragmatica.http.routing;

import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Test;

import static org.pragmatica.http.HttpMethod.GET;
import static org.pragmatica.http.routing.PathParameter.aLong;
import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.http.routing.PathParameter.spacer;
import static org.assertj.core.api.Assertions.assertThat;


/// #755 / #1103: `RouteShapeSelector` matched a route's spacers by SET MEMBERSHIP, so a spacer sitting at the wrong
/// position still selected the route, and the handler's `pathParam()` then failed with `expected 'edit', got '42'`,
/// surfacing as a 5xx. A route that knows its spacers' slots is now matched by position.
class PositionalSpacerSelectionTest {
    record TestResponse(String result) {}

    private static Route<TestResponse> editUser() {
        return Route.<TestResponse> get("/api/users/")
                    .withPath(aLong(), spacer("edit"))
                    .to((id, _) -> Promise.success(new TestResponse("edit " + id)))
                    .asJson();
    }

    /// #1103's repro: the spacer FIRST, then the parameter, on a route declared parameter-then-spacer.
    @Test
    void findRoute_spacerFirstOnParameterThenSpacerRoute_isNoMatch() {
        var router = RequestRouter.with(editUser());

        assertThat(router.findRoute(GET, "/api/users/edit/42").isEmpty())
            .as("`edit` is present in the path but at slot 0; the route declares it at slot 1")
            .isTrue();
    }

    @Test
    void findRoute_spacerAtItsDeclaredSlot_stillResolves() {
        var router = RequestRouter.with(editUser());

        assertThat(router.findRoute(GET, "/api/users/42/edit").isPresent()).isTrue();
    }

    /// #755's repro class: a spacer and an adjacent same-typed parameter swapped, so the literal is present but
    /// the value sits where the spacer should be.
    @Test
    void findRoute_spacerSwappedWithAdjacentStringParameter_isNoMatch() {
        var router = RequestRouter.with(Route.<TestResponse> get("/api/streams/")
                                             .withPath(aString(), spacer("read"))
                                             .to((name, _) -> Promise.success(new TestResponse("read " + name)))
                                             .asJson());

        assertThat(router.findRoute(GET, "/api/streams/read/orders").isEmpty())
            .as("`read` is at slot 0, the declaration puts it at slot 1")
            .isTrue();
        assertThat(router.findRoute(GET, "/api/streams/orders/read").isPresent()).isTrue();
    }

    /// A parameter whose VALUE equals the spacer literal is legitimate: position, not content, tells them apart.
    @Test
    void findRoute_parameterValueEqualToTheSpacerLiteral_resolves() {
        var router = RequestRouter.with(Route.<TestResponse> get("/api/streams/")
                                             .withPath(aString(), spacer("read"))
                                             .to((name, _) -> Promise.success(new TestResponse("read " + name)))
                                             .asJson());

        assertThat(router.findRoute(GET, "/api/streams/read/read").isPresent()).isTrue();
    }

    @Test
    void findRoute_leadingAndTrailingSpacers_areEachMatchedAtTheirOwnSlot() {
        var router = RequestRouter.with(Route.<TestResponse> get("/api/")
                                             .withPath(spacer("streams"), aString(), spacer("read"))
                                             .to((_, name, _) -> Promise.success(new TestResponse("read " + name)))
                                             .asJson());

        assertThat(router.findRoute(GET, "/api/streams/orders/read").isPresent()).isTrue();
        assertThat(router.findRoute(GET, "/api/read/orders/streams").isEmpty())
            .as("both literals are present, both at the wrong slot")
            .isTrue();
    }

    /// With the spacer route refused, a spacer-free sibling of the same arity serves the path, as it should.
    @Test
    void findRoute_spacerRouteRefused_fallsThroughToTheSpacerFreeSibling() {
        var plain = Route.<TestResponse> get("/api/users/")
                         .withPath(aString(), aString())
                         .to((first, second) -> Promise.success(new TestResponse(first + "/" + second)))
                         .asJson();
        var router = RequestRouter.with(editUser(), plain);

        router.findRoute(GET, "/api/users/edit/42")
              .onEmpty(() -> org.junit.jupiter.api.Assertions.fail("the spacer-free sibling must serve this path"))
              .onPresent(route -> assertThat(route.spacers()).isEmpty());
    }
}
