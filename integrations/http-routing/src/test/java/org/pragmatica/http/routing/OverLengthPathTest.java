package org.pragmatica.http.routing;

import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.pragmatica.http.HttpMethod.DELETE;
import static org.pragmatica.http.HttpMethod.GET;
import static org.pragmatica.http.routing.PathParameter.aString;
import static org.assertj.core.api.Assertions.assertThat;


/// #1101, the dispatch half: `selectBestRoute` accepted a path with MORE trailing segments than a
/// spacer-free parameter route declares and handed the handler the first N — so
/// `DELETE /config/nodes/{id}/{key}/anything` executed the two-param handler while authorisation had
/// fallen back to a weaker prefix rule. An over-length path is a routing miss for a route that
/// declares parameters. Arity-0 routes keep their prefix tolerance: `StaticFileRouteSource`
/// registers `route(GET, urlPrefix, …)` and consumes the remainder itself.
class OverLengthPathTest {
    record R(String v) {}

    @Test
    void parameterRoute_overLengthPath_isAMiss_notADispatch() {
        var route = Route.<R> delete("/config/nodes/")
                         .withPath(aString(),
                                   aString())
                         .to((id, key) -> Promise.success(new R(id + "/" + key)))
                         .asJson();
        var router = RequestRouter.with(route);

        assertThat(router.findRoute(DELETE, "/config/nodes/node-1/some.key/anything").isEmpty()).as("one segment too many must not reach a handler that binds two")
                  .isTrue();
        router.findRoute(DELETE, "/config/nodes/node-1/some.key").onEmpty(Assertions::fail);
    }

    @Test
    void arityZeroRoute_keepsPrefixTolerance_forRemainderConsumingHandlers() {
        var files = Route.<R> get("/static/").withoutParameters().to(_ -> Promise.success(new R("file"))).asJson();
        var router = RequestRouter.with(files);

        router.findRoute(GET, "/static/css/site.css").onEmpty(Assertions::fail);
    }
}
