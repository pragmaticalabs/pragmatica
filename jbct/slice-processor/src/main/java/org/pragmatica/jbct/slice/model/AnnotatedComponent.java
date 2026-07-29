// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.model;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// A single record component carrying a marker annotation, resolved from its declaring record.
///
/// This is the ONE resolution mechanism for every "which component of this record is the key"
/// question in the processor. Two annotations use it:
///
///   - `@Key` — the request component an interceptor derives its cache / idempotency key from
///     ([MethodModel]).
///   - `@PartitionKey` — the stream event component the runtime hashes to pick a partition
///     ([DependencyModel]).
///
/// Both resolve identically, so they share this code rather than each growing a private copy that
/// can drift.
///
/// @param ownerQualifiedName Qualified name of the declaring record
/// @param componentName      Name of the annotated record component
/// @param componentTypeName  Qualified type name of the annotated record component
public record AnnotatedComponent(String ownerQualifiedName, String componentName, String componentTypeName) {
    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("^[a-zA-Z_$][a-zA-Z0-9_$]*$");

    /// Locate the single component of `type` annotated with `annotationName`.
    ///
    /// A type that is not a record yields `Option.none()` — both annotations target
    /// `ElementType.RECORD_COMPONENT`, so javac already rejects them anywhere else, and a keyless
    /// carrier is a legitimate shape (it simply keeps its keyless default).
    ///
    /// More than one annotated component is a hard failure rather than a silent first-wins pick:
    /// the choice would otherwise be decided by declaration order, and the resulting mis-routing
    /// (wrong partition, wrong cache key) is invisible at both compile time and run time.
    public static Result<Option<AnnotatedComponent>> annotatedComponent(TypeMirror type, String annotationName) {
        if (! (type instanceof DeclaredType declaredType)) {
            return Result.success(Option.none());
        }

        var element = declaredType.asElement();

        if (element.getKind() != ElementKind.RECORD) {
            return Result.success(Option.none());
        }

        var typeElement = (TypeElement) element;
        var annotated = annotatedComponents(typeElement, annotationName);

        if (annotated.isEmpty()) {
            return Result.success(Option.none());
        }

        if (annotated.size() > 1) {
            return multipleAnnotations(typeElement, annotationName, annotated).result();
        }

        return build(typeElement, annotated.getFirst()).map(Option::some);
    }

    /// Method reference to this component, with the owner type rendered as `ownerName`.
    /// Callers pass the name that is valid at the emission site — the qualified name, or the
    /// simple name when the owner has been registered with an import tracker.
    public String methodReference(String ownerName) {
        return ownerName + "::" + componentName;
    }

    /// The component's type as a REFERENCE type. A record component may legally be declared with a
    /// primitive type (`record Event(@PartitionKey long tenantId, ...)`), but the emitted extractor
    /// cast uses it as a generic type argument (`Fn1<K, T>`), which rejects primitives — so the
    /// emission site needs the boxed name or the generated file will not compile.
    public String boxedComponentTypeName() {
        return switch (componentTypeName) {
            case "boolean" -> "java.lang.Boolean";
            case "byte" -> "java.lang.Byte";
            case "char" -> "java.lang.Character";
            case "short" -> "java.lang.Short";
            case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            case "float" -> "java.lang.Float";
            case "double" -> "java.lang.Double";
            default -> componentTypeName;
        };
    }

    private static Result<AnnotatedComponent> build(TypeElement typeElement, RecordComponentElement component) {
        var componentName = component.getSimpleName().toString();

        if (!JAVA_IDENTIFIER.matcher(componentName).matches()) {
            return Causes.cause("Invalid record component name on " + typeElement.getQualifiedName()
                               + ": " + componentName).result();
        }

        return Result.success(new AnnotatedComponent(typeElement.getQualifiedName().toString(),
                                                     componentName,
                                                     component.asType().toString()));
    }

    private static List<RecordComponentElement> annotatedComponents(TypeElement typeElement, String annotationName) {
        return typeElement.getEnclosedElements()
                          .stream()
                          .filter(RecordComponentElement.class::isInstance)
                          .map(RecordComponentElement.class::cast)
                          .filter(component -> findAnnotationMirror(component, annotationName).isPresent())
                          .toList();
    }

    private static Cause multipleAnnotations(TypeElement typeElement,
                                              String annotationName,
                                              List<RecordComponentElement> annotated) {
        var marker = "@" + simpleName(annotationName);

        return Causes.cause("Multiple " + marker + " annotations found on "
                           + typeElement.getQualifiedName() + ": " + componentNames(annotated)
                           + ". Only one " + marker + " component is allowed per record.");
    }

    private static String componentNames(List<RecordComponentElement> annotated) {
        return annotated.stream()
                        .map(component -> component.getSimpleName().toString())
                        .collect(Collectors.joining(", "));
    }

    private static String simpleName(String annotationName) {
        var lastDot = annotationName.lastIndexOf('.');

        return lastDot >= 0
               ? annotationName.substring(lastDot + 1)
               : annotationName;
    }

    private static Option<AnnotationMirror> findAnnotationMirror(Element element, String annotationName) {
        return element.getAnnotationMirrors()
                      .stream()
                      .filter(mirror -> isAnnotationType(mirror, annotationName))
                      .findFirst()
                      .map(Option::some)
                      .orElse(Option.none());
    }

    private static boolean isAnnotationType(AnnotationMirror mirror, String annotationName) {
        var annotationType = mirror.getAnnotationType().asElement();

        return annotationType instanceof TypeElement typeElement
               && typeElement.getQualifiedName()
                             .toString()
                             .equals(annotationName);
    }
}
