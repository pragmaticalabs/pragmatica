// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.handler;

import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;

/// One uploaded file, stored by the ingress before the request was dispatched (U2).
///
/// @param field        the multipart field the file came in, or `""` for a body that was not multipart [G: the empty name]
/// @param fileName     the file name the client declared, as metadata only; never a storage key
/// @param declaredType the content type the client declared for the file (`application/octet-stream` when it declared none)
/// @param object       where the bytes are
@Codec
public record FileHandle(String field, Option<String> fileName, String declaredType, ObjectHandle object) {
    public static FileHandle fileHandle(String field, Option<String> fileName, String declaredType, ObjectHandle object) {
        return new FileHandle(field, fileName, declaredType, object);
    }
}
