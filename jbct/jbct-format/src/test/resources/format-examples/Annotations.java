package format.examples;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


public class Annotations {
    @SuppressWarnings("unused") static class SingleAnnotation {}

    @SuppressWarnings("unused") @Deprecated static class MultipleAnnotations {}

    @SuppressWarnings("unchecked") void singleValue() {}

    @SuppressWarnings({"unused", "unchecked"}) void arrayValue() {}

    @Target(ElementType.METHOD) @interface NamedParam {}

    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD, ElementType.FIELD}) @interface MultipleParams {}

    @interface WithDefaults {
        String value() default"";
        int count() default 0;
        boolean enabled() default true;
    }

    @Override public String toString() {
        return "Annotations";
    }

    @SuppressWarnings("unused") @Deprecated void multipleOnMethod() {}

    @SuppressWarnings("unused") private String annotatedField;
    @SuppressWarnings("unused") @Deprecated private String multiAnnotatedField;

    void annotatedParameter(@SuppressWarnings("unused") String param) {}

    void multiAnnotatedParameter(@SuppressWarnings("unused") @Deprecated String param) {}

    void annotatedLocalVariable() {
        @SuppressWarnings("unused") var local = "value";
    }

    @SuppressWarnings(value = {"unused", "unchecked", "rawtypes", "deprecation"}) void longAnnotationParams() {}

    @SuppressWarnings("unused") record AnnotatedRecord(String value){}

    record RecordWithAnnotatedComponent(@SuppressWarnings("unused") String value){}

    @FunctionalInterface interface AnnotatedInterface {
        void apply();
    }

    @SuppressWarnings("unused") enum AnnotatedEnum {
        @Deprecated OLD_VALUE,
        NEW_VALUE
    }

    void typeAnnotation() {
        @SuppressWarnings("unused") String@SuppressWarnings("unused") [] array = new String[0];
    }

    @SuppressWarnings("unused") public Annotations() {}

    @WithDefaults void defaultAnnotation() {}

    @WithDefaults(value = "custom", count = 5) void customAnnotation() {}

    @WithDefaults(value = "full", count = 10, enabled = false) void fullAnnotation() {}
}
