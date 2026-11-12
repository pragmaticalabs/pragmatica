package org.pragmatica.http.routing;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.routing.security.Access;
import org.pragmatica.http.routing.security.RequestSecurityContext;
import org.pragmatica.http.routing.security.RouteSecurityPolicy;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.routing.PathParameter.aLong;
import static org.pragmatica.http.routing.PathParameter.spacer;

/// #2879: `Route.withRequestContext` hands the [RequestContext] of the request being served to the handler the
/// argument-binding lambda returned, and keeps every other property of the route as it was declared.
class RouteWithRequestContextTest {
    private static final RouteSecurityPolicy DENY_ALL = new RouteSecurityPolicy() {
        @Override
        public <T extends RequestSecurityContext> Access canAccess(T context) {
            return Access.DENY;
        }
    };

    private static final UploadSpec UPLOAD = UploadSpec.uploadSpec(true, 1_000, 5_000);
    private static final SuccessSpec SUCCESS = SuccessSpec.successSpec(201, _ -> "/items/1");

    private static Route<Handler<String>> bound() {
        return Route.<Handler<String>>get("/items/")
                    .withPath(aLong(), spacer("detail"))
                    .to((id, _) -> Promise.success(request -> Promise.success(id + "@" + request.requestId())))
                    .named("getItem")
                    .withSecurity(DENY_ALL)
                    .versioned(3)
                    .withUpload(UPLOAD)
                    .asJson()
                    .withSuccess(SUCCESS);
    }

    @Test
    void handler_receivesTheRequestContextOfTheRequestBeingServed() {
        var route = Route.withRequestContext(bound());
        var response = route.handler()
                            .handle(TestRequestContext.of(route, "/items/5/detail"))
                            .await()
                            .unwrap();

        assertThat(response).isEqualTo("5@req_test");
    }

    @Test
    void parsedArguments_stillReachTheBindingLambda() {
        var route = Route.withRequestContext(bound());
        var response = route.handler()
                            .handle(TestRequestContext.of(route, "/items/12/detail"))
                            .await()
                            .unwrap();

        assertThat(response).startsWith("12@");
    }

    @Test
    void routeProperties_areThoseOfTheDeclaredRoute() {
        var declared = bound();
        var route = Route.withRequestContext(declared);

        assertThat(route.method()).isEqualTo(HttpMethod.GET);
        assertThat(route.path()).isEqualTo(declared.path());
        assertThat(route.name()).isEqualTo("getItem");
        assertThat(route.version()).isEqualTo(3);
        assertThat(route.security()).isEqualTo(declared.security());
        assertThat(route.contentType()).isEqualTo(CommonContentType.APPLICATION_JSON);
        assertThat(route.pathParamCount()).isEqualTo(declared.pathParamCount()).isEqualTo(2);
        assertThat(route.spacers()).containsExactly("detail");
        assertThat(route.spacerSlots()).isEqualTo(declared.spacerSlots());
        assertThat(route.uploadSpec()).isEqualTo(UPLOAD);
        assertThat(route.successSpec()).isSameAs(SUCCESS);
    }

    @Test
    void bindingFailure_isTheRoutesFailure_andTheRequestHandlerIsNotRun() {
        var route = Route.withRequestContext(bound());
        var response = route.handler()
                            .handle(TestRequestContext.of(route, "/items/not-a-number/detail"))
                            .await();

        assertThat(response.isFailure()).isTrue();
    }
}
