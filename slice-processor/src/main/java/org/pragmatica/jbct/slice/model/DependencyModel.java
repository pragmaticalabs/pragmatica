package org.pragmatica.jbct.slice.model;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

public record DependencyModel(String parameterName,
                              TypeMirror interfaceType,
                              String interfaceQualifiedName,
                              String interfaceSimpleName,
                              String interfacePackage,
                              Option<String> sliceArtifact,
                              Option<String> version) {
    public static Result<DependencyModel> dependencyModel(VariableElement param, ProcessingEnvironment env) {
        var paramName = param.getSimpleName()
                             .toString();
        var type = param.asType();
        if (! (type instanceof DeclaredType dt)) {
            return Causes.cause("Dependency parameter must be an interface: " + paramName)
                         .result();
        }
        var typeElement = (TypeElement) dt.asElement();
        var qualifiedName = typeElement.getQualifiedName()
                                       .toString();
        var simpleName = typeElement.getSimpleName()
                                    .toString();
        var packageName = env.getElementUtils()
                             .getPackageOf(typeElement)
                             .getQualifiedName()
                             .toString();
        return Result.success(new DependencyModel(paramName,
                                                  type,
                                                  qualifiedName,
                                                  simpleName,
                                                  packageName,
                                                  Option.none(),
                                                  Option.none()));
    }

    public DependencyModel withResolved(String sliceArtifact, String version) {
        return new DependencyModel(parameterName,
                                   interfaceType,
                                   interfaceQualifiedName,
                                   interfaceSimpleName,
                                   interfacePackage,
                                   Option.some(sliceArtifact),
                                   Option.some(version));
    }

    public Option<String> fullArtifact() {
        return Option.all(sliceArtifact, version)
                     .map((artifact, ver) -> artifact + ":" + ver);
    }

    /**
     * Check if this dependency is external (different base package).
     * External dependencies need proxies; internal ones are called directly.
     */
    public boolean isExternal(String basePackage) {
        return !interfacePackage.startsWith(basePackage);
    }

    /**
     * Get lowercase name for local proxy record (JBCT naming convention).
     */
    public String localRecordName() {
        return Character.toLowerCase(interfaceSimpleName.charAt(0)) + interfaceSimpleName.substring(1);
    }

    /**
     * Get factory method name for internal dependency (JBCT naming convention).
     */
    public String factoryMethodName() {
        return localRecordName();
    }
}
