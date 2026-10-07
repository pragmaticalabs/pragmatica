// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;
import static com.google.testing.compile.Compiler.javac;

class TerraProcessorTest {
    @Test void process_acronymSlice_preservesSharedFactoryAndArtifactNaming() throws Exception {
        var compilation = compile("sample.URLService", """
            package sample;
            @org.pragmatica.aether.slice.annotation.Slice
            public interface URLService {
                org.pragmatica.lang.Promise<String> echo(String name);
                static URLService uRLService() { return org.pragmatica.lang.Promise::success; }
            }
            """);
        com.google.testing.compile.CompilationSubject.assertThat(compilation).succeeded();
        var descriptor = compilation.generatedSourceFile("sample.URLServiceTerraFactory").orElseThrow().getCharContent(false).toString();
        org.assertj.core.api.Assertions.assertThat(descriptor).contains("test:app-u-r-l-service:1", "URLServiceFactory.urlService(ctx)");
    }

    private Compilation compile(String name, String source, String... additionalOptions) {
        var options = new java.util.ArrayList<>(java.util.List.of("--enable-preview", "--release", "25",
            "-Aslice.target=terra", "-Aslice.groupId=test", "-Aslice.artifactId=app", "-Aslice.version=1"));
        options.addAll(java.util.List.of(additionalOptions));
        return javac().withProcessors(new SliceProcessor()).withOptions(options)
                      .compile(JavaFileObjects.forSourceString(name, source));
    }

    @Test void process_simpleSlice_emitsTerraConstructionWithoutAetherAdapter() throws Exception {
        var compilation = compile("sample.Hello", """
            package sample;
            @org.pragmatica.aether.slice.annotation.Slice
            public interface Hello {
                org.pragmatica.lang.Promise<String> hello(String name);
                static Hello hello() { return org.pragmatica.lang.Promise::success; }
            }
            """);
        com.google.testing.compile.CompilationSubject.assertThat(compilation).succeeded();
        var generated = compilation.generatedSourceFile("sample.HelloFactory").orElseThrow().getCharContent(false).toString();
        org.assertj.core.api.Assertions.assertThat(generated).contains("TerraContext").doesNotContain("SliceCreationContext", "MethodHandle", "helloSlice(", "SliceCodec");
        org.assertj.core.api.Assertions.assertThat(compilation.generatedSourceFile("sample.HelloTerraFactory")).isPresent();
    }

    @Test void process_scheduledMethod_refusesUnsupportedBehavior() {
        var compilation = compile("sample.Clock", """
            package sample;
            import java.lang.annotation.*;
            import org.pragmatica.aether.slice.annotation.*;
            import org.pragmatica.lang.*;
            @Slice public interface Clock {
                @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
                @ResourceQualifier(type=org.pragmatica.aether.slice.Scheduled.class, config="clock")
                @interface Tick {}
                @Tick Promise<Unit> tick();
                static Clock clock() { return Promise::unitPromise; }
            }
            """);
        com.google.testing.compile.CompilationSubject.assertThat(compilation).failed();
        com.google.testing.compile.CompilationSubject.assertThat(compilation).hadErrorContaining("Terra supports only");
    }

    @Test void process_plainInterfaceSubscriber_retainsInjectedStepAndBindsIt() throws Exception {
        var compilation = compile("sample.Flow", """
            package sample;
            import java.lang.annotation.*;
            import org.pragmatica.aether.slice.annotation.*;
            import org.pragmatica.lang.*;
            @Slice public interface Flow {
                Promise<String> echo(String input);
                @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
                @ResourceQualifier(type=org.pragmatica.aether.slice.Subscriber.class, config="events")
                @interface OnEvent {}
                interface Listener {
                    @OnEvent Promise<Unit> receive(String event);
                    static Listener listener() { return _ -> Promise.unitPromise(); }
                }
                static Flow flow(Listener listener) { return Promise::success; }
            }
            """);
        com.google.testing.compile.CompilationSubject.assertThat(compilation).succeeded();
        org.assertj.core.api.Assertions.assertThat(compilation.generatedSourceFile("sample.FlowFactory").orElseThrow().getCharContent(false).toString())
            .contains("ctx.retainStep(\"listener\"");
        org.assertj.core.api.Assertions.assertThat(compilation.generatedSourceFile("sample.FlowTerraFactory").orElseThrow().getCharContent(false).toString())
            .contains("ctx.step(\"listener\", sample.Flow.Listener.class)", "step.receive(event)");
    }

    @Test void process_multiParameterKeyedInterceptor_importsTypeToken() throws Exception {
        var compilation = compile("sample.Keyed", """
            package sample;
            import java.lang.annotation.*;
            import org.pragmatica.aether.slice.annotation.*;
            import org.pragmatica.aether.resource.aspect.Key;
            import org.pragmatica.lang.*;
            @Slice public interface Keyed {
                @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
                @ResourceQualifier(type=org.pragmatica.aether.slice.MethodInterceptor.class, config="cache.values")
                @interface Cached {}
                @Cached Promise<String> lookup(@Key String key, int version);
                static Keyed keyed() { return (key, version) -> Promise.success(key); }
            }
            """);
        com.google.testing.compile.CompilationSubject.assertThat(compilation).succeeded();
        org.assertj.core.api.Assertions.assertThat(compilation.generatedSourceFile("sample.KeyedFactory").orElseThrow().getCharContent(false).toString())
            .contains("import org.pragmatica.lang.type.TypeToken;", "LookupRequest::key");
    }

    @Test void process_unknownTarget_refusesTypo() {
        var compilation = compile("sample.Hello", """
            package sample;
            @org.pragmatica.aether.slice.annotation.Slice
            public interface Hello {
                org.pragmatica.lang.Promise<String> hello(String name);
                static Hello hello() { return org.pragmatica.lang.Promise::success; }
            }
            """, "-Aslice.target=terar");
        com.google.testing.compile.CompilationSubject.assertThat(compilation).failed();
        com.google.testing.compile.CompilationSubject.assertThat(compilation).hadErrorContaining("Unknown slice.target");
    }
}
