// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// #828 — a runner that cannot pipe stdin must REFUSE a command that needs it. If the default reported success, a provider handed such
/// a runner would think the credential files were copied into the container and start a node without them.
class DockerCommandRunnerDefaultTest {
    @Test
    void aRunnerWithoutStdinSupport_refusesTheStdinForm_andStillRunsThePlainForm() {
        DockerCommandRunner plainOnly = command -> Promise.success("ran " + command.getFirst());

        var refused = plainOnly.execute(List.of("docker", "cp", "-", "x:/tmp"), new byte[] {1, 2, 3}).await();

        assertThat(refused.isFailure()).as("the default must refuse, not pretend the bytes were delivered").isTrue();
        assertThat(plainOnly.execute(List.of("docker", "ps")).await().isSuccess()).as("control: the plain form works").isTrue();
    }
}
