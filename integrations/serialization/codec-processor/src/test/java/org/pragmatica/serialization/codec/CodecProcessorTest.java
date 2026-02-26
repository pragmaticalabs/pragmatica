package org.pragmatica.serialization.codec;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

class CodecProcessorTest {

    @Nested
    class RecordCodecTests {
        @Test
        void recordCodec_generatesCorrectCodec_forSimpleRecord() {
            var source = JavaFileObjects.forSourceString("com.example.Point",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;

                @Codec
                public record Point(int x, int y) {}
                """);

            var compilation = compileWith(source);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.PointCodec")
                                   .contentsAsUtf8String()
                                   .contains("TypeCodec<Point> CODEC");
            assertThat(compilation).generatedSourceFile("com.example.PointCodec")
                                   .contentsAsUtf8String()
                                   .contains("codec.write(buf, value.x());");
            assertThat(compilation).generatedSourceFile("com.example.PointCodec")
                                   .contentsAsUtf8String()
                                   .contains("codec.write(buf, value.y());");
            assertThat(compilation).generatedSourceFile("com.example.PointCodec")
                                   .contentsAsUtf8String()
                                   .contains("return new Point(x, y);");
        }

        @Test
        void recordCodec_usesExplicitTag_whenSpecified() {
            var source = JavaFileObjects.forSourceString("com.example.Tagged",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;

                @Codec(tag = 42)
                public record Tagged(String name) {}
                """);

            var compilation = compileWith(source);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.TaggedCodec")
                                   .contentsAsUtf8String()
                                   .contains("int TAG = 42;");
        }

        @Test
        void recordCodec_usesDeterministicTag_whenNoTagSpecified() {
            var source = JavaFileObjects.forSourceString("com.example.AutoTag",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;

                @Codec
                public record AutoTag(String value) {}
                """);

            var compilation = compileWith(source);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.AutoTagCodec")
                                   .contentsAsUtf8String()
                                   .contains("SliceCodec.deterministicTag(\"com.example.AutoTag\")");
        }
    }

    @Nested
    class EnumCodecTests {
        @Test
        void enumCodec_generatesCorrectCodec_forSimpleEnum() {
            var source = JavaFileObjects.forSourceString("com.example.Color",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;

                @Codec
                public enum Color { RED, GREEN, BLUE }
                """);

            var compilation = compileWith(source);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.ColorCodec")
                                   .contentsAsUtf8String()
                                   .contains("TypeCodec<Color> CODEC");
            assertThat(compilation).generatedSourceFile("com.example.ColorCodec")
                                   .contentsAsUtf8String()
                                   .contains("SliceCodec.writeCompact(buf, value.ordinal());");
            assertThat(compilation).generatedSourceFile("com.example.ColorCodec")
                                   .contentsAsUtf8String()
                                   .contains("Color.values()[SliceCodec.readCompact(buf)]");
        }
    }

    @Nested
    class SealedInterfaceTests {
        @Test
        void sealedInterface_generatesCodecsForSubtypes() {
            var sealedSource = JavaFileObjects.forSourceString("com.example.Shape",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;

                @Codec
                public sealed interface Shape permits Shape.Circle, Shape.Rect {
                    @Codec(tag = 100)
                    record Circle(double radius) implements Shape {}

                    @Codec(tag = 101)
                    record Rect(double width, double height) implements Shape {}
                }
                """);

            var compilation = compileWith(sealedSource);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.Shape_CircleCodec");
            assertThat(compilation).generatedSourceFile("com.example.Shape_RectCodec");
        }
    }

    @Nested
    class RegistryTests {
        @Test
        void registry_generatesCodecsList_forPackage() {
            var source = JavaFileObjects.forSourceString("com.example.Msg",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;

                @Codec
                public record Msg(String text) {}
                """);

            var compilation = compileWith(source);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.ExampleCodecs")
                                   .contentsAsUtf8String()
                                   .contains("List<TypeCodec<?>> CODECS = List.of(");
            assertThat(compilation).generatedSourceFile("com.example.ExampleCodecs")
                                   .contentsAsUtf8String()
                                   .contains("MsgCodec.CODEC");
        }
    }

    private static Compilation compileWith(javax.tools.JavaFileObject... sources) {
        return javac().withProcessors(new CodecProcessor())
                      .compile(sources);
    }
}
