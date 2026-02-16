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
                           TypeMirror parameterType,
                           String parameterName,
                           boolean deprecated,
                           List<ResourceQualifierModel> interceptors,
                           Option<KeyExtractorInfo> keyExtractor) {
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("^[a-z][a-zA-Z0-9]*$");
    private static final String KEY_ANNOTATION = "org.pragmatica.aether.resource.aspect.Key";
    private static final String PROMISE_TYPE = "org.pragmatica.lang.Promise";
    private static final String RESOURCE_QUALIFIER_ANNOTATION = "org.pragmatica.aether.slice.annotation.ResourceQualifier";

    public MethodModel {
        interceptors = List.copyOf(interceptors);
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
        var methodInterceptors = extractMethodInterceptors(method, env);
        return extractKeyInfo(param.asType(), env, methodInterceptors)
        .map(keyInfo -> new MethodModel(name,
                                         returnType,
                                         responseType,
                                         param.asType(),
                                         param.getSimpleName()
                                              .toString(),
                                         deprecated,
                                         methodInterceptors,
                                         keyInfo));
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

    /// Extract @Key info from the method parameter, but only if interceptors are present.
    /// If no interceptors, keyExtractor is always none.
    /// Key extractors are only generated from explicit @Key annotations on record components.
    private static Result<Option<KeyExtractorInfo>> extractKeyInfo(TypeMirror paramType,
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
