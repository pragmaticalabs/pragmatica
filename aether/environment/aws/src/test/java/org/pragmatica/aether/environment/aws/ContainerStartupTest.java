// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.environment.aws;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Probes for [ContainerStartup] (#2927): all attempts failing surfaces every attempt's container
/// log; a single failure followed by success passes. Uses the LocalStack image already pulled by
/// the contract suite, with the entrypoint overridden; skipped when no Docker daemon is present.
class ContainerStartupTest {
    private static final DockerImageName IMAGE = DockerImageName.parse("localstack/localstack:3.8");

    @BeforeEach
    void requireDocker() {
        assumeTrue(dockerAvailable(), "requires a Docker daemon");
    }

    @Test
    @Timeout(180)
    void startWithRetry_allAttemptsFail_reportsEveryAttemptWithContainerLog() {
        var created = new AtomicInteger();

        assertThatThrownBy(() -> ContainerStartup.startWithRetry(() -> failing(created, "boom-attempt-"), 3))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("3 attempt(s)")
            .hasMessageContaining("attempt 1/3")
            .hasMessageContaining("attempt 3/3")
            .hasMessageContaining("boom-attempt-1")
            .hasMessageContaining("boom-attempt-3")
            .hasMessageContaining("code 126");
        assertThat(created.get()).isEqualTo(3);
    }

    @Test
    @Timeout(180)
    void startWithRetry_firstAttemptFailsThenSucceeds_returnsRunningContainer() {
        var created = new AtomicInteger();

        var container = ContainerStartup.startWithRetry(() -> created.incrementAndGet() == 1
                                                              ? failing(created, "boom-once-")
                                                              : healthy(),
                                                        3);
        try {
            assertThat(container.isRunning()).isTrue();
            assertThat(created.get()).isEqualTo(2);
        } finally {
            container.stop();
        }
    }

    private static GenericContainer<?> failing(AtomicInteger created, String tag) {
        return configure(new Fixed(IMAGE), "echo " + tag + created.get() + "; exit 126");
    }

    private static GenericContainer<?> healthy() {
        return configure(new Fixed(IMAGE), "echo Ready.; sleep 120");
    }

    private static GenericContainer<?> configure(Fixed container, String script) {
        return container.withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh", "-c", script))
                        .waitingFor(Wait.forLogMessage(".*Ready\\..*\\n", 1)
                                        .withStartupTimeout(Duration.ofSeconds(20)));
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final class Fixed extends GenericContainer<Fixed> {
        Fixed(DockerImageName image) {
            super(image);
        }
    }
}
