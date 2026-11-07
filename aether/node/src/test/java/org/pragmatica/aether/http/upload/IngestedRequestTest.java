// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.UploadHandle;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.QueryParams;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// A stored upload's request carries the head the server delivered, trailers included (BodySink.complete hands over the
/// head and trailers), so a slice behind a streamed upload sees the same request a slice behind an aggregated one does.
class IngestedRequestTest {
    private record Head(Headers trailers) implements HttpRequest {
        @Override
        public String requestId() {
            return "req-head";
        }

        @Override
        public HttpMethod method() {
            return HttpMethod.PUT;
        }

        @Override
        public String path() {
            return "/api/files/1";
        }

        @Override
        public Headers headers() {
            return Headers.headers(Map.of("content-type", List.of("application/pdf")));
        }

        @Override
        public QueryParams queryParams() {
            return QueryParams.queryParams(Map.of("v", List.of("2")));
        }

        @Override
        public byte[] body() {
            return new byte[0];
        }
    }

    private static final UploadHandle HANDLE = UploadHandle.uploadHandle(List.of(), Map.of());

    /// Kills each delegation replaced by a constant, the trailers' above all (a streamed upload would lose them).
    @Test
    void ingestedRequest_delegatesEveryPartOfTheHead() {
        var head = new Head(Headers.trailers(Map.of("x-checksum", List.of("abc"))));
        var ingested = IngestedRequest.ingestedRequest(head, HANDLE, () -> {});

        assertThat(ingested.trailers().asMap()).isEqualTo(head.trailers().asMap());
        assertThat(ingested.requestId()).isEqualTo("req-head");
        assertThat(ingested.method()).isEqualTo(HttpMethod.PUT);
        assertThat(ingested.path()).isEqualTo("/api/files/1");
        assertThat(ingested.headers().asMap()).isEqualTo(head.headers().asMap());
        assertThat(ingested.queryParams().asMap()).isEqualTo(head.queryParams().asMap());
        assertThat(ingested.body()).isEmpty();
    }

    @Test
    void uploadOf_andDiscardUpload_actOnlyOnAnIngestedRequest() {
        var discards = new AtomicInteger();
        var head = new Head(Headers.empty());
        var ingested = IngestedRequest.ingestedRequest(head, HANDLE, discards::incrementAndGet);

        assertThat(IngestedRequest.uploadOf(ingested)).isEqualTo(Option.some(HANDLE));
        assertThat(IngestedRequest.uploadOf(head)).isEqualTo(Option.none());
        IngestedRequest.discardUpload(head);
        assertThat(discards.get()).isZero();
        IngestedRequest.discardUpload(ingested);
        assertThat(discards.get()).isEqualTo(1);
    }
}
