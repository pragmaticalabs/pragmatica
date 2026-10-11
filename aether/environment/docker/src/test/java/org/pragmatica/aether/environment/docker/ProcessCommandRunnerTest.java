// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #828 — the real pipe the provider's secret travels on: `ProcessCommandRunner.execute(command, stdin)` runs a real process and
/// writes the bytes to its stdin. `cat` echoes them back, so arrival is observed; the command list is asserted to hold no part of
/// them, which is the property the pipe exists for.
class ProcessCommandRunnerTest {
    private static final String SENTINEL = "sentinel-secret-7f3a9c";

    private static String text(org.pragmatica.lang.Result<String> result) {
        return result.fold(cause -> cause.message(), output -> output);
    }

    @Test
    void stdinBytesReachTheProcess_andAreNotInItsArgv() {
        var command = List.of("cat");

        var echoed = ProcessCommandRunner.processCommandRunner().execute(command, SENTINEL.getBytes(StandardCharsets.UTF_8)).await();

        assertThat(echoed.isSuccess()).isTrue();
        assertThat(text(echoed)).as("positive control: the bytes arrived").isEqualTo(SENTINEL);
        assertThat(command).as("and travelled on no argv").noneMatch(arg -> arg.contains(SENTINEL));
    }

    @Test
    void aBinaryTarSizedPayload_arrivesIntact() {
        var payload = new byte[4096];

        java.util.Arrays.fill(payload, 0, 100, (byte) 'x');
        var echoed = ProcessCommandRunner.processCommandRunner().execute(List.of("wc", "-c"), payload).await();

        assertThat(text(echoed).trim()).isEqualTo("4096");
    }

    @Test
    void aFailingProcess_failsTheCall() {
        assertThat(ProcessCommandRunner.processCommandRunner().execute(List.of("false"), new byte[0]).await().isFailure()).isTrue();
    }
}
