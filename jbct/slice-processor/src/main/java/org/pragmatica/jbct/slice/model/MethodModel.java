package org.pragmatica.jbct.slice.model;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.List;
import java.util.regex.Pattern;

public record MethodModel(String name,
                          TypeMirror returnType,
                          TypeMirror responseType,
                          TypeMirror parameterType,
                          String parameterName,
                          boolean deprecated,
                          AspectModel aspects) {
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("^[a-z][a-zA-Z0-9]*$");
    private static final String ASPECT_ANNOTATION = "org.pragmatica.aether.infra.aspect.Aspect";
    private static final String KEY_ANNOTATION = "org.pragmatica.aether.infra.aspect.Key";
    private static final String PROMISE_TYPE = "org.pragmatica.lang.Promise";

    public static Result<MethodModel> methodModel(ExecutableElement method, ProcessingEnvironment env) {
        var name = method.getSimpleName()
                         .toString();
        if (!METHOD_NAME_PATTERN.matcher(name)
                                .matches()) {
            return Causes.cause("Invalid slice method name '" + name
                                + "': must start with lowercase letter and contain only alphanumeric characters")
                         .result();
        }
        var returnType = method.getReturnType();
        return validatePromiseReturnType(returnType, name)
        .flatMap(_ -> validateAndBuildModel(method, env, name, returnType));
    }

    private static Result<MethodModel> validateAndBuildModel(ExecutableElement method,
                                                             ProcessingEnvironment env,
                                                             String name,
                                                             TypeMirror returnType) {
        var responseType = extractPromiseTypeArg(returnType);
        var params = method.getParameters();
        if (params.size() != 1) {
            return Causes.cause("Slice methods must have exactly one parameter: " + name)
                         .result();
        }
        var param = params.getFirst();
        var deprecated = method.getAnnotation(Deprecated.class) != null;
        return extractAspects(method, param, env)
        .map(aspects -> new MethodModel(name,
                                        returnType,
                                        responseType,
                                        param.asType(),
                                        param.getSimpleName()
                                             .toString(),
                                        deprecated,
                                        aspects));
    }

    private static Result<Unit> validatePromiseReturnType(TypeMirror returnType, String methodName) {
        if (! (returnType instanceof DeclaredType dt)) {
            return Causes.cause("Slice method '" + methodName + "' must return Promise<T>, found: " + returnType)
                         .result();
        }
        var typeElement = dt.asElement();
        if (! (typeElement instanceof TypeElement te)) {
            return Causes.cause("Slice method '" + methodName + "' must return Promise<T>, found: " + returnType)
                         .result();
        }
        var qualifiedName = te.getQualifiedName()
                              .toString();
        if (!qualifiedName.equals(PROMISE_TYPE)) {
            return Causes.cause("Slice method '" + methodName + "' must return Promise<T>, found: " + qualifiedName)
                         .result();
        }
        if (dt.getTypeArguments()
              .isEmpty()) {
            return Causes.cause("Slice method '" + methodName
                                + "' must return Promise<T> with type argument, found raw Promise")
                         .result();
        }
        return Result.success(Unit.unit());
    }

    private static Result<AspectModel> extractAspects(ExecutableElement method,
                                                      VariableElement param,
                                                      ProcessingEnvironment env) {
        return findAnnotationMirror(method, ASPECT_ANNOTATION)
        .fold(() -> Result.success(AspectModel.none()),
              mirror -> buildAspectModel(mirror, param, env));
    }

    private static Result<AspectModel> buildAspectModel(AnnotationMirror mirror,
                                                        VariableElement param,
                                                        ProcessingEnvironment env) {
        var kinds = extractAspectKinds(mirror);
        var hasCache = kinds.contains("CACHE");
        if (!hasCache) {
            return Result.success(new AspectModel(kinds, Option.none()));
        }
        return extractKeyInfo(param.asType(), env).map(keyInfo -> new AspectModel(kinds, keyInfo));
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
        var annotationType = mirror.getAnnotationType()
                                   .asElement();
        return annotationType instanceof TypeElement te &&
        te.getQualifiedName()
          .toString()
          .equals(annotationName);
    }

    private static List<String> extractAspectKinds(AnnotationMirror aspectMirror) {
        return aspectMirror.getElementValues()
                           .entrySet()
                           .stream()
                           .filter(entry -> entry.getKey()
                                                 .getSimpleName()
                                                 .toString()
                                                 .equals("value"))
                           .flatMap(entry -> extractKindsFromValue(entry.getValue()).stream())
                           .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractKindsFromValue(AnnotationValue annotationValue) {
        var value = annotationValue.getValue();
        if (! (value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                   .filter(AnnotationValue.class::isInstance)
                   .map(AnnotationValue.class::cast)
                   .map(AnnotationValue::getValue)
                   .filter(VariableElement.class::isInstance)
                   .map(VariableElement.class::cast)
                   .map(ve -> ve.getSimpleName()
                                .toString())
                   .toList();
    }

    private static Result<Option<KeyExtractorInfo>> extractKeyInfo(TypeMirror paramType,
                                                                   ProcessingEnvironment env) {
        if (! (paramType instanceof DeclaredType dt)) {
            return KeyExtractorInfo.identity(paramType.toString())
                                   .map(Option::some);
        }
        var element = dt.asElement();
        if (element.getKind() != ElementKind.RECORD) {
            return KeyExtractorInfo.identity(paramType.toString())
                                   .map(Option::some);
        }
        var typeElement = (TypeElement) element;
        var keyFields = findKeyAnnotatedFields(typeElement);
        if (keyFields.isEmpty()) {
            return KeyExtractorInfo.identity(paramType.toString())
                                   .map(Option::some);
        }
        if (keyFields.size() > 1) {
            return Causes.cause("Multiple @Key annotations found on " + typeElement.getSimpleName()
                                + ". Only one @Key field is allowed per record.")
                         .result();
        }
        return buildKeyExtractorFromField(keyFields.getFirst(), typeElement);
    }

    private static List<RecordComponentElement> findKeyAnnotatedFields(TypeElement typeElement) {
        return typeElement.getEnclosedElements()
                          .stream()
                          .filter(RecordComponentElement.class::isInstance)
                          .map(RecordComponentElement.class::cast)
                          .filter(MethodModel::hasKeyAnnotation)
                          .toList();
    }

    private static Result<Option<KeyExtractorInfo>> buildKeyExtractorFromField(RecordComponentElement keyField,
                                                                               TypeElement typeElement) {
        var keyType = keyField.asType()
                              .toString();
        var fieldName = keyField.getSimpleName()
                                .toString();
        var paramTypeName = typeElement.getQualifiedName()
                                       .toString();
        return KeyExtractorInfo.single(keyType, fieldName, paramTypeName)
                               .map(Option::some);
    }

    private static boolean hasKeyAnnotation(RecordComponentElement element) {
        return findAnnotationMirror(element, KEY_ANNOTATION).isPresent();
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
