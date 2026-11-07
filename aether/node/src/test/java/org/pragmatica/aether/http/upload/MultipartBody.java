// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/// Builds a `multipart/form-data` body (RFC 7578) byte for byte, so a test controls every delimiter, header block and
/// CRLF, including the malformed ones. Each part is written as `--boundary CRLF headers CRLF content CRLF`, so the CRLF
/// that ends a part is the first two bytes of the next delimiter.
public final class MultipartBody {
    private final String boundary;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private MultipartBody(String boundary) {
        this.boundary = boundary;
    }

    public static MultipartBody multipartBody(String boundary) {
        return new MultipartBody(boundary);
    }

    /// `multipart/form-data; boundary=<boundary>`.
    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    /// Text as it is, ISO-8859-1 (one byte per char).
    public MultipartBody raw(String text) {
        return raw(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    public MultipartBody raw(byte[] bytes) {
        out.writeBytes(bytes);

        return this;
    }

    /// One part: the dash-boundary line, `headers` (each line ending CRLF), the empty line, `content`, and the CRLF that
    /// begins the next delimiter.
    public MultipartBody part(String headers, byte[] content) {
        return raw("--" + boundary + "\r\n" + headers + "\r\n").raw(content)
                                                                 .raw("\r\n");
    }

    public MultipartBody file(String name, String fileName, String contentType, byte[] content) {
        return part("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n"
                    + "Content-Type: " + contentType + "\r\n",
                    content);
    }

    public MultipartBody field(String name, String value) {
        return part("Content-Disposition: form-data; name=\"" + name + "\"\r\n", value.getBytes(StandardCharsets.UTF_8));
    }

    /// The closing delimiter `--boundary--` (its leading CRLF was written by the last part).
    public MultipartBody close() {
        return raw("--" + boundary + "--");
    }

    /// The offset just past the delimiter that follows the parts written so far: a body cut there has seen the last part
    /// end, and its next two bytes decide what comes next.
    public int afterNextDelimiter() {
        return out.size() + 2 + boundary.length();
    }

    public byte[] bytes() {
        return out.toByteArray();
    }
}
