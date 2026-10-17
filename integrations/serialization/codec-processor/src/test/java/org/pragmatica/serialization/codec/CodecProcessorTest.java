package org.pragmatica.serialization.codec;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.pragmatica.serialization.SliceCodec;

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
                                   .contains("buf.writeInt(value.x());");
            assertThat(compilation).generatedSourceFile("com.example.PointCodec")
                                   .contentsAsUtf8String()
                                   .contains("buf.writeInt(value.y());");
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
                public enum Color { RED, GREEN, BLUE, UNKNOWN }
                """);

            var compilation = compileWith(source);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.ColorCodec")
                                   .contentsAsUtf8String()
                                   .contains("TypeCodec<Color> CODEC");
            assertThat(compilation).generatedSourceFile("com.example.ColorCodec")
                                   .contentsAsUtf8String()
                                   .contains("SliceCodec.writeCompact(buf, value.ordinal());");
            // #964: the bounds-checked read, NOT the raw values()[readCompact(buf)] index this
            // replaced. The literal is asserted because the generated string IS the wire behaviour —
            // an ordinal past values() must reach the sentinel rather than throw AIOOBE into a
            // boundary that drops the message.
            assertThat(compilation).generatedSourceFile("com.example.ColorCodec")
                                   .contentsAsUtf8String()
                                   .contains("SliceCodec.readEnum(buf, Color.values(), Color.UNKNOWN)");
            assertThat(compilation).generatedSourceFile("com.example.ColorCodec")
                                   .contentsAsUtf8String()
                                   .doesNotContain("values()[SliceCodec.readCompact(buf)]");
        }

        /// #964: the processor REFUSES an enum that cannot represent a value it does not know.
        ///
        /// An error rather than a warning, and rejected here rather than left to fail later inside
        /// generated source: the generated `readBody` references `X.UNKNOWN`, so without this the
        /// author's diagnostic would be "cannot find symbol" pointing at a file they did not write.
        @Test
        void enumCodec_failsCompilation_whenSentinelIsMissing() {
            var source = JavaFileObjects.forSourceString("com.example.NoSentinel",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;

                @Codec
                public enum NoSentinel { RED, GREEN }
                """);

            var compilation = compileWith(source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("must declare UNKNOWN as its LAST constant");
            assertThat(compilation).hadErrorContaining("com.example.NoSentinel");
        }

        /// LAST is the load-bearing half of the rule, so it is enforced rather than merely documented.
        /// A sentinel in the middle silently REMAPS every constant after it onto other legitimate
        /// values when an older node reads them — corruption, which is strictly worse than the
        /// unknown-value case the sentinel exists to handle.
        @Test
        void enumCodec_failsCompilation_whenSentinelIsNotLast() {
            var source = JavaFileObjects.forSourceString("com.example.SentinelInMiddle",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;

                @Codec
                public enum SentinelInMiddle { RED, UNKNOWN, GREEN }
                """);

            var compilation = compileWith(source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("must declare UNKNOWN as its LAST constant");
        }

        /// The nested/sealed and @CodecFor paths call `generateEnumCodec` directly rather than through
        /// `processEnum`, so each needed its own guard. Without this test a nested enum could slip past
        /// the rule while the top-level tests stayed green — the same shape as a check that examines a
        /// smaller space than the claim it supports.
        @Test
        void enumCodec_failsCompilation_whenNestedSentinelIsMissing() {
            var source = JavaFileObjects.forSourceString("com.example.Holder",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;

                @Codec
                public record Holder(String name, Holder.Kind kind) {
                    @Codec
                    enum Kind { A, B }
                }
                """);

            var compilation = compileWith(source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("must declare UNKNOWN as its LAST constant");
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

        /// #1633: a record field typed by a `@Codec` sealed interface. The generated record codec used to call
        /// `ShapeCodec`, which the processor never emits (only the permitted subtypes get codecs), so the
        /// generated source did not compile. The field is now dispatched by its runtime subtype's tag, and a
        /// value of each subtype survives a round trip through a real [SliceCodec].
        @Test
        @SuppressWarnings("unchecked")
        void recordWithSealedInterfaceField_compiles_andRoundTripsEachSubtype() throws Exception {
            var drawingSource = JavaFileObjects.forSourceString("com.example.Drawing",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;

                @Codec(tag = 102)
                public record Drawing(String name, Shape shape) {}
                """);
            var compilation = compileWith(SHAPE_SOURCE, drawingSource);

            assertThat(compilation).succeeded();

            var loader = classLoaderOf(compilation);
            var codecs = (java.util.List<SliceCodec.TypeCodec<?>>) loader.loadClass("com.example.ExampleCodecs")
                                                                        .getField("CODECS")
                                                                        .get(null);
            var codec = SliceCodec.sliceCodec(codecs);
            var circle = loader.loadClass("com.example.Shape$Circle").getConstructor(double.class).newInstance(2.5);
            var rect = loader.loadClass("com.example.Shape$Rect").getConstructor(double.class, double.class).newInstance(3.0, 4.0);
            var shapeType = loader.loadClass("com.example.Shape");
            var drawingType = loader.loadClass("com.example.Drawing");

            for (var shape : java.util.List.of(circle, rect)) {
                var drawing = drawingType.getConstructor(String.class, shapeType).newInstance("d", shape);
                var buf = io.netty.buffer.Unpooled.buffer();

                codec.write(buf, drawing);
                Object decoded = codec.read(buf);

                org.junit.jupiter.api.Assertions.assertEquals(drawing, decoded);
            }
        }
    }

    private static final javax.tools.JavaFileObject SHAPE_SOURCE = JavaFileObjects.forSourceString("com.example.Shape",
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

    /// Child-first over the compilation's class output for the test's own `com.example` types, so the
    /// generated codecs link against the types they were compiled with; everything else comes from the
    /// test classpath, so the codecs and this test share one [SliceCodec].
    private static ClassLoader classLoaderOf(Compilation compilation) throws java.io.IOException {
        var classes = new java.util.HashMap<String, byte[]>();

        for (var file : compilation.generatedFiles()) {
            if (file.getKind() == javax.tools.JavaFileObject.Kind.CLASS) {
                var path = file.toUri().getPath();
                var binaryName = path.substring(path.indexOf("CLASS_OUTPUT/") + "CLASS_OUTPUT/".length(),
                                                path.length() - ".class".length())
                                     .replace('/', '.');

                try (var in = file.openInputStream()) {
                    classes.put(binaryName, in.readAllBytes());
                }
            }
        }

        return new ClassLoader(CodecProcessorTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    var loaded = findLoadedClass(name);
                    var bytes = classes.get(name);

                    if (loaded == null && bytes != null && name.startsWith("com.example.")) {
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    }
                    if (loaded == null) {
                        return super.loadClass(name, resolve);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
        };
    }

    @Nested
    class FieldValidationTests {
        @Test
        void validation_emitsError_forUnregisteredFieldType() {
            var source = JavaFileObjects.forSourceString("com.example.BadRecord",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;
                import java.net.InetSocketAddress;

                @Codec
                public record BadRecord(String name, InetSocketAddress address) {}
                """);

            var compilation = compileWith(source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("Field 'address' of type 'java.net.InetSocketAddress'");
            assertThat(compilation).hadErrorContaining("has no codec");
        }

        @Test
        void validation_succeeds_forCodecAnnotatedFieldType() {
            var enumSource = JavaFileObjects.forSourceString("com.example.Role",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;

                @Codec
                public enum Role { ADMIN, USER, UNKNOWN }
                """);
            var recordSource = JavaFileObjects.forSourceString("com.example.Account",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;

                @Codec
                public record Account(String name, Role role) {}
                """);

            var compilation = compileWith(enumSource, recordSource);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.AccountCodec");
        }

        @Test
        void validation_succeeds_forBuiltinTypes() {
            var source = JavaFileObjects.forSourceString("com.example.BuiltinRecord",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;
                import java.util.List;
                import java.util.Map;
                import java.util.Set;

                @Codec
                public record BuiltinRecord(
                    String name,
                    int count,
                    long timestamp,
                    boolean active,
                    double score,
                    List<String> tags,
                    Map<String, String> metadata,
                    Set<String> categories
                ) {}
                """);

            var compilation = compileWith(source);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.BuiltinRecordCodec");
        }

        @Test
        void validation_succeeds_forListOfCodecType() {
            var itemSource = JavaFileObjects.forSourceString("com.example.Item",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;

                @Codec
                public record Item(String value) {}
                """);
            var containerSource = JavaFileObjects.forSourceString("com.example.Container",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;
                import java.util.List;

                @Codec
                public record Container(List<Item> items) {}
                """);

            var compilation = compileWith(itemSource, containerSource);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.ContainerCodec");
        }

        @Test
        void validation_emitsError_forUnregisteredFieldInSealedSubtype() {
            var source = JavaFileObjects.forSourceString("com.example.Message",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;
                import java.net.URI;

                @Codec
                public sealed interface Message permits Message.Text, Message.Link {
                    @Codec(tag = 1)
                    record Text(String content) implements Message {}

                    @Codec(tag = 2)
                    record Link(URI url) implements Message {}
                }
                """);

            var compilation = compileWith(source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("Field 'url' of type 'java.net.URI'");
            assertThat(compilation).hadErrorContaining("has no codec");
        }

        @Test
        void validation_succeeds_forByteArrayField() {
            var source = JavaFileObjects.forSourceString("com.example.Binary",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;

                @Codec
                public record Binary(String name, byte[] data) {}
                """);

            var compilation = compileWith(source);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.BinaryCodec");
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

    @Nested
    class CodecForTests {
        @Test
        void codecFor_suppressesError_forExternalClassType() {
            var externalType = JavaFileObjects.forSourceString("com.example.ExternalType",
                """
                package com.example;

                public class ExternalType {
                    private final String value;
                    public ExternalType(String value) { this.value = value; }
                    public String value() { return value; }
                }
                """);
            var source = JavaFileObjects.forSourceString("com.example.MyRecord",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;
                import org.pragmatica.serialization.CodecFor;

                @Codec
                @CodecFor(ExternalType.class)
                public record MyRecord(String name, ExternalType ext) {}
                """);

            var compilation = compileWith(externalType, source);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.MyRecordCodec");
        }

        @Test
        void codecFor_generatesEnumCodec_forExternalEnum() {
            var externalEnum = JavaFileObjects.forSourceString("com.example.ExternalEnum",
                """
                package com.example;

                public enum ExternalEnum { A, B, C, UNKNOWN }
                """);
            var source = JavaFileObjects.forSourceString("com.example.Wrapper",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;
                import org.pragmatica.serialization.CodecFor;

                @Codec
                @CodecFor(ExternalEnum.class)
                public record Wrapper(String name, ExternalEnum kind) {}
                """);

            var compilation = compileWith(externalEnum, source);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.ExternalEnumCodec")
                                   .contentsAsUtf8String()
                                   .contains("TypeCodec<ExternalEnum> CODEC");
        }

        @Test
        void codecFor_generatesRecordCodec_forExternalRecord() {
            var externalRecord = JavaFileObjects.forSourceString("com.example.ExternalRecord",
                """
                package com.example;

                public record ExternalRecord(int x, int y) {}
                """);
            var source = JavaFileObjects.forSourceString("com.example.Container",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;
                import org.pragmatica.serialization.CodecFor;

                @Codec
                @CodecFor(ExternalRecord.class)
                public record Container(String label, ExternalRecord point) {}
                """);

            var compilation = compileWith(externalRecord, source);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.ExternalRecordCodec")
                                   .contentsAsUtf8String()
                                   .contains("TypeCodec<ExternalRecord> CODEC");
            assertThat(compilation).generatedSourceFile("com.example.ContainerCodec");
        }

        @Test
        void codecFor_generatesRequiredTypes_inRegistry() {
            var externalType = JavaFileObjects.forSourceString("com.example.ExternalType",
                """
                package com.example;

                public class ExternalType {}
                """);
            var source = JavaFileObjects.forSourceString("com.example.MyRecord",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;
                import org.pragmatica.serialization.CodecFor;

                @Codec
                @CodecFor(ExternalType.class)
                public record MyRecord(String name) {}
                """);

            var compilation = compileWith(externalType, source);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.ExampleCodecs")
                                   .contentsAsUtf8String()
                                   .contains("Set<Class<?>> REQUIRED_TYPES = Set.of(");
            assertThat(compilation).generatedSourceFile("com.example.ExampleCodecs")
                                   .contentsAsUtf8String()
                                   .contains("com.example.ExternalType.class");
        }

        @Test
        void codecFor_emptyRequiredTypes_whenNoCodecFor() {
            var source = JavaFileObjects.forSourceString("com.example.Simple",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;

                @Codec
                public record Simple(String value) {}
                """);

            var compilation = compileWith(source);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.ExampleCodecs")
                                   .contentsAsUtf8String()
                                   .contains("Set<Class<?>> REQUIRED_TYPES = Set.of();");
        }

        @Test
        void codecFor_stillFailsCompilation_forUnlistedExternalType() {
            var source = JavaFileObjects.forSourceString("com.example.BadRecord",
                """
                package com.example;

                import org.pragmatica.serialization.Codec;
                import java.net.InetSocketAddress;

                @Codec
                public record BadRecord(String name, InetSocketAddress address) {}
                """);

            var compilation = compileWith(source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("has no codec");
        }
    }

    private static Compilation compileWith(javax.tools.JavaFileObject... sources) {
        return javac().withProcessors(new CodecProcessor())
                      .compile(sources);
    }
}
