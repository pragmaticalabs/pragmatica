// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the JSON-envelope to spec §8.6 exit-code mapping. The CLI HTTP layer pre-formats
/// every non-2xx as `{"error":"HTTP <code>: <body>"}`; this helper scrapes the status back out.
class StreamHttpResponseTest {

    @Nested
    class StatusExtraction {

        @Test
        void extractHttpStatus_404Envelope_returnsSome404() {
            var json = "{\"error\":\"HTTP 404: stream not found\"}";
            assertThat(StreamHttpResponse.extractHttpStatus(json).or(0)).isEqualTo(404);
        }

        @Test
        void extractHttpStatus_409Envelope_returnsSome409() {
            var json = "{\"error\":\"HTTP 409: group already exists\"}";
            assertThat(StreamHttpResponse.extractHttpStatus(json).or(0)).isEqualTo(409);
        }

        @Test
        void extractHttpStatus_410Envelope_returnsSome410() {
            var json = "{\"error\":\"HTTP 410: stream version was purged\"}";
            assertThat(StreamHttpResponse.extractHttpStatus(json).or(0)).isEqualTo(410);
        }

        @Test
        void extractHttpStatus_nonHttpError_returnsNone() {
            var json = "{\"error\":\"Network failure: connection refused\"}";
            assertThat(StreamHttpResponse.extractHttpStatus(json).isEmpty()).isTrue();
        }

        @Test
        void extractHttpStatus_successfulResponse_returnsNone() {
            var json = "{\"streams\":[]}";
            assertThat(StreamHttpResponse.extractHttpStatus(json).isEmpty()).isTrue();
        }

        @Test
        void extractHttpStatus_emptyString_returnsNone() {
            assertThat(StreamHttpResponse.extractHttpStatus("").isEmpty()).isTrue();
        }

        @Test
        void extractHttpStatus_null_returnsNone() {
            assertThat(StreamHttpResponse.extractHttpStatus(null).isEmpty()).isTrue();
        }
    }

    @Nested
    class ExitCodeMapping {

        @Test
        void mapErrorExitCode_404_returnsNotFound() {
            var json = "{\"error\":\"HTTP 404: stream not found\"}";
            assertThat(StreamHttpResponse.mapErrorExitCode(json)).isEqualTo(StreamExitCode.NOT_FOUND);
        }

        @Test
        void mapErrorExitCode_409_returnsConflict() {
            var json = "{\"error\":\"HTTP 409: group already exists\"}";
            assertThat(StreamHttpResponse.mapErrorExitCode(json)).isEqualTo(StreamExitCode.CONFLICT);
        }

        @Test
        void mapErrorExitCode_410_returnsGone() {
            var json = "{\"error\":\"HTTP 410: stream version was purged\"}";
            assertThat(StreamHttpResponse.mapErrorExitCode(json)).isEqualTo(StreamExitCode.GONE);
        }

        @Test
        void mapErrorExitCode_500_returnsGenericError() {
            var json = "{\"error\":\"HTTP 500: internal server error\"}";
            assertThat(StreamHttpResponse.mapErrorExitCode(json)).isEqualTo(StreamExitCode.ERROR);
        }

        @Test
        void mapErrorExitCode_networkError_returnsGenericError() {
            var json = "{\"error\":\"Network failure: connection refused\"}";
            assertThat(StreamHttpResponse.mapErrorExitCode(json)).isEqualTo(StreamExitCode.ERROR);
        }
    }
}
