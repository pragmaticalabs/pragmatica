// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/// Bytecode-level "is anything outside this method's declaring class calling it" scanner.
///
/// Scope, by design (see #519 phase-1 vs #690 phase-2 boundary):
/// - A direct `INVOKE*` edge counts. A method-reference / lambda edge compiled to `invokedynamic`
///   (`LambdaMetafactory`) also counts — the bootstrap-method `Handle` argument names the real target,
///   and skipping it would be a false-DEAD on every accessor only ever used as `Type::accessor`.
/// - A call from the SAME declaring class does NOT count (excluded at the point the edge is recorded).
///   Records generate `equals`/`hashCode`/`toString` bodies that invoke every accessor on `this` —
///   without this exclusion, a record's own generated methods would make every accessor look live
///   regardless of any real caller.
/// - Reflective invocation (`Method.invoke`, `RecordComponent.getAccessor().invoke`) produces no
///   bytecode edge at all and is invisible here by construction. That is a feature, not a gap it fails
///   to close: [ReflectiveConfigExemptions] finds the reflective-binding call sites themselves and
///   exempts everything they bind, rather than trying to model `invoke()` as a call graph edge.
/// - A call reached only through an interface/supertype reference whose static owner differs from the
///   accessor's declaring class (polymorphic dispatch) is NOT matched — this class only targets plain
///   config records, which do not implement shared behavioral interfaces for their accessors. Documented
///   limitation, not an oversight: biases toward LIVE (conservative), never toward DEAD.
final class BytecodeReachability {
    private final Set<MethodRef> invokedFromOutsideDeclaringClass;

    private BytecodeReachability(Set<MethodRef> invokedFromOutsideDeclaringClass) {
        this.invokedFromOutsideDeclaringClass = invokedFromOutsideDeclaringClass;
    }

    boolean isReachable(MethodRef target) {
        return invokedFromOutsideDeclaringClass.contains(target);
    }

    static BytecodeReachability scan(List<Path> corpusRoots) {
        var edges = new HashSet<MethodRef>();
        var visitor = new EdgeCollectingVisitor(edges);

        for (var root : corpusRoots) {
            forEachClassFile(root, bytes -> new ClassReader(bytes).accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES));
        }

        return new BytecodeReachability(edges);
    }

    private interface ClassBytesConsumer {
        void accept(byte[] classBytes);
    }

    private static void forEachClassFile(Path root, ClassBytesConsumer consumer) {
        if (Files.isDirectory(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(path -> path.toString().endsWith(".class"))
                    .forEach(path -> consumer.accept(readQuietly(path)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return;
        }

        if (root.toString().endsWith(".jar") && Files.isRegularFile(root)) {
            try (JarFile jar = new JarFile(root.toFile())) {
                jar.stream()
                   .filter(entry -> entry.getName().endsWith(".class"))
                   .forEach(entry -> consumer.accept(readQuietly(jar, entry)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static byte[] readQuietly(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] readQuietly(JarFile jar, JarEntry entry) {
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Records every `INVOKE*` and method-reference edge whose caller class differs from the callee's
    /// owner. `edges` is shared and mutated across every class file in the corpus.
    private static final class EdgeCollectingVisitor extends ClassVisitor {
        private final Set<MethodRef> edges;
        private String currentClassInternalName;

        private EdgeCollectingVisitor(Set<MethodRef> edges) {
            super(Opcodes.ASM9);
            this.edges = edges;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            currentClassInternalName = name;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                    recordEdge(owner, name, descriptor);
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                    for (Object arg : bootstrapMethodArguments) {
                        if (arg instanceof Handle handle) {
                            recordEdge(handle.getOwner(), handle.getName(), handle.getDesc());
                        }
                    }
                }
            };
        }

        private void recordEdge(String owner, String name, String descriptor) {
            if (!owner.equals(currentClassInternalName)) {
                edges.add(new MethodRef(owner, name, descriptor));
            }
        }
    }
}
