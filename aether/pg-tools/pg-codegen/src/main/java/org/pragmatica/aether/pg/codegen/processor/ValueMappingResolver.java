// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;


/// Discovers the `ValueMapping` descriptor a value object declares, via the reflection-free
/// convention that the value object exposes a `public static ValueMapping<Self, P> valueMapping()`
/// method. The processor never invokes the method; it reads the declared return type at compile time
/// to learn the primitive column representation `P`, then generates code that calls
/// `Vo.valueMapping()` at runtime.
///
/// Because the convention is a single conventionally-named static method, a value object can carry
/// at most one `ValueMapping` — two descriptors for one value object is a compiler error by
/// construction (Java forbids two static methods with the same signature), so no ambiguity check is
/// required.
@Contract
public final class ValueMappingResolver {
    private ValueMappingResolver() {}

    /// The `P` (primitive column) side of a discovered `ValueMapping<Vo, P>`, plus the value
    /// object's simple name used to reference `Vo.valueMapping()` in generated code.
    public record Binding(String voSimpleName, String pTypeName) {
        /// Bind expression: lower the value object to its column value (total, cannot fail).
        public String lowerExpr(String valueExpr) {
            return voSimpleName + ".valueMapping().lower().apply(" + valueExpr + ")";
        }

        /// The `lift` function reference used inside a row decode's `flatMap`.
        public String liftExpr() {
            return voSimpleName + ".valueMapping().lift()";
        }

        /// Row accessor (method + class-literal type argument) for reading `P` from a row.
        public JavaTypeAccessor.AccessorInfo pAccessor() {
            return JavaTypeAccessor.forField(pTypeName);
        }
    }

    /// Returns the `ValueMapping` binding for the given type, or empty when the type declares no
    /// conforming `static valueMapping()` method.
    public static Option<Binding> resolve(TypeMirror type) {
        if (! (type instanceof DeclaredType declaredType)) {
            return Option.empty();
        }

        if (! (declaredType.asElement() instanceof TypeElement voType)) {
            return Option.empty();
        }

        for (var enclosed : voType.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }

            var binding = bindingFor(voType, (ExecutableElement) enclosed);

            if (binding.isPresent()) {
                return binding;
            }
        }

        return Option.empty();
    }

    private static Option<Binding> bindingFor(TypeElement voType, ExecutableElement method) {
        if (!method.getSimpleName().contentEquals("valueMapping") || !method.getModifiers().contains(Modifier.STATIC) || !method.getParameters().isEmpty()) {
            return Option.empty();
        }

        if (! (method.getReturnType() instanceof DeclaredType returnType) || !returnType.asElement().getSimpleName().contentEquals("ValueMapping")) {
            return Option.empty();
        }

        var typeArgs = returnType.getTypeArguments();

        if (typeArgs.size() != 2) {
            return Option.empty();
        }

        return Option.present(new Binding(voType.getSimpleName().toString(),
                                          typeArgs.get(1).toString()));
    }
}
