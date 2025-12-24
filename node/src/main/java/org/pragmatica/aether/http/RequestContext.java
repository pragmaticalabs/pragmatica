package org.pragmatica.aether.http;

import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;

/**
 * Context for an incoming HTTP request containing all relevant data.
 */
public record RequestContext(
        HttpMethod method,
        String path,
        Map<String, String> pathVariables,
        Map<String, List<String>> queryParams,
        Map<String, String> headers,
        byte[] body
) {
    public Option<String> queryParam(String name) {
        var values = queryParams.get(name);
        return values != null && !values.isEmpty()
               ? Option.some(values.get(0))
               : Option.none();
    }

    public Option<String> header(String name) {
        return Option.option(headers.get(name.toLowerCase()));
    }

    public Option<String> pathVariable(String name) {
        return Option.option(pathVariables.get(name));
    }
}
