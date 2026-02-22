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
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

public record SliceModel(String packageName,
                          String simpleName,
                          String qualifiedName,
                          List<MethodModel> methods,
                          List<DependencyModel> dependencies,
                          ExecutableElement factoryMethod,
                          FactoryReturnKind factoryReturnKind) {
    public enum FactoryReturnKind { DIRECT, RESULT, OPTION, PROMISE }

    public SliceModel {
        methods = List.copyOf(methods);
        dependencies = List.copyOf(dependencies);
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
        .map(_3 -> new SliceModel(packageName, simpleName, qualifiedName, methods, dependencies, factoryMethod,
                                  detectReturnKind(factoryMethod))))))));
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
        return Result.success(Unit.unit());
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
        return Result.success(Unit.unit());
    }

    private static Result<Unit> checkDuplicateResource(DependencyModel dep, HashMap<String, String> seen) {
        return dep.resourceQualifier()
                  .toResult(Causes.cause("Resource dep without qualifier: " + dep.parameterName()))
                  .flatMap(rq -> checkDuplicate(rq, dep.parameterName(), seen));
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
        return Result.success(Unit.unit());
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
            return Result.success(Unit.unit());
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
        return Result.success(Unit.unit());
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

    public String factoryMethodName() {
        return factoryMethod.getSimpleName()
                            .toString();
    }
}
