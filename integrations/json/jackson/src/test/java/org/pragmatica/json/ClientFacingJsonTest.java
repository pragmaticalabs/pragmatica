// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.json;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #3723 / #3794: the general Result codec writes a failure's `message()` (the CLI, cloud clients and state files read it
/// back), so a mapper that writes an HTTP client body is derived with `clientFacing()`, and a failure written through it
/// carries `clientMessage()` only. A caught throwable's text (its stack trace) is the sentinel: it must not appear.
class ClientFacingJsonTest {
    private static final String SENTINEL = "SENTINEL-3794-json";

    record Holder(Result<String> outcome) {}

    record CauseHolder(Cause failure) {}

    record Wrapper(Cause origin, String message) implements Cause {
        @Override
        public String clientMessage() {
            return "wrapper over " + origin.clientMessage();
        }
    }

    private static Cause aThrowable() {
        return Causes.fromThrowable(new IllegalStateException(SENTINEL));
    }

    private static String write(JsonMapper mapper, Object value) {
        return mapper.writeAsString(value).fold(cause -> {
            throw new AssertionError(cause.message());
        }, json -> json);
    }

    private static void assertNoTrace(String json) {
        assertThat(json).doesNotContain(SENTINEL)
                        .doesNotContain("IllegalStateException")
                        .doesNotContain("\\tat ")
                        .doesNotContain("at java");
    }

    @Test
    void generalMapper_writesTheFailuresMessage_soTheRoundTripKeepsIt() {
        var json = write(JsonMapper.defaultJsonMapper(), aThrowable().<String> result());

        assertThat(json).contains(SENTINEL);
    }

    @Test
    void clientFacing_failedResultOverAThrowable_hidesIt() {
        var json = write(JsonMapper.defaultJsonMapper().clientFacing(), aThrowable().<String> result());

        assertNoTrace(json);
        assertThat(json).contains("\"success\":false").contains("Internal error");
    }

    @Test
    void clientFacing_failedResultOverAWrapper_writesTheWrappersClientText() {
        var wrapper = new Wrapper(aThrowable(), "wrapper " + aThrowable().message());
        var json = write(JsonMapper.defaultJsonMapper().clientFacing(), wrapper.<String> result());

        assertNoTrace(json);
        assertThat(json).contains("wrapper over Internal error");
    }

    @Test
    void clientFacing_resultInsideARecord_keepsTheClientTextThroughTheContextualSerializer() {
        var json = write(JsonMapper.valueObjectJsonMapper().clientFacing(), new Holder(aThrowable().result()));

        assertNoTrace(json);
        assertThat(json).contains("Internal error");
    }

    @Test
    void clientFacing_causeComponent_writesItsClientTextNotItsFields() {
        var json = write(JsonMapper.defaultJsonMapper().clientFacing(), new CauseHolder(aThrowable()));

        assertNoTrace(json);
        assertThat(json).contains("Internal error");
    }

    @Test
    void clientFacing_plainFailureAndSuccess_areWrittenAsTheGeneralMapperWritesThem() {
        var general = JsonMapper.defaultJsonMapper();
        var client = general.clientFacing();

        assertThat(write(client, Causes.cause("plain-text").<String> result())).isEqualTo(write(general, Causes.cause("plain-text").<String> result()));
        assertThat(write(client, Result.success("ok"))).isEqualTo(write(general, Result.success("ok")));
    }

    @Test
    void clientFacing_ofAClientFacingMapper_isStillClientFacing() {
        var json = write(JsonMapper.defaultJsonMapper().clientFacing().clientFacing(), aThrowable().<String> result());

        assertNoTrace(json);
    }

    @Test
    void clientFacing_leavesTheGeneralMapperItWasDerivedFromUnchanged() {
        var general = JsonMapper.defaultJsonMapper();

        general.clientFacing();

        assertThat(write(general, aThrowable().<String> result())).contains(SENTINEL);
    }
}
