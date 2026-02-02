package org.pragmatica.jbct.slice.routing;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed route DSL specification.
 * <p>
 * DSL format: {@code "METHOD /path/{param:Type}?query1&query2:Type"}
 * <ul>
 *   <li>Method: GET, POST, PUT, DELETE, PATCH</li>
 *   <li>Path params: {@code {name}} or {@code {name:Type}} (Type defaults to String)</li>
 *   <li>Query params: {@code name} or {@code name:Type} after {@code ?}, separated by {@code &}</li>
 * </ul>
 *
 * @param method       HTTP method (GET, POST, PUT, DELETE, PATCH)
 * @param pathTemplate path template with placeholders (e.g., "/users/{id}")
 * @param pathParams   extracted path parameters
 * @param queryParams  extracted query parameters
 */
public record RouteDsl(String method,
                       String pathTemplate,
                       List<PathParam> pathParams,
                       List<QueryParam> queryParams) {
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
        if (dsl == null || dsl.isBlank()) {
            return EMPTY_DSL.result();
        }
        var matcher = DSL_PATTERN.matcher(dsl.trim());
        if (!matcher.matches()) {
            return INVALID_FORMAT.result();
        }
        var method = matcher.group(1)
                            .toUpperCase();
        var pathPart = matcher.group(2);
        var queryPart = matcher.group(3);
        return validateMethod(method).flatMap(_ -> parsePathParams(pathPart))
                             .map(pathParams -> new RouteDsl(method,
                                                             pathPart,
                                                             pathParams,
                                                             parseQueryParams(queryPart)));
    }

    private static Result<String> validateMethod(String method) {
        return VALID_METHODS.contains(method)
               ? Result.success(method)
               : Causes.cause("Invalid HTTP method: " + method + ". Valid: " + VALID_METHODS)
                       .result();
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
                    params.add(QueryParam.queryParam(typedMatcher.group(1)
                                                                 .trim(),
                                                     typedMatcher.group(2)
                                                                 .trim()));
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
            var name = typedMatcher.group(1)
                                   .trim();
            var type = typedMatcher.group(2)
                                   .trim();
            if (name.isEmpty()) {
                return Causes.cause("Path parameter name cannot be empty")
                             .result();
            }
            if (type.isEmpty()) {
                return Causes.cause("Path parameter type cannot be empty: " + name)
                             .result();
            }
            return Result.success(new String[]{name, type});
        }
        var name = paramSpec.trim();
        if (name.isEmpty()) {
            return Causes.cause("Path parameter name cannot be empty")
                         .result();
        }
        return Result.success(new String[]{name, "String"});
    }

    /**
     * Check if route has any path parameters.
     */
    public boolean hasPathParams() {
        return ! pathParams.isEmpty();
    }

    /**
     * Check if route has any query parameters.
     */
    public boolean hasQueryParams() {
        return ! queryParams.isEmpty();
    }

    /**
     * Check if route has any parameters (path or query).
     */
    public boolean hasParams() {
        return hasPathParams() || hasQueryParams();
    }

    /**
     * Returns the path template with type annotations stripped from path parameters.
     * E.g., "/{id:Long}/items/{itemId:Integer}" becomes "/{id}/items/{itemId}".
     */
    public String cleanPath() {
        return PATH_PARAM_PATTERN.matcher(pathTemplate)
                                 .replaceAll(mr -> {
                                                 var content = mr.group(1);
                                                 var colonIndex = content.indexOf(':');
                                                 var name = colonIndex >= 0
                                                            ? content.substring(0, colonIndex)
                                                            : content;
                                                 return "{" + name + "}";
                                             });
    }
}
