// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.handler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// An [UploadHandle] is a value: changing the collections it was built from afterwards changes nothing in it.
class UploadHandleTest {
    private static FileHandle file(String field) {
        return FileHandle.fileHandle(field,
                                     Option.none(),
                                     "application/octet-stream",
                                     ObjectHandle.objectHandle("ns", "id", "00", 1, "11"));
    }

    @Test
    void files_areCopied_soLaterChangesToTheSourceListDoNotShow() {
        var source = new ArrayList<>(List.of(file("a")));
        var handle = UploadHandle.uploadHandle(source, new HashMap<>());

        source.add(file("b"));

        assertThat(handle.files()).extracting(FileHandle::field).containsExactly("a");
    }

    @Test
    void fields_areCopied_soLaterChangesToTheSourceMapDoNotShow() {
        var source = new HashMap<String, List<String>>();

        source.put("k", List.of("v"));

        var handle = UploadHandle.uploadHandle(List.of(), source);

        source.put("late", List.of("x"));

        assertThat(handle.fields()).containsOnlyKeys("k");
    }
}
