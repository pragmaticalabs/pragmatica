// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.pragmatica.aether.resource.ScheduleConfig;
import org.pragmatica.aether.resource.TopicConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.slice.StreamConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Commissioning-time-only evidence that [BytecodeReachability] and [ReflectiveConfigExemptions]
/// classify known-dead and known-reflectively-bound code correctly, run once against real production
/// history before the permanent gate ([ConfigKeyLivenessTest]) was trusted to bind on it.
///
/// The positive control this class used to carry is gone, and deliberately not replaced. It had two
/// candidate subjects and BOTH were resolved: #503 deleted `WorkerCodecs`, and #381 wired
/// `ConfigNotificationManager.notifyChange` to a production caller. That is the structural problem
/// with a positive control drawn from real production history — it is valid only while its subject
/// stays dead, so it expires exactly when its ticket is fixed. The permanent gate carries a synthetic
/// one instead, which no ticket resolution can invalidate:
/// [ConfigKeyLivenessTest#selfTest_syntheticFixture_distinguishesLiveFromDeadAccessor]. For #381
/// specifically the assertion is now inverted and enabled — [ConfigChangePushLivenessTest] pins
/// `notifyChange` as LIVE, reachable from production code.
///
/// `@Disabled` deliberately, and NOT part of the CI gate: coupling CI to a ticket's eventual
/// resolution (this test would start failing the moment it is fixed, for reasons that have nothing to
/// do with #519) is wrong. Re-run by hand whenever
/// the scanner's core logic changes, to make sure a "simplification" didn't quietly reopen a
/// false-DEAD or false-LIVE gap.
@Disabled("Commissioning-time only — must not gate CI on a ticket's resolution (#519). Both positive controls were retired when their tickets closed (#503 deleted WorkerCodecs, #381 wired notifyChange); ConfigKeyLivenessTest's synthetic self-test replaced them, and ConfigChangePushLivenessTest now pins notifyChange LIVE")
class DeadSurfaceCommissioningTest {
    private static final List<java.nio.file.Path> PRODUCTION_ROOTS = ReactorRoots.productionRoots();

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
