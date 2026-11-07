// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// The leading token and the named parameters of a Content-Type or Content-Disposition value.
class HeaderParametersTest {
    /// Kills a media type compared with its case or surrounding spaces.
    @Test
    void mediaType_isLowerCasedAndStripped_withOrWithoutParameters() {
        assertThat(HeaderParameters.mediaType("  Multipart/Form-Data ; boundary=x")).isEqualTo("multipart/form-data");
        assertThat(HeaderParameters.mediaType("Form-Data")).isEqualTo("form-data");
    }

    /// Kills a case-sensitive parameter name.
    @Test
    void parameter_nameMatchesWhateverItsCase() {
        assertThat(HeaderParameters.parameter("multipart/form-data; BOUNDARY=abc", "boundary")).isEqualTo(Option.some("abc"));
    }

    /// Kills a token value read with its spaces, or past the next `;`.
    @Test
    void parameter_tokenValue_isStrippedAndEndsAtTheNextSemicolon() {
        assertThat(HeaderParameters.parameter("form-data; name = field1 ; filename=x", "name")).isEqualTo(Option.some("field1"));
        assertThat(HeaderParameters.parameter("form-data; name=field1;filename=x", "filename")).isEqualTo(Option.some("x"));
    }

    /// Kills a quoted value cut at a `;` it contains, or read with its escapes.
    @Test
    void parameter_quotedValue_keepsSemicolonsAndUnescapes() {
        assertThat(HeaderParameters.parameter("form-data; filename=\"a;b \\\"c\\\\.txt\"; name=f", "filename")).isEqualTo(Option.some("a;b \"c\\.txt"));
        assertThat(HeaderParameters.parameter("form-data; filename=\"a;name=evil\"; name=\"real\"", "name")).as("a parameter inside a quoted value is not one")
                                                                                                         .isEqualTo(Option.some("real"));
    }

    /// Kills a match on a name that merely ends with the one asked for (`filename` is not `name`).
    @Test
    void parameter_doesNotMatchALongerName() {
        assertThat(HeaderParameters.parameter("form-data; filename=\"a.txt\"; name=\"f\"", "name")).isEqualTo(Option.some("f"));
        assertThat(HeaderParameters.parameter("form-data; filename=\"a.txt\"", "name")).isEqualTo(Option.none());
    }

    /// Kills a reader that stops at a parameter without `=`, or invents a value for an absent one.
    @Test
    void parameter_skipsMalformedParameters_andIsEmptyWhenAbsent() {
        assertThat(HeaderParameters.parameter("multipart/form-data; junk; boundary=b1", "boundary")).isEqualTo(Option.some("b1"));
        assertThat(HeaderParameters.parameter("multipart/form-data", "boundary")).isEqualTo(Option.none());
        assertThat(HeaderParameters.parameter("multipart/form-data;", "boundary")).isEqualTo(Option.none());
        assertThat(HeaderParameters.parameter("multipart/form-data; boundary=", "boundary")).isEqualTo(Option.some(""));
        assertThat(HeaderParameters.parameter("form-data; filename=\"unterminated", "filename")).isEqualTo(Option.some("unterminated"));
    }
}
