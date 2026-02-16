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
                               Option<ResourceQualifierModel> resourceQualifier,
                               boolean sliceAnnotated,
                               boolean hasFactoryMethod) {
    /// Backward-compatible constructor without resourceQualifier, sliceAnnotated, hasFactoryMethod.
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
             Option.none(),
             false,
             false);
    }

    private static final String SLICE_ANNOTATION = "org.pragmatica.aether.slice.annotation.Slice";

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
        // Check if the interface has @Slice annotation
        var isSlice = hasSliceAnnotation(typeElement);
        // Check if the interface has a factory method (lowercase-first of simple name, static)
        var hasFactory = hasFactoryMethod(typeElement, simpleName);
        return Result.success(new DependencyModel(paramName,
                                                   type,
                                                   qualifiedName,
                                                   simpleName,
                                                   packageName,
                                                   Option.none(),
                                                   Option.none(),
                                                   resourceQualifier,
                                                   isSlice,
                                                   hasFactory));
    }

    /// Check if this dependency is a resource (has @ResourceQualifier).
    public boolean isResource() {
        return resourceQualifier.isPresent();
    }

    /// Check if this dependency is a @Slice-annotated interface.
    public boolean isSlice() {
        return sliceAnnotated;
    }

    /// Check if this dependency is a plain interface: not resource, not slice, has factory method.
    public boolean isPlainInterface() {
        return !isResource() && !sliceAnnotated && hasFactoryMethod;
    }

    /// Returns usable name for nested types: "EnclosingType.SimpleName" for nested, just "SimpleName" for top-level.
    public String sourceUsableName() {
        return interfaceLocalName();
    }

    /// Returns "EnclosingType.SimpleName" for nested types, just "SimpleName" for top-level.
    public String interfaceLocalName() {
        if (interfacePackage.isEmpty()) {
            return interfaceQualifiedName;
        }
        var prefix = interfacePackage + ".";
        if (interfaceQualifiedName.startsWith(prefix)) {
            return interfaceQualifiedName.substring(prefix.length());
        }
        return interfaceSimpleName;
    }

    /// Returns the qualified name to use in import statements.
    /// For nested types, imports the enclosing top-level class (e.g., `pkg.Outer` for `pkg.Outer.Inner`).
    /// For top-level types, returns the full qualified name.
    public String importName() {
        var localName = interfaceLocalName();
        var dotIndex = localName.indexOf('.');
        if (dotIndex > 0) {
            return interfacePackage + "." + localName.substring(0, dotIndex);
        }
        return interfaceQualifiedName;
    }

    public DependencyModel withResolved(String sliceArtifact, String version) {
        return new DependencyModel(parameterName,
                                    interfaceType,
                                    interfaceQualifiedName,
                                    interfaceSimpleName,
                                    interfacePackage,
                                    Option.some(sliceArtifact),
                                    Option.some(version),
                                    resourceQualifier,
                                    sliceAnnotated,
                                    hasFactoryMethod);
    }

    public Option<String> fullArtifact() {
        return Option.all(sliceArtifact, version)
                     .map((artifact, ver) -> artifact + ":" + ver);
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

    private static boolean hasSliceAnnotation(TypeElement typeElement) {
        return typeElement.getAnnotationMirrors()
                          .stream()
                          .anyMatch(mirror -> mirror.getAnnotationType()
                                                    .asElement()
                                                    .toString()
                                                    .equals(SLICE_ANNOTATION));
    }

    private static boolean hasFactoryMethod(TypeElement typeElement, String simpleName) {
        var expectedName = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
        return typeElement.getEnclosedElements()
                          .stream()
                          .filter(e -> e.getKind() == ElementKind.METHOD)
                          .anyMatch(e -> e.getSimpleName()
                                          .toString()
                                          .equals(expectedName));
    }
}
