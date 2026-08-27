// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.pragmatica.aether.config.ConfigKeyLive;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// #519 dead-config-accessor gate. Two independent checks:
///
/// 1. [#selfTest_syntheticFixture_distinguishesLiveFromDeadAccessor] — the permanent sensor (main's
///    ruling: this fixture, not #503/#381, is what the gate is coupled to). A self-contained record +
///    consumer with one deliberately-called and one deliberately-uncalled accessor, scanned against
///    nothing but this module's own compiled test output. If this ever goes red, the scanner itself is
///    broken — trust nothing else in this file until it's green again.
/// 2. [#clusterBootstrapConfig_everyAccessor_isReachableExemptOrSuppressed] — the actual gate, against
///    the real `ClusterBootstrapConfig` tree. Baseline-and-ratchet, not instant-gate (main's condition):
///    every accessor the first commissioning run found dead was triaged once (fixed, reflectively
///    exempted, or `@ConfigKeyLive`-suppressed with a ticket reference) before this assertion was
///    allowed to bind — see #675 for the AutoHealSpec findings. From here, the test fails only on a
///    NEW dead accessor, not on the ones already triaged. The corpus is [ReactorRoots], a repository-wide
///    sweep of compiled module output rather than this JVM's own classpath: the first commissioning run
///    found that a classpath-only corpus false-DEADs any accessor whose sole caller lives in a module
///    `aether/node` doesn't depend on (`aether/cli` depends on `aether-config` directly, as a sibling of
///    `node`) — 9 of that run's 15 flagged accessors were exactly this, not real dead code. The sweep
///    fails loudly instead if a module's output is missing rather than silently under-scanning.
class ConfigKeyLivenessTest {
    // --- synthetic self-test fixture -----------------------------------------------------------------

    record SelfTestFixtureRecord(String liveField, String deadField) {}

    static final class SelfTestConsumer {
        static String touchLiveField(SelfTestFixtureRecord fixture) {
            return fixture.liveField();
        }
    }

    @Test
    void selfTest_syntheticFixture_distinguishesLiveFromDeadAccessor() throws Exception {
        var corpus = List.of(thisModulesOwnTestClassesDirectory());
        var reachability = BytecodeReachability.scan(corpus);

        var liveAccessor = MethodRef.of(SelfTestFixtureRecord.class.getDeclaredMethod("liveField"));
        var deadAccessor = MethodRef.of(SelfTestFixtureRecord.class.getDeclaredMethod("deadField"));

        assertTrue(reachability.isReachable(liveAccessor),
                  "synthetic fixture: liveField() is called from SelfTestConsumer and must be flagged live — " +
                  "if this fails, the scanner has a false-DEAD bug and nothing else in this file can be trusted");
        assertFalse(reachability.isReachable(deadAccessor),
                   "synthetic fixture: deadField() has no caller anywhere and must be flagged dead — " +
                   "if this fails, the scanner has a false-LIVE bug (e.g. record equals/hashCode/toString " +
                   "self-calls leaking through) and nothing else in this file can be trusted");
    }

    private static Path thisModulesOwnTestClassesDirectory() throws Exception {
        return Path.of(SelfTestConsumer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    // --- the real gate --------------------------------------------------------------------------------

    @Test
    void clusterBootstrapConfig_everyAccessor_isReachableExemptOrSuppressed() {
        var missing = ReactorRoots.missingProductionOutput();

        assertTrue(missing.isEmpty(),
                  "Corpus incomplete: these module(s) have src/main/java but no target/classes, so any " +
                  "accessor whose sole caller lives there would false-DEAD: " + missing +
                  ". Run a full reactor build (./build.sh, or `mvn -pl aether install -DskipTests`) " +
                  "before trusting this gate's result.");

        var corpus = ReactorRoots.productionRoots();
        var reachability = BytecodeReachability.scan(corpus);
        var reflectivelyBound = ReflectiveConfigExemptions.scan(corpus);
        var accessors = ConfigRecordScope.walk(ClusterBootstrapConfig.class);

        var newUnsuppressedDeadAccessors = accessors.stream()
                                                     .filter(accessor -> !reachability.isReachable(accessor.toMethodRef()))
                                                     .filter(accessor -> !reflectivelyBound.contains(Type.getInternalName(accessor.declaringClass())))
                                                     .filter(accessor -> accessor.accessorMethod().getAnnotation(ConfigKeyLive.class) == null)
                                                     .map(accessor -> accessor.declaringClass().getSimpleName() + "." + accessor.accessorMethod().getName() + "()")
                                                     .toList();

        assertTrue(newUnsuppressedDeadAccessors.isEmpty(),
                  "New dead config accessor(s), no live caller / reflective-binding exemption / @ConfigKeyLive " +
                  "suppression found: " + newUnsuppressedDeadAccessors +
                  ". Wire it to a real consumer, or suppress with @ConfigKeyLive(\"<ticket>: <why>\") on the " +
                  "record component (see #519).");
    }
}
