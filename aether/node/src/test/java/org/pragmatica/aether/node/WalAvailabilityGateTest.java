// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #634 item 2: an unwritable WAL dir must be a BOOT ERROR, not one WARN followed by fsync-free acks —
/// that silently converts "durable entity" into "in-memory entity". The degrade survives ONLY behind the
/// explicit non-durable opt-in (Forge read-only mounts, dev profiles).
class WalAvailabilityGateTest {
    private static final Path WAL_DIR = Path.of("/somewhere/wal");
    private static final Result<Unit> WRITABLE = Result.unitResult();
    private static final Result<Unit> UNWRITABLE = Causes.cause("read-only file system").result();

    @Test
    void writableDir_walEnabled_regardlessOfOptIn() {
        assertThat(AetherNode.decideWalAvailability(WAL_DIR, WRITABLE, false).unwrap())
            .isEqualTo(Option.some(WAL_DIR));
        assertThat(AetherNode.decideWalAvailability(WAL_DIR, WRITABLE, true).unwrap())
            .isEqualTo(Option.some(WAL_DIR));
    }

    @Test
    void unwritableDir_withoutOptIn_refusesBoot_namingTheEscapeHatch() {
        var outcome = AetherNode.decideWalAvailability(WAL_DIR, UNWRITABLE, false);

        assertThat(outcome.isFailure()).isTrue();

        String refusal = outcome.fold(cause -> cause.message(), _ -> "unexpectedly succeeded");

        assertThat(refusal)
            .as("the operator must learn both WHY boot refused and HOW to opt in deliberately")
            .contains("NO fsync")
            .contains("aether.allowNonDurableStreams");
    }

    @Test
    void unwritableDir_withOptIn_degradesToWallessExactlyAsBefore() {
        assertThat(AetherNode.decideWalAvailability(WAL_DIR, UNWRITABLE, true).unwrap())
            .as("the explicit opt-in keeps the previous degrade so Forge/dev keep working")
            .isEqualTo(Option.none());
    }
}
