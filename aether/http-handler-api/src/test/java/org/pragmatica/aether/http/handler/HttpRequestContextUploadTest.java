// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// U2: `upload` is the last component of [HttpRequestContext]; every factory that predates it carries none, and
/// `withSecurity` keeps it.
class HttpRequestContextUploadTest {
    private static final UploadHandle HANDLE = UploadHandle.uploadHandle(List.of(FileHandle.fileHandle("f",
                                                                                                       Option.some("a.txt"),
                                                                                                       "text/plain",
                                                                                                       ObjectHandle.objectHandle("ns",
                                                                                                                                 "id",
                                                                                                                                 "m",
                                                                                                                                 1,
                                                                                                                                 "d"))),
                                                                         Map.of("k", List.of("v")));

    @Test
    void earlierFactories_carryNoUpload() {
        assertThat(HttpRequestContext.httpRequestContext("/p", "GET", Map.of(), Map.of(), "req").upload()).isEqualTo(Option.none());
        assertThat(HttpRequestContext.httpRequestContext("/p",
                                                         "POST",
                                                         Map.of(),
                                                         Map.of(),
                                                         new byte[]{1},
                                                         Map.of(),
                                                         "req",
                                                         SecurityContext.securityContext())
                                     .upload()).isEqualTo(Option.none());
    }

    @Test
    void withSecurity_keepsTheUpload() {
        var context = HttpRequestContext.httpRequestContext("/p",
                                                            "POST",
                                                            Map.of(),
                                                            Map.of(),
                                                            new byte[0],
                                                            Map.of(),
                                                            "req",
                                                            SecurityContext.securityContext(),
                                                            Option.some(HANDLE));

        assertThat(context.withSecurity(SecurityContext.securityContext()).upload()).isEqualTo(Option.some(HANDLE));
    }

    @Test
    void uploadHandle_copiesItsCollections() {
        var files = new ArrayList<FileHandle>(HANDLE.files());
        var handle = UploadHandle.uploadHandle(files, HANDLE.fields());

        files.clear();

        assertThat(handle.files()).hasSize(1);
    }
}
