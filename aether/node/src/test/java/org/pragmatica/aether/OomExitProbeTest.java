// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #966 — proves what `-XX:+ExitOnOutOfMemoryError` guarantees, on the JVM the tests run on, by
/// running a child that exhausts its heap inside a `catch (Throwable)` loop (`OomExitProbeMain`).
///
/// The control arm is the load-bearing half. Without the flag the child catches the OOM and stays
/// alive — the ticket's shape: a process whose allocators are dead but whose threads keep running.
/// It is what proves the exit in the other arm comes from the flag and not from the OOM itself; a
/// probe that only showed exit code 3 could pass on a child that died of an uncaught error.
///
/// Guarantee pinned: on the first heap allocation HotSpot cannot satisfy, the VM `_exit`s with code
/// 3 from inside `report_java_out_of_memory` — BEFORE the error is delivered to Java, so the
/// `catch` never runs (asserted: the `CAUGHT` line is absent). Not covered here, and not by the flag:
/// an `OutOfMemoryError` thrown from Java code, direct-memory exhaustion, and GC thrash that never
/// throws.
class OomExitProbeTest {
    /// HotSpot's `report_java_out_of_memory` exit status under `ExitOnOutOfMemoryError`.
    private static final int EXIT_ON_OOM_STATUS = 3;
    private static final String VM_TERMINATION_LINE = "Terminating due to java.lang.OutOfMemoryError";
    private static final long EXIT_DEADLINE_SECONDS = 20;
    private static final long CONTROL_OBSERVATION_SECONDS = 3;

    @Test
    void heapExhaustion_withExitOnOom_exitsWithStatus3_beforeAnyCatchRuns(@TempDir Path dir) throws Exception {
        var out = dir.resolve("with-flag.out");
        var child = launch(out, List.of("-XX:+ExitOnOutOfMemoryError"));

        var exited = child.waitFor(EXIT_DEADLINE_SECONDS, TimeUnit.SECONDS);
        var output = drain(child, out);

        assertThat(exited).as("child must exit within %ds; output:\n%s", EXIT_DEADLINE_SECONDS, output).isTrue();
        assertThat(output).as("control: the child reached its allocation loop").contains(OomExitProbeMain.STARTED);
        assertThat(child.exitValue()).as("HotSpot _exit(3) from report_java_out_of_memory; output:\n%s", output)
                                     .isEqualTo(EXIT_ON_OOM_STATUS);
        assertThat(output).as("the VM's own termination line names the cause").contains(VM_TERMINATION_LINE);
        assertThat(output).as("the exit happens BEFORE the error reaches Java: no catch may run")
                          .doesNotContain(OomExitProbeMain.CAUGHT);
    }

    /// Same child, same heap, no flag: the OOM is caught and the process stays alive — the #966
    /// zombie. Killed by the test afterwards, which is the point: nothing else would have.
    @Test
    void heapExhaustion_withoutTheFlag_isCaughtAndTheProcessStaysAlive(@TempDir Path dir) throws Exception {
        var out = dir.resolve("control.out");
        var child = launch(out, List.of());

        var exited = child.waitFor(CONTROL_OBSERVATION_SECONDS, TimeUnit.SECONDS);
        var output = drain(child, out);

        assertThat(output).as("control: the child reached its allocation loop").contains(OomExitProbeMain.STARTED);
        assertThat(output).as("the heap WAS exhausted and the error WAS caught; output:\n%s", output)
                          .contains(OomExitProbeMain.CAUGHT);
        assertThat(exited).as("without the flag a heap-exhausted JVM keeps running (#966); output:\n%s", output)
                          .isFalse();
        assertThat(output).doesNotContain(VM_TERMINATION_LINE);
    }

    private static Process launch(Path out, List<String> extraJvmFlags) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = new ArrayList<>(List.of(java, "-Xmx32m", "-XX:+UseSerialGC"));

        command.addAll(extraJvmFlags);
        command.addAll(List.of("-cp", testClassesDir().toString(), OomExitProbeMain.class.getName()));

        return new ProcessBuilder(command).redirectErrorStream(true)
                                          .redirectOutput(out.toFile())
                                          .start();
    }

    /// Reads whatever the child wrote; for the control arm, kills it first so the file is complete
    /// and nothing outlives the test.
    private static String drain(Process child, Path out) throws Exception {
        if (child.isAlive()) {
            child.destroyForcibly().waitFor(EXIT_DEADLINE_SECONDS, TimeUnit.SECONDS);
        }

        return Files.readString(out);
    }

    private static Path testClassesDir() throws Exception {
        return Path.of(OomExitProbeMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
