package org.pragmatica.http.routing;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.HttpMethod.GET;
import static org.pragmatica.http.routing.PathParameter.aLong;
import static org.pragmatica.http.routing.PathParameter.spacer;
import static org.pragmatica.http.routing.Route.get;

/// Regression coverage for interleaved-spacer value extraction (Bug B).
///
/// For a route `/orders/{id1}/items/{id2}` declared as `withPath(aLong(), spacer("items"), aLong())`
/// and request `/orders/5/items/9`, the handler must receive the two real path parameters
/// (`id1 = 5`, `id2 = 9`), with the static `items` segment consumed by the spacer and never
/// surfacing as a parameter value.
class InterleavedSpacerExtractionTest {

    record Captured(long id1, String spacer, long id2) {}

    record TestResponse(String result) {}

    @Test
    void handler_receivesRealPathParams_skippingInterleavedSpacer() {
        var captured = new AtomicReference<Captured>();

        var route = Route.<TestResponse>get("/orders/")
                         .withPath(aLong(), spacer("items"), aLong())
                         .to((id1, sp, id2) -> {
                             captured.set(new Captured(id1, sp, id2));
                             return Promise.success(new TestResponse("ok"));
                         })
                         .asJson();

        var router = RequestRouter.with(route);

        var matched = router.findRoute(GET, "/orders/5/items/9")
                            .onEmpty(Assertions::fail)
                            .or(route);

        var ctx = TestRequestContext.of(matched, "/orders/5/items/9");

        matched.handler()
               .handle(ctx)
               .await()
               .onFailure(cause -> Assertions.fail(cause.message()));

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().id1()).isEqualTo(5L);
        assertThat(captured.get().spacer()).isEqualTo("items");
        assertThat(captured.get().id2()).isEqualTo(9L);
    }

    @Test
    void handler_receivesRealPathParams_forTwoArgInterleavedSpacer() {
        var captured = new AtomicReference<long[]>();

        var route = Route.<TestResponse>get("/orders/")
                         .withPath(aLong(), spacer("items"), aLong())
                         .to((id1, _, id2) -> {
                             captured.set(new long[]{id1, id2});
                             return Promise.success(new TestResponse("ok"));
                         })
                         .asJson();

        var ctx = TestRequestContext.of(route, "/orders/5/items/9");

        route.handler()
             .handle(ctx)
             .await()
             .onFailure(cause -> Assertions.fail(cause.message()));

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get()[0]).isEqualTo(5L);
        assertThat(captured.get()[1]).isEqualTo(9L);
    }
}
