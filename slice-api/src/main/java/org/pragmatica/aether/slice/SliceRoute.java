package org.pragmatica.aether.slice;

import java.util.List;

/**
 * Declares an HTTP route that a slice handles.
 * Used for route self-registration during slice activation.
 *
 * <p>Example:
 * <pre>{@code
 * SliceRoute.post("/api/orders", "placeOrder")
 *     .withBody()
 * }</pre>
 */
public record SliceRoute(
 String httpMethod,
 String pathPattern,
 String methodName,
 List<RouteBinding> bindings) {
    /**
     * Create a GET route.
     */
    public static SliceRouteBuilder get(String pathPattern, String methodName) {
        return new SliceRouteBuilder("GET", pathPattern, methodName);
    }

    /**
     * Create a POST route.
     */
    public static SliceRouteBuilder post(String pathPattern, String methodName) {
        return new SliceRouteBuilder("POST", pathPattern, methodName);
    }

    /**
     * Create a PUT route.
     */
    public static SliceRouteBuilder put(String pathPattern, String methodName) {
        return new SliceRouteBuilder("PUT", pathPattern, methodName);
    }

    /**
     * Create a DELETE route.
     */
    public static SliceRouteBuilder delete(String pathPattern, String methodName) {
        return new SliceRouteBuilder("DELETE", pathPattern, methodName);
    }

    /**
     * Create a PATCH route.
     */
    public static SliceRouteBuilder patch(String pathPattern, String methodName) {
        return new SliceRouteBuilder("PATCH", pathPattern, methodName);
    }

    /**
     * Route binding types.
     */
    public sealed interface RouteBinding {
        String paramName();

        record Body(String paramName) implements RouteBinding {}

        record PathVar(String paramName) implements RouteBinding {}

        record QueryVar(String paramName) implements RouteBinding {}

        record Header(String paramName, String headerName) implements RouteBinding {}
    }

    /**
     * Builder for constructing SliceRoute with bindings.
     */
    public static class SliceRouteBuilder {
        private final String httpMethod;
        private final String pathPattern;
        private final String methodName;
        private final List<RouteBinding> bindings = new java.util.ArrayList<>();

        SliceRouteBuilder(String httpMethod, String pathPattern, String methodName) {
            this.httpMethod = httpMethod;
            this.pathPattern = pathPattern;
            this.methodName = methodName;
        }

        /**
         * Add a body binding.
         */
        public SliceRouteBuilder withBody() {
            bindings.add(new RouteBinding.Body("body"));
            return this;
        }

        /**
         * Add a body binding with custom parameter name.
         */
        public SliceRouteBuilder withBody(String paramName) {
            bindings.add(new RouteBinding.Body(paramName));
            return this;
        }

        /**
         * Add a path variable binding.
         */
        public SliceRouteBuilder withPathVar(String paramName) {
            bindings.add(new RouteBinding.PathVar(paramName));
            return this;
        }

        /**
         * Add a query variable binding.
         */
        public SliceRouteBuilder withQueryVar(String paramName) {
            bindings.add(new RouteBinding.QueryVar(paramName));
            return this;
        }

        /**
         * Add a header binding.
         */
        public SliceRouteBuilder withHeader(String paramName, String headerName) {
            bindings.add(new RouteBinding.Header(paramName, headerName));
            return this;
        }

        /**
         * Build the SliceRoute.
         */
        public SliceRoute build() {
            return new SliceRoute(httpMethod, pathPattern, methodName, List.copyOf(bindings));
        }
    }
}
