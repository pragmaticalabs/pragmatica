// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1400: the assembly routes every accepted cursor-checkpoint `ValuePut` to the projection hook, so the old assignee
/// of a moved partition leaves REBUILDING on the group's committed cursor. The hook's behaviour is pinned in
/// `ProjectionAwareCursorStoreCheckpointPutTest` and, on a real applier and store, in `DurableProjectionRebuildTest`
/// (whose fixture adds the same route by hand); neither can see the assembly's own route list, so the one line is pinned
/// here by source, like the other assembly seams (see [AssemblySource]). Red when the route is deleted or its consumer
/// replaced; a second copy fails `containsOnlyOnce` too.
class ProjectionCheckpointRouteWiringTest {
    @Test
    void theAssembly_routesEveryPutToTheProjectionHooksCheckpointReport() {
        assertThat(NodeDepartureWiringTest.assemblyCode()).containsOnlyOnce("allEntries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class,streamClusterCursorStore::onCheckpointPut));");
    }
}
