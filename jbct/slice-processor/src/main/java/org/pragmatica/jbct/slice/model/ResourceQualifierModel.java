package org.pragmatica.jbct.slice.model;

import org.pragmatica.lang.Option;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Holds extracted data from @ResourceQualifier meta-annotation.
 * <p>
 * When a parameter is annotated with an annotation that itself is annotated with
 * {@code @ResourceQualifier(type=X.class, config="y")}, this model captures:
 * <ul>
 *   <li>resourceType - the Class<?> from the type() attribute</li>
 *   <li>configSection - the String from the config() attribute</li>
 * </ul>
 * <p>
 * Used by FactoryClassGenerator to generate:
 * {@code ctx.resources().provide(ResourceType.class, "configSection")}
 */
public record ResourceQualifierModel(TypeMirror resourceType,
                                      String resourceTypeSimpleName,
                                      String configSection) {

    private static final String RESOURCE_QUALIFIER_ANNOTATION =
        "org.pragmatica.aether.slice.annotation.ResourceQualifier";

    /**
     * Extract ResourceQualifierModel from a parameter if it has a @ResourceQualifier meta-annotation.
     *
     * @param param Parameter to check
     * @param env   Processing environment
     * @return Option containing the model if found, empty otherwise
     */
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
        TypeMirror resourceType = null;
        String configSection = null;

        var elementValues = env.getElementUtils().getElementValuesWithDefaults(metaAnnotation);
        for (var entry : elementValues.entrySet()) {
            var key = entry.getKey().getSimpleName().toString();
            var value = entry.getValue();

            if ("type".equals(key)) {
                resourceType = extractTypeMirror(value);
            } else if ("config".equals(key)) {
                configSection = extractString(value);
            }
        }

        if (resourceType != null && configSection != null) {
            var simpleName = extractSimpleName(resourceType);
            return Option.some(new ResourceQualifierModel(resourceType, simpleName, configSection));
        }
        return Option.none();
    }

    private static TypeMirror extractTypeMirror(AnnotationValue value) {
        var obj = value.getValue();
        if (obj instanceof TypeMirror tm) {
            return tm;
        }
        return null;
    }

    private static String extractString(AnnotationValue value) {
        var obj = value.getValue();
        if (obj instanceof String s) {
            return s;
        }
        return null;
    }

    private static String extractSimpleName(TypeMirror type) {
        var fullName = type.toString();
        var lastDot = fullName.lastIndexOf('.');
        return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    }
}
