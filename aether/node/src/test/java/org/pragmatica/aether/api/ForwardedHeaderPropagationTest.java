// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.ResponseWriter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Pins header propagation on the management forward path.
///
/// `HttpForwarder` stamps `X-Aether-Served-By` onto a leader-forwarded response so a caller can tell
/// WHICH node produced the body -- its own comment calls the alternative "the hall of mirrors".
/// `ManagementServerImpl.sendForwardedResponse` previously copied out only `Content-Type`, so the stamp
/// was computed and then dropped, and every HTTP client saw a forwarded response as indistinguishable
/// from one the receiving node answered itself.
///
/// Measured on a live 3-node rc4 cluster before the fix: `GET /api/v1/nodes` (a `LEADER`-targeted
/// route) returned no `X-Aether-Served-By` from any of the three ports, including the two followers
/// that demonstrably forwarded.
class ForwardedHeaderPropagationTest {
    private static final class RecordingWriter implements ResponseWriter {
        private final Map<String, String> headers = new LinkedHashMap<>();
        private boolean written;

        @Override
        public ResponseWriter header(String name, String value) {
            headers.put(name, value);

            return this;
        }

        @Override
        public void write(HttpStatus status, byte[] body, ContentType contentType) {
            written = true;
        }
    }

    private static HttpResponseData forwarded(Map<String, String> headers) {
        return new HttpResponseData(200, headers, new byte[0]);
    }

    @Test
    void servedByHeaderIsPropagatedToTheClient() {
        var writer = new RecordingWriter();

        ManagementServerImpl.copyForwardedHeaders(forwarded(Map.of("X-Aether-Served-By", "node-1")), writer);

        assertThat(writer.headers)
                .describedAs("the stamp naming the answering node must survive to the client")
                .containsEntry("X-Aether-Served-By", "node-1");
    }

    /// WIRING, not just the helper. The two tests above would stay green if `sendForwardedResponse`
    /// simply stopped calling `copyForwardedHeaders` -- they exercise the helper directly. This one
    /// drives the real send path end to end, so deleting the call site reddens it.
    @Test
    void theRealSendPathEmitsTheServedByHeader() {
        var recording = new RecordingWriter();
        var instrumented = InstrumentedResponseWriter.instrumentedResponseWriter(recording);
        var headers = new LinkedHashMap<String, String>();

        headers.put("Content-Type", "application/json");
        headers.put("X-Aether-Served-By", "node-1");

        ManagementServerImpl.sendForwardedResponse(instrumented, new HttpResponseData(200, headers, new byte[0]));

        assertThat(recording.headers)
                .describedAs("the send path itself must propagate the stamp, not merely the helper it should call")
                .containsEntry("X-Aether-Served-By", "node-1");
        assertThat(recording.written)
                .describedAs("and it must still write the body -- header propagation must not replace the write")
                .isTrue();
    }

    /// Control: `Content-Type` is consumed as the typed argument to `write`, so propagating it here
    /// too would emit it twice. This asserts the skip is deliberate and scoped to that one header --
    /// without it, "copy everything" and "copy the right things" are indistinguishable.
    @Test
    void contentTypeIsNotDuplicatedButOtherHeadersSurvive() {
        var writer = new RecordingWriter();
        var headers = new LinkedHashMap<String, String>();

        headers.put("Content-Type", "application/json");
        headers.put("X-Aether-Served-By", "node-2");
        headers.put("X-Request-Id", "req-abc");

        ManagementServerImpl.copyForwardedHeaders(forwarded(headers), writer);

        assertThat(writer.headers).doesNotContainKey("Content-Type");
        assertThat(writer.headers).containsEntry("X-Aether-Served-By", "node-2");
        assertThat(writer.headers).containsEntry("X-Request-Id", "req-abc");
    }

    /// Framing headers describe the FORWARDED response's body, not the one this server re-writes, so
    /// copying them would corrupt the wire. No such header reaches this path today — this guards the
    /// blanket copy against forwarded responses gaining richer headers later, and it is the assertion
    /// that makes the skip deliberate rather than incidental.
    @Test
    void framingHeadersOfTheForwardedResponseAreNotCopied() {
        var writer = new RecordingWriter();
        var headers = new LinkedHashMap<String, String>();

        headers.put("Content-Length", "12345");
        headers.put("Transfer-Encoding", "chunked");
        headers.put("Connection", "keep-alive");
        headers.put("X-Aether-Served-By", "node-3");

        ManagementServerImpl.copyForwardedHeaders(forwarded(headers), writer);

        assertThat(writer.headers)
                .doesNotContainKeys("Content-Length", "Transfer-Encoding", "Connection");
        assertThat(writer.headers)
                .describedAs("the skip list must not swallow the stamp it exists to carry")
                .containsEntry("X-Aether-Served-By", "node-3");
    }
}
