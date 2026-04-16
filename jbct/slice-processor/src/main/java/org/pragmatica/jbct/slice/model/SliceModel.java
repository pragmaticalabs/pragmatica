// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice.model;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

public record SliceModel(String packageName,
                          String simpleName,
                          String qualifiedName,
                          List<MethodModel> methods,
                          List<DependencyModel> dependencies,
                          ExecutableElement factoryMethod,
                          FactoryReturnKind factoryReturnKind,
                          List<PlainInterfaceModel> plainInterfaceModels) {
    public enum FactoryReturnKind { DIRECT, RESULT, OPTION, PROMISE }

    public SliceModel {
        methods = List.copyOf(methods);
        dependencies = List.copyOf(dependencies);
        plainInterfaceModels = List.copyOf(plainInterfaceModels);
    }

    public static Result<SliceModel> sliceModel(TypeElement element, ProcessingEnvironment env) {
        var packageName = env.getElementUtils()
                             .getPackageOf(element)
                             .getQualifiedName()
                             .toString();
        var simpleName = element.getSimpleName()
                                .toString();
        var qualifiedName = element.getQualifiedName()
                                   .toString();
        return extractMethods(element, env)
        .flatMap(methods -> validateNoOverloads(methods)
        .flatMap(_ -> findFactoryMethod(element, simpleName)
        .flatMap(factoryMethod -> validateReturnType(factoryMethod, qualifiedName)
        .flatMap(_2 -> extractDependencies(factoryMethod, env)
        .flatMap(dependencies -> validateNoDuplicateResources(dependencies)
        .map(_3 -> assembleSliceModel(packageName, simpleName, qualifiedName, methods, dependencies,
                                      factoryMethod, env)))))));
    }

    private static SliceModel assembleSliceModel(String packageName, String simpleName, String qualifiedName,
                                                  List<MethodModel> methods, List<DependencyModel> dependencies,
                                                  ExecutableElement factoryMethod, ProcessingEnvironment env) {
        var plainModels = buildPlainInterfaceModels(dependencies, env);
        return new SliceModel(packageName, simpleName, qualifiedName, methods, dependencies, factoryMethod,
                              detectReturnKind(factoryMethod), plainModels);
    }

    /// Pair of step parameter name and its annotated method.
    public record TransitiveMethod(String stepParamName, MethodModel method) {
        public String qualifiedMethodName() {
            return stepParamName + capitalize(method.name());
        }

        private static String capitalize(String name) {
            if (name == null || name.isEmpty()) {
                return "";
            }
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
    }

    /// Check if any plain interface has annotated methods at all.
    public boolean hasTransitiveReactive() {
        return plainInterfaceModels.stream()
                                   .anyMatch(PlainInterfaceModel::hasAnnotatedMethods);
    }

    /// Get all transitive reactive methods with qualified names.
    public List<TransitiveMethod> transitiveReactiveMethods() {
        return plainInterfaceModels.stream()
            .filter(PlainInterfaceModel::hasAnnotatedMethods)
            .flatMap(step -> step.annotatedMethods().stream()
                .filter(MethodModel::hasAnyReactive)
                .map(m -> new TransitiveMethod(step.parameterName(), m)))
            .toList();
    }

    /// Get all transitive methods matching a specific reactive category.
    public List<TransitiveMethod> transitiveMethodsOfCategory(String category) {
        return transitiveReactiveMethods().stream()
            .filter(tm -> tm.method().hasReactiveOfCategory(category))
            .toList();
    }

    /// Check if any plain interface dependency has transitive subscription methods.
    public boolean hasTransitiveSubscriptions() {
        return !transitiveMethodsOfCategory("subscription").isEmpty();
    }

    /// Get all transitive subscription methods with qualified names.
    public List<TransitiveMethod> transitiveSubscriptionMethods() {
        return transitiveMethodsOfCategory("subscription");
    }

    /// Check if any plain interface dependency has transitive scheduled methods.
    public boolean hasTransitiveScheduled() {
        return !transitiveMethodsOfCategory("scheduled").isEmpty();
    }

    /// Get all transitive scheduled methods with qualified names.
    public List<TransitiveMethod> transitiveScheduledMethods() {
        return transitiveMethodsOfCategory("scheduled");
    }

    /// Check if any plain interface dependency has transitive stream subscription methods.
    public boolean hasTransitiveStreamSubscriptions() {
        return !transitiveMethodsOfCategory("stream").isEmpty();
    }

    /// Get all transitive stream subscription methods with qualified names.
    public List<TransitiveMethod> transitiveStreamSubscriptionMethods() {
        return transitiveMethodsOfCategory("stream");
    }

    /// Check if any plain interface dependency has transitive pg-notification subscription methods.
    public boolean hasTransitivePgNotificationSubscriptions() {
        return !transitiveMethodsOfCategory("pg-notification").isEmpty();
    }

    /// Get all transitive pg-notification subscription methods with qualified names.
    public List<TransitiveMethod> transitivePgNotificationSubscriptionMethods() {
        return transitiveMethodsOfCategory("pg-notification");
    }

    /// Check if any plain interface dependency has transitive config update subscription methods.
    public boolean hasTransitiveConfigUpdateSubscriptions() {
        return !transitiveMethodsOfCategory("config-update").isEmpty();
    }

    /// Get all transitive config update subscription methods with qualified names.
    public List<TransitiveMethod> transitiveConfigUpdateMethods() {
        return transitiveMethodsOfCategory("config-update");
    }

    /// Check if any plain interface has annotated methods at all.
    public boolean hasTransitiveAnnotatedMethods() {
        return plainInterfaceModels.stream()
                                   .anyMatch(PlainInterfaceModel::hasAnnotatedMethods);
    }

    /// Build PlainInterfaceModel instances for plain interface dependencies,
    /// scanning their methods for reactive annotations (subscriptions, scheduled, etc.).
    private static List<PlainInterfaceModel> buildPlainInterfaceModels(List<DependencyModel> dependencies,
                                                                        ProcessingEnvironment env) {
        var result = new ArrayList<PlainInterfaceModel>();
        for (var dep : dependencies) {
            if (!dep.isPlainInterface()) {
                continue;
            }
            var annotatedMethods = scanPlainInterfaceAnnotatedMethods(dep, env);
            var factoryMethodName = Character.toLowerCase(dep.interfaceSimpleName().charAt(0))
                                   + dep.interfaceSimpleName().substring(1);
            result.add(new PlainInterfaceModel(dep.interfaceLocalName(), factoryMethodName,
                                               dep.parameterName(), List.of(), annotatedMethods));
        }
        return result;
    }

    /// Scan a plain interface's non-static, non-default methods for reactive annotations.
    private static List<MethodModel> scanPlainInterfaceAnnotatedMethods(DependencyModel dep,
                                                                         ProcessingEnvironment env) {
        var typeElement = env.getElementUtils().getTypeElement(dep.interfaceQualifiedName());
        if (typeElement == null) {
            return List.of();
        }
        var annotated = new ArrayList<MethodModel>();
        for (var enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            var method = (ExecutableElement) enclosed;
            if (method.getModifiers().contains(Modifier.STATIC) || method.getModifiers().contains(Modifier.DEFAULT)) {
                continue;
            }
            var annotations = MethodModel.extractMethodAnnotations(method, env);
            if (MethodModel.hasReactiveAnnotations(annotations)) {
                MethodModel.methodModel(method, env)
                           .onSuccess(annotated::add);
            }
        }
        return annotated;
    }

    private static Result<List<MethodModel>> extractMethods(TypeElement element, ProcessingEnvironment env) {
        var results = element.getEnclosedElements()
                             .stream()
                             .filter(e -> e.getKind() == ElementKind.METHOD)
                             .map(e -> (ExecutableElement) e)
                             .filter(m -> !m.getModifiers()
                                            .contains(Modifier.STATIC))
                             .filter(m -> !m.getModifiers()
                                            .contains(Modifier.DEFAULT))
                             .map(m -> MethodModel.methodModel(m, env))
                             .toList();
        return Result.allOf(results);
    }

    private static Result<Unit> validateNoOverloads(List<MethodModel> methods) {
        var duplicateNames = methods.stream()
                                    .collect(Collectors.groupingBy(MethodModel::name, Collectors.counting()))
                                    .entrySet()
                                    .stream()
                                    .filter(e -> e.getValue() > 1)
                                    .map(java.util.Map.Entry::getKey)
                                    .toList();
        if (!duplicateNames.isEmpty()) {
            return Causes.cause("Overloaded slice methods not supported: "
                                + String.join(", ", duplicateNames)
                                + ". Use distinct method names — runtime dispatches by name.")
                         .result();
        }
        return Result.unitResult();
    }

    private static Result<ExecutableElement> findFactoryMethod(TypeElement element, String simpleName) {
        var expectedName = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
        return element.getEnclosedElements()
                      .stream()
                      .filter(e -> e.getKind() == ElementKind.METHOD)
                      .map(e -> (ExecutableElement) e)
                      .filter(m -> m.getModifiers()
                                    .contains(Modifier.STATIC))
                      .filter(m -> m.getSimpleName()
                                    .toString()
                                    .equals(expectedName))
                      .findFirst()
                      .map(Result::success)
                      .orElseGet(() -> Causes.cause("No factory method found: " + expectedName + "(...)")
                                             .result());
    }

    private static Result<List<DependencyModel>> extractDependencies(ExecutableElement factoryMethod,
                                                                      ProcessingEnvironment env) {
        var results = factoryMethod.getParameters()
                                   .stream()
                                   .map(param -> DependencyModel.dependencyModel(param, env))
                                   .toList();
        return Result.allOf(results);
    }

    /// Validate that no two resource dependencies share the same (type, config) pair.
    private static Result<Unit> validateNoDuplicateResources(List<DependencyModel> dependencies) {
        var seen = new HashMap<String, String>(); // key: "type:config" -> paramName
        for (var dep : dependencies) {
            if (dep.isResource()) {
                var result = checkDuplicateResource(dep, seen);
                if (result.isFailure()) {
                    return result;
                }
            }
        }
        return Result.unitResult();
    }

    private static Result<Unit> checkDuplicateResource(DependencyModel dep, HashMap<String, String> seen) {
        return dep.resourceQualifier()
                  .toResult(Causes.cause("Resource dep without qualifier: " + dep.parameterName()))
                  .flatMap(rq -> {
                               // Skip dedup for factory-wrapped resources (e.g., @PgSql persistence interfaces)
                               // — they share a connector but produce different typed instances
                               if (!rq.resourceType().toString().equals(dep.interfaceQualifiedName())) {
                                   return Result.unitResult();
                               }
                               return checkDuplicate(rq, dep.parameterName(), seen);
                           });
    }

    private static Result<Unit> checkDuplicate(ResourceQualifierModel rq, String paramName, HashMap<String, String> seen) {
        var key = rq.deduplicationKey();
        var existing = seen.put(key, paramName);
        if (existing != null) {
            return Causes.cause("Duplicate resource dependency: parameters '" + existing + "' and '"
                                + paramName + "' both provision " + rq.resourceTypeSimpleName()
                                + " with config '" + rq.configSection() + "'")
                         .result();
        }
        return Result.unitResult();
    }

    private static FactoryReturnKind detectReturnKind(ExecutableElement factoryMethod) {
        var returnType = factoryMethod.getReturnType();
        if (returnType instanceof DeclaredType dt) {
            var element = dt.asElement();
            if (element instanceof TypeElement te) {
                var qualifiedName = te.getQualifiedName().toString();
                return switch (qualifiedName) {
                    case "org.pragmatica.lang.Result" -> FactoryReturnKind.RESULT;
                    case "org.pragmatica.lang.Option" -> FactoryReturnKind.OPTION;
                    case "org.pragmatica.lang.Promise" -> FactoryReturnKind.PROMISE;
                    default -> FactoryReturnKind.DIRECT;
                };
            }
        }
        return FactoryReturnKind.DIRECT;
    }

    private static Result<Unit> validateReturnType(ExecutableElement factoryMethod, String sliceQualifiedName) {
        var returnKind = detectReturnKind(factoryMethod);
        if (returnKind == FactoryReturnKind.DIRECT) {
            return Result.unitResult();
        }
        var returnType = factoryMethod.getReturnType();
        if (returnType instanceof DeclaredType dt && !dt.getTypeArguments().isEmpty()) {
            var typeArg = dt.getTypeArguments().getFirst().toString();
            if (!typeArg.equals(sliceQualifiedName)) {
                return Causes.cause("Factory method return type " + returnKind + "<" + typeArg
                                    + "> does not match slice type " + sliceQualifiedName)
                             .result();
            }
        }
        return Result.unitResult();
    }

    public boolean hasDependencies() {
        return !dependencies.isEmpty();
    }

    /// Check if any method has interceptors.
    public boolean hasMethodInterceptors() {
        return methods.stream()
                      .anyMatch(MethodModel::hasInterceptors);
    }

    /// Check if any method has topic subscriptions.
    public boolean hasSubscriptions() {
        return methods.stream()
                      .anyMatch(MethodModel::hasSubscriptions);
    }

    /// Get all methods that have topic subscriptions.
    public List<MethodModel> subscriptionMethods() {
        return methods.stream()
                      .filter(MethodModel::hasSubscriptions)
                      .toList();
    }

    /// Check if any method has scheduled invocations.
    public boolean hasScheduled() {
        return methods.stream()
                      .anyMatch(MethodModel::hasScheduled);
    }

    /// Get all methods that have scheduled invocations.
    public List<MethodModel> scheduledMethods() {
        return methods.stream()
                      .filter(MethodModel::hasScheduled)
                      .toList();
    }

    /// Check if any method has stream subscriptions.
    public boolean hasStreamSubscriptions() {
        return methods.stream()
                      .anyMatch(MethodModel::hasStreamSubscriptions);
    }

    /// Get all methods that have stream subscriptions.
    public List<MethodModel> streamSubscriptionMethods() {
        return methods.stream()
                      .filter(MethodModel::hasStreamSubscriptions)
                      .toList();
    }

    /// Check if any method has PostgreSQL notification subscriptions.
    public boolean hasPgNotificationSubscriptions() {
        return methods.stream()
                      .anyMatch(MethodModel::hasPgNotificationSubscriptions);
    }

    /// Get all methods that have PostgreSQL notification subscriptions.
    public List<MethodModel> pgNotificationSubscriptionMethods() {
        return methods.stream()
                      .filter(MethodModel::hasPgNotificationSubscriptions)
                      .toList();
    }

    /// Check if any method has config update subscriptions.
    public boolean hasConfigUpdateSubscriptions() {
        return methods.stream()
                      .anyMatch(MethodModel::hasConfigUpdateSubscriptions);
    }

    /// Get all methods that have config update subscriptions.
    public List<MethodModel> configUpdateMethods() {
        return methods.stream()
                      .filter(MethodModel::hasConfigUpdateSubscriptions)
                      .toList();
    }

    public String factoryMethodName() {
        return factoryMethod.getSimpleName()
                            .toString();
    }
}
