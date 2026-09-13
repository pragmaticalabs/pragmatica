package org.pragmatica.http.routing;

import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.pragmatica.http.HttpMethod.GET;
import static org.pragmatica.http.routing.PathParameter.aLong;
import static org.pragmatica.http.routing.PathParameter.spacer;
import static org.assertj.core.api.Assertions.assertThat;


/// #764, the residual half of the dispatch defect. `RequestRouter.selectBestRoute`'s arity guard
/// exempted spacer-bearing routes ("left to their own matching"), and `findFallbackRoute` returned
/// `candidates.getFirst()` unconditionally. Either hole hands an under-specified request to a handler
/// whose `pathParam(i)` overflows — surfacing as `404 "Unknown request path"`
/// (`RequestContext.NOT_FOUND`) from a route that IS registered and listed, instead of the ordinary
/// no-match. An under-specified match must be a routing miss.
class SpacerRouteArityGuardTest {
    record TestResponse(String result) {}

    private static Route<TestResponse> edit() {
        return Route.<TestResponse> get("/api/users/")
                    .withPath(aLong(),
                              spacer("edit"))
                    .to((id, _) -> Promise.success(new TestResponse("edit " + id)))
                    .asJson();
    }

    private static Route<TestResponse> profile() {
        return Route.<TestResponse> get("/api/users/")
                    .withPath(aLong(),
                              spacer("profile"))
                    .to((id, _) -> Promise.success(new TestResponse("profile " + id)))
                    .asJson();
    }

    @Nested
    class LoneSpacerRoute {
        private final RequestRouter router = RequestRouter.with(edit());

        @Test
        void findRoute_underSuppliedPath_isNoMatch_notTheSingleCandidate() {
            // Hole 1: the single-candidate shortcut returned the only spacer route for a request
            // one segment short of its arity; the handler then died at pathParam(1).
            assertThat(router.findRoute(GET, "/api/users/42").isEmpty()).as("a request with fewer segments than the route's arity must be a routing miss")
                      .isTrue();
            assertThat(router.findRoute(GET, "/api/users").isEmpty()).as("the bare prefix must be a routing miss")
                      .isTrue();
        }

        @Test
        void findRoute_spacerMismatch_isNoMatch() {
            assertThat(router.findRoute(GET, "/api/users/42/delete").isEmpty()).as("arity satisfied but the spacer is absent: no route serves this path")
                      .isTrue();
        }

        /// Pins the ARITY clause alone (review of #1076, SF-1): "edit" satisfies the spacer clause, so
        /// only the arity guard can refuse this one-segment path. With the old spacer exemption it was
        /// dispatched and died as `Invalid long value: edit` + `Not Found: Unknown request path`.
        @Test
        void findRoute_spacerPresentButUnderSupplied_isNoMatch() {
            assertThat(router.findRoute(GET, "/api/users/edit").isEmpty()).isTrue();
        }

        @Test
        void findRoute_fullySpecifiedPath_stillResolves() {
            router.findRoute(GET, "/api/users/42/edit")
                  .onEmpty(Assertions::fail)
                  .onPresent(route -> assertThat(route.spacers()).containsExactly("edit"));
        }
    }

    @Nested
    class OnlySpacerSiblings {
        private final RequestRouter router = RequestRouter.with(edit(), profile());

        @Test
        void findRoute_underSuppliedPath_isNoMatch_notTheFirstRegistered() {
            assertThat(router.findRoute(GET, "/api/users/42").isEmpty()).as("with every candidate a spacer route needing two segments, one segment is a miss")
                      .isTrue();
        }

        @Test
        void findRoute_noSpacerMatches_isNoMatch_notTheFirstRegistered() {
            // Hole 2: with no spacer match and no spacer-free candidate, findFallbackRoute returned
            // candidates.getFirst() — the `edit` route — for a path that names neither spacer.
            assertThat(router.findRoute(GET, "/api/users/42/delete").isEmpty()).as("a spacer route whose spacer is absent from the path cannot serve it")
                      .isTrue();
        }

        @Test
        void findRoute_spacerPresentButUnderSupplied_isNoMatch() {
            assertThat(router.findRoute(GET, "/api/users/edit").isEmpty()).isTrue();
            assertThat(router.findRoute(GET, "/api/users/profile").isEmpty()).isTrue();
        }

        @Test
        void findRoute_eachSpacer_resolvesItsOwnRoute() {
            router.findRoute(GET, "/api/users/42/edit")
                  .onEmpty(Assertions::fail)
                  .onPresent(route -> assertThat(route.spacers()).containsExactly("edit"));
            router.findRoute(GET, "/api/users/42/profile")
                  .onEmpty(Assertions::fail)
                  .onPresent(route -> assertThat(route.spacers()).containsExactly("profile"));
        }
    }
}
