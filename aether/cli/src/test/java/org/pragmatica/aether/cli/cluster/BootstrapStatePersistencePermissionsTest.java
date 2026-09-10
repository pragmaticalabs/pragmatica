// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.ClusterName;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// #980 — `bootstrap-state.json` CONTAINS THE CLUSTER SECRET, and under the ruling that derives the
/// bootstrap admin API key from that secret the file is admin-equivalent. It was written with a bare
/// `Files.writeString` at default permissions (`0644` under a typical umask) while everything derived
/// from it — the persisted `api-key`, the runtime TOML — was already locked to `0600` by
/// `SecureFiles`. The root secret was the one thing left world-readable.
///
/// Runs against the REAL `~/.aether/clusters/<name>` path rather than a temp dir: `AETHER_DIR` is an
/// interface constant resolved from `user.home` at class-load, so a temp-dir variant would either
/// depend on class-loading order within the module's JVM or test a different path from the one that
/// ships. A per-run random cluster name keeps it collision-free, and `@AfterEach` removes it.
class BootstrapStatePersistencePermissionsTest {
    private final ClusterName clusterName = uniqueClusterName();

    @AfterEach
    @SuppressWarnings("JBCT-EX-01")
    void cleanUp() throws Exception {
        var dir = BootstrapStatePersistence.AETHER_DIR.resolve(clusterName.value());

        Files.deleteIfExists(dir.resolve(BootstrapStatePersistence.STATE_FILE_NAME));
        Files.deleteIfExists(dir);
    }

    @Test
    void save_writesTheClusterSecretFileOwnerOnly() {
        var state = BootstrapState.initialState(clusterName, "config-hash", "2026-09-10T00:00:00Z")
                                  .withClusterSecret("top-secret");

        var result = BootstrapStatePersistence.save(state);

        assertThat(result.isSuccess()).isTrue();
        var file = BootstrapStatePersistence.statePath(clusterName);
        assertThat(Files.exists(file)).isTrue();

        assumeTrue(posix(), "POSIX permissions only assertable on a POSIX filesystem");
        assertThat(readPerms(file)).as("the file holding the cluster secret must be owner-only")
                  .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    /// The state is re-saved after every phase, so an operator whose file predates this fix gets its
    /// permissions repaired on the next `aether cluster bootstrap` rather than only on a fresh one.
    @Test
    @SuppressWarnings("JBCT-EX-01")
    void save_tightensAnExistingWorldReadableStateFile() throws Exception {
        assumeTrue(posix(), "POSIX permissions only assertable on a POSIX filesystem");
        var state = BootstrapState.initialState(clusterName, "config-hash", "2026-09-10T00:00:00Z")
                                  .withClusterSecret("top-secret");

        BootstrapStatePersistence.save(state);
        var file = BootstrapStatePersistence.statePath(clusterName);

        Files.setPosixFilePermissions(file,
                                      java.util.Set.of(PosixFilePermission.OWNER_READ,
                                                       PosixFilePermission.OWNER_WRITE,
                                                       PosixFilePermission.GROUP_READ,
                                                       PosixFilePermission.OTHERS_READ));

        BootstrapStatePersistence.save(state);

        assertThat(readPerms(file)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                                                              PosixFilePermission.OWNER_WRITE);
    }

    private static ClusterName uniqueClusterName() {
        var suffix = new byte[6];

        new SecureRandom().nextBytes(suffix);

        return ClusterName.clusterName("jbct-980-perm-" + HexFormat.of().formatHex(suffix)).unwrap();
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    @SuppressWarnings("JBCT-EX-01")
    private static java.util.Set<PosixFilePermission> readPerms(Path file) {
        try {
            return Files.getPosixFilePermissions(file);
        } catch (Exception e) {
            throw new AssertionError("unable to read POSIX permissions of " + file, e);
        }
    }
}
