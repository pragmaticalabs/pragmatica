// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.LogLevelKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.LogLevelValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/// #2616 (the DynamicConfigManager F4/W-9 hazard): the level registry and the logger configuration are written by the
/// commit's own notification handler. `setLevel`/`resetLevel` used to write them again in the apply continuation, so a
/// later commit B absorbed between A's commit and A's continuation was overwritten by A. The continuation must not
/// touch either.
@Timeout(30)
class LogLevelRegistryStaleContinuationTest {
    private static final String LOGGER = "org.pragmatica.aether.api.stale.continuation.test";

    @Test
    void setLevel_continuation_doesNotOverwriteALaterCommit() {
        var applyA = Promise.<List<Object>> promise();
        var registry = registryApplying(applyA);

        registry.setLevel(LOGGER, "DEBUG");
        registry.onLogLevelPut(put("DEBUG"));
        registry.onLogLevelPut(put("WARN"));
        applyA.succeed(List.of());

        assertThat(registry.allLevels()).as("B was committed after A; A's continuation must not resurrect A")
                                        .containsEntry(LOGGER, "WARN");
    }

    @Test
    void resetLevel_continuation_doesNotRemoveALaterCommit() {
        var applyA = Promise.<List<Object>> promise();
        var registry = registryApplying(applyA);

        registry.onLogLevelPut(put("DEBUG"));
        registry.resetLevel(LOGGER);
        registry.onLogLevelRemove(remove());
        registry.onLogLevelPut(put("WARN"));
        applyA.succeed(List.of());

        assertThat(registry.allLevels()).as("B re-set the logger after A's reset; A's continuation must not drop it")
                                        .containsEntry(LOGGER, "WARN");
    }

    @Test
    void setLevel_continuation_leavesTheRegistryToTheCommit() {
        var applyA = Promise.<List<Object>> promise();
        var registry = registryApplying(applyA);

        registry.setLevel(LOGGER, "DEBUG");
        applyA.succeed(List.of());

        assertThat(registry.allLevels()).as("only a commit's own onLogLevelPut writes the registry")
                                        .doesNotContainKey(LOGGER);
    }

    @Test
    void resetLevel_continuation_leavesTheRegistryToTheCommit() {
        var applyA = Promise.<List<Object>> promise();
        var registry = registryApplying(applyA);

        registry.onLogLevelPut(put("DEBUG"));
        registry.resetLevel(LOGGER);
        applyA.succeed(List.of());

        assertThat(registry.allLevels()).as("only a commit's own onLogLevelRemove drops the logger")
                                        .containsEntry(LOGGER, "DEBUG");
    }

    @Test
    void setLevel_continuation_doesNotReapplyTheLoggerConfiguration() {
        var applyA = Promise.<List<Object>> promise();
        var registry = registryApplying(applyA);

        registry.setLevel(LOGGER, "DEBUG");
        registry.onLogLevelPut(put("DEBUG"));
        registry.onLogLevelPut(put("TRACE"));
        applyA.succeed(List.of());

        assertThat(LogManager.getLogger(LOGGER).getLevel()).as("B set the logger after A; A's continuation must not reapply A to the logger")
                                                           .isEqualTo(Level.TRACE);
    }

    @Test
    void resetLevel_continuation_doesNotResetTheLoggerConfiguration() {
        var applyA = Promise.<List<Object>> promise();
        var registry = registryApplying(applyA);

        registry.onLogLevelPut(put("DEBUG"));
        registry.resetLevel(LOGGER);
        registry.onLogLevelRemove(remove());
        registry.onLogLevelPut(put("TRACE"));
        applyA.succeed(List.of());

        assertThat(LogManager.getLogger(LOGGER).getLevel()).as("B re-set the logger after A's reset; A's continuation must not clear it")
                                                           .isEqualTo(Level.TRACE);
    }

    @Test
    void onLogLevelPut_writesTheRegistryAndTheLoggerConfiguration() {
        var registry = registryApplying(Promise.<List<Object>> promise());

        registry.onLogLevelPut(put("TRACE"));

        assertThat(registry.allLevels()).containsEntry(LOGGER, "TRACE");
        assertThat(LogManager.getLogger(LOGGER).getLevel()).isEqualTo(Level.TRACE);
    }

    @Test
    void onLogLevelRemove_dropsTheRegistryEntryAndClearsTheLoggerConfiguration() {
        var registry = registryApplying(Promise.<List<Object>> promise());

        registry.onLogLevelPut(put("TRACE"));
        registry.onLogLevelRemove(remove());

        assertThat(registry.allLevels()).doesNotContainKey(LOGGER);
        assertThat(LogManager.getLogger(LOGGER).getLevel()).isNotEqualTo(Level.TRACE);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static LogLevelRegistry registryApplying(Promise<List<Object>> apply) {
        var node = (RabiaNode<KVCommand<AetherKey>>) Mockito.mock(RabiaNode.class);

        Mockito.when(node.apply(Mockito.anyList())).thenReturn((Promise) apply);

        return LogLevelRegistry.logLevelRegistry(node, (KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class));
    }

    private static ValuePut<LogLevelKey, LogLevelValue> put(String level) {
        return new ValuePut<>(new KVCommand.Put<>(LogLevelKey.forLogger(LOGGER), LogLevelValue.logLevelValue(LOGGER, level)),
                              Option.none());
    }

    private static ValueRemove<LogLevelKey, LogLevelValue> remove() {
        return new ValueRemove<>(new KVCommand.Remove<>(LogLevelKey.forLogger(LOGGER)), Option.none());
    }
}
