/*
 *  Copyright (c) 2025-2026 Sergiy Yevtushenko.
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
 *
 */

package org.pragmatica.testing;

import java.lang.reflect.Proxy;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pragmatica.lang.FatalEscape;
import org.pragmatica.lang.FatalEscapeError;
import org.pragmatica.lang.Promise;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

@Timeout(30)
class FatalEscapeRecorderTest {

    @Nested
    class Registered {
        @RegisterExtension
        final FatalEscapeRecorder escapes = FatalEscapeRecorder.fatalEscapeRecorder();

        @Test
        void errorEscapingAContinuation_isRecorded() {
            var error = new AssertionError("escaped in a test");
            var mapped = Promise.success(1).map(_ -> raise(error));

            assertThat(mapped.await().isFailure()).isTrue();
            assertThat(escapes.recorded()).extracting(FatalEscape::error).containsExactly(error);
        }

        @Test
        void slotIsHeldDuringTheTest() {
            var refused = Promise.installFatalEscapeHandler(_ -> unit());

            assertThat(refused.isFailure()).isTrue();
            refused.onFailure(cause -> assertThat(cause).isSameAs(FatalEscapeError.ALREADY_INSTALLED));
        }
    }

    @Nested
    class Lifecycle {
        @Test
        void afterEach_freesTheSlot() {
            var recorder = FatalEscapeRecorder.fatalEscapeRecorder();

            recorder.beforeEach(context("first"));
            assertThat(Promise.installFatalEscapeHandler(_ -> unit()).isFailure()).as("held after beforeEach").isTrue();
            recorder.afterEach(context("first"));

            var after = Promise.installFatalEscapeHandler(_ -> unit());

            assertThat(after.isSuccess()).as("free after afterEach").isTrue();
            after.onSuccess(registration -> assertThat(registration.uninstall().isSuccess()).isTrue());
        }

        @Test
        void beforeEach_startsEmpty_whenTheExtensionInstanceIsReused() {
            var recorder = FatalEscapeRecorder.fatalEscapeRecorder();

            recorder.beforeEach(context("first"));
            assertThat(Promise.success(1).map(_ -> raise(new AssertionError("first"))).await().isFailure()).isTrue();
            assertThat(recorder.recorded()).hasSize(1);
            recorder.afterEach(context("first"));

            recorder.beforeEach(context("second"));
            assertThat(recorder.recorded()).isEmpty();
            recorder.afterEach(context("second"));
        }

        @Test
        void afterEach_warnsNamingTheTest_whenAnEscapeReachedNoHandler() {
            var recorder = FatalEscapeRecorder.fatalEscapeRecorder();

            recorder.beforeEach(context("closes the pre-install buffer"));
            recorder.afterEach(context("closes the pre-install buffer"));

            try (var log = LogCapture.capture(FatalEscapeRecorder.class, Level.WARN, false)) {
                assertThat(Promise.success(1).map(_ -> raise(new AssertionError("unowned"))).await().isFailure()).isTrue();

                recorder.beforeEach(context("the next test"));
                recorder.afterEach(context("the next test"));

                assertThat(log.messages(Level.WARN)).anySatisfy(message -> assertThat(message).contains("observed after the next test"));
            }
        }

        @Test
        void escapeDeliveredAfterTeardown_warnsNamingErrorAndTest() {
            var recorder = FatalEscapeRecorder.fatalEscapeRecorder();
            var late = new AssertionError("late escape");
            var escape = new FatalEscape("onResult", late, "com.example.Origin.method(Origin.java:7)", "worker-1");

            recorder.beforeEach(context("the finished test"));
            recorder.afterEach(context("the finished test"));

            try (var log = LogCapture.capture(FatalEscapeRecorder.class, Level.WARN, false)) {
                recorder.record(escape);

                assertThat(log.messages(Level.WARN)).anySatisfy(message -> assertThat(message).contains("late escape")
                                                                                              .contains("onResult")
                                                                                              .contains("teardown of the finished test"));
                assertThat(recorder.recorded()).isEmpty();
            }
        }

        @Test
        void escapeDeliveredWhileInstalled_isRecordedWithoutWarning() {
            var recorder = FatalEscapeRecorder.fatalEscapeRecorder();
            var escape = new FatalEscape("onResult", new AssertionError("in time"), "origin", "worker-1");

            recorder.beforeEach(context("the running test"));

            try (var log = LogCapture.capture(FatalEscapeRecorder.class, Level.WARN, false)) {
                recorder.record(escape);

                assertThat(recorder.recorded()).containsExactly(escape);
                assertThat(log.messages(Level.WARN)).noneMatch(message -> message.contains("delivered after the teardown"));
            } finally {
                recorder.afterEach(context("the running test"));
            }
        }

        private static ExtensionContext context(String displayName) {
            return (ExtensionContext) Proxy.newProxyInstance(ExtensionContext.class.getClassLoader(),
                                                            new Class<?>[]{ExtensionContext.class},
                                                            (_, method, _) -> "getDisplayName".equals(method.getName())
                                                                              ? displayName
                                                                              : null);
        }
    }

    private static <T> T raise(Error error) {
        throw error;
    }
}
