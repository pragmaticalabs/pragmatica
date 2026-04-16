// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Promise;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/// Captures HTTP response data from ManagementRouter dispatch
/// for serialization back through the binary forward protocol.
///
/// Since route handlers write responses asynchronously via Promise callbacks,
/// this writer exposes a Promise that resolves when the response is actually written.
final class ForwardedResponseWriter implements ResponseWriter {
    private final Map<String, String> responseHeaders = new ConcurrentHashMap<>();

    private final Promise<HttpResponseData> completionPromise = Promise.promise();

    private volatile int statusCode = HttpStatus.INTERNAL_SERVER_ERROR.code();

    private volatile byte[] responseBody = new byte[0];

    static ForwardedResponseWriter forwardedResponseWriter() {
        return new ForwardedResponseWriter();
    }

    @SuppressWarnings("JBCT-RET-01") @Override public void write(HttpStatus status,
                                                                 byte[] body,
                                                                 ContentType contentType) {
        this.statusCode = status.code();
        this.responseBody = body;
        responseHeaders.put("Content-Type", contentType.headerText());
        completionPromise.succeed(new HttpResponseData(statusCode, Map.copyOf(responseHeaders), responseBody));
    }

    @Override public ResponseWriter header(String name, String value) {
        responseHeaders.put(name, value);
        return this;
    }

    Promise<HttpResponseData> completion() {
        return completionPromise;
    }
}
