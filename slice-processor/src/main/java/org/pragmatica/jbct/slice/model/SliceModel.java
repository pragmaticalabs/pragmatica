package org.pragmatica.jbct.slice.model;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

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
    public static Result<SliceModel> sliceModel(TypeElement element, ProcessingEnvironment env) {
        var packageName = env.getElementUtils()
                             .getPackageOf(element)
                             .getQualifiedName()
                             .toString();
        var simpleName = element.getSimpleName()
                                .toString();
        var qualifiedName = element.getQualifiedName()
                                   .toString();
        var apiPackage = packageName + ".api";
        return extractMethods(element)
                      .flatMap(methods -> findFactoryMethod(element, simpleName)
                                                 .flatMap(factoryMethod -> extractDependencies(factoryMethod, env)
                                                                                  .map(dependencies -> new SliceModel(
                                                                                  packageName,
                                                                                  simpleName,
                                                                                  qualifiedName,
                                                                                  apiPackage,
                                                                                  methods,
                                                                                  dependencies,
                                                                                  factoryMethod))));
    }

    private static Result<List<MethodModel>> extractMethods(TypeElement element) {
        var results = element.getEnclosedElements()
                             .stream()
                             .filter(e -> e.getKind() == ElementKind.METHOD)
                             .map(e -> (ExecutableElement) e)
                             .filter(m -> !m.getModifiers()
                                            .contains(Modifier.STATIC))
                             .filter(m -> !m.getModifiers()
                                            .contains(Modifier.DEFAULT))
                             .map(MethodModel::methodModel)
                             .toList();
        return Result.allOf(results);
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

    public boolean hasDependencies() {
        return !dependencies.isEmpty();
    }

    public String factoryMethodName() {
        return factoryMethod.getSimpleName()
                            .toString();
    }
}
