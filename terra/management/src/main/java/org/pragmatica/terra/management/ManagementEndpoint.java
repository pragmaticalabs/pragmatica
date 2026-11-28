// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.management;

import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// One management endpoint at `<base-path>/<id>` (#3651). `sensitive` endpoints require an authenticated caller;
/// `selector` is the path template after the id that discovery advertises (`{*path}`, `{requiredMetricName}`), when
/// the endpoint takes one. Part 1 endpoints are read-only: a method other than GET answers 405.
public record ManagementEndpoint(String id,
                                 boolean sensitive,
                                 Option<String> selector,
                                 Fn1<Promise<Reply>, Call> handler) {
    public static ManagementEndpoint managementEndpoint(String id,
                                                        boolean sensitive,
                                                        Option<String> selector,
                                                        Fn1<Promise<Reply>, Call> handler) {
        return new ManagementEndpoint(id, sensitive, selector, handler);
    }

    /// The link name discovery gives the selector template: `{*path}` is linked as `<id>-path`.
    Option<String> selectorName() {
        return selector.map(template -> template.replaceAll("[{}*]", ""));
    }

    /// A request to one endpoint: the path segments after the id, the query, whether the caller is authenticated,
    /// and whether the host admits slice requests (readiness).
    public record Call(List<String> selectors, Map<String, List<String>> query, boolean authorized, boolean ready) {
        public static Call call(List<String> selectors,
                                Map<String, List<String>> query,
                                boolean authorized,
                                boolean ready) {
            return new Call(List.copyOf(selectors), Map.copyOf(query), authorized, ready);
        }
    }

    /// An endpoint's answer: a JSON body in the negotiated actuator media type, plain text with its own type, or a
    /// bare status.
    public sealed interface Reply {
        record Json(int status, Object body) implements Reply {}

        record Text(int status, String body, String contentType) implements Reply {}

        record Empty(int status) implements Reply {}

        static Reply json(int status, Object body) {
            return new Json(status, body);
        }

        static Reply text(String body, String contentType) {
            return new Text(200, body, contentType);
        }

        static Reply empty(int status) {
            return new Empty(status);
        }
    }
}
