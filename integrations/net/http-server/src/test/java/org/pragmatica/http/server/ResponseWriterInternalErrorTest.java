/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.http.server;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #3723: [ResponseWriter#internalError(Cause)] writes a 500 whose body is the client text of the cause. A cause built from
/// a caught throwable carries its stack trace in `message()`; the body carries the fixed text and none of the trace, alone, wrapped
/// in an [HttpError], or inside a composite.
class ResponseWriterInternalErrorTest {
    private static final String SENTINEL = "SENTINEL-3723-writer";

    private record Written(int status, String body) {}

    private static Cause thrown() {
        return Causes.fromThrowable(new IllegalStateException(SENTINEL));
    }

    private static Written write(Cause cause) {
        var status = new AtomicReference<HttpStatus>();
        var body = new AtomicReference<String>();

        new ResponseWriter() {
            @Override
            public void write(HttpStatus responseStatus, byte[] bytes, ContentType contentType) {
                status.set(responseStatus);
                body.set(new String(bytes, StandardCharsets.UTF_8));
            }

            @Override
            public ResponseWriter header(String name, String value) {
                return this;
            }
        }.internalError(cause);

        return new Written(status.get().code(), body.get());
    }

    private static void assertNoTrace(String body) {
        assertThat(body).doesNotContain(SENTINEL)
                        .doesNotContain("IllegalStateException")
                        .doesNotContain("at java")
                        .doesNotContain("\\tat ")
                        .doesNotContain("\tat ");
    }

    @Test
    void control_theThrowableMessageIsTheStackTrace() {
        assertThat(thrown().message()).contains(SENTINEL).contains("IllegalStateException").contains("\tat ");
    }

    @Test
    void internalError_ofAThrowableCause_writesTheFixedText() {
        var written = write(thrown());

        assertThat(written.status()).isEqualTo(500);
        assertThat(written.body()).isEqualTo("{\"error\":\"Internal error\"}");
        assertNoTrace(written.body());
    }

    @Test
    void internalError_ofAnHttpErrorOverAThrowable_hidesTheTrace() {
        var written = write(HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, thrown()));

        assertThat(written.body()).contains("Internal error");
        assertNoTrace(written.body());
    }

    @Test
    void internalError_ofACompositeWithAThrowableMember_keepsTheOthersAndHidesTheTrace() {
        var written = write(Causes.composite(Causes.cause("plain-member").result(), thrown().result()));

        assertThat(written.body()).contains("plain-member").contains("Internal error");
        assertNoTrace(written.body());
    }
}
