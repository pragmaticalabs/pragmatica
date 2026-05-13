package format.examples;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.List;

public class AnnotationLocations {
    @Target(ElementType.TYPE_USE)
    @interface NotNull {}

    @Target(ElementType.TYPE_USE)
    @interface NonEmpty {}

    @Target(ElementType.PARAMETER)
    @interface Required {}

    // Annotations on parameters stay INLINE (rule exception a).
    void paramAnnotations(@Required String first, @Required @NotNull String second) {}

    // Type-use annotations on type arguments stay INLINE (rule exception b).
    List<@NotNull String> typeArgUsage(String s) {
        return List.of(s);
    }

    // Type-use annotation on array element type stays INLINE.
    String @NotNull [] arrayTypeUsage() {
        return new String[0];
    }

    // Type parameter bound annotation — type-use case, INLINE.
    <T extends @NotNull Object> T typeParamBound(T value) {
        return value;
    }

    // Annotation on the DECLARATION (not param/type-use) breaks onto own line.
    @SuppressWarnings("unused")
    String annotatedMethod() {
        return "";
    }

    // Multiple decl annotations each on own line; param annotation inline.
    @SuppressWarnings("unused")
    @Deprecated
    String declAndParamAnnotations(@Required @NotNull String value) {
        return value;
    }
}
