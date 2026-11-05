// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.r2dbc;

import org.testcontainers.DockerClientFactory;

/// The Docker precondition of a live-database test. By default a missing Docker daemon makes the caller SKIP
/// (`assumeTrue`), which keeps a developer laptop green. CI sets `-Daether.requireDocker=true`: there a missing
/// daemon is a FAILURE, because a self-skipping test that ran on no database proves nothing (the migration-fence
/// mutations "skip the fence" and "release to the pool early" can only go red where these tests really run).
final class DockerRequirement {
    static final String REQUIRE_PROPERTY = "aether.requireDocker";
    /// Test-the-guard switch: pretends no daemon is reachable, so the require/skip decision can be exercised on a
    /// machine that has Docker (`-Daether.hideDocker=true` with and without the require property).
    static final String HIDE_PROPERTY = "aether.hideDocker";

    private DockerRequirement() {}

    static boolean dockerAvailable() {
        var available = probe();

        if (!available && Boolean.getBoolean(REQUIRE_PROPERTY)) {
            throw new AssertionError("Docker is unavailable but -D" + REQUIRE_PROPERTY + "=true requires it: refusing to skip");
        }

        return available;
    }

    private static boolean probe() {
        if (Boolean.getBoolean(HIDE_PROPERTY)) {
            return false;
        }

        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
