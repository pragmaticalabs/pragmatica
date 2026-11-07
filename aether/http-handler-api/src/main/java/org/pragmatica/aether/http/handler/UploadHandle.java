// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.handler;

import java.util.List;
import java.util.Map;

import org.pragmatica.serialization.Codec;


/// What a streamed upload became once the ingress stored it (U2): the files, in the order they arrived, by reference, and
/// the multipart text fields by name, in arrival order per name. A request carrying one has an empty body; a forwarded
/// one crosses the cluster as a few hundred bytes whatever the upload's size.
@Codec
public record UploadHandle(List<FileHandle> files, Map<String, List<String>> fields) {
    public UploadHandle {
        files = List.copyOf(files);
        fields = Map.copyOf(fields);
    }

    public static UploadHandle uploadHandle(List<FileHandle> files, Map<String, List<String>> fields) {
        return new UploadHandle(files, fields);
    }
}
