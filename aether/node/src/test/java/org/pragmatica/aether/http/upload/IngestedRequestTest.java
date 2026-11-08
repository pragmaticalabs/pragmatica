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
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// An ingested request answers every [HttpRequest] accessor from the head the server delivered, each from its own field.
class IngestedRequestTest {
    private static final Headers TRAILERS = Headers.headers(Map.of("x-checksum", List.of("abc")));

    private static final HttpRequest HEAD = new HttpRequest() {
        @Override
        public String requestId() {
            return "req-1";
        }

        @Override
        public HttpMethod method() {
            return HttpMethod.PUT;
        }

        @Override
        public String path() {
            return "/api/upload/";
        }

        @Override
        public Headers headers() {
            return Headers.headers(Map.of("content-type", List.of("image/png")));
        }

        @Override
        public QueryParams queryParams() {
            return QueryParams.empty();
        }

        @Override
        public byte[] body() {
            return new byte[0];
        }

        @Override
        public Headers trailers() {
            return TRAILERS;
        }
    };

    @Test
    void accessors_answerTheHeadsOwnFields() {
        var request = IngestedRequest.ingestedRequest(HEAD, UploadHandle.uploadHandle(List.of(), Map.of()), Promise::unitPromise);

        assertThat(request.requestId()).isEqualTo("req-1");
        assertThat(request.method()).isEqualTo(HttpMethod.PUT);
        assertThat(request.path()).isEqualTo("/api/upload/");
        assertThat(request.headers().get("content-type").toOptional()).contains("image/png");
        assertThat(request.trailers().get("x-checksum").toOptional()).as("trailers are not the headers").contains("abc");
        assertThat(request.trailers().get("content-type").toOptional()).isEmpty();
        assertThat(request.body()).isEmpty();
    }

    @Test
    void discardUpload_runsTheDiscard_ofAnIngestedRequestOnly() {
        var runs = new AtomicInteger();
        var request = IngestedRequest.ingestedRequest(HEAD, UploadHandle.uploadHandle(List.of(), Map.of()), () -> Promise.success(runs.incrementAndGet()).mapToUnit());

        IngestedRequest.discardUpload(request);
        IngestedRequest.discardUpload(HEAD);

        assertThat(runs.get()).isEqualTo(1);
    }
}
