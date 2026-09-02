// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/// Auto-discovers which record types are bound reflectively via `ConfigService.config(section,
/// XClass.class)` / `.configAsync(...)`, so they never need a hand-maintained allowlist of
/// reflectively-bound types (main's condition 1: reflective binding is a LIVE edge by construction,
/// not an exception someone has to remember to add).
///
/// `ProviderBasedConfigService` binds every record component of `X` via `getRecordComponents()` +
/// reflection — none of that produces an `INVOKE*` edge for [BytecodeReachability] to see. Rather than
/// modeling `Method.invoke` as a graph edge (which would make everything reflectively invoked anywhere
/// look reachable, defeating the check), this finds the actual `.config(class-literal)` call sites and
/// exempts every accessor of that literal's class from the dead-accessor gate — WARN, not FAIL, since
/// "passed to the generic config binder" is a coarser signal than "this specific accessor is used."
///
/// Detection is a same-method correlation: within one method body, a `LDC` of a class-type constant
/// plus a call to `ConfigService.config`/`.configAsync` marks every such class literal in that method as
/// exempt. This slightly over-approximates (multiple unrelated class literals in one method would all
/// get exempted) but over-exemption only downgrades FAIL to WARN for the affected accessors — it can
/// never hide a genuinely dead accessor from human review, only from the hard gate.
final class ReflectiveConfigExemptions {
    private static final String CONFIG_SERVICE_OWNER = "org/pragmatica/config/ConfigService";
    private static final Set<String> CONFIG_BINDING_METHODS = Set.of("config", "configAsync");

    private ReflectiveConfigExemptions() {}

    /// @return internal class names (`java/lang/String` form) of every record type reflectively bound
    /// via a `ConfigService.config(...)`/`.configAsync(...)` class-literal call site found in the corpus.
    static Set<String> scan(List<Path> corpusRoots) {
        var exempted = new HashSet<String>();

        for (var root : corpusRoots) {
            forEachClassFile(root, bytes -> new ClassReader(bytes).accept(new ExemptionVisitor(exempted),
                                                                          ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES));
        }

        return exempted;
    }

    private static final class ExemptionVisitor extends ClassVisitor {
        private final Set<String> exempted;

        private ExemptionVisitor(Set<String> exempted) {
            super(Opcodes.ASM9);
            this.exempted = exempted;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                private final Set<String> classLiteralsSeen = new HashSet<>();
                private boolean callsConfigBinder = false;

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof Type type && type.getSort() == Type.OBJECT) {
                        classLiteralsSeen.add(type.getInternalName());
                    }
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                    if (owner.equals(CONFIG_SERVICE_OWNER) && CONFIG_BINDING_METHODS.contains(name)) {
                        callsConfigBinder = true;
                    }
                }

                @Override
                public void visitEnd() {
                    if (callsConfigBinder) {
                        exempted.addAll(classLiteralsSeen);
                    }
                }
            };
        }
    }

    // Duplicated (not shared with BytecodeReachability) deliberately: minimum code over a premature
    // shared abstraction for two call sites that iterate class files identically but visit different
    // things.
    private static void forEachClassFile(Path root, java.util.function.Consumer<byte[]> consumer) {
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
                   .forEach(entry -> {
                       try (InputStream in = jar.getInputStream(entry)) {
                           consumer.accept(in.readAllBytes());
                       } catch (IOException e) {
                           throw new UncheckedIOException(e);
                       }
                   });
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
}
