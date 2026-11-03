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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/// The first [Error] escape of a JVM can be a deep `StackOverflowError`. The hook's class must not be first
/// initialised there: a class whose static initialiser fails stays unusable for the JVM's life, and every later
/// `Error` escape would then throw out of `escaped()`, leave the batch of dependents unresolved and make
/// `installFatalEscapeHandler` throw. Only a fresh JVM has a cold hook class, so each case is a child JVM
/// ([FatalEscapeFreshJvmChild]); the in-process suite cannot reach this state.
@Timeout(300)
class FatalEscapeFreshJvmTest {
    private static final List<String> STACK_SIZES = List.of("1m", "4m");
    private static final int REPETITIONS = 4;

    private record Run(String label, Process process, Path result) {}

    @Test
    void firstErrorEscapeAtTheStackLimit_leavesTheHookUsable() throws Exception {
        var runs = startAll();
        var problems = new ArrayList<String>();

        for (var run : runs) {
            problems.addAll(problemsOf(run));
        }

        assertThat(problems).as("fresh-JVM children whose hook was unusable after a deep first escape").isEmpty();
    }

    private static List<Run> startAll() throws IOException {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var classpath = System.getProperty("java.class.path");
        var runs = new ArrayList<Run>();

        for (var stack : STACK_SIZES) {
            for (int repetition = 0; repetition < REPETITIONS; repetition++) {
                var result = Files.createTempFile("fatal-escape-fresh-jvm", ".txt");
                var process = new ProcessBuilder(java,
                                                 "-Xss" + stack,
                                                 "-cp",
                                                 classpath,
                                                 FatalEscapeFreshJvmChild.class.getName(),
                                                 result.toString()).redirectErrorStream(true)
                                                                  .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                                                  .start();

                runs.add(new Run("-Xss" + stack + " #" + repetition, process, result));
            }
        }

        return runs;
    }

    private static List<String> problemsOf(Run run) throws Exception {
        if (!run.process().waitFor(120, TimeUnit.SECONDS)) {
            run.process().destroyForcibly();

            return List.of(run.label() + ": timed out");
        }

        if (run.process().exitValue() != 0) {
            return List.of(run.label() + ": exit " + run.process().exitValue());
        }

        return Files.readAllLines(run.result()).stream().map(line -> run.label() + ": " + line).toList();
    }
}
