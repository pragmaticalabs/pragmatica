package org.pragmatica.http.routing;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Tuple.Tuple2;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.pragmatica.lang.Tuple.tuple;

public enum HttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
    PATCH,
    HEAD,
    OPTIONS,
    TRACE,
    CONNECT;

    private static final Map<String, HttpMethod> FROM_NAME;

    static {
        FROM_NAME = Stream.of(HttpMethod.values())
                           .map(value -> tuple(value.name().toLowerCase(Locale.ROOT),
                                               value))
                           .collect(Collectors.toMap(Tuple2::first,
                                                     Tuple2::last));
    }

    public static Option<HttpMethod> fromString(String name) {
        return Option.option(FROM_NAME.get(name.toLowerCase(Locale.ROOT)));
    }
}
