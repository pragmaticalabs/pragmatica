// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.http.routing;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.JsonCodec;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #3723 / #3794 (F3): every HTTP response body is written by a [JsonCodecAdapter], so this is the one place the client
/// text of a failed `Result` or a `Cause` is enforced, whatever mapper the adapter wraps and however it is built. The
/// general Jackson codec (CLI, cloud clients, state files) keeps writing `message()`.
class JsonCodecAdapterClientFacingTest {
    private static final String SENTINEL = "SENTINEL-3794-adapter";

    record CauseHolder(Cause failure) {}

    private static Cause aThrowable() {
        return Causes.fromThrowable(new IllegalStateException(SENTINEL));
    }

    private static String body(JsonCodec codec, Object value) {
        return codec.serialize(value)
                    .fold(cause -> {
                              throw new AssertionError(cause.message());
                          },
                          bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    private static void assertClientText(String body) {
        assertThat(body).doesNotContain(SENTINEL)
                        .doesNotContain("IllegalStateException")
                        .doesNotContain("\\tat ")
                        .contains("Internal error");
    }

    @Test
    void forMapper_failedResultOverAThrowable_writesClientText() {
        assertClientText(body(JsonCodecAdapter.forMapper(JsonMapper.defaultJsonMapper()), aThrowable().<String> result()));
    }

    @Test
    void defaultCodec_failedResultOverAThrowable_writesClientText() {
        assertClientText(body(JsonCodecAdapter.defaultCodec(), aThrowable().<String> result()));
    }

    @Test
    void constructor_failedResultOverAThrowable_writesClientText() {
        assertClientText(body(new JsonCodecAdapter(JsonMapper.valueObjectJsonMapper()), aThrowable().<String> result()));
    }

    @Test
    void forMapper_causeComponent_writesClientText() {
        assertClientText(body(JsonCodecAdapter.forMapper(JsonMapper.valueObjectJsonMapper()), new CauseHolder(aThrowable())));
    }

    @Test
    void forMapper_successResult_isWrittenAsTheMapperWritesIt() {
        assertThat(body(JsonCodecAdapter.forMapper(JsonMapper.defaultJsonMapper()), Result.success("ok"))).contains("\"success\":true")
                                                                                                         .contains("ok");
    }
}
