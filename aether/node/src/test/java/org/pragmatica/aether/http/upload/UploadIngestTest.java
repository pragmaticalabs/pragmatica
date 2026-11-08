// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.routing.UploadSpec;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.ObjectSpec;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;

/// The node's streaming ingest (U2): the plan of one upload, read from its head, and the per-namespace stores it binds.
class UploadIngestTest {
    private static final String COORDINATE = "com.example:svc:1.0.0";
    private static final long WAIT_SECONDS = 30;

    private static UploadIngest ingest() {
        return UploadIngest.uploadIngest(StorageInstance.storageInstance("upload-ingest-test",
                                                                         List.of(MemoryTier.memoryTier(16L * 1024 * 1024))),
                                         TimeSpan.timeSpan(1).hours());
    }

    private static HttpRequest head(Map<String, List<String>> headers) {
        return new HttpRequest() {
            @Override
            public String requestId() {
                return "req-plan";
            }

            @Override
            public HttpMethod method() {
                return HttpMethod.POST;
            }

            @Override
            public String path() {
                return "/api/upload/";
            }

            @Override
            public Headers headers() {
                return Headers.headers(headers);
            }

            @Override
            public QueryParams queryParams() {
                return QueryParams.empty();
            }

            @Override
            public byte[] body() {
                return new byte[0];
            }
        };
    }

    private static ObjectSpec binarySpec(Map<String, List<String>> headers) {
        var plan = ingest().plan(head(headers), UploadSpec.uploadSpec(true, 0, 0), COORDINATE).unwrap();

        return ((IngestSink.Body.Binary) plan.body()).spec();
    }

    @Test
    void plan_binaryBodyWithoutContentType_isStoredAsOctetStream() {
        assertThat(binarySpec(Map.of()).contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void plan_binaryBodyWithContentType_keepsIt() {
        assertThat(binarySpec(Map.of("content-type", List.of("image/png"))).contentType()).isEqualTo("image/png");
    }

    @Test
    void plan_declaredLengthOfZero_isKept() {
        assertThat(binarySpec(Map.of("content-length", List.of("0"))).declaredLength()).isEqualTo(Option.some(0L));
    }

    @Test
    void plan_declaredLengthWithSurroundingSpaces_isRead() {
        assertThat(binarySpec(Map.of("content-length", List.of(" 12 "))).declaredLength()).isEqualTo(Option.some(12L));
    }

    @Test
    void plan_declaredLengthThatIsNegativeOrNotANumber_isDropped() {
        assertThat(binarySpec(Map.of("content-length", List.of("-1"))).declaredLength()).isEqualTo(Option.none());
        assertThat(binarySpec(Map.of("content-length", List.of("abc"))).declaredLength()).isEqualTo(Option.none());
    }

    @Test
    void plan_twiceForTheSameSlice_usesTheSameStoreInstance() {
        var ingest = ingest();
        var spec = UploadSpec.uploadSpec(true, 0, 0);
        var first = ingest.plan(head(Map.of()), spec, COORDINATE).unwrap();
        var second = ingest.plan(head(Map.of()), spec, "com.example:svc:2.0.0").unwrap();
        var other = ingest.plan(head(Map.of()), spec, "com.example:other:1.0.0").unwrap();

        assertThat(second.store()).as("every version of a slice shares its namespace's store").isSameAs(first.store());
        assertThat(other.store()).isNotSameAs(first.store());
    }

    @Test
    void plan_storesObjectsInTheNamespaceOfTheSlice_notASharedOne() {
        var plan = ingest().plan(head(Map.of()), UploadSpec.uploadSpec(true, 0, 0), COORDINATE).unwrap();
        var writer = plan.store().create(ObjectSpec.objectSpec("text/plain")).await(TimeSpan.timeSpan(WAIT_SECONDS).seconds()).unwrap();
        var info = writer.commit(Option.none()).await(TimeSpan.timeSpan(WAIT_SECONDS).seconds()).unwrap();

        assertThat(info.ref().namespace()).isEqualTo("com.example.svc");
    }
}
