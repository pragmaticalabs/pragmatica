package org.pragmatica.jbct.slice.model;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
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
                              Option<String> version,
                              Option<ResourceQualifierModel> resourceQualifier) {
    /// Backward-compatible constructor without resourceQualifier.
    public DependencyModel(String parameterName,
                           TypeMirror interfaceType,
                           String interfaceQualifiedName,
                           String interfaceSimpleName,
                           String interfacePackage,
                           Option<String> sliceArtifact,
                           Option<String> version) {
        this(parameterName,
             interfaceType,
             interfaceQualifiedName,
             interfaceSimpleName,
             interfacePackage,
             sliceArtifact,
             version,
             Option.none());
    }

    public static Result<DependencyModel> dependencyModel(VariableElement param, ProcessingEnvironment env) {
        var paramName = param.getSimpleName()
                             .toString();
        var type = param.asType();
        if (! (type instanceof DeclaredType dt)) {
            return Causes.cause("Dependency parameter must be an interface: " + paramName)
                         .result();
        }
        var element = dt.asElement();
        if (element.getKind() != ElementKind.INTERFACE) {
            return Causes.cause("Dependency parameter '" + paramName + "' must be an interface, found: " + element.getKind())
                         .result();
        }
        var typeElement = (TypeElement) element;
        var qualifiedName = typeElement.getQualifiedName()
                                       .toString();
        var simpleName = typeElement.getSimpleName()
                                    .toString();
        var packageName = env.getElementUtils()
                             .getPackageOf(typeElement)
                             .getQualifiedName()
                             .toString();
        // Check for @ResourceQualifier meta-annotation
        var resourceQualifier = ResourceQualifierModel.fromParameter(param, env);
        return Result.success(new DependencyModel(paramName,
                                                  type,
                                                  qualifiedName,
                                                  simpleName,
                                                  packageName,
                                                  Option.none(),
                                                  Option.none(),
                                                  resourceQualifier));
    }

    /// Check if this dependency is a resource (has @ResourceQualifier).
    public boolean isResource() {
        return resourceQualifier.isPresent();
    }

    public DependencyModel withResolved(String sliceArtifact, String version) {
        return new DependencyModel(parameterName,
                                   interfaceType,
                                   interfaceQualifiedName,
                                   interfaceSimpleName,
                                   interfacePackage,
                                   Option.some(sliceArtifact),
                                   Option.some(version),
                                   resourceQualifier);
    }

    public Option<String> fullArtifact() {
        return Option.all(sliceArtifact, version)
                     .map((artifact, ver) -> artifact + ":" + ver);
    }

    /// Checks if this dependency is an infrastructure dependency.
    /// Infrastructure dependencies:
    /// - Live under org.pragmatica.aether.infra.* package
    /// - NOT proxied via SliceInvokerFacade
    ///
    /// Examples: DatabaseConnector
    public boolean isInfrastructure() {
        return interfaceQualifiedName.startsWith("org.pragmatica.aether.infra.");
    }

    /// Get lowercase name for local proxy record (JBCT naming convention).
    /// Handles acronyms properly: "HTTPService" -> "httpService"
    public String localRecordName() {
        if (interfaceSimpleName.isEmpty()) {
            return "";
        }
        // Find the end of leading uppercase sequence
        int i = 0;
        while (i < interfaceSimpleName.length() && Character.isUpperCase(interfaceSimpleName.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return interfaceSimpleName;
        }
        if (i == 1) {
            return Character.toLowerCase(interfaceSimpleName.charAt(0)) + interfaceSimpleName.substring(1);
        }
        // Acronym handling: "HTTPService" -> "httpService"
        if (i < interfaceSimpleName.length()) {
            return interfaceSimpleName.substring(0, i - 1)
                                      .toLowerCase() + interfaceSimpleName.substring(i - 1);
        }
        return interfaceSimpleName.toLowerCase();
    }
}
