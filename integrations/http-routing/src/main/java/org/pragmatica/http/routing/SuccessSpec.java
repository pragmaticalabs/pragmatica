package org.pragmatica.http.routing;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;


/// The success response a route declares in `routes.toml` (`status`, `location`, #2805), carried with the route to the
/// router that answers it.
///
/// The slice processor validates the declaration at build time (a 2xx status, `204` only on a `Unit` result, a
/// `location` that names fields of the result record), so this carrier holds only what the build accepted.
/// A route that declares nothing answers `200` with a body, or `204` for a `Unit` result.
///
/// @param status   the declared success status, empty when not declared
/// @param location the `Location` header built from the success value, empty when not declared
public record SuccessSpec(Option<Integer> status, Option<Fn1<String, Object>> location) {
    /// A route with no success declaration: the router's defaults apply.
    public static final SuccessSpec DEFAULT = new SuccessSpec(Option.none(), Option.none());

    /// A declared success status with no `Location` header.
    public static SuccessSpec successSpec(int status) {
        return new SuccessSpec(Option.some(status), Option.none());
    }

    /// A declared success status with a `Location` header built from the success value.
    public static SuccessSpec successSpec(int status, Fn1<String, Object> location) {
        return new SuccessSpec(Option.some(status), Option.some(location));
    }

    /// A field value rendered as one `Location` path segment: percent-encoded, so a `/` or a space in the value cannot
    /// change the path's shape.
    public static String pathSegment(Object value) {
        return URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8)
                         .replace("+", "%20");
    }
}
