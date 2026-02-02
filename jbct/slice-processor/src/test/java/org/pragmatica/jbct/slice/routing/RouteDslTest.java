package org.pragmatica.jbct.slice.routing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteDslTest {

    @Nested
    class SimpleRoutes {

        @Test
        void parse_succeeds_forSimpleGetWithoutParams() {
            var result = RouteDsl.parse("GET /users");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> {
                assertThat(route.method()).isEqualTo("GET");
                assertThat(route.pathTemplate()).isEqualTo("/users");
                assertThat(route.pathParams()).isEmpty();
                assertThat(route.queryParams()).isEmpty();
            });
        }

        @Test
        void parse_succeeds_forPostRoute() {
            var result = RouteDsl.parse("POST /users");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> {
                assertThat(route.method()).isEqualTo("POST");
                assertThat(route.pathTemplate()).isEqualTo("/users");
            });
        }

        @Test
        void parse_succeeds_forPutRoute() {
            var result = RouteDsl.parse("PUT /users/{id}");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> {
                assertThat(route.method()).isEqualTo("PUT");
                assertThat(route.pathTemplate()).isEqualTo("/users/{id}");
            });
        }

        @Test
        void parse_succeeds_forDeleteRoute() {
            var result = RouteDsl.parse("DELETE /users/{id}");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> {
                assertThat(route.method()).isEqualTo("DELETE");
                assertThat(route.pathTemplate()).isEqualTo("/users/{id}");
            });
        }

        @Test
        void parse_succeeds_forPatchRoute() {
            var result = RouteDsl.parse("PATCH /users/{id}");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> {
                assertThat(route.method()).isEqualTo("PATCH");
            });
        }

        @Test
        void parse_normalizesMethodToUpperCase() {
            var result = RouteDsl.parse("get /users");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> assertThat(route.method()).isEqualTo("GET"));
        }
    }

    @Nested
    class PathParameters {

        @Test
        void parse_succeeds_forSinglePathParam() {
            var result = RouteDsl.parse("GET /users/{id:Long}");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> {
                assertThat(route.pathParams()).hasSize(1);
                assertThat(route.pathParams().getFirst().name()).isEqualTo("id");
                assertThat(route.pathParams().getFirst().type()).isEqualTo("Long");
                assertThat(route.pathParams().getFirst().position()).isEqualTo(0);
            });
        }

        @Test
        void parse_succeeds_forPathParamWithDefaultType() {
            var result = RouteDsl.parse("GET /users/{id}");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> {
                assertThat(route.pathParams()).hasSize(1);
                assertThat(route.pathParams().getFirst().name()).isEqualTo("id");
                assertThat(route.pathParams().getFirst().type()).isEqualTo("String");
            });
        }

        @Test
        void parse_succeeds_forMultiplePathParams() {
            var result = RouteDsl.parse("GET /users/{id:Long}/orders/{orderId:String}");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> {
                assertThat(route.pathParams()).hasSize(2);

                var firstParam = route.pathParams().get(0);
                assertThat(firstParam.name()).isEqualTo("id");
                assertThat(firstParam.type()).isEqualTo("Long");
                assertThat(firstParam.position()).isEqualTo(0);

                var secondParam = route.pathParams().get(1);
                assertThat(secondParam.name()).isEqualTo("orderId");
                assertThat(secondParam.type()).isEqualTo("String");
                assertThat(secondParam.position()).isEqualTo(1);
            });
        }

        @Test
        void hasPathParams_returnsTrue_whenPathParamsExist() {
            var result = RouteDsl.parse("GET /users/{id}");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> assertThat(route.hasPathParams()).isTrue());
        }

        @Test
        void hasPathParams_returnsFalse_whenNoPathParams() {
            var result = RouteDsl.parse("GET /users");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> assertThat(route.hasPathParams()).isFalse());
        }
    }

    @Nested
    class QueryParameters {

        @Test
        void parse_succeeds_forSingleQueryParam() {
            var result = RouteDsl.parse("GET /users?name");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> {
                assertThat(route.queryParams()).hasSize(1);
                assertThat(route.queryParams().getFirst().name()).isEqualTo("name");
                assertThat(route.queryParams().getFirst().type()).isEqualTo("String");
            });
        }

        @Test
        void parse_succeeds_forTypedQueryParam() {
            var result = RouteDsl.parse("GET /users?limit:Integer");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> {
                assertThat(route.queryParams()).hasSize(1);
                assertThat(route.queryParams().getFirst().name()).isEqualTo("limit");
                assertThat(route.queryParams().getFirst().type()).isEqualTo("Integer");
            });
        }

        @Test
        void parse_succeeds_forMultipleQueryParams() {
            var result = RouteDsl.parse("GET /users?name&limit:Integer");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> {
                assertThat(route.queryParams()).hasSize(2);

                var firstParam = route.queryParams().get(0);
                assertThat(firstParam.name()).isEqualTo("name");
                assertThat(firstParam.type()).isEqualTo("String");

                var secondParam = route.queryParams().get(1);
                assertThat(secondParam.name()).isEqualTo("limit");
                assertThat(secondParam.type()).isEqualTo("Integer");
            });
        }

        @Test
        void hasQueryParams_returnsTrue_whenQueryParamsExist() {
            var result = RouteDsl.parse("GET /users?name");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> assertThat(route.hasQueryParams()).isTrue());
        }

        @Test
        void hasQueryParams_returnsFalse_whenNoQueryParams() {
            var result = RouteDsl.parse("GET /users");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> assertThat(route.hasQueryParams()).isFalse());
        }
    }

    @Nested
    class MixedParameters {

        @Test
        void parse_succeeds_forPathAndQueryParams() {
            var result = RouteDsl.parse("GET /users/{id:Long}?status&limit:Integer");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> {
                assertThat(route.method()).isEqualTo("GET");
                assertThat(route.pathTemplate()).isEqualTo("/users/{id:Long}");

                assertThat(route.pathParams()).hasSize(1);
                assertThat(route.pathParams().getFirst().name()).isEqualTo("id");
                assertThat(route.pathParams().getFirst().type()).isEqualTo("Long");

                assertThat(route.queryParams()).hasSize(2);
                assertThat(route.queryParams().get(0).name()).isEqualTo("status");
                assertThat(route.queryParams().get(1).name()).isEqualTo("limit");
                assertThat(route.queryParams().get(1).type()).isEqualTo("Integer");
            });
        }

        @Test
        void hasParams_returnsTrue_whenBothTypesExist() {
            var result = RouteDsl.parse("GET /users/{id}?name");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> assertThat(route.hasParams()).isTrue());
        }

        @Test
        void hasParams_returnsTrue_whenOnlyPathParams() {
            var result = RouteDsl.parse("GET /users/{id}");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> assertThat(route.hasParams()).isTrue());
        }

        @Test
        void hasParams_returnsTrue_whenOnlyQueryParams() {
            var result = RouteDsl.parse("GET /users?name");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> assertThat(route.hasParams()).isTrue());
        }

        @Test
        void hasParams_returnsFalse_whenNoParams() {
            var result = RouteDsl.parse("GET /users");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> assertThat(route.hasParams()).isFalse());
        }
    }

    @Nested
    class InvalidInputs {

        @Test
        void parse_fails_forEmptyDsl() {
            var result = RouteDsl.parse("");

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("empty"));
        }

        @Test
        void parse_fails_forNullDsl() {
            var result = RouteDsl.parse(null);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("empty"));
        }

        @Test
        void parse_fails_forBlankDsl() {
            var result = RouteDsl.parse("   ");

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("empty"));
        }

        @Test
        void parse_fails_forUnknownHttpMethod() {
            var result = RouteDsl.parse("CONNECT /users");

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("Invalid HTTP method"));
        }

        @Test
        void parse_treatsEmptyBracesAsLiteralPath() {
            // Empty braces {} don't match as path param pattern, treated as literal
            var result = RouteDsl.parse("GET /users/{}");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(route -> {
                assertThat(route.pathTemplate()).isEqualTo("/users/{}");
                assertThat(route.pathParams()).isEmpty();
            });
        }

        @Test
        void parse_fails_forMissingPath() {
            var result = RouteDsl.parse("GET");

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("Invalid route DSL format"));
        }
    }
}
