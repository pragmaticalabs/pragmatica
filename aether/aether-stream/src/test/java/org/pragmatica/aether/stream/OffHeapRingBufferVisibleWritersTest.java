// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandleInfo;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2981 S12 (design 2.2 check 3, r7 rv-r6 M3): the structural ALLOW-list over the bytecode of `OffHeapRingBuffer` and every nestmate.
/// Outside the exempt writers, every `GETFIELD visibleOffset` must be followed (skipping pseudo-elements: labels, line numbers,
/// local-variable entries) by `INVOKEVIRTUAL AtomicLong.get|getAcquire|getOpaque|getPlain`; any other use (a mutator, a store to a
/// local, an argument, a method reference) is a hole in "every visible ADVANCE goes through `ownerAdvance`".
///
/// The exemption follows the WRITER (CTO ruling): `seedHeadChecked`, `queueVisibleAdvance`, `cutHeader`. `seedHead` has no
/// `GETFIELD` (it delegates), so it is not exempt. A new writer inside an exempt method is not caught here; each is pinned by its
/// own behaviour test: `cutHeader` never lowers-to-raise -> [OffHeapRingBufferCutNeverRaisesVisibleTest]; `queueVisibleAdvance` only
/// raises via `max`; `seedHeadChecked` is bounded by the seed check before its `visibleOffset.set` ->
/// `OffHeapRingBufferTest$SeedOffset#seedHead_withAVisibleSeedOutsideMinusOneToBase_isRejected_withoutMutation`.
class OffHeapRingBufferVisibleWritersTest {
    private static final String FIELD = "visibleOffset";
    private static final String RING = "org/pragmatica/aether/stream/OffHeapRingBuffer";
    private static final String ATOMIC_LONG = "java/util/concurrent/atomic/AtomicLong";
    private static final ClassDesc RING_DESC = ClassDesc.of("org.pragmatica.aether.stream.OffHeapRingBuffer");
    private static final ClassDesc ATOMIC_DESC = ClassDesc.of("java.util.concurrent.atomic.AtomicLong");
    private static final Set<String> READS = Set.of("get", "getAcquire", "getOpaque", "getPlain");
    private static final Set<String> EXEMPT = Set.of("seedHeadChecked", "queueVisibleAdvance", "cutHeader");

    @Test
    void everyVisibleOffsetUse_outsideTheExemptWriters_isAPlainRead() throws IOException {
        var violations = new ArrayList<String>();
        var touching = new TreeSet<String>();

        for (var bytes : ringClassBytes()) {
            scan(ClassFile.of().parse(bytes), violations, touching);
        }

        assertThat(touching).as("positive control: the scan reaches every exempt writer and the readers")
                            .containsAll(EXEMPT)
                            .contains("visibleOffset");
        assertThat(violations).as("a use of visibleOffset that is not a plain read, outside the exempt writers").isEmpty();
    }

    @Test
    void theFieldIsPrivateFinal_neverStoredOutsideTheConstructor_andNoConstantReachesItByName() throws IOException {
        var problems = new ArrayList<String>();

        for (var bytes : ringClassBytes()) {
            constantPoolProblems(ClassFile.of().parse(bytes), problems);
        }

        var ring = ClassFile.of().parse(ringClassBytes().getFirst());
        var field = ring.fields().stream().filter(f -> f.fieldName().equalsString(FIELD)).findFirst().orElseThrow();

        assertThat(field.flags().has(AccessFlag.PRIVATE)).isTrue();
        assertThat(field.flags().has(AccessFlag.FINAL)).isTrue();
        assertThat(problems).as("PUTFIELD outside <init>, REF_getField handle, or a \"visibleOffset\" string constant").isEmpty();
    }

    @Test
    void visibleOffsetAccessor_returnsLong_soNoReferenceEscapes() throws NoSuchMethodException {
        assertThat(OffHeapRingBuffer.class.getMethod("visibleOffset").getReturnType()).isEqualTo(long.class);
        assertThat(Modifier.isPublic(OffHeapRingBuffer.class.getMethod("visibleOffset").getModifiers())).isTrue();
    }

    // ---- instrument validation: the scan on synthetic classes shaped like the three mutation probes -------------------------

    /// Probe (c): a legitimate read with a LINE NUMBER entry between GETFIELD and `get` stays GREEN.
    @Test
    void scan_aReadWithALineNumberBetweenGetfieldAndGet_isGreen() {
        assertThat(violationsOf(synthetic("lineNumberedRead", code -> code.aload(0)
                                                                          .getfield(RING_DESC, FIELD, ATOMIC_DESC)
                                                                          .lineNumber(42)
                                                                          .invokevirtual(ATOMIC_DESC,
                                                                                         "get",
                                                                                         MethodTypeDesc.of(ConstantDescs.CD_long))
                                                                          .lreturn()))).isEmpty();
    }

    /// Probe (a): a new method calling `visibleOffset.incrementAndGet()` is RED.
    @Test
    void scan_aMutatorCall_isRed() {
        assertThat(violationsOf(synthetic("bump", code -> code.aload(0)
                                                              .getfield(RING_DESC, FIELD, ATOMIC_DESC)
                                                              .invokevirtual(ATOMIC_DESC,
                                                                             "incrementAndGet",
                                                                             MethodTypeDesc.of(ConstantDescs.CD_long))
                                                              .lreturn()))).hasSize(1);
    }

    /// The reference passed on (the shape of an argument or a method-reference receiver) is RED.
    @Test
    void scan_aFieldReferenceThatEscapesTheCall_isRed() {
        assertThat(violationsOf(synthetic("escape", code -> code.aload(0)
                                                                .getfield(RING_DESC, FIELD, ATOMIC_DESC)
                                                                .astore(1)
                                                                .lconst_0()
                                                                .lreturn()))).hasSize(1);
    }

    // ---- scan ---------------------------------------------------------------------------------------------------------------

    private static List<String> violationsOf(byte[] bytes) {
        var violations = new ArrayList<String>();

        scan(ClassFile.of().parse(bytes), violations, new TreeSet<>());
        return violations;
    }

    private static void scan(ClassModel model, List<String> violations, Set<String> touching) {
        model.methods().forEach(method -> scanMethod(model, method, violations, touching));
    }

    private static void scanMethod(ClassModel model, MethodModel method, List<String> violations, Set<String> touching) {
        var name = method.methodName().stringValue();

        method.code().ifPresent(code -> scanCode(model.thisClass().asInternalName() + "." + name, name, code.elementList(), violations, touching));
    }

    private static void scanCode(String where,
                                 String name,
                                 List<CodeElement> elements,
                                 List<String> violations,
                                 Set<String> touching) {
        for (var i = 0; i < elements.size(); i++) {
            if (readsField(elements.get(i))) {
                touching.add(name);
                if (!EXEMPT.contains(name) && !isPlainReadAt(elements, i + 1)) {
                    violations.add(where);
                }
            }
        }
    }

    private static boolean readsField(CodeElement element) {
        return element instanceof FieldInstruction field
               && field.opcode() == java.lang.classfile.Opcode.GETFIELD
               && field.owner().asInternalName().equals(RING)
               && field.name().equalsString(FIELD);
    }

    private static boolean isPlainReadAt(List<CodeElement> elements, int from) {
        for (var i = from; i < elements.size(); i++) {
            if (elements.get(i) instanceof Instruction instruction) {
                return instruction instanceof InvokeInstruction invoke
                       && invoke.opcode() == java.lang.classfile.Opcode.INVOKEVIRTUAL
                       && invoke.owner().asInternalName().equals(ATOMIC_LONG)
                       && READS.contains(invoke.name().stringValue());
            }
        }
        return false;
    }

    private static void constantPoolProblems(ClassModel model, List<String> problems) {
        for (PoolEntry entry : model.constantPool()) {
            if (entry instanceof MethodHandleEntry handle
                && handle.kind() == MethodHandleInfo.REF_getField
                && handle.reference().name().equalsString(FIELD)) {
                problems.add(model.thisClass().asInternalName() + ": REF_getField handle");
            }
            if (entry instanceof StringEntry string && string.utf8().equalsString(FIELD)) {
                problems.add(model.thisClass().asInternalName() + ": string constant");
            }
        }
        model.methods().forEach(method -> putfieldsOutsideInit(model, method, problems));
    }

    private static void putfieldsOutsideInit(ClassModel model, MethodModel method, List<String> problems) {
        if (!method.methodName().equalsString("<init>")) {
            method.code()
                  .ifPresent(code -> code.elementList().stream()
                                         .filter(e -> e instanceof FieldInstruction f
                                                      && f.opcode() == java.lang.classfile.Opcode.PUTFIELD
                                                      && f.name().equalsString(FIELD))
                                         .forEach(_ -> problems.add(model.thisClass().asInternalName() + "." + method.methodName().stringValue() + ": PUTFIELD")));
        }
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------------------

    /// Bytes of `OffHeapRingBuffer` (first) and every nestmate.
    private static List<byte[]> ringClassBytes() throws IOException {
        var out = new ArrayList<byte[]>();

        for (var member : OffHeapRingBuffer.class.getNestMembers()) {
            try (var in = OffHeapRingBuffer.class.getResourceAsStream("/" + member.getName().replace('.', '/') + ".class")) {
                out.add(in.readAllBytes());
            }
        }
        return out;
    }

    private static byte[] synthetic(String method, java.util.function.Consumer<java.lang.classfile.CodeBuilder> body) {
        return ClassFile.of().build(ClassDesc.of("org.pragmatica.aether.stream.SyntheticProbe"),
                                    cb -> cb.withMethodBody(method,
                                                            MethodTypeDesc.of(ConstantDescs.CD_long),
                                                            ClassFile.ACC_PUBLIC,
                                                            body));
    }
}
