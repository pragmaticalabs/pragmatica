// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The header-parameter reader the multipart decoder and the ingest sink share: media type and `name=value` parameters.
class HeaderParametersTest {
    @Test
    void mediaType_isLowerCasedAndStripped() {
        assertThat(HeaderParameters.mediaType("  Multipart/Form-Data  ; boundary=x")).isEqualTo("multipart/form-data");
        assertThat(HeaderParameters.mediaType("TEXT/Plain")).isEqualTo("text/plain");
    }

    @Test
    void parameterValue_afterATab_isRead() {
        assertThat(HeaderParameters.parameter("form-data;\tname=\t\"a\"", "name").or("<none>")).isEqualTo("a");
        assertThat(HeaderParameters.parameter("form-data; name=\tvalue", "name").or("<none>")).isEqualTo("value");
    }

    @Test
    void quotedValue_endingInATrailingBackslash_isReadWithoutOverrunning() {
        assertThat(HeaderParameters.parameter("form-data; name=\"ab\\", "name").or("<none>")).isEqualTo("ab\\");
    }

    @Test
    void tokenValue_isStripped() {
        assertThat(HeaderParameters.parameter("form-data; name=abc  ; other=1", "name").or("<none>")).isEqualTo("abc");
    }

    @Test
    void segmentWithoutEquals_isSkipped_andTheNextParameterIsFound() {
        assertThat(HeaderParameters.parameter("foo; name=x", "name").or("<none>")).isEqualTo("x");
        assertThat(HeaderParameters.parameter("form-data; flag; name=x", "name").or("<none>")).isEqualTo("x");
    }
}
