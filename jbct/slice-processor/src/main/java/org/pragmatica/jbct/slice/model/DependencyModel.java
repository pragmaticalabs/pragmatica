// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.model;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


public record DependencyModel(String parameterName,
                              TypeMirror interfaceType,
                              String interfaceQualifiedName,
                              String interfaceSimpleName,
                              String interfacePackage,
                              Option<String> sliceArtifact,
                              Option<String> version,
                              Option<ResourceQualifierModel> resourceQualifier,
                              boolean sliceAnnotated,
                              boolean hasFactoryMethod,
                              Option<AnnotatedComponent> partitionKey) {
    /// Backward-compatible constructor without resourceQualifier, sliceAnnotated, hasFactoryMethod,
    /// partitionKey.
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
             false,
             Option.none());
    }

    private static final String SLICE_ANNOTATION = "org.pragmatica.aether.slice.annotation.Slice";
    private static final String PUBLISHER_TYPE = "org.pragmatica.aether.slice.Publisher";
    private static final String STREAM_PUBLISHER_TYPE = "org.pragmatica.aether.slice.StreamPublisher";
    private static final String STREAM_ACCESS_TYPE = "org.pragmatica.aether.slice.StreamAccess";
    private static final String PARTITION_KEY_ANNOTATION = "org.pragmatica.aether.slice.annotation.PartitionKey";

    public static Result<DependencyModel> dependencyModel(VariableElement param, ProcessingEnvironment env) {
        var paramName = param.getSimpleName().toString();
        var type = param.asType();

        if (! (type instanceof DeclaredType dt)) {
            return Causes.cause("Dependency parameter must be an interface: " + paramName).result();
        }

        var element = dt.asElement();
        // Check for @ResourceQualifier meta-annotation early — ConfigurationSection allows records
        var resourceQualifier = ResourceQualifierModel.fromParameter(param, env);
        var isConfigSection = resourceQualifier.map(ResourceQualifierModel::isConfigurationSection).or(false);

        if (element.getKind() != ElementKind.INTERFACE && !isConfigSection) {
            return Causes.cause("Dependency parameter '" + paramName
                               + "' must be an interface, found: " + element.getKind()).result();
        }

        var typeElement = (TypeElement) element;
        var qualifiedName = typeElement.getQualifiedName().toString();
        var simpleName = typeElement.getSimpleName().toString();
        var packageName = env.getElementUtils().getPackageOf(typeElement).getQualifiedName().toString();
        // Check if the interface has @Slice annotation
        var isSlice = isConfigSection
                      ? false
                      : hasSliceAnnotation(typeElement);
        // Check if the interface has a factory method (lowercase-first of simple name, static)
        var hasFactory = hasFactoryMethod(typeElement, simpleName);

        return resolvePartitionKey(type, resourceQualifier).map(partitionKey -> new DependencyModel(paramName,
                                                                                                     type,
                                                                                                     qualifiedName,
                                                                                                     simpleName,
                                                                                                     packageName,
                                                                                                     Option.none(),
                                                                                                     Option.none(),
                                                                                                     resourceQualifier,
                                                                                                     isSlice,
                                                                                                     hasFactory,
                                                                                                     partitionKey));
    }

    /// Resolve the `@PartitionKey` component of a stream resource's event type (#507).
    ///
    /// Only stream resources carry one: `StreamPublisherFactory` / `StreamAccessFactory` are the
    /// only provisioners that read `ProvisioningContext.keyExtractor()` for partition routing.
    /// Topic `Publisher<T>` is unpartitioned — `PublisherFactory` ignores the extractor — so a key
    /// emitted there would advertise routing that does not happen.
    private static Result<Option<AnnotatedComponent>> resolvePartitionKey(TypeMirror type,
                                                                          Option<ResourceQualifierModel> qualifier) {
        if (!isStreamResource(qualifier)) {
            return Result.success(Option.none());
        }

        return eventTypeMirror(type).map(eventType -> AnnotatedComponent.annotatedComponent(eventType,
                                                                                             PARTITION_KEY_ANNOTATION))
                                    .or(() -> Result.success(Option.none()));
    }

    /// The single type argument of a `StreamPublisher<T>` / `StreamAccess<T>` parameter.
    private static Option<TypeMirror> eventTypeMirror(TypeMirror type) {
        if (! (type instanceof DeclaredType dt)) {
            return Option.none();
        }

        var typeArgs = dt.getTypeArguments();

        return typeArgs.isEmpty()
               ? Option.none()
               : Option.some(typeArgs.getFirst());
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
        return ! isResource()
               && !sliceAnnotated
               && hasFactoryMethod;
    }

    /// Check if this dependency is a ConfigurationSection resource.
    /// ConfigurationSection resources trigger config parsing code generation
    /// instead of resource provisioning.
    public boolean isConfigurationSection() {
        return resourceQualifier.map(ResourceQualifierModel::isConfigurationSection)
                                .or(false);
    }

    /// Check if this dependency is a Publisher resource.
    public boolean isPublisher() {
        return isResourceType(resourceQualifier, PUBLISHER_TYPE);
    }

    /// Check if this dependency is a StreamPublisher resource.
    public boolean isStreamPublisher() {
        return isResourceType(resourceQualifier, STREAM_PUBLISHER_TYPE);
    }

    /// Check if this dependency is a StreamAccess resource.
    public boolean isStreamAccess() {
        return isResourceType(resourceQualifier, STREAM_ACCESS_TYPE);
    }

    /// Check if this dependency is any stream resource (StreamPublisher or StreamAccess).
    public boolean isStreamResource() {
        return isStreamResource(resourceQualifier);
    }

    private static boolean isStreamResource(Option<ResourceQualifierModel> qualifier) {
        return isResourceType(qualifier, STREAM_PUBLISHER_TYPE) || isResourceType(qualifier, STREAM_ACCESS_TYPE);
    }

    private static boolean isResourceType(Option<ResourceQualifierModel> qualifier, String resourceType) {
        return qualifier.map(q -> resourceType.equals(q.resourceType().toString()))
                        .or(false);
    }

    /// Extract message type from Publisher<T> generic parameter.
    /// Returns the qualified type name of T, or empty if not a Publisher or no type arg.
    public Option<String> publisherMessageType() {
        return isPublisher()
               ? eventTypeMirror(interfaceType).map(TypeMirror::toString)
               : Option.none();
    }

    /// Extract event type from StreamPublisher<T> or StreamAccess<T> generic parameter.
    /// Returns the qualified type name of T, or empty if not a stream resource or no type arg.
    public Option<String> streamEventType() {
        return isStreamResource()
               ? eventTypeMirror(interfaceType).map(TypeMirror::toString)
               : Option.none();
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
                                   hasFactoryMethod,
                                   partitionKey);
    }

    public Option<String> fullArtifact() {
        return Option.all(sliceArtifact, version).map((artifact, ver) -> artifact + ":" + ver);
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
