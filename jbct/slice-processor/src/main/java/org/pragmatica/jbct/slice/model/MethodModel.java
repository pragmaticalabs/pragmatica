package org.pragmatica.jbct.slice.model;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public record MethodModel(String name,
                           TypeMirror returnType,
                           TypeMirror responseType,
                           List<MethodParameterInfo> parameters,
                           boolean deprecated,
                           List<ResourceQualifierModel> interceptors,
                           Option<KeyExtractorInfo> keyExtractor,
                           Option<MethodParameterInfo> multiParamKeyParam) {

    public record MethodParameterInfo(String name, TypeMirror type, boolean isKey) {}

    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("^[a-z][a-zA-Z0-9]*$");
    private static final String KEY_ANNOTATION = "org.pragmatica.aether.resource.aspect.Key";
    private static final String PROMISE_TYPE = "org.pragmatica.lang.Promise";
    private static final String RESOURCE_QUALIFIER_ANNOTATION = "org.pragmatica.aether.slice.annotation.ResourceQualifier";

    public MethodModel {
        interceptors = List.copyOf(interceptors);
        parameters = List.copyOf(parameters);
    }

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

    /// Check if this method has any interceptors.
    public boolean hasInterceptors() {
        return !interceptors.isEmpty();
    }

    /// Returns true if this method has zero parameters.
    public boolean hasNoParams() {
        return parameters.isEmpty();
    }

    /// Returns true if this method has exactly one parameter.
    public boolean hasSingleParam() {
        return parameters.size() == 1;
    }

    /// Returns true if this method has more than one parameter.
    public boolean hasMultipleParams() {
        return parameters.size() > 1;
    }

    /// Returns the single parameter type (for backwards compatibility with single-param methods).
    /// For 0-param methods returns "org.pragmatica.lang.Unit".
    /// For multi-param methods, this should not be called — use parameters() instead.
    public String effectiveRequestType() {
        if (hasNoParams()) {
            return "org.pragmatica.lang.Unit";
        }
        if (hasSingleParam()) {
            return parameters.getFirst().type().toString();
        }
        throw new IllegalStateException("effectiveRequestType() called on multi-param method: " + name);
    }

    /// Returns the parameter type for single-param methods.
    /// Used by RouteSourceGenerator for route generation.
    public TypeMirror parameterType() {
        if (hasSingleParam()) {
            return parameters.getFirst().type();
        }
        throw new IllegalStateException("parameterType() called on method with " + parameters.size() + " params: " + name);
    }

    /// Returns the parameter name for single-param methods.
    /// Used by RouteSourceGenerator for route generation.
    public String parameterName() {
        if (hasSingleParam()) {
            return parameters.getFirst().name();
        }
        throw new IllegalStateException("parameterName() called on method with " + parameters.size() + " params: " + name);
    }

    private static Result<MethodModel> validateAndBuildModel(ExecutableElement method,
                                                              ProcessingEnvironment env,
                                                              String name,
                                                              TypeMirror returnType) {
        var responseType = extractPromiseTypeArg(returnType);
        var params = method.getParameters();
        var deprecated = method.getAnnotation(Deprecated.class) != null;
        var methodInterceptors = extractMethodInterceptors(method, env);
        var paramInfos = buildParameterInfos(params, env);
        return validateKeyAnnotations(paramInfos, name)
        .flatMap(_ -> resolveKeyInfo(paramInfos, env, methodInterceptors, name))
        .map(keyResult -> new MethodModel(name,
                                           returnType,
                                           responseType,
                                           paramInfos,
                                           deprecated,
                                           methodInterceptors,
                                           keyResult.keyExtractor(),
                                           keyResult.multiParamKeyParam()));
    }

    private record KeyResolution(Option<KeyExtractorInfo> keyExtractor,
                                  Option<MethodParameterInfo> multiParamKeyParam) {}

    private static List<MethodParameterInfo> buildParameterInfos(
        List<? extends javax.lang.model.element.VariableElement> params,
        ProcessingEnvironment env) {
        var result = new ArrayList<MethodParameterInfo>();
        for (var param : params) {
            var isKey = hasKeyAnnotationOnParam(param);
            result.add(new MethodParameterInfo(param.getSimpleName().toString(), param.asType(), isKey));
        }
        return result;
    }

    private static boolean hasKeyAnnotationOnParam(javax.lang.model.element.VariableElement param) {
        return param.getAnnotationMirrors()
                    .stream()
                    .anyMatch(mirror -> isAnnotationType(mirror, KEY_ANNOTATION));
    }

    private static Result<Unit> validateKeyAnnotations(List<MethodParameterInfo> paramInfos, String methodName) {
        var keyCount = paramInfos.stream().filter(MethodParameterInfo::isKey).count();
        if (keyCount > 1) {
            return Causes.cause("Multiple @Key annotations found on method '" + methodName
                                + "'. Only one @Key is allowed per method.")
                         .result();
        }
        return Result.success(Unit.unit());
    }

    private static Result<KeyResolution> resolveKeyInfo(List<MethodParameterInfo> paramInfos,
                                                         ProcessingEnvironment env,
                                                         List<ResourceQualifierModel> interceptors,
                                                         String methodName) {
        if (interceptors.isEmpty()) {
            return Result.success(new KeyResolution(Option.none(), Option.none()));
        }

        if (paramInfos.isEmpty()) {
            return Result.success(new KeyResolution(Option.none(), Option.none()));
        }

        if (paramInfos.size() == 1) {
            var param = paramInfos.getFirst();
            if (param.isKey()) {
                return Result.success(new KeyResolution(Option.none(), Option.none()));
            }
            return extractKeyInfoFromRecord(param.type(), env, interceptors)
            .map(keyInfo -> new KeyResolution(keyInfo, Option.none()));
        }

        // Multi-param: check for @Key on parameter
        var keyParam = paramInfos.stream().filter(MethodParameterInfo::isKey).findFirst();
        if (keyParam.isPresent()) {
            return Result.success(new KeyResolution(Option.none(), Option.some(keyParam.get())));
        }
        return Result.success(new KeyResolution(Option.none(), Option.none()));
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

    /// Extract method-level interceptors from annotations with @ResourceQualifier meta-annotation.
    /// Annotations are processed in declaration order; the generated interceptor composition
    /// wraps inside-out (last annotation = innermost).
    private static List<ResourceQualifierModel> extractMethodInterceptors(ExecutableElement method,
                                                                           ProcessingEnvironment env) {
        var interceptors = new ArrayList<ResourceQualifierModel>();
        for (var annotation : method.getAnnotationMirrors()) {
            var rq = ResourceQualifierModel.fromAnnotationMirror(annotation, env);
            rq.onPresent(interceptors::add);
        }
        return interceptors;
    }

    /// Extract @Key info from the method parameter record, but only if interceptors are present.
    /// If no interceptors, keyExtractor is always none.
    /// Key extractors are only generated from explicit @Key annotations on record components.
    private static Result<Option<KeyExtractorInfo>> extractKeyInfoFromRecord(TypeMirror paramType,
                                                                              ProcessingEnvironment env,
                                                                              List<ResourceQualifierModel> interceptors) {
        if (interceptors.isEmpty()) {
            return Result.success(Option.none());
        }
        if (! (paramType instanceof DeclaredType dt)) {
            return Result.success(Option.none());
        }
        var element = dt.asElement();
        if (element.getKind() != ElementKind.RECORD) {
            return Result.success(Option.none());
        }
        var typeElement = (TypeElement) element;
        var keyFields = findKeyAnnotatedFields(typeElement);
        if (keyFields.isEmpty()) {
            return Result.success(Option.none());
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
