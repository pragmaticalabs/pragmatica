// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.pragmatica.aether.deployment.config.ConfigNotificationManager;
import org.pragmatica.aether.resource.ScheduleConfig;
import org.pragmatica.aether.resource.TopicConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.slice.ConfigFacade;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.worker.WorkerCodecs;
import org.pragmatica.serialization.SliceCodec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Commissioning-time-only evidence that [BytecodeReachability] and [ReflectiveConfigExemptions]
/// classify known-dead and known-reflectively-bound code correctly, run once against real production
/// history before the permanent gate ([ConfigKeyLivenessTest]) was trusted to bind on it.
///
/// `@Disabled` deliberately, and NOT part of the CI gate — main's condition: #503 and #381 are open
/// tickets, and coupling CI to their eventual resolution (this test would start failing the moment
/// either is fixed, for reasons that have nothing to do with #519) is wrong. Re-run by hand whenever
/// the scanner's core logic changes, to make sure a "simplification" didn't quietly reopen a
/// false-DEAD or false-LIVE gap.
@Disabled("Commissioning-time only — #503 and #381 are open tickets and must not gate CI on their resolution (#519)")
class DeadSurfaceCommissioningTest {
    private static final List<java.nio.file.Path> PRODUCTION_ROOTS = ReactorRoots.productionRoots();

    @Test
    void positiveControl_workerCodecs_hasNoProductionCaller_isFlaggedDead() throws Exception {
        var reachability = BytecodeReachability.scan(PRODUCTION_ROOTS);
        var target = MethodRef.of(WorkerCodecs.class.getDeclaredMethod("workerCodecs", SliceCodec.class));

        assertFalse(reachability.isReachable(target),
                   "#503: WorkerCodecs.workerCodecs(SliceCodec) has no caller outside SystemCodecPinningTest " +
                   "(a test), so with the corpus restricted to production roots it must be flagged dead");
    }

    @Test
    void positiveControl_configNotificationManagerNotifyChange_hasNoProductionCaller_isFlaggedDead() throws Exception {
        var reachability = BytecodeReachability.scan(PRODUCTION_ROOTS);
        var interfaceTarget = MethodRef.of(ConfigNotificationManager.class.getDeclaredMethod("notifyChange", String.class, ConfigFacade.class));

        assertFalse(reachability.isReachable(interfaceTarget),
                   "#381: ConfigNotificationManager.notifyChange(String, ConfigFacade) has zero callers " +
                   "anywhere and must be flagged dead");
    }

    @Test
    void negativeControl_reflectivelyBoundRecords_areExemptedNotFlagged() {
        var reflectivelyBound = ReflectiveConfigExemptions.scan(PRODUCTION_ROOTS);

        for (Class<?> reflectivelyBoundConfig : List.of(TopicConfig.class, ScheduleConfig.class, StreamConfig.class, DatabaseConnectorConfig.class)) {
            assertTrue(reflectivelyBound.contains(Type.getInternalName(reflectivelyBoundConfig)),
                      reflectivelyBoundConfig.getSimpleName() + " is bound via ConfigService.config(section, " +
                      reflectivelyBoundConfig.getSimpleName() + ".class) at a real call site and must be in " +
                      "the reflective-exemption set, or the permanent gate would false-DEAD every one of its " +
                      "accessors the moment it stopped being invoked directly");
        }
    }
}
