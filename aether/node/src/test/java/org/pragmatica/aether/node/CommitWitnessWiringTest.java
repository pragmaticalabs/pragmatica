// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1956: a rebuild's rewind record counts as committed once the `CommitWitness` sees its accepted-put notification. The
/// witness is only fed if `AetherNode` routes every committed `ValuePut` to it AND hands the same instance to the replay
/// cursor's collaborators. Remove the route and nothing fails loudly: the cursor falls back to the committed read-back
/// alone, which the restarted consumer's first checkpoint at the same epoch overtakes, so a lost rewind is reported as
/// success. `DurableProjectionRebuildTest` drives the witness directly and cannot reach this assembly.
///
/// [unverified: wiring pinned by text only] The matches are source-text matches on `AetherNode`, like
/// `StreamFailoverAnnouncerWiringTest`; the witness's behaviour is pinned by `DurableProjectionRebuildTest`.
class CommitWitnessWiringTest {
    @Test
    void everyCommittedPutReachesTheWitness_andTheSameWitnessReachesTheReplayCursor() {
        var code = assemblyCode();

        assertThat(code).containsOnlyOnce("varrewindWitness=CommitWitness.commitWitness();");
        assertThat(code).containsOnlyOnce("allEntries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class,rewindWitness::onPut));");
        assertThat(code).as("the witness the router feeds is the one the projection support's cursor consults")
                        .containsOnlyOnce("epochSources.incarnation()::current,rewindWitness);");
    }

    private static String assemblyCode() {
        return AssemblySource.stripped();
    }
}
