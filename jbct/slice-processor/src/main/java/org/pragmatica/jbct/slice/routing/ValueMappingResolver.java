// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.routing;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.LinkedHashMap;
import java.util.Map;

import org.pragmatica.lang.Option;


/// Discovers the `ValueMapping` a value object declares, via the reflection-free convention that it
/// exposes a `public static ValueMapping<Self, P> valueMapping()` method. Reads the declared return
/// type at compile time to learn the primitive representation `P`; the method is never invoked. Used
/// by [RouteSourceGenerator] to bind a value-object HTTP path/query segment by composing the
/// framework-owned `String -> P` parser with the value object's `lift`.
///
/// Deliberately duplicates the pg-codegen resolver (spec #397 §6): the two annotation processors
/// live in separate modules and share no processor-support module, and the resolver is tiny.
public final class ValueMappingResolver {
    private ValueMappingResolver() {}

    /// A discovered `ValueMapping<Vo, P>`: the value object's fully-qualified name (referenced as
    /// `<Vo>.valueMapping().lift()` in generated code, so no import is required) and the primitive
    /// representation `P` (used to pick the framework `String -> P` parser).
    public record Binding(String voQualifiedName, String pTypeName) {}

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

    /// Resolves the value-object bindings for each record component of `recordType`, keyed by
    /// component name, for those components whose type declares a `valueMapping()`. A query-parameter
    /// component binds as `Option<Vo>` in the request record, so an `Option<X>` component is resolved
    /// against `X`. Empty when the type is not a record. Component order is preserved.
    public static Map<String, Binding> resolveRecordComponents(TypeMirror recordType) {
        var result = new LinkedHashMap<String, Binding>();

        if (recordType instanceof DeclaredType dt && dt.asElement() instanceof TypeElement te && te.getKind() == ElementKind.RECORD) {
            for (var component : te.getRecordComponents()) {
                resolve(unwrapOption(component.asType())).onPresent(binding -> result.put(component.getSimpleName()
                                                                                                   .toString(),
                                                                                          binding));
            }
        }

        return result;
    }

    /// If the type is `Option<X>` (how a query-parameter component appears in a request record),
    /// returns `X`; otherwise returns the type unchanged (path-parameter components bind the value
    /// object directly).
    private static TypeMirror unwrapOption(TypeMirror type) {
        if (type instanceof DeclaredType dt && dt.asElement() instanceof TypeElement te && te.getQualifiedName()
                                                                                             .contentEquals("org.pragmatica.lang.Option") && dt.getTypeArguments()
                                                                                                                                               .size() == 1) {
            return dt.getTypeArguments()
                     .getFirst();
        }

        return type;
    }

    private static Option<Binding> bindingFor(TypeElement voType, ExecutableElement method) {
        if (!method.getSimpleName().contentEquals("valueMapping") || !method.getModifiers().contains(Modifier.STATIC) || !method.getParameters()
                                                                                                                                .isEmpty()) {
            return Option.empty();
        }

        if (! (method.getReturnType() instanceof DeclaredType returnType) || !returnType.asElement()
                                                                                        .getSimpleName()
                                                                                        .contentEquals("ValueMapping")) {
            return Option.empty();
        }

        var typeArgs = returnType.getTypeArguments();

        if (typeArgs.size() != 2) {
            return Option.empty();
        }

        return Option.present(new Binding(voType.getQualifiedName().toString(),
                                          typeArgs.get(1).toString()));
    }
}
