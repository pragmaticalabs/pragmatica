package org.pragmatica.http.routing;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.HttpMethod.GET;
import static org.pragmatica.http.routing.PathParameter.aLong;
import static org.pragmatica.http.routing.PathParameter.spacer;
import static org.pragmatica.http.routing.Route.get;

/// Regression coverage for arity-aware route selection (Bug A) and trailing-spacer coexistence.
///
/// A no-parameter collection route (`/items`) and a single-path-parameter sibling (`/items/{id}`)
/// both normalize to the same registration key (`/items/`). Selection must pick the route whose
/// declared path arity matches the trailing-segment count of the request, instead of returning the
/// first registered candidate.
class ExactVsParamSiblingTest {

    record TestResponse(String result) {}

    @Nested
    class ExactVsParamSibling {
        @Test
        void findRoute_resolvesCollectionRoute_whenNoTrailingSegment() {
            var collection = Route.<TestResponse>get("/items")
                                  .withoutParameters()
                                  .to(_ -> Promise.success(new TestResponse("list")))
                                  .asJson();
            var item = Route.<TestResponse>get("/items/")
                            .withPath(aLong())
                            .to(id -> Promise.success(new TestResponse("item " + id)))
                            .asJson();

            var router = RequestRouter.with(collection, item);

            router.findRoute(GET, "/items")
                  .onEmpty(Assertions::fail)
                  .onPresent(route -> assertThat(route.pathParamCount()).isZero());
        }

        @Test
        void findRoute_resolvesParamRoute_whenSingleTrailingSegment() {
            var collection = Route.<TestResponse>get("/items")
                                  .withoutParameters()
                                  .to(_ -> Promise.success(new TestResponse("list")))
                                  .asJson();
            var item = Route.<TestResponse>get("/items/")
                            .withPath(aLong())
                            .to(id -> Promise.success(new TestResponse("item " + id)))
                            .asJson();

            var router = RequestRouter.with(collection, item);

            router.findRoute(GET, "/items/42")
                  .onEmpty(Assertions::fail)
                  .onPresent(route -> assertThat(route.pathParamCount()).isEqualTo(1));
        }

        @Test
        void findRoute_resolvesCollectionRoute_whenParamRegisteredFirst() {
            // Registration order reversed: the param route registers first, proving selection is
            // arity-driven and not registration-order-driven.
            var item = Route.<TestResponse>get("/items/")
                            .withPath(aLong())
                            .to(id -> Promise.success(new TestResponse("item " + id)))
                            .asJson();
            var collection = Route.<TestResponse>get("/items")
                                  .withoutParameters()
                                  .to(_ -> Promise.success(new TestResponse("list")))
                                  .asJson();

            var router = RequestRouter.with(item, collection);

            router.findRoute(GET, "/items")
                  .onEmpty(Assertions::fail)
                  .onPresent(route -> assertThat(route.pathParamCount()).isZero());
        }
    }

    @Nested
    class TrailingSpacerCoexistence {
        private RequestRouter router() {
            var collection = Route.<TestResponse>get("/items")
                                  .withoutParameters()
                                  .to(_ -> Promise.success(new TestResponse("list")))
                                  .asJson();
            var item = Route.<TestResponse>get("/items/")
                            .withPath(aLong())
                            .to(id -> Promise.success(new TestResponse("item " + id)))
                            .asJson();
            var image = Route.<TestResponse>get("/items/")
                             .withPath(aLong(), spacer("image"))
                             .to((id, _) -> Promise.success(new TestResponse("image " + id)))
                             .asJson();
            return RequestRouter.with(collection, item, image);
        }

        @Test
        void findRoute_resolvesCollection_forBarePath() {
            router().findRoute(GET, "/items")
                    .onEmpty(Assertions::fail)
                    .onPresent(route -> {
                        assertThat(route.pathParamCount()).isZero();
                        assertThat(route.spacers()).isEmpty();
                    });
        }

        @Test
        void findRoute_resolvesPlainParam_forSingleSegment() {
            router().findRoute(GET, "/items/42")
                    .onEmpty(Assertions::fail)
                    .onPresent(route -> {
                        assertThat(route.spacers()).isEmpty();
                        assertThat(route.pathParamCount()).isEqualTo(1);
                    });
        }

        @Test
        void findRoute_resolvesSpacerRoute_forSegmentPlusSpacer() {
            router().findRoute(GET, "/items/42/image")
                    .onEmpty(Assertions::fail)
                    .onPresent(route -> assertThat(route.spacers()).containsExactly("image"));
        }
    }
}
