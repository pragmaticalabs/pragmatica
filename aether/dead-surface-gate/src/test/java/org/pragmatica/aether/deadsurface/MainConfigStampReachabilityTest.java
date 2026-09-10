// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// #980 verification finding SF2 — pins that `Main.run()` still CALLS the late-bound config stamps.
///
/// **The gap this closes, and why I was wrong about it.** `AetherNodeConfig`'s staged builder cannot
/// reach these fields mid-chain, so `Main` applies them after `build()`. Deleting such a call compiles
/// cleanly and is invisible to every unit test: the stamping method still exists, still works, still
/// has its own passing tests — it is simply never invoked. I reported this as "closes only under a
/// real node boot"; the #980 adversarial verification disproved that by building an ASM probe that
/// reddens on the mutation, and it was right.
///
/// For the cluster secret the consequence is concrete: an unstamped `Option.empty()` reaches
/// `BootstrapAdminKeyLeg`, which mints a RANDOM admin key on a node holding a perfectly good secret,
/// and `aether cluster bootstrap` then takes a 401 from a healthy cluster — #980 returning silently.
/// The other four stamps fail differently but just as quietly.
///
/// **Why this does not reuse [BytecodeReachability].** That scanner deliberately DISCARDS calls made
/// from within the declaring class — records generate `equals`/`hashCode`/`toString` bodies invoking
/// every accessor on `this`, and without the exclusion every accessor would look live regardless of
/// any real caller. `Main.run()` calling `Main.withResolvedClusterSecret` is exactly such an
/// intra-class edge, so the shared scanner reports it absent by design. Relaxing that exclusion would
/// break the gate it exists for, so this test carries its own narrower visitor: it asks a different
/// and stronger question — not "is this called from anywhere in production" but **"is this called from
/// `Main.run()` specifically"** — which also rules out a call that survives only in dead code.
///
/// ASM is pinned at `${asm.version}` 9.9.1 in this module's pom. That matters: ASM 9.5 throws on Java
/// 25 class files (major 69) and its exception is otherwise indistinguishable from "edge not found" —
/// a silent false RED. The two controls below catch a scanner that answers the same way for
/// everything, in both directions.
class MainConfigStampReachabilityTest {
    private static final String MAIN_CLASS = "org/pragmatica/aether/Main.class";
    private static final String RUN_METHOD = "run";

    /// THE pin, and the one whose absence reintroduces #980.
    @Test
    void mainRun_stampsTheClusterSecret_ontoTheNodeConfig() {
        assertStampedInRun("withResolvedClusterSecret",
                           "the cluster secret would never reach BootstrapAdminKeyLeg, which would mint a "
                           + "RANDOM admin key on a node holding a perfectly good secret — `aether cluster "
                           + "bootstrap` then 401s against a healthy cluster, which is #980 all over again");
    }

    /// The four siblings sharing the same shape. Not #980's concern, but the same silent-omission
    /// hazard on adjacent lines, and one assertion each.
    @Test
    void mainRun_stampsTheOtherLateBoundConfig_ontoTheNodeConfig() {
        assertStampedInRun("withClusterName", "the node would carry no cluster identity (#298)");
        assertStampedInRun("withAutoHeal", "auto-heal would silently fall back to defaults (#298)");
        assertStampedInRun("withStorageEncryption", "a configured storage keyring would be silently ignored (#253)");
        assertStampedInRun("withAlerts", "a configured [alerts] section would be silently ignored (#957)");
    }

    /// Control A — the scanner does not answer TRUE for everything. A visitor that recorded nothing,
    /// or a `Main.class` that failed to parse, would make every assertion above red and read as a
    /// genuine regression; a visitor that matched any name would make them all vacuous. This pins the
    /// vacuous direction.
    @Test
    void scanner_doesNotReportAMethodThatRunCannotCall() {
        assertFalse(callsMadeFromRun().contains("withAbsolutelyNothing"),
                    "the scanner reported a call `Main.run()` cannot possibly make; it is matching "
                    + "indiscriminately and every assertion in this class is meaningless");
    }

    /// Control B — the scanner sees SOMETHING, and specifically the builder entry point every stamp
    /// chains from. An empty set would turn the assertions above red for a reason that has nothing to
    /// do with the stamps, which is the false-RED this control exists to distinguish.
    @Test
    void scanner_findsTheBuilderCallRunIsKnownToMake() {
        var calls = callsMadeFromRun();

        assertFalse(calls.isEmpty(),
                    "no call edges were read out of Main.run() at all — Main.class is missing, "
                    + "unparseable by this ASM version, or the method name changed. Nothing else in "
                    + "this class can be trusted until this is green.");
        assertTrue(calls.contains("builder"),
                   "Main.run() is known to call AetherNodeConfig.builder(); if the scanner cannot see "
                   + "that edge it cannot see the stamps either. Read: " + calls.size() + " distinct "
                   + "call targets");
    }

    private static void assertStampedInRun(String stampMethod, String consequence) {
        assertTrue(callsMadeFromRun().contains(stampMethod),
                   "Main.run() does not call " + stampMethod + "(). Deleting that call compiles cleanly "
                   + "and every unit test stays green, so this is the only thing that notices. "
                   + "Consequence if it is genuinely gone: " + consequence);
    }

    /// Every method name invoked from the body of `Main.run()`. Names rather than full descriptors
    /// deliberately: an overload or a parameter change should not silently turn this green or red for
    /// the wrong reason — the question is whether the stamp is applied at all.
    private static Set<String> callsMadeFromRun() {
        var names = new HashSet<String>();
        var visitor = new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!RUN_METHOD.equals(name)) {
                    return null;
                }

                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String target,
                                                String targetDescriptor, boolean isInterface) {
                        names.add(target);
                    }
                };
            }
        };

        new ClassReader(mainClassBytes()).accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        return names;
    }

    /// Reads compiled PRODUCTION output, never this JVM's classpath — the same discipline
    /// [ReactorRoots] documents, and the reason a test-only caller cannot satisfy this check.
    private static byte[] mainClassBytes() {
        for (var root : ReactorRoots.productionRoots()) {
            var candidate = root.resolve(MAIN_CLASS);

            if (Files.isRegularFile(candidate)) {
                return readQuietly(candidate);
            }
        }

        throw new AssertionError(MAIN_CLASS + " not found in any production root. The corpus is "
                                 + ReactorRoots.productionRoots().size() + " module output directories; "
                                 + "if aether/node has not been compiled this check examines nothing.");
    }

    private static byte[] readQuietly(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
