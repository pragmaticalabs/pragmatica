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
package org.pragmatica.lang;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Unit.unit;

/// Child JVM of [FatalEscapeFreshJvmTest]: the first [Error] escape of the JVM happens near stack exhaustion,
/// then, on a shallow stack, the hook must still be usable. Writes one line per failed check to the file named
/// by `args[0]`; an empty file means every check passed.
public final class FatalEscapeFreshJvmChild {
    private static final int AWAIT_SECONDS = 2;

    private FatalEscapeFreshJvmChild() {}

    public static void main(String[] args) throws Exception {
        var failures = new ArrayList<String>();

        // Warm escaped() with a RuntimeException, which never reaches the hook, so that the JDK string
        // concatenation, logging and cause paths are linked and only the hook's own class is still cold.
        Promise.success(1).map(FatalEscapeFreshJvmChild::boomRuntime).await();
        recurse(0);
        checkShallowMap(failures);
        checkBatch(failures);
        checkInstall(failures);
        Files.write(Path.of(args[0]), failures);
        System.exit(0);
    }

    /// Unwinds a stack overflow and attempts a failing `map` in every catch on the way up, so that the first
    /// [Error] escape of the JVM is raised with almost no stack left.
    private static void recurse(int depth) {
        try {
            recurse(depth + 1);
        } catch (Throwable ignored) {
            attemptDeepEscape();
        }
    }

    private static void attemptDeepEscape() {
        Promise.success(1).map(FatalEscapeFreshJvmChild::boomError);
    }

    private static void checkShallowMap(List<String> failures) {
        try {
            if (!Promise.success(1).map(FatalEscapeFreshJvmChild::boomError).await().isFailure()) {
                failures.add("shallow map did not fail");
            }
        } catch (Throwable thrown) {
            failures.add("shallow map threw " + thrown);
        }
    }

    private static void checkBatch(List<String> failures) {
        try {
            var source = Promise.<Integer>promise();
            var failing = source.map(FatalEscapeFreshJvmChild::boomError);
            var sibling = source.map(value -> value + 1);

            source.succeed(1);
            failing.await(TimeSpan.timeSpan(AWAIT_SECONDS).seconds());
            sibling.await(TimeSpan.timeSpan(AWAIT_SECONDS).seconds());

            if (!failing.isResolved() || !sibling.isResolved()) {
                failures.add("batch left a dependent unresolved: failing=" + failing.isResolved() + " sibling=" + sibling.isResolved());
            }
        } catch (Throwable thrown) {
            failures.add("batch threw " + thrown);
        }
    }

    private static void checkInstall(List<String> failures) {
        try {
            var installed = Promise.installFatalEscapeHandler(_ -> unit());

            if (installed.isFailure()) {
                failures.add("install refused: " + installed);
            }
        } catch (Throwable thrown) {
            failures.add("install threw " + thrown);
        }
    }

    private static Integer boomRuntime(Integer ignored) {
        throw new IllegalStateException("warm-up");
    }

    private static Integer boomError(Integer ignored) {
        throw new AssertionError("deep");
    }
}
