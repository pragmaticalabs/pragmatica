// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactContentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactVersionsKey;
import org.pragmatica.serialization.SystemTags;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #420 T-P, the flag-day pairing invariant (ruling `925b10b70a`; know `fa3e253382`): `ArtifactContentValue`'s wire
/// tag moves to the assigned block 2990..2999 (PR-1a's half) in the same commit that takes both artifact keys out
/// of the backup (PR-6's half). A head holding exactly one half is red here. Both halves land in the one squashed
/// PR-1u, so this file belongs to NEITHER half: a PR that edits or deletes it defeats the invariant it pins.
///
/// Green at rc6 (`moved` and `out` both false) and at PR-1u's head (both true).
class FlagDayPairingTest {
    private static final int ASSIGNED_BLOCK_FROM = 2990;
    private static final int ASSIGNED_BLOCK_TO = 2999;
    private static final ArtifactBase BASE = ArtifactBase.artifactBase("org.example:orders-slice").unwrap();

    private static final ArtifactContentKey CONTENT_KEY = ArtifactContentKey.artifactContentKey(BASE,
                                                                                                "1.0.0",
                                                                                                "orders-slice-1.0.0.jar");

    private static final ArtifactVersionsKey VERSIONS_KEY = ArtifactVersionsKey.artifactVersionsKey(BASE);

    @Test
    void recordTagAndBackupKeysMoveTogether() {
        // The canonical, dotted name is the key tagFor expects: a rename reads as "not pinned", never as a half.
        var tag = SystemTags.tagFor(AetherValue.ArtifactContentValue.class.getCanonicalName());

        assertThat(tag).as("T-P: ArtifactContentValue is not pinned in SystemTags (renamed?); re-key it deliberately")
                  .isNotEqualTo(SystemTags.NOT_PINNED);
        var moved = tag >= ASSIGNED_BLOCK_FROM && tag <= ASSIGNED_BLOCK_TO;
        var out = !BackupEntryCodec.isBackedUp(CONTENT_KEY);

        assertThat(BackupEntryCodec.isBackedUp(VERSIONS_KEY)).as("T-P (D-11): ArtifactContentKey and ArtifactVersionsKey leave the backup together, in one commit")
                  .isEqualTo(BackupEntryCodec.isBackedUp(CONTENT_KEY));
        assertThat(out).as(halfMessage(moved, tag)).isEqualTo(moved);
    }

    private static String halfMessage(boolean moved, int tag) {
        return moved
               ? "T-P: PR-1a without PR-6: the tag moved to " + tag + " but the artifact keys are still backed up"
               : "T-P: PR-6 without PR-1a: the artifact keys left the backup but the tag is still " + tag;
    }
}
