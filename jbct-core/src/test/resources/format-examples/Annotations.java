package format.examples;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public class Annotations {
    // Single annotation on class
    @SuppressWarnings("unused")
    static class SingleAnnotation {}

    // Multiple annotations on class
    @SuppressWarnings("unused")
    @Deprecated
    static class MultipleAnnotations {}

    // Annotation with single value
    @SuppressWarnings("unchecked")
    void singleValue() {}

    // Annotation with array value
    @SuppressWarnings({"unused", "unchecked"})
    void arrayValue() {}

    // Annotation with named parameter
    @Target(ElementType.METHOD) @interface NamedParam {}

    // Annotation with multiple parameters
    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD, ElementType.FIELD}) @interface MultipleParams {}

    // Annotation with default values
    @interface WithDefaults {
        String value() default "";
        int count() default 0;
        boolean enabled() default true;
    }

    // Single annotation on method
    @Override
    public String toString() {
        return "Annotations";
    }

    // Multiple annotations on method
    @SuppressWarnings("unused")
    @Deprecated
    void multipleOnMethod() {}

    // Annotation on field
    @SuppressWarnings("unused")
    private String annotatedField;

    // Multiple annotations on field
    @SuppressWarnings("unused")
    @Deprecated
    private String multiAnnotatedField;

    // Annotation on parameter
    void annotatedParameter(@SuppressWarnings("unused") String param) {}

    // Multiple annotations on parameter
    void multiAnnotatedParameter(@SuppressWarnings("unused") @Deprecated String param) {}

    // Annotation on local variable
    void annotatedLocalVariable() {
        @SuppressWarnings("unused") var local = "value";
    }

    // Annotation with long parameters
    @SuppressWarnings(value = {"unused", "unchecked", "rawtypes", "deprecation"})
    void longAnnotationParams() {}

    // Annotation on record
    @SuppressWarnings("unused") record AnnotatedRecord(String value) {}

    // Annotation on record component
    record RecordWithAnnotatedComponent(@SuppressWarnings("unused") String value) {}

    // Annotation on interface
    @FunctionalInterface
    interface AnnotatedInterface {
        void apply();
    }

    // Annotation on enum
    @SuppressWarnings("unused") enum AnnotatedEnum {
        @Deprecated OLD_VALUE,
        NEW_VALUE
    }

    // Type annotation
    void typeAnnotation() {
        @SuppressWarnings("unused") String@SuppressWarnings("unused") [] array = new String[0];
    }

    // Annotation on constructor
    @SuppressWarnings("unused")
    public Annotations() {}

    // Custom annotation usage
    @WithDefaults
    void defaultAnnotation() {}

    @WithDefaults(value = "custom", count = 5)
    void customAnnotation() {}

    @WithDefaults(value = "full", count = 10, enabled = false)
    void fullAnnotation() {}
}
