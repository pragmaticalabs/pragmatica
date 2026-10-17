// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge.api;

import java.net.URI;
import java.net.http.HttpRequest;

import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Promise;


/// #1105: how every Forge proxy route calls an embedded node's management API. Each request carries the
/// operator's credential as `X-API-Key` when one is configured: with a sibling `aether.toml` declaring keys
/// the nodes run `SecurityMode.API_KEY`, and a keyless proxy call is refused on the node (`AUTH_FAILURE`)
/// while the dashboard shows the call failing. One builder for all proxy classes, so no route can forget it.
public record NodeHttp(JdkHttpOperations ops, OperatorKey operatorKey) {
    static final String API_KEY_HEADER = "X-API-Key";

    public static NodeHttp nodeHttp(OperatorKey operatorKey) {
        return new NodeHttp(JdkHttpOperations.jdkHttpOperations(), operatorKey);
    }

    /// A request to `path` on the local node management port `port`, carrying the operator key if any.
    public HttpRequest.Builder request(int port, String path) {
        var builder = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path));

        return operatorKey.current()
                          .fold(() -> builder,
                                key -> builder.header(API_KEY_HEADER, key));
    }

    public Promise<HttpResult<String>> sendString(HttpRequest request) {
        return ops.sendString(request);
    }
}
