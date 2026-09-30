// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.deployment.generation.BootstrapModule;
import org.pragmatica.aether.slice.kvstore.AetherKey.BackupRestoreKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreOutcome;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreValue;
import org.pragmatica.aether.update.AbTestManager;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/// #1533 — the ONE restore-commit hook re-drives every component that loaded cluster state once, before a
/// fresh cluster's restore landed: the bootstrap config seed, the rollout manager and the A/B test manager.
/// Only a TERMINAL decision re-drives; the IN_PROGRESS marker of a restore still being applied does not.
class RestoreDecisionHookTest {
    private final BootstrapModule bootstrapModule = mock(BootstrapModule.class);
    private final DeploymentManager deploymentManager = mock(DeploymentManager.class);
    private final AbTestManager abTestManager = mock(AbTestManager.class);

    /// One test per re-driven component, so dropping any one re-drive reddens a test that names it.
    @Test
    void onRestoreDecision_reDrivesTheBootstrapConfigSeed_onATerminalDecision() {
        AetherNode.onRestoreDecision(decision(BackupRestoreOutcome.RESTORED), bootstrapModule, deploymentManager, abTestManager);

        verify(bootstrapModule).retryIfNeeded();
    }

    @Test
    void onRestoreDecision_reDrivesTheRolloutManager_onATerminalDecision() {
        AetherNode.onRestoreDecision(decision(BackupRestoreOutcome.RESTORED), bootstrapModule, deploymentManager, abTestManager);

        verify(deploymentManager).reloadRestoredState();
    }

    @Test
    void onRestoreDecision_reDrivesTheAbTestManager_onATerminalDecision() {
        AetherNode.onRestoreDecision(decision(BackupRestoreOutcome.RESTORED), bootstrapModule, deploymentManager, abTestManager);

        verify(abTestManager).reloadRestoredState();
    }

    @Test
    void onRestoreDecision_doesNothing_whileTheRestoreIsInProgress() {
        AetherNode.onRestoreDecision(decision(BackupRestoreOutcome.IN_PROGRESS), bootstrapModule, deploymentManager, abTestManager);

        verify(bootstrapModule, never()).retryIfNeeded();
        verify(deploymentManager, never()).reloadRestoredState();
        verify(abTestManager, never()).reloadRestoredState();
    }

    private static ValuePut<BackupRestoreKey, BackupRestoreValue> decision(BackupRestoreOutcome outcome) {
        return new ValuePut<>(new KVCommand.Put<>(BackupRestoreKey.backupRestoreKey(), BackupRestoreValue.decided(outcome)),
                              Option.none());
    }
}
