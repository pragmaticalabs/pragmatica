package org.pragmatica.jbct.slice.model;

import org.pragmatica.lang.Option;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/// Holds extracted data from @ResourceQualifier meta-annotation.
///
/// When a parameter is annotated with an annotation that itself is annotated with
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

    private static final String RESOURCE_QUALIFIER_ANNOTATION =
        "org.pragmatica.aether.slice.annotation.ResourceQualifier";

    /// Extract ResourceQualifierModel from a parameter if it has a @ResourceQualifier meta-annotation.
    ///
    /// @param param Parameter to check
    /// @param env   Processing environment
    /// @return Option containing the model if found, empty otherwise
    public static Option<ResourceQualifierModel> fromParameter(VariableElement param,
                                                                ProcessingEnvironment env) {
        // Check each annotation on the parameter
        for (var annotation : param.getAnnotationMirrors()) {
            var annotationType = annotation.getAnnotationType().asElement();
            // Check if this annotation type is annotated with @ResourceQualifier
            for (var metaAnnotation : annotationType.getAnnotationMirrors()) {
                var metaAnnotationName = metaAnnotation.getAnnotationType()
                                                        .asElement()
                                                        .toString();
                if (RESOURCE_QUALIFIER_ANNOTATION.equals(metaAnnotationName)) {
                    return extractFromMetaAnnotation(metaAnnotation, env);
                }
            }
        }
        return Option.none();
    }

    private static Option<ResourceQualifierModel> extractFromMetaAnnotation(AnnotationMirror metaAnnotation,
                                                                             ProcessingEnvironment env) {
        var elementValues = env.getElementUtils().getElementValuesWithDefaults(metaAnnotation);

        var resourceType = findAnnotationValue(elementValues, "type").flatMap(ResourceQualifierModel::extractTypeMirror);
        var configSection = findAnnotationValue(elementValues, "config").flatMap(ResourceQualifierModel::extractString);

        return resourceType.flatMap(type -> configSection.map(config -> resourceQualifierModel(type,
                                                                                                    extractSimpleName(type),
                                                                                                    config)));
    }

    private static Option<AnnotationValue> findAnnotationValue(
        java.util.Map<? extends javax.lang.model.element.ExecutableElement, ? extends AnnotationValue> elementValues,
        String key) {
        for (var entry : elementValues.entrySet()) {
            if (key.equals(entry.getKey().getSimpleName().toString())) {
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

    private static String extractSimpleName(TypeMirror type) {
        var fullName = type.toString();
        var lastDot = fullName.lastIndexOf('.');
        return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    }
}
