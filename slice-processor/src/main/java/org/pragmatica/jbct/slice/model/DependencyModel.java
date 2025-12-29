package org.pragmatica.jbct.slice.model;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

public record DependencyModel(
 String parameterName,
 TypeMirror interfaceType,
 String interfaceQualifiedName,
 String interfaceSimpleName,
 String interfacePackage,
 String sliceArtifact,
 String version) {
    public static DependencyModel from(VariableElement param, ProcessingEnvironment env) {
        var paramName = param.getSimpleName()
                             .toString();
        var type = param.asType();
        if (! (type instanceof DeclaredType dt)) {
            throw new IllegalStateException(
            "Dependency parameter must be an interface: " + paramName);
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
        return new DependencyModel(
        paramName, type, qualifiedName, simpleName, packageName, null, null);
    }

    public DependencyModel withResolved(String sliceArtifact, String version) {
        return new DependencyModel(
        parameterName,
        interfaceType,
        interfaceQualifiedName,
        interfaceSimpleName,
        interfacePackage,
        sliceArtifact,
        version);
    }

    public String fullArtifact() {
        if (sliceArtifact == null || version == null) {
            throw new IllegalStateException("Dependency not resolved: call resolve() first");
        }
        return sliceArtifact + ":" + version;
    }

    public String proxyClassName() {
        return interfaceSimpleName + "Proxy";
    }
}
