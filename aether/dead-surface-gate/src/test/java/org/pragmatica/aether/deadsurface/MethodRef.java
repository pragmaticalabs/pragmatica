// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import org.objectweb.asm.Type;

import java.lang.reflect.Method;

/// A bytecode-level method identity: JVM internal class name (`java/lang/String` form), method name
/// and JVM descriptor. Descriptors are always derived via [Type#getMethodDescriptor], never
/// hand-written, so there is no way for a target and an observed call site to disagree on erasure,
/// boxing, or array shape.
record MethodRef(String owner, String name, String descriptor) {
    static MethodRef of(Method method) {
        return new MethodRef(Type.getInternalName(method.getDeclaringClass()), method.getName(), Type.getMethodDescriptor(method));
    }
}
