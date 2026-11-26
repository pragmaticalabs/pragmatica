// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.http.routing;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #3723: a multipart failure wraps the decoder's exception as its origin (server log only) and its client text is its fixed
/// message. The origin here is a caught throwable, whose client text is "Internal error": a `Cause.Wrapped` default taking
/// over would show that instead of the declared wording.
class MultipartErrorClientMessageTest {
    private static final String SECRET = "SENTINEL-multipart-3723-secret";

    private static Cause thrown() {
        return Causes.fromThrowable(new IllegalStateException(SECRET));
    }

    @Test
    void parseFailed_overAThrowable_answersItsFixedMessage() {
        var error = MultipartError.ParseFailed.FACTORY.apply(thrown());

        assertThat(HttpError.clientMessage(error)).isEqualTo("Multipart body could not be parsed");
        assertThat(HttpError.clientMessage(MultipartError.asHttpError(error))).contains("Multipart body could not be parsed")
                                                                              .doesNotContain(SECRET);
    }

    @Test
    void readFailed_overAThrowable_answersItsFixedMessage() {
        var error = MultipartError.ReadFailed.FACTORY.apply(thrown());

        assertThat(HttpError.clientMessage(error)).isEqualTo("Multipart body could not be read");
        assertThat(HttpError.clientMessage(MultipartError.asHttpError(error))).contains("Multipart body could not be read")
                                                                              .doesNotContain(SECRET);
    }
}
