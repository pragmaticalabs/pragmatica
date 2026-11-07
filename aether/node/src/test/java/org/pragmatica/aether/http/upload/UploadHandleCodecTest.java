// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.FileHandle;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.ObjectHandle;
import org.pragmatica.aether.http.handler.UploadHandle;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;

/// The U2 handles cross the wire through the production node codec: an [HttpRequestContext] carrying an [UploadHandle]
/// decodes to an equal handle (every component, both file name forms, repeated field values), and one without decodes to
/// none.
class UploadHandleCodecTest {
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

    private static UploadHandle handle() {
        var first = FileHandle.fileHandle("doc",
                                          Option.some("report.pdf"),
                                          "application/pdf",
                                          ObjectHandle.objectHandle("com.example.svc",
                                                                    "AAAAAAAAAAAAAAAAAAAAAAAAAA",
                                                                    "ab".repeat(32),
                                                                    123_456_789L,
                                                                    "cd".repeat(32)));
        var second = FileHandle.fileHandle("",
                                           Option.none(),
                                           "application/octet-stream",
                                           ObjectHandle.objectHandle("com.example.svc",
                                                                     "BBBBBBBBBBBBBBBBBBBBBBBBBB",
                                                                     "01".repeat(32),
                                                                     0L,
                                                                     "23".repeat(32)));

        return UploadHandle.uploadHandle(List.of(first, second), Map.of("tag", List.of("a", "b"), "note", List.of("x")));
    }

    private static HttpRequestContext context(Option<UploadHandle> upload) {
        return HttpRequestContext.httpRequestContext("/files",
                                                     "POST",
                                                     Map.of(),
                                                     Map.of("content-type", List.of("multipart/form-data; boundary=b")),
                                                     new byte[0],
                                                     Map.of(),
                                                     "req-codec",
                                                     SecurityContext.securityContext(),
                                                     upload);
    }

    @Test
    void encodeDecode_contextWithUpload_keepsTheHandle() {
        HttpRequestContext decoded = CODEC.decode(CODEC.encode(context(Option.some(handle()))));

        assertThat(decoded.upload()).isEqualTo(Option.some(handle()));
        assertThat(decoded.body()).isEmpty();
    }

    @Test
    void encodeDecode_contextWithoutUpload_decodesToNone() {
        HttpRequestContext decoded = CODEC.decode(CODEC.encode(context(Option.none())));

        assertThat(decoded.upload()).isEqualTo(Option.none());
    }

    @Test
    void withSecurity_keepsTheUpload() {
        assertThat(context(Option.some(handle())).withSecurity(SecurityContext.securityContext())
                                                  .upload()).isEqualTo(Option.some(handle()));
    }
}
