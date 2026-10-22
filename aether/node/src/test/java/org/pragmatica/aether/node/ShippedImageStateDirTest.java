// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1473: the volume sentinel lives in the node state directory, and the image has to provision it. The node
/// runs as the unprivileged `aether` user, so a state directory the Dockerfile does not create and chown is
/// one the sentinel write cannot use -- the guard would silently do nothing. This pins the image layout to the
/// default the code writes to.
class ShippedImageStateDirTest {
    private static final Path DOCKERFILE = Path.of("..", "docker", "aether-node", "Dockerfile");

    @Test
    void dockerfile_createsTheDefaultStateDirOwnedByTheNodeUser() throws IOException {
        var dockerfile = Files.readString(DOCKERFILE);

        var defaultDir = StorageFactory.DEFAULT_STATE_DIR;

        assertThat(dockerfile).contains("mkdir -p " + defaultDir + " && chown aether:aether " + defaultDir);
        assertThat(dockerfile.indexOf(defaultDir)).as("provisioned before USER aether drops root")
                                                  .isLessThan(dockerfile.indexOf("USER aether"));
    }
}
