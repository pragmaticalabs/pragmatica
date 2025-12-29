package org.pragmatica.jbct.slice.model;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

public record MethodModel(
 String name,
 TypeMirror returnType,
 TypeMirror responseType,
 TypeMirror parameterType,
 String parameterName,
 boolean deprecated) {
    public static MethodModel from(ExecutableElement method) {
        var name = method.getSimpleName()
                         .toString();
        var returnType = method.getReturnType();
        var responseType = extractPromiseTypeArg(returnType);
        var params = method.getParameters();
        if (params.size() != 1) {
            throw new IllegalStateException(
            "Slice methods must have exactly one parameter: " + name);
        }
        var param = params.getFirst();
        var deprecated = method.getAnnotation(Deprecated.class) != null;
        return new MethodModel(
        name,
        returnType,
        responseType,
        param.asType(),
        param.getSimpleName()
             .toString(),
        deprecated);
    }

    private static TypeMirror extractPromiseTypeArg(TypeMirror returnType) {
        if (returnType instanceof DeclaredType dt) {
            var typeArgs = dt.getTypeArguments();
            if (!typeArgs.isEmpty()) {
                return typeArgs.getFirst();
            }
        }
        return returnType;
    }
}
