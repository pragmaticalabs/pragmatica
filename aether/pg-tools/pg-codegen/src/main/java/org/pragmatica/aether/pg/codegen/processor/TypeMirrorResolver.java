// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.codegen.processor;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.List;

/// Resolves javax.lang.model TypeMirror instances to MethodAnalyzer.ReturnKind and inner type names.
///
/// Bridges the annotation processing type system to the codegen pipeline.
@Contract public final class TypeMirrorResolver {
    private TypeMirrorResolver() {}

    /// Resolved return type information extracted from a TypeMirror.
    ///
    /// `innerTypeName` is the name used in generated code (may include enclosing type for inner records).
    /// `simpleTypeName` is the bare class name used for table name derivation.
    /// `scalarAccessor` is populated when the inner type is a non-record scalar (e.g. `BigDecimal`,
    /// `Instant`, `UUID`), in which case `needsMapper` is false and the generator emits a single
    /// `row.<accessor>` call instead of a record-style `Result.all(...)` composition.
    /// `rawInnerTypeName` is the original TypeMirror#toString form (FQN for non-java.lang types) used
    /// to build scalar imports.
    public record ResolvedReturn(MethodAnalyzer.ReturnKind kind,
                                 String innerTypeName,
                                 String simpleTypeName,
                                 boolean needsMapper,
                                 Option<JavaTypeAccessor.AccessorInfo> scalarAccessor,
                                 String rawInnerTypeName){
        public static ResolvedReturn ofRecord(MethodAnalyzer.ReturnKind kind,
                                              String innerTypeName,
                                              String simpleTypeName) {
            return new ResolvedReturn(kind, innerTypeName, simpleTypeName, true, Option.empty(), innerTypeName);
        }

        public static ResolvedReturn ofScalar(MethodAnalyzer.ReturnKind kind,
                                              String innerTypeName,
                                              String simpleTypeName,
                                              JavaTypeAccessor.AccessorInfo accessor,
                                              String rawInnerTypeName) {
            return new ResolvedReturn(kind, innerTypeName, simpleTypeName, false,
                                      Option.present(accessor), rawInnerTypeName);
        }

        public static ResolvedReturn ofSimpleScalar(MethodAnalyzer.ReturnKind kind,
                                                    String innerTypeName) {
            return new ResolvedReturn(kind, innerTypeName, innerTypeName, false, Option.empty(), innerTypeName);
        }

        public static ResolvedReturn ofUnit() {
            return new ResolvedReturn(MethodAnalyzer.ReturnKind.UNIT, "Unit", "Unit", false, Option.empty(), "Unit");
        }

        /// Kept for paths that legitimately have no inner type (empty Option<T> / List<T> type args).
        public static ResolvedReturn ofOpaque(MethodAnalyzer.ReturnKind kind) {
            return new ResolvedReturn(kind, "Object", "Object", true, Option.empty(), "Object");
        }
    }

    /// Record field extracted from a TypeElement.
    public record FieldInfo(String name, String typeName){}

    /// Resolves a method return type (expected to be Promise<...>) to ReturnKind + inner type name.
    public static ResolvedReturn resolve(TypeMirror returnType, Types typeUtils) {
        if ( ! (returnType instanceof DeclaredType promiseType)) {
        return null;}
        var promiseElement = (TypeElement) promiseType.asElement();
        var promiseName = promiseElement.getSimpleName().toString();
        if ( !"Promise".equals(promiseName)) {
        return null;}
        var typeArgs = promiseType.getTypeArguments();
        if ( typeArgs.isEmpty()) {
        return null;}
        var innerMirror = typeArgs.getFirst();
        return resolveInnerType(innerMirror, typeUtils);
    }

    /// Extracts record component fields from a TypeElement.
    public static List<FieldInfo> extractFields(TypeElement typeElement) {
        return typeElement.getEnclosedElements().stream()
                                              .filter(e -> e.getKind() == ElementKind.RECORD_COMPONENT)
                                              .map(RecordComponentElement.class::cast)
                                              .map(rc -> new FieldInfo(rc.getSimpleName().toString(),
                                                                       rc.asType().toString()))
                                              .toList();
    }

    /// Extracts the TypeElement for the inner type of a resolved return, if it is a declared type.
    public static TypeElement innerTypeElement(TypeMirror returnType) {
        if ( ! (returnType instanceof DeclaredType promiseType)) {
        return null;}
        var typeArgs = promiseType.getTypeArguments();
        if ( typeArgs.isEmpty()) {
        return null;}
        var inner = typeArgs.getFirst();
        return unwrapToElement(inner);
    }

    private static ResolvedReturn resolveInnerType(TypeMirror inner, Types typeUtils) {
        if (inner instanceof DeclaredType declaredInner) {
            var innerElement = (TypeElement) declaredInner.asElement();
            var innerName = innerElement.getSimpleName().toString();
            var qualifiedName = innerElement.getQualifiedName().toString();
            return switch (innerName) {
                case "Unit" -> ResolvedReturn.ofUnit();
                case "Long" -> resolveMaybeBoxedLong(qualifiedName);
                case "Boolean" -> resolveMaybeBoxedBoolean(qualifiedName);
                case "Option" -> resolveOptionType(declaredInner);
                case "List" -> resolveListType(declaredInner);
                default -> resolveSingleDeclared(innerElement, qualifiedName);
            };
        }
        if (inner.getKind() == TypeKind.LONG) {
            return ResolvedReturn.ofSimpleScalar(MethodAnalyzer.ReturnKind.LONG, "Long");
        }
        if (inner.getKind() == TypeKind.BOOLEAN) {
            return ResolvedReturn.ofSimpleScalar(MethodAnalyzer.ReturnKind.BOOLEAN, "Boolean");
        }
        // Primitive array (e.g. byte[]) shows up as ArrayType, not DeclaredType.
        if (inner.getKind() == TypeKind.ARRAY) {
            var raw = inner.toString();
            return JavaTypeAccessor.forScalarReturn(raw)
                                   .fold(() -> null,
                                         accessor -> ResolvedReturn.ofScalar(
                                             MethodAnalyzer.ReturnKind.SINGLE, raw, raw, accessor, raw));
        }
        return null;
    }

    private static ResolvedReturn resolveSingleDeclared(TypeElement innerElement, String qualifiedName) {
        var innerName = innerElement.getSimpleName().toString();
        if (innerElement.getKind() == ElementKind.RECORD) {
            return ResolvedReturn.ofRecord(MethodAnalyzer.ReturnKind.SINGLE,
                                           nestAwareName(innerElement),
                                           innerName);
        }
        var accessorOpt = JavaTypeAccessor.forScalarReturn(qualifiedName);
        if (accessorOpt.isPresent()) {
            var accessor = accessorOpt.expect("checked with isPresent above");
            var displayName = accessor.importFqn().isEmpty() ? innerName : innerName;
            return ResolvedReturn.ofScalar(MethodAnalyzer.ReturnKind.SINGLE,
                                           displayName,
                                           innerName,
                                           accessor,
                                           qualifiedName);
        }
        // Fallback: treat as record-like. The caller (QueryAnnotationProcessor) will still run
        // field extraction, which yields an empty list for non-record types and surfaces as a
        // compile-time error via the generator's emitted source.
        return ResolvedReturn.ofRecord(MethodAnalyzer.ReturnKind.SINGLE,
                                       nestAwareName(innerElement),
                                       innerName);
    }

    private static ResolvedReturn resolveMaybeBoxedLong(String qualifiedName) {
        if ("java.lang.Long".equals(qualifiedName)) {
            return ResolvedReturn.ofSimpleScalar(MethodAnalyzer.ReturnKind.LONG, "Long");
        }
        return ResolvedReturn.ofSimpleScalar(MethodAnalyzer.ReturnKind.SINGLE, "Long");
    }

    private static ResolvedReturn resolveMaybeBoxedBoolean(String qualifiedName) {
        if ("java.lang.Boolean".equals(qualifiedName)) {
            return ResolvedReturn.ofSimpleScalar(MethodAnalyzer.ReturnKind.BOOLEAN, "Boolean");
        }
        return ResolvedReturn.ofSimpleScalar(MethodAnalyzer.ReturnKind.SINGLE, "Boolean");
    }

    private static ResolvedReturn resolveOptionType(DeclaredType optionType) {
        var optionArgs = optionType.getTypeArguments();
        if (optionArgs.isEmpty()) {
            return ResolvedReturn.ofOpaque(MethodAnalyzer.ReturnKind.OPTIONAL);
        }
        return resolveWrappedInner(optionArgs.getFirst(), MethodAnalyzer.ReturnKind.OPTIONAL);
    }

    private static ResolvedReturn resolveListType(DeclaredType listType) {
        var listArgs = listType.getTypeArguments();
        if (listArgs.isEmpty()) {
            return ResolvedReturn.ofOpaque(MethodAnalyzer.ReturnKind.LIST);
        }
        return resolveWrappedInner(listArgs.getFirst(), MethodAnalyzer.ReturnKind.LIST);
    }

    private static ResolvedReturn resolveWrappedInner(TypeMirror inner, MethodAnalyzer.ReturnKind kind) {
        if (inner.getKind() == TypeKind.ARRAY) {
            var raw = inner.toString();
            return JavaTypeAccessor.forScalarReturn(raw)
                                   .fold(() -> ResolvedReturn.ofRecord(kind, raw, raw),
                                         accessor -> ResolvedReturn.ofScalar(kind, raw, raw, accessor, raw));
        }
        var displayName = extractSimpleName(inner);
        var simpleName = extractBareSimpleName(inner);
        var raw = inner.toString();
        if (inner instanceof DeclaredType declared && declared.asElement() instanceof TypeElement te
            && te.getKind() == ElementKind.RECORD) {
            return ResolvedReturn.ofRecord(kind, displayName, simpleName);
        }
        var qualified = inner instanceof DeclaredType dt && dt.asElement() instanceof TypeElement te2
                        ? te2.getQualifiedName().toString()
                        : raw;
        var accessorOpt = JavaTypeAccessor.forScalarReturn(qualified);
        if (accessorOpt.isPresent()) {
            var accessor = accessorOpt.expect("checked with isPresent above");
            return ResolvedReturn.ofScalar(kind, simpleName, simpleName, accessor, qualified);
        }
        // Unknown inner type for wrapped kind: keep old semantics (record-like, needsMapper=true).
        return ResolvedReturn.ofRecord(kind, displayName, simpleName);
    }

    private static String extractSimpleName(TypeMirror mirror) {
        if ( mirror instanceof DeclaredType dt) {
        return nestAwareName((TypeElement) dt.asElement());}
        return mirror.toString();
    }

    /// Returns just the simple class name without enclosing type qualification.
    private static String extractBareSimpleName(TypeMirror mirror) {
        if ( mirror instanceof DeclaredType dt) {
        return ((TypeElement) dt.asElement()).getSimpleName()
                                             .toString();}
        return mirror.toString();
    }

    /// Returns the simple name for top-level types, or EnclosingSimple.InnerSimple for nested types.
    /// This ensures inner records like UserRepo.UserRow are referenced correctly in generated code.
    private static String nestAwareName(TypeElement element) {
        var enclosing = element.getEnclosingElement();
        if ( enclosing instanceof TypeElement enclosingType) {
        return enclosingType.getSimpleName().toString() + "." + element.getSimpleName().toString();}
        return element.getSimpleName().toString();
    }

    private static TypeElement unwrapToElement(TypeMirror mirror) {
        if ( mirror instanceof DeclaredType dt) {
            var innerElement = (TypeElement) dt.asElement();
            var innerName = innerElement.getSimpleName().toString();
            // Unwrap Option<T> and List<T>
            if ( "Option".equals(innerName) || "List".equals(innerName)) {
                var args = dt.getTypeArguments();
                if ( !args.isEmpty() && args.getFirst() instanceof DeclaredType nestedDt) {
                return (TypeElement) nestedDt.asElement();}
            }
            return innerElement;
        }
        return null;
    }

}
