// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.handler;

import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;


/// One uploaded file, stored by the ingress before the request was dispatched (U2).
///
/// **`fileName` and `declaredType` are untrusted input.** They are what the client sent, delivered verbatim and
/// unsanitized: a file name may contain NUL, `/`, `\`, `..` segments, a drive prefix and up to about 8 KiB of text (the
/// bound on one multipart part's header block). Sanitize the name before using it as a path, a header value or markup;
/// never trust the declared type to describe the bytes.
///
/// @param field        the multipart field the file came in, or `""` for a body that was not multipart [G: the empty name]
/// @param fileName     the file name the client declared, as metadata only; never a storage key. Untrusted (see above)
/// @param declaredType the content type the client declared for the file (`application/octet-stream` when it declared none);
///                     untrusted
/// @param object       where the bytes are
@Codec
public record FileHandle(String field, Option<String> fileName, String declaredType, ObjectHandle object) {
    public static FileHandle fileHandle(String field,
                                        Option<String> fileName,
                                        String declaredType,
                                        ObjectHandle object) {
        return new FileHandle(field, fileName, declaredType, object);
    }
}
