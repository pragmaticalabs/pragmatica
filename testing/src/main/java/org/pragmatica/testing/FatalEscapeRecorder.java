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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.FatalEscape;
import org.pragmatica.lang.FatalEscapeHandler;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// JUnit 5 extension that installs a recording [FatalEscapeHandler] before each test and uninstalls it after, so
/// the test can assert on every [Error] that escaped a [Promise] continuation while it ran:
///
/// ```java
/// @RegisterExtension
/// final FatalEscapeRecorder escapes = FatalEscapeRecorder.fatalEscapeRecorder();
///
/// @Test
/// void noErrorEscapes() {
///     ...
///     assertThat(escapes.recorded()).isEmpty();
/// }
/// ```
///
/// In a test JVM this extension is meant to be the only installer: a test that is already holding the slot
/// (a second extension, a production entry point) fails to start. At each teardown the extension reads
/// [Promise#unownedFatalEscapes()] and logs a WARN naming the test that just ran when the count rose since the
/// previous teardown, which is how an escape between two tests, or in a test without the extension, shows up.
///
/// An escape can still reach the recorder after its test's teardown: an escape that read the slot just before
/// the uninstall may call the handler once after `uninstall` returned (the race stated on
/// [FatalEscapeHandler.Registration#uninstall()]), for example from the previous test's leftover executor work.
/// It is delivered, so it is not in the unowned count, and no test's [#recorded()] holds it. The extension
/// therefore logs a WARN naming the error, the continuation and the test that had finished.
///
/// The escapes recorded before the first install in the JVM are replayed into the first test that installs,
/// whichever test produced them. Recording is not ordered.
public final class FatalEscapeRecorder implements BeforeEachCallback, AfterEachCallback {
    private static final Logger log = LoggerFactory.getLogger(FatalEscapeRecorder.class);
    private static final AtomicLong UNOWNED_AT_LAST_TEARDOWN = new AtomicLong();

    private final List<FatalEscape> recorded = new CopyOnWriteArrayList<>();
    private volatile Option<FatalEscapeHandler.Registration> registration = Option.none();
    // True from just before the install (the replay of pre-install escapes runs inside it) until the uninstall.
    private volatile boolean active;
    private volatile String lastTest = "no test yet";

    private FatalEscapeRecorder() {}

    /// **[Factory]**
    /// Create the extension, for a `@RegisterExtension` field.
    ///
    /// @return a new extension instance
    public static FatalEscapeRecorder fatalEscapeRecorder() {
        return new FatalEscapeRecorder();
    }

    /// The escapes recorded during the current test, in no particular order.
    ///
    /// @return a snapshot of the recorded escapes
    public List<FatalEscape> recorded() {
        return List.copyOf(recorded);
    }

    @Override
    @Contract
    public void beforeEach(ExtensionContext context) {
        recorded.clear();
        lastTest = context.getDisplayName();
        active = true;
        registration = Option.some(Promise.installFatalEscapeHandler(this::record).expect("FatalEscapeRecorder cannot install for " + context.getDisplayName()));
    }

    @Override
    @Contract
    public void afterEach(ExtensionContext context) {
        var held = registration;

        registration = Option.none();
        active = false;
        held.map(FatalEscapeHandler.Registration::uninstall)
            .onPresent(uninstalled -> uninstalled.expect("FatalEscapeRecorder lost its slot during " + context.getDisplayName()));
        reportUnowned(context.getDisplayName());
    }

    /// Package-private so that a test can deliver after teardown, as the uninstall race does.
    Unit record(FatalEscape escape) {
        if (active) {
            recorded.add(escape);
        } else {
            warnLate(escape);
        }

        return unit();
    }

    private Unit warnLate(FatalEscape escape) {
        log.warn("{} escaping {} at {} on {} was delivered after the teardown of {}; no test records it",
                 escape.error(),
                 escape.continuation(),
                 escape.origin(),
                 escape.threadName(),
                 lastTest);

        return unit();
    }

    private static Unit reportUnowned(String test) {
        var now = Promise.unownedFatalEscapes();
        var before = UNOWNED_AT_LAST_TEARDOWN.getAndSet(now);

        if (now > before) {
            log.warn("{} fatal escape(s) reached no handler since the previous FatalEscapeRecorder teardown; observed after {}",
                     now - before,
                     test);
        }

        return unit();
    }
}
