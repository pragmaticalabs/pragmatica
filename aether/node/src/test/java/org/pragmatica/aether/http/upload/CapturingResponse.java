// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// A response writer that keeps what was written: the first status (as a promise, since the server may answer from
/// another thread), the headers, the body, and how many times a response was written.
public final class CapturingResponse implements ResponseWriter {
    private static final TimeSpan TIMEOUT = TimeSpan.timeSpan(30).seconds();

    private final Promise<Integer> status = Promise.promise();
    private final Map<String, String> headers = new ConcurrentHashMap<>();
    private final AtomicReference<String> body = new AtomicReference<>("");
    private final AtomicInteger writes = new AtomicInteger();

    private CapturingResponse() {}

    public static CapturingResponse capturingResponse() {
        return new CapturingResponse();
    }

    @Override
    public void write(HttpStatus httpStatus, byte[] content, ContentType contentType) {
        writes.incrementAndGet();
        body.compareAndSet("", new String(content, StandardCharsets.UTF_8));
        status.succeed(httpStatus.code());
    }

    @Override
    public ResponseWriter header(String name, String value) {
        headers.put(name.toLowerCase(Locale.ROOT), value);

        return this;
    }

    @Override
    public Promise<Unit> writeAsync(HttpStatus httpStatus, byte[] content, ContentType contentType) {
        write(httpStatus, content, contentType);

        return Promise.unitPromise();
    }

    /// The status written, waiting for it up to 30 s.
    public int status() {
        var written = status.await(TIMEOUT);

        assertThat(written.isSuccess()).as("a response was written").isTrue();

        return written.unwrap();
    }

    public boolean answered() {
        return status.isResolved();
    }

    public Option<String> headerValue(String name) {
        return Option.option(headers.get(name.toLowerCase(Locale.ROOT)));
    }

    public String body() {
        return body.get();
    }

    public int writes() {
        return writes.get();
    }
}
