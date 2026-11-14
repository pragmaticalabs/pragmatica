// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.lang.Option;

/// F5b (pragmatica #2765; phase-3 design 8.2 D-10): every #1573 scenario of [AutoRollbackOnAllInstancesFailedTest] on an
/// FSM2 cluster. Before F5b the leader-side detector read its ACTIVE instances from `NodeArtifactKey`, which FSM2 never
/// writes, so on FSM2 auto-rollback was silently off: the rollback scenarios time out without a committed rollback.
class AutoRollbackOnAllInstancesFailedFsm2Test extends AutoRollbackOnAllInstancesFailedTest {
    @Override
    Option<DeploymentEngine> engineOverride() {
        return Option.some(DeploymentEngine.FSM2);
    }
}
