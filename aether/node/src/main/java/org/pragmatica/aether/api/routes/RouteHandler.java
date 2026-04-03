package org.pragmatica.aether.api.routes;

import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Option;


/// Route handler interface for management API endpoints.
public interface RouteHandler {
    boolean handle(RequestContext ctx, ResponseWriter response);

    default String extractPath(String uri) {
        var queryIndex = uri.indexOf('?');
        return queryIndex >= 0
              ? uri.substring(0, queryIndex)
              : uri;
    }

    default Option<String> extractQueryParam(String uri, String paramName) {
        var queryIndex = uri.indexOf('?');
        if (queryIndex <0) {return Option.empty();}
        var query = uri.substring(queryIndex + 1);
        for (var pair : query.split("&")) {
            var kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(paramName)) {return Option.option(java.net.URLDecoder.decode(kv[1],
                                                                                                            java.nio.charset.StandardCharsets.UTF_8));}
        }
        return Option.empty();
    }
}
