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
                           List<ReactiveMethodBinding> reactive,
                           Option<KeyExtractorInfo> keyExtractor,
                           Option<MethodParameterInfo> multiParamKeyParam) {

    public record MethodParameterInfo(String name, TypeMirror type, boolean isKey) {}

    /// Generic binding for any method-level @ResourceQualifier annotation that is NOT an interceptor.
    /// The category is derived from the resource type's simple name, lowercased and hyphenated.
    public record ReactiveMethodBinding(ResourceQualifierModel qualifier, String category) {}

    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("^[a-z][a-zA-Z0-9]*$");
    private static final String KEY_ANNOTATION = "org.pragmatica.aether.resource.aspect.Key";
    private static final String PROMISE_TYPE = "org.pragmatica.lang.Promise";
    private static final String RESOURCE_QUALIFIER_ANNOTATION = "org.pragmatica.aether.slice.annotation.ResourceQualifier";
    private static final String SUBSCRIBER_TYPE = "org.pragmatica.aether.slice.Subscriber";
    private static final String SCHEDULED_TYPE = "org.pragmatica.aether.slice.Scheduled";
    private static final String STREAM_SUBSCRIBER_TYPE = "org.pragmatica.aether.slice.StreamSubscriber";
    private static final String PG_NOTIFICATION_SUBSCRIBER_TYPE = "org.pragmatica.aether.slice.PgNotificationSubscriber";
    private static final String CONFIGURATION_SECTION_TYPE = "org.pragmatica.aether.slice.annotation.ConfigurationSection";
    private static final String RATE_GUARD_TYPE = "org.pragmatica.aether.slice.RateGuard";
    private static final String PRINCIPAL_TYPE = "org.pragmatica.aether.http.handler.security.Principal";
    private static final String SECURITY_CONTEXT_TYPE = "org.pragmatica.aether.http.handler.security.SecurityContext";

    public MethodModel {
        interceptors = List.copyOf(interceptors);
        reactive = List.copyOf(reactive);
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

    /// Check if this method has any reactive bindings of the given category.
    public boolean hasReactiveOfCategory(String category) {
        return reactive.stream().anyMatch(r -> r.category().equals(category));
    }

    /// Get all reactive bindings of the given category.
    public List<ReactiveMethodBinding> reactiveOfCategory(String category) {
        return reactive.stream().filter(r -> r.category().equals(category)).toList();
    }

    /// Check if this method has any reactive bindings.
    public boolean hasAnyReactive() {
        return !reactive.isEmpty();
    }

    /// Check if this method has any topic subscriptions.
    public boolean hasSubscriptions() {
        return hasReactiveOfCategory("subscription");
    }

    /// Check if this method has any scheduled invocations.
    public boolean hasScheduled() {
        return hasReactiveOfCategory("scheduled");
    }

    /// Check if this method has any stream subscriptions.
    public boolean hasStreamSubscriptions() {
        return hasReactiveOfCategory("stream");
    }

    /// Check if this method has any PostgreSQL notification subscriptions.
    public boolean hasPgNotificationSubscriptions() {
        return hasReactiveOfCategory("pg-notification");
    }

    /// Check if this method has any config update subscriptions.
    public boolean hasConfigUpdateSubscriptions() {
        return hasReactiveOfCategory("config-update");
    }

    /// Check if a parameter is a security injection type (Principal or SecurityContext).
    public static boolean isSecurityParam(MethodParameterInfo param) {
        var typeName = param.type().toString();
        return typeName.equals(PRINCIPAL_TYPE) || typeName.equals(SECURITY_CONTEXT_TYPE);
    }

    /// Check if parameter is Principal type.
    public static boolean isPrincipalParam(MethodParameterInfo param) {
        return param.type().toString().equals(PRINCIPAL_TYPE);
    }

    /// Check if parameter is SecurityContext type.
    public static boolean isSecurityContextParam(MethodParameterInfo param) {
        return param.type().toString().equals(SECURITY_CONTEXT_TYPE);
    }

    /// Check if this method has any security injection parameters.
    public boolean hasSecurityParams() {
        return parameters.stream().anyMatch(MethodModel::isSecurityParam);
    }

    /// Get only the business parameters (excluding Principal/SecurityContext).
    public List<MethodParameterInfo> businessParameters() {
        return parameters.stream().filter(p -> !isSecurityParam(p)).toList();
    }

    /// Returns the business parameter type for single-business-param methods.
    /// Used by RouteSourceGenerator when security params are present.
    public TypeMirror businessParameterType() {
        var biz = businessParameters();
        if (biz.size() == 1) {
            return biz.getFirst().type();
        }
        throw new IllegalStateException("businessParameterType() called on method with " + biz.size() + " business params: " + name);
    }

    /// Returns true if the method parameter is List<T> (batch stream consumer).
    public boolean isBatchStreamConsumer() {
        if (!hasStreamSubscriptions() || parameters.size() != 1) {
            return false;
        }
        var paramType = parameters.getFirst().type();
        return paramType instanceof DeclaredType dt
               && dt.asElement().toString().equals("java.util.List");
    }

    /// Extract the stream event type from consumer method parameter.
    /// For single: parameter type T.
    /// For batch: element type T from List<T>.
    public Option<String> streamConsumerEventType() {
        if (!hasStreamSubscriptions() || parameters.size() != 1) {
            return Option.none();
        }
        var paramType = parameters.getFirst().type();
        if (paramType instanceof DeclaredType dt) {
            if (dt.asElement().toString().equals("java.util.List") && !dt.getTypeArguments().isEmpty()) {
                return Option.some(dt.getTypeArguments().getFirst().toString());
            }
            return Option.some(dt.toString());
        }
        return Option.none();
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
        var methodAnnotations = extractMethodAnnotations(method, env);
        var methodInterceptors = methodAnnotations.interceptors();
        var methodReactive = methodAnnotations.reactive();
        var paramInfos = buildParameterInfos(params, env);

        // Validate subscription methods
        var subscriptionBindings = methodReactive.stream()
            .filter(r -> r.category().equals("subscription"))
            .map(ReactiveMethodBinding::qualifier)
            .toList();
        var subscriptionValidation = validateSubscriptions(subscriptionBindings, paramInfos, name, returnType);
        if (subscriptionValidation.isFailure()) {
            return subscriptionValidation.flatMap(_ -> Result.success(null)); // propagate error
        }

        // Validate scheduled methods
        var scheduledBindings = methodReactive.stream()
            .filter(r -> r.category().equals("scheduled"))
            .map(ReactiveMethodBinding::qualifier)
            .toList();
        var scheduledValidation = validateScheduled(scheduledBindings, paramInfos, name, returnType);
        if (scheduledValidation.isFailure()) {
            return scheduledValidation.flatMap(_ -> Result.success(null)); // propagate error
        }

        // Validate stream subscription methods
        var streamBindings = methodReactive.stream()
            .filter(r -> r.category().equals("stream"))
            .map(ReactiveMethodBinding::qualifier)
            .toList();
        var streamSubValidation = validateStreamSubscriptions(streamBindings, paramInfos, name, returnType);
        if (streamSubValidation.isFailure()) {
            return streamSubValidation.flatMap(_ -> Result.success(null)); // propagate error
        }

        // Validate pg-notification subscription methods
        var pgNotifBindings = methodReactive.stream()
            .filter(r -> r.category().equals("pg-notification"))
            .map(ReactiveMethodBinding::qualifier)
            .toList();
        var pgNotifValidation = validatePgNotificationSubscriptions(pgNotifBindings, paramInfos, name, returnType);
        if (pgNotifValidation.isFailure()) {
            return pgNotifValidation.flatMap(_ -> Result.success(null)); // propagate error
        }

        // Validate config update subscription methods
        var configBindings = methodReactive.stream()
            .filter(r -> r.category().equals("config-update"))
            .map(ReactiveMethodBinding::qualifier)
            .toList();
        var configUpdateValidation = validateConfigUpdateSubscriptions(configBindings, paramInfos, name, returnType);
        if (configUpdateValidation.isFailure()) {
            return configUpdateValidation.flatMap(_ -> Result.success(null)); // propagate error
        }

        return validateKeyAnnotations(paramInfos, name)
        .flatMap(_ -> resolveKeyInfo(paramInfos, env, methodInterceptors, name))
        .map(keyResult -> new MethodModel(name,
                                           returnType,
                                           responseType,
                                           paramInfos,
                                           deprecated,
                                           methodInterceptors,
                                           methodReactive,
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
        return Result.unitResult();
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
        return Result.unitResult();
    }

    record MethodAnnotations(List<ResourceQualifierModel> interceptors,
                                      List<ReactiveMethodBinding> reactive) {}

    /// Check if a MethodAnnotations result has any reactive annotations.
    static boolean hasReactiveAnnotations(MethodAnnotations annotations) {
        return !annotations.reactive().isEmpty();
    }

    /// Extract method-level annotations with @ResourceQualifier meta-annotation.
    /// Splits them into interceptors and reactive bindings based on resource type.
    static MethodAnnotations extractMethodAnnotations(ExecutableElement method,
                                                      ProcessingEnvironment env) {
        var interceptors = new ArrayList<ResourceQualifierModel>();
        var reactive = new ArrayList<ReactiveMethodBinding>();
        for (var annotation : method.getAnnotationMirrors()) {
            ResourceQualifierModel.fromAnnotationMirror(annotation, env)
                                  .onPresent(model -> classifyAnnotation(model, interceptors, reactive));
        }
        return new MethodAnnotations(interceptors, reactive);
    }

    private static void classifyAnnotation(ResourceQualifierModel model,
                                            List<ResourceQualifierModel> interceptors,
                                            List<ReactiveMethodBinding> reactive) {
        var resourceType = model.resourceType().toString();
        var category = reactiveCategory(resourceType);
        if (category != null) {
            reactive.add(new ReactiveMethodBinding(model, category));
        } else {
            interceptors.add(model);
        }
    }

    private static String reactiveCategory(String resourceType) {
        return switch (resourceType) {
            case SUBSCRIBER_TYPE -> "subscription";
            case SCHEDULED_TYPE -> "scheduled";
            case STREAM_SUBSCRIBER_TYPE -> "stream";
            case PG_NOTIFICATION_SUBSCRIBER_TYPE -> "pg-notification";
            case CONFIGURATION_SECTION_TYPE -> "config-update";
            case RATE_GUARD_TYPE -> "rate-guard";
            default -> null;  // Not reactive — it's an interceptor
        };
    }

    private static Result<Unit> validateSubscriptions(List<ResourceQualifierModel> subscriptions,
                                                       List<MethodParameterInfo> params,
                                                       String methodName,
                                                       TypeMirror returnType) {
        if (subscriptions.isEmpty()) {
            return Result.unitResult();
        }
        // Subscription methods must have exactly one parameter
        if (params.size() != 1) {
            return Causes.cause("Subscription method '" + methodName
                                + "' must have exactly one parameter (the message type), found: " + params.size())
                         .result();
        }
        // Return type must be Promise<Unit>
        if (returnType instanceof DeclaredType dt && !dt.getTypeArguments().isEmpty()) {
            var typeArg = dt.getTypeArguments().getFirst().toString();
            if (!"org.pragmatica.lang.Unit".equals(typeArg)) {
                return Causes.cause("Subscription method '" + methodName
                                    + "' must return Promise<Unit>, found: Promise<" + typeArg + ">")
                             .result();
            }
        }
        return Result.unitResult();
    }

    private static Result<Unit> validateScheduled(List<ResourceQualifierModel> scheduled,
                                                    List<MethodParameterInfo> params,
                                                    String methodName,
                                                    TypeMirror returnType) {
        if (scheduled.isEmpty()) {
            return Result.unitResult();
        }
        // Scheduled methods must have zero parameters
        if (!params.isEmpty()) {
            return Causes.cause("Scheduled method '" + methodName
                                + "' must have zero parameters, found: " + params.size())
                         .result();
        }
        // Return type must be Promise<Unit>
        if (returnType instanceof DeclaredType dt && !dt.getTypeArguments().isEmpty()) {
            var typeArg = dt.getTypeArguments().getFirst().toString();
            if (!"org.pragmatica.lang.Unit".equals(typeArg)) {
                return Causes.cause("Scheduled method '" + methodName
                                    + "' must return Promise<Unit>, found: Promise<" + typeArg + ">")
                             .result();
            }
        }
        return Result.unitResult();
    }

    private static Result<Unit> validateStreamSubscriptions(List<ResourceQualifierModel> streamSubscriptions,
                                                              List<MethodParameterInfo> params,
                                                              String methodName,
                                                              TypeMirror returnType) {
        if (streamSubscriptions.isEmpty()) {
            return Result.unitResult();
        }
        // Stream subscription methods must have exactly one parameter (T or List<T>)
        if (params.size() != 1) {
            return Causes.cause("Stream subscription method '" + methodName
                                + "' must have exactly one parameter (the event type or List<event type>), found: " + params.size())
                         .result();
        }
        // Return type must be Promise<Unit>
        if (returnType instanceof DeclaredType dt && !dt.getTypeArguments().isEmpty()) {
            var typeArg = dt.getTypeArguments().getFirst().toString();
            if (!"org.pragmatica.lang.Unit".equals(typeArg)) {
                return Causes.cause("Stream subscription method '" + methodName
                                    + "' must return Promise<Unit>, found: Promise<" + typeArg + ">")
                             .result();
            }
        }
        return Result.unitResult();
    }

    private static Result<Unit> validatePgNotificationSubscriptions(List<ResourceQualifierModel> pgNotificationSubscriptions,
                                                                       List<MethodParameterInfo> params,
                                                                       String methodName,
                                                                       TypeMirror returnType) {
        if (pgNotificationSubscriptions.isEmpty()) {
            return Result.unitResult();
        }
        // PG notification subscription methods must have exactly one parameter (PgNotification)
        if (params.size() != 1) {
            return Causes.cause("PG notification subscription method '" + methodName
                                + "' must have exactly one parameter (PgNotification), found: " + params.size())
                         .result();
        }
        // Parameter type must be PgNotification
        var paramType = params.getFirst().type().toString();
        if (!"org.pragmatica.aether.slice.PgNotification".equals(paramType)) {
            return Causes.cause("PG notification subscription method '" + methodName
                                + "' parameter must be PgNotification, found: " + paramType)
                         .result();
        }
        // Return type must be Promise<Unit>
        if (returnType instanceof DeclaredType dt && !dt.getTypeArguments().isEmpty()) {
            var typeArg = dt.getTypeArguments().getFirst().toString();
            if (!"org.pragmatica.lang.Unit".equals(typeArg)) {
                return Causes.cause("PG notification subscription method '" + methodName
                                    + "' must return Promise<Unit>, found: Promise<" + typeArg + ">")
                             .result();
            }
        }
        return Result.unitResult();
    }

    private static Result<Unit> validateConfigUpdateSubscriptions(List<ResourceQualifierModel> configUpdateSubscriptions,
                                                                      List<MethodParameterInfo> params,
                                                                      String methodName,
                                                                      TypeMirror returnType) {
        if (configUpdateSubscriptions.isEmpty()) {
            return Result.unitResult();
        }
        // Config update methods must have exactly one parameter (the config record type)
        if (params.size() != 1) {
            return Causes.cause("Config update method '" + methodName
                                + "' must have exactly one parameter (the config record type), found: " + params.size())
                         .result();
        }
        // Return type must be Promise<Unit>
        if (returnType instanceof DeclaredType dt && !dt.getTypeArguments().isEmpty()) {
            var typeArg = dt.getTypeArguments().getFirst().toString();
            if (!"org.pragmatica.lang.Unit".equals(typeArg)) {
                return Causes.cause("Config update method '" + methodName
                                    + "' must return Promise<Unit>, found: Promise<" + typeArg + ">")
                             .result();
            }
        }
        return Result.unitResult();
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
