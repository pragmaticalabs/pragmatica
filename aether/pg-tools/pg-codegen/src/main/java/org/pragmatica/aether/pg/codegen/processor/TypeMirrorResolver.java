package org.pragmatica.aether.pg.codegen.processor;

import javax.lang.model.element.Element;
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
public final class TypeMirrorResolver {
    private TypeMirrorResolver() {}

    /// Resolved return type information extracted from a TypeMirror.
    ///
    /// `innerTypeName` is the name used in generated code (may include enclosing type for inner records).
    /// `simpleTypeName` is the bare class name used for table name derivation.
    public record ResolvedReturn(
        MethodAnalyzer.ReturnKind kind,
        String innerTypeName,
        String simpleTypeName,
        boolean needsMapper
    ) {}

    /// Record field extracted from a TypeElement.
    public record FieldInfo(String name, String typeName) {}

    /// Resolves a method return type (expected to be Promise<...>) to ReturnKind + inner type name.
    public static ResolvedReturn resolve(TypeMirror returnType, Types typeUtils) {
        if (!(returnType instanceof DeclaredType promiseType)) {
            return null;
        }

        var promiseElement = (TypeElement) promiseType.asElement();
        var promiseName = promiseElement.getSimpleName().toString();

        if (!"Promise".equals(promiseName)) {
            return null;
        }

        var typeArgs = promiseType.getTypeArguments();
        if (typeArgs.isEmpty()) {
            return null;
        }

        var innerMirror = typeArgs.getFirst();
        return resolveInnerType(innerMirror, typeUtils);
    }

    /// Extracts record component fields from a TypeElement.
    public static List<FieldInfo> extractFields(TypeElement typeElement) {
        return typeElement.getEnclosedElements().stream()
            .filter(e -> e.getKind() == ElementKind.RECORD_COMPONENT)
            .map(RecordComponentElement.class::cast)
            .map(rc -> new FieldInfo(rc.getSimpleName().toString(), rc.asType().toString()))
            .toList();
    }

    /// Extracts the TypeElement for the inner type of a resolved return, if it is a declared type.
    public static TypeElement innerTypeElement(TypeMirror returnType) {
        if (!(returnType instanceof DeclaredType promiseType)) {
            return null;
        }

        var typeArgs = promiseType.getTypeArguments();
        if (typeArgs.isEmpty()) {
            return null;
        }

        var inner = typeArgs.getFirst();
        return unwrapToElement(inner);
    }

    private static ResolvedReturn resolveInnerType(TypeMirror inner, Types typeUtils) {
        if (inner instanceof DeclaredType declaredInner) {
            var innerElement = (TypeElement) declaredInner.asElement();
            var innerName = innerElement.getSimpleName().toString();
            var qualifiedName = innerElement.getQualifiedName().toString();

            return switch (innerName) {
                case "Unit" -> new ResolvedReturn(MethodAnalyzer.ReturnKind.UNIT, "Unit", "Unit", false);
                case "Long" -> resolveMaybeBoxedLong(qualifiedName);
                case "Boolean" -> resolveMaybeBoxedBoolean(qualifiedName);
                case "Option" -> resolveOptionType(declaredInner);
                case "List" -> resolveListType(declaredInner);
                default -> new ResolvedReturn(MethodAnalyzer.ReturnKind.SINGLE, nestAwareName(innerElement), innerName, true);
            };
        }

        if (inner.getKind() == TypeKind.LONG) {
            return new ResolvedReturn(MethodAnalyzer.ReturnKind.LONG, "Long", "Long", false);
        }
        if (inner.getKind() == TypeKind.BOOLEAN) {
            return new ResolvedReturn(MethodAnalyzer.ReturnKind.BOOLEAN, "Boolean", "Boolean", false);
        }

        return null;
    }

    private static ResolvedReturn resolveMaybeBoxedLong(String qualifiedName) {
        if ("java.lang.Long".equals(qualifiedName)) {
            return new ResolvedReturn(MethodAnalyzer.ReturnKind.LONG, "Long", "Long", false);
        }
        return new ResolvedReturn(MethodAnalyzer.ReturnKind.SINGLE, "Long", "Long", false);
    }

    private static ResolvedReturn resolveMaybeBoxedBoolean(String qualifiedName) {
        if ("java.lang.Boolean".equals(qualifiedName)) {
            return new ResolvedReturn(MethodAnalyzer.ReturnKind.BOOLEAN, "Boolean", "Boolean", false);
        }
        return new ResolvedReturn(MethodAnalyzer.ReturnKind.SINGLE, "Boolean", "Boolean", false);
    }

    private static ResolvedReturn resolveOptionType(DeclaredType optionType) {
        var optionArgs = optionType.getTypeArguments();
        if (optionArgs.isEmpty()) {
            return new ResolvedReturn(MethodAnalyzer.ReturnKind.OPTIONAL, "Object", "Object", true);
        }
        var optionInner = optionArgs.getFirst();
        var displayName = extractSimpleName(optionInner);
        var simpleName = extractBareSimpleName(optionInner);
        return new ResolvedReturn(MethodAnalyzer.ReturnKind.OPTIONAL, displayName, simpleName, isComplexType(simpleName));
    }

    private static ResolvedReturn resolveListType(DeclaredType listType) {
        var listArgs = listType.getTypeArguments();
        if (listArgs.isEmpty()) {
            return new ResolvedReturn(MethodAnalyzer.ReturnKind.LIST, "Object", "Object", true);
        }
        var listInner = listArgs.getFirst();
        var displayName = extractSimpleName(listInner);
        var simpleName = extractBareSimpleName(listInner);
        return new ResolvedReturn(MethodAnalyzer.ReturnKind.LIST, displayName, simpleName, isComplexType(simpleName));
    }

    private static String extractSimpleName(TypeMirror mirror) {
        if (mirror instanceof DeclaredType dt) {
            return nestAwareName((TypeElement) dt.asElement());
        }
        return mirror.toString();
    }

    /// Returns just the simple class name without enclosing type qualification.
    private static String extractBareSimpleName(TypeMirror mirror) {
        if (mirror instanceof DeclaredType dt) {
            return ((TypeElement) dt.asElement()).getSimpleName().toString();
        }
        return mirror.toString();
    }

    /// Returns the simple name for top-level types, or EnclosingSimple.InnerSimple for nested types.
    /// This ensures inner records like UserRepo.UserRow are referenced correctly in generated code.
    private static String nestAwareName(TypeElement element) {
        var enclosing = element.getEnclosingElement();
        if (enclosing instanceof TypeElement enclosingType) {
            return enclosingType.getSimpleName().toString() + "." + element.getSimpleName().toString();
        }
        return element.getSimpleName().toString();
    }

    private static TypeElement unwrapToElement(TypeMirror mirror) {
        if (mirror instanceof DeclaredType dt) {
            var innerElement = (TypeElement) dt.asElement();
            var innerName = innerElement.getSimpleName().toString();

            // Unwrap Option<T> and List<T>
            if ("Option".equals(innerName) || "List".equals(innerName)) {
                var args = dt.getTypeArguments();
                if (!args.isEmpty() && args.getFirst() instanceof DeclaredType nestedDt) {
                    return (TypeElement) nestedDt.asElement();
                }
            }
            return innerElement;
        }
        return null;
    }

    private static boolean isComplexType(String typeName) {
        return switch (typeName) {
            case "String", "Long", "Integer", "Boolean", "Double", "Float", "Short", "Byte", "Unit" -> false;
            default -> true;
        };
    }
}
