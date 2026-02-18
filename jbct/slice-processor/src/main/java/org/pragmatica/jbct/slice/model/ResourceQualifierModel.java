package org.pragmatica.jbct.slice.model;

import org.pragmatica.lang.Option;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/// Holds extracted data from @ResourceQualifier meta-annotation.
///
/// When a parameter or method is annotated with an annotation that itself is annotated with
/// `@ResourceQualifier(type=X.class, config="y")`, this model captures:
///
///   - resourceType - the Class<?> from the type() attribute
///   - configSection - the String from the config() attribute
///
///
/// Used by FactoryClassGenerator to generate:
/// `ctx.resources().provide(ResourceType.class, "configSection")`
public record ResourceQualifierModel(TypeMirror resourceType,
                                     String resourceTypeSimpleName,
                                     String configSection) {
    public static ResourceQualifierModel resourceQualifierModel(TypeMirror resourceType,
                                                                 String resourceTypeSimpleName,
                                                                 String configSection) {
        return new ResourceQualifierModel(resourceType, resourceTypeSimpleName, configSection);
    }

    private static final String RESOURCE_QUALIFIER_ANNOTATION = "org.pragmatica.aether.slice.annotation.ResourceQualifier";

    /// Extract ResourceQualifierModel from a parameter if it has a @ResourceQualifier meta-annotation.
    ///
    /// @param param Parameter to check
    /// @param env   Processing environment
    /// @return Option containing the model if found, empty otherwise
    public static Option<ResourceQualifierModel> fromParameter(VariableElement param,
                                                                ProcessingEnvironment env) {
        // Check each annotation on the parameter
        for (var annotation : param.getAnnotationMirrors()) {
            var result = fromAnnotationMirror(annotation, env);
            if (result.isPresent()) {
                return result;
            }
        }
        return Option.none();
    }

    /// Extract ResourceQualifierModel from an annotation if it has @ResourceQualifier meta-annotation.
    ///
    /// @param annotation AnnotationMirror to check
    /// @param env        Processing environment
    /// @return Option containing the model if found, empty otherwise
    public static Option<ResourceQualifierModel> fromAnnotationMirror(AnnotationMirror annotation,
                                                                       ProcessingEnvironment env) {
        var annotationType = annotation.getAnnotationType()
                                       .asElement();
        // Check if this annotation type is annotated with @ResourceQualifier
        for (var metaAnnotation : annotationType.getAnnotationMirrors()) {
            var metaAnnotationName = metaAnnotation.getAnnotationType()
                                                    .asElement()
                                                    .toString();
            if (RESOURCE_QUALIFIER_ANNOTATION.equals(metaAnnotationName)) {
                return extractFromMetaAnnotation(metaAnnotation, annotation, env);
            }
        }
        return Option.none();
    }

    private static Option<ResourceQualifierModel> extractFromMetaAnnotation(AnnotationMirror metaAnnotation,
                                                                             AnnotationMirror userAnnotation,
                                                                             ProcessingEnvironment env) {
        var elementValues = env.getElementUtils()
                               .getElementValuesWithDefaults(metaAnnotation);
        var resourceType = findAnnotationValue(elementValues, "type").flatMap(ResourceQualifierModel::extractTypeMirror);
        var configSection = findAnnotationValue(elementValues, "config").flatMap(ResourceQualifierModel::extractString);
        // If the user annotation has a "config" attribute, it overrides the meta-annotation default
        var userConfig = extractUserConfigOverride(userAnnotation, env);
        var finalConfig = userConfig.fold(() -> configSection, Option::some);
        return resourceType.flatMap(type -> finalConfig.map(config -> resourceQualifierModel(type,
                                                                                              extractSourceUsableName(type, env),
                                                                                              config)));
    }

    private static Option<String> extractUserConfigOverride(AnnotationMirror annotation,
                                                             ProcessingEnvironment env) {
        var elementValues = env.getElementUtils()
                               .getElementValuesWithDefaults(annotation);
        return findAnnotationValue(elementValues, "config").flatMap(ResourceQualifierModel::extractString);
    }

    private static Option<AnnotationValue> findAnnotationValue(java.util.Map<? extends javax.lang.model.element.ExecutableElement, ? extends AnnotationValue> elementValues,
                                                                String key) {
        for (var entry : elementValues.entrySet()) {
            if (key.equals(entry.getKey()
                                .getSimpleName()
                                .toString())) {
                return Option.some(entry.getValue());
            }
        }
        return Option.none();
    }

    private static Option<TypeMirror> extractTypeMirror(AnnotationValue value) {
        var obj = value.getValue();
        if (obj instanceof TypeMirror tm) {
            return Option.some(tm);
        }
        return Option.none();
    }

    private static Option<String> extractString(AnnotationValue value) {
        var obj = value.getValue();
        if (obj instanceof String s) {
            return Option.some(s);
        }
        return Option.none();
    }

    /// Extracts the source-usable name from a type mirror.
    /// For nested types (e.g., `pkg.Outer.Inner`), returns `Outer.Inner`.
    /// For top-level types, returns just the simple name.
    private static String extractSourceUsableName(TypeMirror type, ProcessingEnvironment env) {
        if (type instanceof DeclaredType dt && dt.asElement() instanceof TypeElement te) {
            var packageName = env.getElementUtils()
                                 .getPackageOf(te)
                                 .getQualifiedName()
                                 .toString();
            var qualifiedName = te.getQualifiedName()
                                  .toString();
            if (!packageName.isEmpty() && qualifiedName.startsWith(packageName + ".")) {
                return qualifiedName.substring(packageName.length() + 1);
            }
            return qualifiedName;
        }
        var fullName = type.toString();
        var lastDot = fullName.lastIndexOf('.');
        return lastDot >= 0
               ? fullName.substring(lastDot + 1)
               : fullName;
    }

    /// Returns the simple name portion suitable for Java variable names (no dots).
    /// For `Outer.Inner`, returns `Inner`.
    public String variableSafeName() {
        var dotIndex = resourceTypeSimpleName.lastIndexOf('.');
        return dotIndex >= 0
               ? resourceTypeSimpleName.substring(dotIndex + 1)
               : resourceTypeSimpleName;
    }

    /// Deduplication key: type simple name + config section.
    public String deduplicationKey() {
        return resourceTypeSimpleName + ":" + configSection;
    }
}
