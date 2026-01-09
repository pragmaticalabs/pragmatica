package org.pragmatica.jbct.slice;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

class SliceProcessorTest {
    @Test
    void should_fail_on_non_interface() {
        var source = JavaFileObjects.forSourceString("test.NotAnInterface",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;

            @Slice
            public class NotAnInterface {}
            """);
        var sliceAnnotation = JavaFileObjects.forSourceString("org.pragmatica.aether.slice.annotation.Slice",
                                                              """
            package org.pragmatica.aether.slice.annotation;

            import java.lang.annotation.*;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.SOURCE)
            public @interface Slice {}
            """);
        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sliceAnnotation, source);
        assertThat(compilation)
                  .failed();
        assertThat(compilation)
                  .hadErrorContaining("can only be applied to interfaces");
    }

    @Test
    void should_fail_on_missing_factory_method() {
        var source = JavaFileObjects.forSourceString("test.TestService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;

            @Slice
            public interface TestService {
                Promise<String> doSomething(String request);
            }
            """);
        var sliceAnnotation = JavaFileObjects.forSourceString("org.pragmatica.aether.slice.annotation.Slice",
                                                              """
            package org.pragmatica.aether.slice.annotation;

            import java.lang.annotation.*;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.SOURCE)
            public @interface Slice {}
            """);
        var promise = JavaFileObjects.forSourceString("org.pragmatica.lang.Promise",
                                                      """
            package org.pragmatica.lang;

            public interface Promise<T> {}
            """);
        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sliceAnnotation, promise, source);
        assertThat(compilation)
                  .failed();
        assertThat(compilation)
                  .hadErrorContaining("No factory method found");
    }

    @Test
    void should_process_valid_slice_interface() {
        var source = JavaFileObjects.forSourceString("test.TestService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;

            @Slice
            public interface TestService {
                Promise<String> doSomething(String request);

                static TestService testService() {
                    return null;
                }
            }
            """);
        var sliceAnnotation = JavaFileObjects.forSourceString("org.pragmatica.aether.slice.annotation.Slice",
                                                              """
            package org.pragmatica.aether.slice.annotation;

            import java.lang.annotation.*;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.SOURCE)
            public @interface Slice {}
            """);
        var promise = JavaFileObjects.forSourceString("org.pragmatica.lang.Promise",
                                                      """
            package org.pragmatica.lang;

            public interface Promise<T> {}
            """);
        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sliceAnnotation, promise, source);
        assertThat(compilation)
                  .succeeded();
        assertThat(compilation)
                  .generatedSourceFile("test.api.TestService");
    }
}
