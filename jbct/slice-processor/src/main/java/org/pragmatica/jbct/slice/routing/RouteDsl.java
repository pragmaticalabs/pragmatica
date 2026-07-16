// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// Parsed route DSL specification.
///
/// DSL format: `"METHOD /path/{param:Type`?query1&query2:Type"}
///
///   - Method: GET, POST, PUT, DELETE, PATCH
///   - Path params: `{name`} or `{name:Type`} (Type defaults to String)
///   - Query params: `name` or `name:Type` after `?`, separated by `&`
///
///
/// @param method       HTTP method (GET, POST, PUT, DELETE, PATCH)
/// @param pathTemplate path template with placeholders (e.g., "/users/{id}")
/// @param pathParams   extracted path parameters
/// @param queryParams  extracted query parameters
/// @param consumes     request body media type ([MediaType#JSON] by default)
/// @param produces     response body media type ([MediaType#JSON] by default)
public record RouteDsl(String method,
                       String pathTemplate,
                       List<PathParam> pathParams,
                       List<QueryParam> queryParams,
                       MediaType consumes,
                       MediaType produces) {
    public RouteDsl {
        pathParams = List.copyOf(pathParams);
        queryParams = List.copyOf(queryParams);
    }

    private static final Set<String> VALID_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");
    private static final Pattern DSL_PATTERN = Pattern.compile("^(\\w+)\\s+(/[^?]*)(?:\\?(.*))?$");
    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([^}]+)}");
    private static final Pattern TYPED_PARAM_PATTERN = Pattern.compile("^([^:]+):(.+)$");
    private static final Cause EMPTY_DSL = Causes.cause("Route DSL cannot be empty");

    private static final Cause INVALID_FORMAT = Causes.cause("Invalid route DSL format. Expected: METHOD /path/{param}?query");

    public static Result<RouteDsl> parse(String dsl) {
        return parse(dsl, MediaType.JSON, MediaType.JSON);
    }

    /// Parse a route DSL string, attaching the resolved `consumes`/`produces` media types.
    /// The bare-string and array route forms call [#parse(String)], which defaults both to
    /// [MediaType#JSON] (JSON in, JSON out) — preserving backward compatibility.
    public static Result<RouteDsl> parse(String dsl, MediaType consumes, MediaType produces) {
        if (dsl == null || dsl.isBlank()) {
            return EMPTY_DSL.result();
        }

        var matcher = DSL_PATTERN.matcher(dsl.trim());

        if (!matcher.matches()) {
            return INVALID_FORMAT.result();
        }

        var method = matcher.group(1).toUpperCase();
        var pathPart = matcher.group(2);
        var queryPart = matcher.group(3);

        return validateMethod(method).flatMap(_ -> parsePathParams(pathPart))
                             .map(pathParams -> new RouteDsl(method,
                                                             pathPart,
                                                             pathParams,
                                                             parseQueryParams(queryPart),
                                                             consumes,
                                                             produces));
    }

    private static Result<String> validateMethod(String method) {
        return VALID_METHODS.contains(method)
               ? Result.success(method)
               : Causes.cause("Invalid HTTP method: " + method + ". Valid: " + VALID_METHODS).result();
    }

    private static Result<List<PathParam>> parsePathParams(String path) {
        var matcher = PATH_PARAM_PATTERN.matcher(path);
        var paramSpecs = new ArrayList<String>();

        while (matcher.find()) {
            paramSpecs.add(matcher.group(1));
        }

        var results = new ArrayList<Result<PathParam>>();

        for (int i = 0; i < paramSpecs.size(); i++) {
            var position = i;

            results.add(parseTypedParam(paramSpecs.get(i)).map(nt -> PathParam.pathParam(nt[0], nt[1], position)));
        }

        return Result.allOf(results);
    }

    private static List<QueryParam> parseQueryParams(String queryPart) {
        if (queryPart == null || queryPart.isBlank()) {
            return List.of();
        }

        var params = new ArrayList<QueryParam>();

        for (var paramSpec : queryPart.split("&")) {
            if (!paramSpec.isBlank()) {
                var typedMatcher = TYPED_PARAM_PATTERN.matcher(paramSpec.trim());

                if (typedMatcher.matches()) {
                    params.add(QueryParam.queryParam(typedMatcher.group(1).trim(),
                                                     typedMatcher.group(2).trim()));
                } else {
                    params.add(QueryParam.queryParam(paramSpec.trim()));
                }
            }
        }

        return params;
    }

    private static Result<String[]> parseTypedParam(String paramSpec) {
        var typedMatcher = TYPED_PARAM_PATTERN.matcher(paramSpec.trim());

        if (typedMatcher.matches()) {
            var name = typedMatcher.group(1).trim();
            var type = typedMatcher.group(2).trim();

            if (name.isEmpty()) {
                return Causes.cause("Path parameter name cannot be empty").result();
            }

            if (type.isEmpty()) {
                return Causes.cause("Path parameter type cannot be empty: " + name).result();
            }

            return Result.success(new String[]{name, type});
        }

        var name = paramSpec.trim();

        if (name.isEmpty()) {
            return Causes.cause("Path parameter name cannot be empty").result();
        }

        return Result.success(new String[]{name, "String"});
    }

    /// Check if route has any path parameters.
    public boolean hasPathParams() {
        return ! pathParams.isEmpty();
    }

    /// Check if route has any query parameters.
    public boolean hasQueryParams() {
        return ! queryParams.isEmpty();
    }

    /// Check if route has any parameters (path or query).
    public boolean hasParams() {
        return hasPathParams() || hasQueryParams();
    }

    /// Returns the path prefix up to the first path parameter placeholder.
    /// E.g., "/{shortCode}" becomes "/", "/{id}/items/{itemId}" becomes "/".
    /// For paths without parameters, returns the full path template.
    public String basePath() {
        var idx = pathTemplate.indexOf('{');

        return idx >= 0
               ? pathTemplate.substring(0, idx)
               : pathTemplate;
    }

    /// A single ordered element of the path that follows the static [#basePath()] prefix: either a
    /// real path parameter or a static (literal) segment. The generator emits parameters as typed
    /// [org.pragmatica.http.routing.PathParameter] factory calls and statics as
    /// `PathParameter.spacer("...")`, preserving the original path order so nothing after the first
    /// parameter is dropped.
    public sealed interface PathSegment {
        /// A real path parameter (carries a value bound to the handler lambda).
        record Param(PathParam param) implements PathSegment {}

        /// A static literal path segment (consumed positionally as a spacer; carries no value).
        record Static(String text) implements PathSegment {}
    }

    /// Returns the ordered path segments that follow the static [#basePath()] prefix, interleaving
    /// real path parameters ([PathSegment.Param]) with static literal segments
    /// ([PathSegment.Static]) in their original path order.
    ///
    /// `PathParam.position` is a dense parameter index (0, 1, ...) and therefore cannot reconstruct
    /// the interleaving of static segments — the ordering is derived directly from the template
    /// string instead. Examples (prefix shown for clarity):
    ///   - `/orders/{orderId:Long}/items/{itemId:Long}` (prefix `/orders/`) →
    ///     `[Param(orderId), Static("items"), Param(itemId)]`
    ///   - `/items/{id:Long}/image` (prefix `/items/`) → `[Param(id), Static("image")]`
    ///   - `/{userId:Long}/orders` (prefix `/`) → `[Param(userId), Static("orders")]`
    ///   - `/export/{id:Long}` (prefix `/export/`) → `[Param(id)]` (no trailing static)
    public List<PathSegment> pathSegments() {
        var prefix = basePath();
        var remainder = pathTemplate.substring(prefix.length());
        var segments = new ArrayList<PathSegment>();
        var matcher = PATH_PARAM_PATTERN.matcher(remainder);
        var paramIndex = 0;
        var cursor = 0;

        while (matcher.find()) {
            addStaticSegments(segments,
                              remainder.substring(cursor, matcher.start()));
            segments.add(new PathSegment.Param(pathParams.get(paramIndex)));
            paramIndex++;
            cursor = matcher.end();
        }

        addStaticSegments(segments, remainder.substring(cursor));

        return List.copyOf(segments);
    }

    private static void addStaticSegments(List<PathSegment> segments, String chunk) {
        for (var part : chunk.split("/")) {
            if (!part.isBlank()) {
                segments.add(new PathSegment.Static(part));
            }
        }
    }

    /// Returns the path template with type annotations stripped from path parameters.
    /// E.g., "/{id:Long}/items/{itemId:Integer}" becomes "/{id}/items/{itemId}".
    public String cleanPath() {
        return PATH_PARAM_PATTERN.matcher(pathTemplate).replaceAll(mr -> {
            var content = mr.group(1);
            var colonIndex = content.indexOf(':');
            var name = colonIndex >= 0
                       ? content.substring(0, colonIndex)
                       : content;

            return "{" + name + "}";
        });
    }
}
