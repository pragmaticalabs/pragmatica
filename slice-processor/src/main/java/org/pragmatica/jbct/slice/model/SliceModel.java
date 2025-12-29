package org.pragmatica.jbct.slice.model;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.List;

public record SliceModel(
 String packageName,
 String simpleName,
 String qualifiedName,
 String apiPackage,
 List<MethodModel> methods,
 List<DependencyModel> dependencies,
 ExecutableElement factoryMethod) {
    public static SliceModel from(TypeElement element, ProcessingEnvironment env) {
        var packageName = env.getElementUtils()
                             .getPackageOf(element)
                             .getQualifiedName()
                             .toString();
        var simpleName = element.getSimpleName()
                                .toString();
        var qualifiedName = element.getQualifiedName()
                                   .toString();
        var apiPackage = packageName + ".api";
        var methods = extractMethods(element);
        var factoryMethod = findFactoryMethod(element, simpleName);
        var dependencies = extractDependencies(factoryMethod, env);
        return new SliceModel(
        packageName, simpleName, qualifiedName, apiPackage, methods, dependencies, factoryMethod);
    }

    private static List<MethodModel> extractMethods(TypeElement element) {
        return element.getEnclosedElements()
                      .stream()
                      .filter(e -> e.getKind() == ElementKind.METHOD)
                      .map(e -> (ExecutableElement) e)
                      .filter(m -> !m.getModifiers()
                                     .contains(Modifier.STATIC))
                      .filter(m -> !m.getModifiers()
                                     .contains(Modifier.DEFAULT))
                      .map(MethodModel::from)
                      .toList();
    }

    private static ExecutableElement findFactoryMethod(TypeElement element, String simpleName) {
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
                      .orElseThrow(() -> new IllegalStateException(
        "No factory method found: " + expectedName + "(...)"));
    }

    private static List<DependencyModel> extractDependencies(ExecutableElement factoryMethod,
                                                             ProcessingEnvironment env) {
        return factoryMethod.getParameters()
                            .stream()
                            .map(param -> DependencyModel.from(param, env))
                            .toList();
    }

    public boolean hasDependencies() {
        return !dependencies.isEmpty();
    }

    public String factoryMethodName() {
        return factoryMethod.getSimpleName()
                            .toString();
    }
}
