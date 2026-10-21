package org.pragmatica.serialization.codec;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.pragmatica.serialization.SliceCodec;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /// `@Json` refuses at compile time what its generated codec cannot carry, rather than failing at the first
    /// request. Map keys are the subtle case: a JSON object key is a string, so only a String, an enum, or a
    /// value object over a String qualifies.
    @Nested
    class JsonTests {
        @Test
        void json_generatesCodec_forMapKeyedByValueObjectOverString() {
            var source = JavaFileObjects.forSourceString("com.example.Prices",
                """
                package com.example;

                import org.pragmatica.lang.Result;
                import org.pragmatica.serialization.json.Json;
                import java.util.Map;

                @Json
                public record Prices(Map<Sku, Long> bySku) {
                    public record Sku(String value) {
                        public static Result<Sku> sku(String value) {
                            return Result.success(new Sku(value));
                        }
                    }
                }
                """);

            var compilation = compileWith(source);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.PricesJson")
                                   .contentsAsUtf8String()
                                   .contains("Sku.sku(in.readKey())");
        }

        @Test
        void json_failsCompilation_forIntegerMapKey() {
            var source = JavaFileObjects.forSourceString("com.example.Counts",
                """
                package com.example;

                import org.pragmatica.serialization.json.Json;
                import java.util.Map;

                @Json
                public record Counts(Map<Integer, String> byId) {}
                """);

            var compilation = compileWith(source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("@Json type 'com.example.Counts' cannot get a generated codec");
            assertThat(compilation).hadErrorContaining("map key must be a String");
        }

        @Test
        void json_failsCompilation_forMapKeyedByValueObjectOverLong() {
            var source = JavaFileObjects.forSourceString("com.example.Ledger",
                """
                package com.example;

                import org.pragmatica.lang.Result;
                import org.pragmatica.serialization.json.Json;
                import java.util.Map;

                @Json
                public record Ledger(Map<AccountNo, Long> balances) {
                    public record AccountNo(long value) {
                        public static Result<AccountNo> accountNo(long value) {
                            return Result.success(new AccountNo(value));
                        }
                    }
                }
                """);

            var compilation = compileWith(source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("map key must be a String");
        }

        @Test
        void json_failsCompilation_forInterfaceComponent() {
            var source = JavaFileObjects.forSourceString("com.example.Holder",
                """
                package com.example;

                import org.pragmatica.serialization.json.Json;

                @Json
                public record Holder(Runnable task) {}
                """);

            var compilation = compileWith(source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("@Json type 'com.example.Holder' cannot get a generated codec");
            assertThat(compilation).hadErrorContaining("neither a record nor an enum");
        }
    }

    /// A type Jackson would shape through its annotations is refused, wherever the annotation sits: a generated
    /// codec would silently ignore it and change the wire form.
    @Nested
    class JacksonAnnotatedTypes {
        @Test
        void json_failsCompilation_forJsonPropertyOnComponent() {
            var source = JavaFileObjects.forSourceString("com.example.Renamed",
                """
                package com.example;

                import com.fasterxml.jackson.annotation.JsonProperty;
                import org.pragmatica.serialization.json.Json;

                @Json
                public record Renamed(@JsonProperty("user_name") String userName) {}
                """);

            var compilation = compileWith(source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("@Json type 'com.example.Renamed' cannot get a generated codec");
            assertThat(compilation).hadErrorContaining("Jackson annotation @com.fasterxml.jackson.annotation.JsonProperty on com.example.Renamed");
        }

        @Test
        void json_failsCompilation_forJsonTypeInfoOnImplementedInterface() {
            var source = JavaFileObjects.forSourceString("com.example.Shapes",
                """
                package com.example;

                import com.fasterxml.jackson.annotation.JsonTypeInfo;
                import org.pragmatica.serialization.json.Json;

                public interface Shapes {
                    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
                    sealed interface Shape permits Circle {}

                    @Json
                    record Circle(int r) implements Shape {}
                }
                """);

            var compilation = compileWith(source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("Jackson annotation @com.fasterxml.jackson.annotation.JsonTypeInfo on com.example.Shapes.Shape");
        }

        @Test
        void json_failsCompilation_forJsonPropertyOnEnumConstant() {
            var source = JavaFileObjects.forSourceString("com.example.Level",
                """
                package com.example;

                import com.fasterxml.jackson.annotation.JsonProperty;
                import org.pragmatica.serialization.json.Json;

                @Json
                public enum Level {
                    @JsonProperty("lo") LOW,
                    HIGH
                }
                """);

            var compilation = compileWith(source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("Jackson annotation @com.fasterxml.jackson.annotation.JsonProperty on com.example.Level.LOW");
        }

        @Test
        void json_failsCompilation_forJacksonMetaAnnotation() {
            var marker = JavaFileObjects.forSourceString("com.example.Snake",
                """
                package com.example;

                import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.RUNTIME)
                @JacksonAnnotationsInside
                public @interface Snake {}
                """);
            var source = JavaFileObjects.forSourceString("com.example.Tagged",
                """
                package com.example;

                import org.pragmatica.serialization.json.Json;

                @Json
                @Snake
                public record Tagged(String name) {}
                """);

            var compilation = compileWith(marker, source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("Jackson annotation @com.example.Snake on com.example.Tagged");
        }

        // Jackson binds through creator parameters, so an annotation found only on a parameter must refuse the type.
        @Test
        void json_failsCompilation_forJsonPropertyOnlyOnCanonicalConstructorParameter() {
            var source = JavaFileObjects.forSourceString("com.example.Renamed",
                """
                package com.example;

                import com.fasterxml.jackson.annotation.JsonProperty;
                import org.pragmatica.serialization.json.Json;

                @Json
                public record Renamed(String userName) {
                    public Renamed(@JsonProperty("user_name") String userName) {
                        this.userName = userName;
                    }
                }
                """);

            var compilation = compileWith(source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("Jackson annotation @com.fasterxml.jackson.annotation.JsonProperty on parameter userName of com.example.Renamed.<init>");
        }

        @Test
        void json_failsCompilation_forJsonPropertyOnlyOnFactoryParameter() {
            var source = JavaFileObjects.forSourceString("com.example.Handle",
                """
                package com.example;

                import com.fasterxml.jackson.annotation.JsonProperty;
                import org.pragmatica.lang.Result;
                import org.pragmatica.serialization.json.Json;

                @Json
                public record Handle(String value) {
                    public static Result<Handle> handle(@JsonProperty("h") String value) {
                        return Result.success(new Handle(value));
                    }
                }
                """);

            var compilation = compileWith(source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("Jackson annotation @com.fasterxml.jackson.annotation.JsonProperty on parameter value of com.example.Handle.handle");
        }

        // A container refused for its element says why the element was refused, not just that the container was.
        @Test
        void json_failsCompilation_namingTheInnermostRefusal_forARefusedListElement() {
            var source = JavaFileObjects.forSourceString("com.example.Outer",
                """
                package com.example;

                import com.fasterxml.jackson.annotation.JsonProperty;
                import org.pragmatica.serialization.json.Json;
                import java.util.List;

                @Json
                public record Outer(List<Inner> items) {
                    public record Inner(@JsonProperty("n") String name) {}
                }
                """);

            var compilation = compileWith(source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("component items: java.util.List<com.example.Outer.Inner>: com.example.Outer.Inner: "
                                                       + "Jackson annotation @com.fasterxml.jackson.annotation.JsonProperty on com.example.Outer.Inner");
        }

        // The innermost refusal is found through any depth of container nesting.
        @Test
        void json_failsCompilation_namingTheInnermostRefusal_throughNestedContainers() {
            var source = JavaFileObjects.forSourceString("com.example.Deep",
                """
                package com.example;

                import com.fasterxml.jackson.annotation.JsonProperty;
                import org.pragmatica.serialization.json.Json;
                import java.util.List;

                @Json
                public record Deep(List<List<Bad>> rows) {
                    public record Bad(@JsonProperty("n") String name) {}
                }
                """);

            var compilation = compileWith(source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("component rows: java.util.List<java.util.List<com.example.Deep.Bad>>: "
                                                       + "java.util.List<com.example.Deep.Bad>: com.example.Deep.Bad: "
                                                       + "Jackson annotation @com.fasterxml.jackson.annotation.JsonProperty on com.example.Deep.Bad");
        }

        // ...and through a map value as well as a list element.
        @Test
        void json_failsCompilation_namingTheInnermostRefusal_forAListNestedInAMapValue() {
            var source = JavaFileObjects.forSourceString("com.example.Deeper",
                """
                package com.example;

                import com.fasterxml.jackson.annotation.JsonProperty;
                import org.pragmatica.serialization.json.Json;
                import java.util.List;
                import java.util.Map;

                @Json
                public record Deeper(Map<String, List<Bad>> byName) {
                    public record Bad(@JsonProperty("n") String name) {}
                }
                """);

            var compilation = compileWith(source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("component byName: java.util.Map<java.lang.String,java.util.List<com.example.Deeper.Bad>>: "
                                                       + "java.util.List<com.example.Deeper.Bad>: com.example.Deeper.Bad: "
                                                       + "Jackson annotation @com.fasterxml.jackson.annotation.JsonProperty on com.example.Deeper.Bad");
        }

        @Test
        void json_generatesCodec_forUnannotatedSiblingOfRefusedType() {
            var source = JavaFileObjects.forSourceString("com.example.Plain",
                """
                package com.example;

                import org.pragmatica.serialization.json.Json;

                @Json
                public record Plain(String userName) {}
                """);

            assertThat(compileWith(source)).succeeded();
        }
    }

    /// The `@Json` user surface: one flat `<Flat>Json` entry point per annotated type, which is also that type's
    /// `ServiceLoader` provider for `JsonCodecs.codecFor`, listed in one services file however many rounds it took.
    @Nested
    class JsonEntryPointAndServices {
        private static final String SERVICES = "META-INF/services/org.pragmatica.serialization.json.spi.JsonCodecProvider";

        @Test
        void entryPoint_isAFlatClassNamedAfterTheType_withCodecShortcutsAndHiddenMachinery() {
            var source = JavaFileObjects.forSourceString("com.example.Ticketing",
                """
                package com.example;

                import org.pragmatica.serialization.json.Json;

                public interface Ticketing {
                    @Json
                    record BuyRequest(Seat seat) {}

                    record Seat(String row) {}
                }
                """);

            var compilation = compileWith(source);

            assertThat(compilation).succeeded();
            var entryPoint = assertThat(compilation).generatedSourceFile("com.example.Ticketing_BuyRequestJson")
                                                    .contentsAsUtf8String();

            entryPoint.contains("@org.pragmatica.serialization.json.spi.GeneratedFor(\"com.example.Ticketing.BuyRequest\")\n"
                                + "public final class Ticketing_BuyRequestJson implements org.pragmatica.serialization.json.spi.JsonCodecProvider {");
            entryPoint.contains("public static final org.pragmatica.serialization.json.JsonTypeCodec<com.example.Ticketing.BuyRequest> CODEC = Ticketing_BuyRequestJsonCodec.CODEC;");
            entryPoint.contains("public static org.pragmatica.lang.Result<com.example.Ticketing.BuyRequest> decode(byte[] json) {");
            entryPoint.contains("public static org.pragmatica.lang.Result<com.example.Ticketing.BuyRequest> decode(java.lang.String json) {");
            entryPoint.contains("public static byte[] encode(com.example.Ticketing.BuyRequest value) {");
            entryPoint.contains("public static final java.util.Map<java.lang.Class<?>, org.pragmatica.serialization.json.JsonTypeCodec<?>> CODECS");
            entryPoint.contains("java.util.Map.entry(com.example.Ticketing.Seat.class, Ticketing_SeatJsonCodec.CODEC)");
            entryPoint.contains("\n    interface Ticketing_SeatJsonCodec {");
            entryPoint.contains("org.pragmatica.serialization.json.spi.JsonReader in");
            entryPoint.contains("    public Ticketing_BuyRequestJson() {}");
            entryPoint.contains("    public java.lang.Class<?> type() {\n        return com.example.Ticketing.BuyRequest.class;\n    }");
            entryPoint.contains("    public org.pragmatica.serialization.json.JsonTypeCodec<?> codec() {\n        return CODEC;\n    }");
            entryPoint.doesNotContain("public interface");
        }

        // One provider per @Json type, sorted by name; a type reachable only as a component (Inner) has none, and no
        // per-package class or manifest is generated.
        @Test
        void services_listsEveryEntryPoint_sorted_andNothingElse() {
            var first = JavaFileObjects.forSourceString("com.example.a.First",
                """
                package com.example.a;

                @org.pragmatica.serialization.json.Json
                public record First(Inner inner) {
                    public record Inner(String text) {}
                }
                """);
            var second = JavaFileObjects.forSourceString("com.example.a.Second",
                """
                package com.example.a;

                @org.pragmatica.serialization.json.Json
                public enum Second { ONE, UNKNOWN }
                """);
            var other = JavaFileObjects.forSourceString("com.example.b.Other",
                """
                package com.example.b;

                @org.pragmatica.serialization.json.Json
                public record Other(long id) {}
                """);

            var compilation = compileWith(other, second, first);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, SERVICES)
                                   .contentsAsUtf8String()
                                   .isEqualTo("com.example.a.FirstJson\ncom.example.a.SecondJson\ncom.example.b.OtherJson\n");
            assertEquals(Set.of("com.example.a.FirstJson", "com.example.a.SecondJson", "com.example.b.OtherJson"),
                         compilation.generatedSourceFiles()
                                    .stream()
                                    .map(file -> file.getName().replaceAll("^.*?/com/", "com/").replace(".java", "").replace('/', '.'))
                                    .collect(Collectors.toSet()));
            assertEquals(List.of(SERVICES),
                         compilation.generatedFiles()
                                    .stream()
                                    .filter(file -> file.getKind() == JavaFileObject.Kind.OTHER)
                                    .map(file -> file.getName().replaceAll("^.*?/META-INF/", "META-INF/"))
                                    .toList());
        }

        // The binary @Codec registry option leaves JSON alone: the provider is still named after the type.
        @Test
        void services_ignoresTheBinaryRegistrySuffixOption() {
            var source = JavaFileObjects.forSourceString("com.example.Msg",
                """
                package com.example;

                @org.pragmatica.serialization.json.Json
                public record Msg(String text) {}
                """);

            var compilation = javac().withProcessors(new CodecProcessor())
                                     .withOptions("-Acodec.registry.suffix=Test")
                                     .compile(source);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, SERVICES)
                                   .contentsAsUtf8String()
                                   .isEqualTo("com.example.MsgJson\n");
        }

        // Late is generated by another processor in Early's round, or in the round after (when EarlyJson is a root):
        // either way the one services file, written in the last round, lists both.
        @Test
        void services_listsTypesArrivingInLaterRounds() {
            for (var trigger : List.of("com.example.Early", "com.example.EarlyJson")) {
                var compilation = javac().withProcessors(new CodecProcessor(), new LateJsonType(trigger))
                                         .compile(early());

                assertThat(compilation).succeeded();
                assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, SERVICES)
                                       .contentsAsUtf8String()
                                       .isEqualTo("com.example.EarlyJson\ncom.example.LateJson\n");
            }
        }

        // @Deprecated is an annotation CodecProcessor does not claim, so the helper is asked in Early's round too.
        private static JavaFileObject early() {
            return JavaFileObjects.forSourceString("com.example.Early",
                                                   "package com.example;\n\n@Deprecated\n@org.pragmatica.serialization.json.Json\npublic record Early(String text) {}\n");
        }
    }

    /// Stands in for another processor that generates a `@Json` type: once, in the round where `trigger` is a root.
    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_25)
    static final class LateJsonType extends AbstractProcessor {
        private final String trigger;
        private boolean generated;

        LateJsonType(String trigger) {
            this.trigger = trigger;
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            var triggered = roundEnv.getRootElements()
                                    .stream()
                                    .anyMatch(root -> root instanceof TypeElement type && type.getQualifiedName().contentEquals(trigger));

            if (triggered && !generated) {
                generated = true;
                try (var writer = processingEnv.getFiler().createSourceFile("com.example.Late").openWriter()) {
                    writer.write("package com.example;\n\n@org.pragmatica.serialization.json.Json\npublic record Late(long id) {}\n");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            return false;
        }
    }

    /// A generated name that is already taken (by a user type, or by a class generated for another source) is one
    /// clean compile error naming both and the way out, never the Filer's exception text.
    @Nested
    class JsonNameClashes {
        @Test
        void userTypeNamedLikeTheEntryPoint_isOneCleanError() {
            var order = JavaFileObjects.forSourceString("com.example.Order",
                                                        "package com.example;\n\n@org.pragmatica.serialization.json.Json\npublic record Order(String id) {}\n");
            var userType = JavaFileObjects.forSourceString("com.example.OrderJson",
                                                           "package com.example;\n\npublic class OrderJson {}\n");

            var compilation = compileWith(order, userType);

            assertSingleCleanError(compilation,
                                   "Cannot generate com.example.OrderJson for com.example.Order: the type com.example.OrderJson already exists",
                                   "If it is stale output of an older build, clean the build",
                                   "otherwise rename com.example.OrderJson, or rename or move com.example.Order.");
        }

        // Flat names are injective: a nested A.B is A_BJson, a top-level A_B is A_0BJson, so both compile, each entry
        // point typed to its own type, and the services file lists both.
        @Test
        void nestedTypeAndTopLevelTypeWithAnUnderscore_getDistinctEntryPoints() {
            var nested = JavaFileObjects.forSourceString("com.example.A",
                                                         "package com.example;\n\npublic interface A {\n    @org.pragmatica.serialization.json.Json\n    record B(String x) {}\n}\n");
            var topLevel = JavaFileObjects.forSourceString("com.example.A_B",
                                                           "package com.example;\n\n@org.pragmatica.serialization.json.Json\npublic record A_B(String y) {}\n");

            var compilation = compileWith(nested, topLevel);

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.A_BJson")
                                   .contentsAsUtf8String()
                                   .contains("JsonTypeCodec<com.example.A.B> CODEC = A_BJsonCodec.CODEC;");
            assertThat(compilation).generatedSourceFile("com.example.A_0BJson")
                                   .contentsAsUtf8String()
                                   .contains("JsonTypeCodec<com.example.A_B> CODEC = A_0BJsonCodec.CODEC;");
            assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT,
                                                  "META-INF/services/org.pragmatica.serialization.json.spi.JsonCodecProvider")
                                   .contentsAsUtf8String()
                                   .isEqualTo("com.example.A_0BJson\ncom.example.A_BJson\n");
        }

        // Inside one entry point too: a reachable nested Outer.In and a reachable top-level Outer_In get distinct
        // nested codec interfaces, never a numbered fallback.
        @Test
        void reachableTypesWithCollidingJoinedNames_getDistinctNestedCodecs() {
            var outer = JavaFileObjects.forSourceString("com.example.Outer",
                                                        "package com.example;\n\npublic interface Outer {\n    record In(String a) {}\n}\n");
            var flat = JavaFileObjects.forSourceString("com.example.Outer_In",
                                                       "package com.example;\n\npublic record Outer_In(String b) {}\n");
            var root = JavaFileObjects.forSourceString("com.example.Root",
                                                       "package com.example;\n\n@org.pragmatica.serialization.json.Json\npublic record Root(Outer.In first, Outer_In second) {}\n");

            var compilation = compileWith(outer, flat, root);

            assertThat(compilation).succeeded();
            var entryPoint = assertThat(compilation).generatedSourceFile("com.example.RootJson")
                                                    .contentsAsUtf8String();

            entryPoint.contains("java.util.Map.entry(com.example.Outer.In.class, Outer_InJsonCodec.CODEC)");
            entryPoint.contains("java.util.Map.entry(com.example.Outer_In.class, Outer_0InJsonCodec.CODEC)");
            entryPoint.doesNotContain("JsonCodec2");
        }

        // A class with the entry point's name that was generated for another source (by its marker) is refused, never
        // overwritten: one source's codec must not silently replace another's.
        @Test
        void classGeneratedForAnotherSource_isOneCleanError() {
            var order = JavaFileObjects.forSourceString("com.example.Order",
                                                        "package com.example;\n\n@org.pragmatica.serialization.json.Json\npublic record Order(String id) {}\n");
            var other = JavaFileObjects.forSourceString("com.example.OrderJson",
                                                        "package com.example;\n\n@org.pragmatica.serialization.json.spi.GeneratedFor(\"com.example.Other\")\npublic final class OrderJson {}\n");

            var compilation = compileWith(order, other);

            assertSingleCleanError(compilation,
                                   "Cannot generate com.example.OrderJson for com.example.Order: it already exists, generated for com.example.Other",
                                   "Rename one of the two");
        }

        // Flat names are unique within a package only: reachable types of two packages sharing one get numbered nested
        // codec names, assigned in qualified-name order, and the entry point compiles and keeps each codec apart.
        @Test
        void reachableTypesOfTwoPackagesWithOneFlatName_getNumberedNestedCodecs() {
            var compilation = compileWith(sameFlatName("y", "String a"),
                                          sameFlatName("z", "long b"),
                                          JavaFileObjects.forSourceString("com.example.Root",
                                                                          "package com.example;\n\n@org.pragmatica.serialization.json.Json\npublic record Root(com.example.z.Foo second, com.example.y.Foo first) {}\n"));

            assertThat(compilation).succeeded();
            var entryPoint = assertThat(compilation).generatedSourceFile("com.example.RootJson")
                                                    .contentsAsUtf8String();

            entryPoint.contains("java.util.Map.entry(com.example.y.Foo.class, FooJsonCodec.CODEC)");
            entryPoint.contains("java.util.Map.entry(com.example.z.Foo.class, FooJsonCodec2.CODEC)");
            entryPoint.contains("\n    interface FooJsonCodec {");
            entryPoint.contains("\n    interface FooJsonCodec2 {");
        }

        // The entry point's root keeps its plain nested name; a reached type of another package with its flat name is
        // numbered.
        @Test
        void rootAndAReachableTypeOfAnotherPackageWithItsFlatName_rootKeepsThePlainName() {
            var compilation = compileWith(sameFlatName("y", "String a"),
                                          JavaFileObjects.forSourceString("com.example.Foo",
                                                                          "package com.example;\n\n@org.pragmatica.serialization.json.Json\npublic record Foo(com.example.y.Foo inner) {}\n"));

            assertThat(compilation).succeeded();
            var entryPoint = assertThat(compilation).generatedSourceFile("com.example.FooJson")
                                                    .contentsAsUtf8String();

            entryPoint.contains("JsonTypeCodec<com.example.Foo> CODEC = FooJsonCodec.CODEC;");
            entryPoint.contains("java.util.Map.entry(com.example.y.Foo.class, FooJsonCodec2.CODEC)");
        }

        // Root first is a rule of its own, not a side effect of qualified-name order: this root (z) sorts after the
        // reached y.Foo, and still keeps the plain name while y.Foo is numbered.
        @Test
        void rootSortingAfterAReachedTypeOfItsFlatName_stillKeepsThePlainName() {
            var compilation = compileWith(sameFlatName("y", "String a"),
                                          JavaFileObjects.forSourceString("com.example.z.Foo",
                                                                          "package com.example.z;\n\n@org.pragmatica.serialization.json.Json\npublic record Foo(com.example.y.Foo inner) {}\n"));

            assertThat(compilation).succeeded();
            var entryPoint = assertThat(compilation).generatedSourceFile("com.example.z.FooJson")
                                                    .contentsAsUtf8String();

            entryPoint.contains("JsonTypeCodec<com.example.z.Foo> CODEC = FooJsonCodec.CODEC;");
            entryPoint.contains("java.util.Map.entry(com.example.y.Foo.class, FooJsonCodec2.CODEC)");
        }

        private static JavaFileObject sameFlatName(String packageSegment, String component) {
            return JavaFileObjects.forSourceString("com.example." + packageSegment + ".Foo",
                                                   "package com.example." + packageSegment + ";\n\npublic record Foo(" + component + ") {}\n");
        }

        private static void assertSingleCleanError(Compilation compilation, String... fragments) {
            assertThat(compilation).failed();
            assertEquals(1, compilation.errors().size(), compilation.errors().toString());
            var message = compilation.errors().getFirst().getMessage(null);

            for (var fragment : fragments) {
                assertTrue(message.contains(fragment), message);
            }

            assertFalse(message.contains("\tat ") || message.contains("Exception") || message.contains("FilerException"), message);
        }
    }

    /// `@Json` where the processor cannot serve it is one clean compile error, never a broken generated source or a
    /// silently ignored annotation.
    @Nested
    class UnsupportedJsonPlacements {
        @Test
        void typeInTheUnnamedPackage_isOneCleanError() {
            var compilation = compileWith(JavaFileObjects.forSourceString("Loose",
                                                                          "@org.pragmatica.serialization.json.Json\npublic record Loose(String a) {}\n"));

            JsonNameClashes.assertSingleCleanError(compilation,
                                                   "@Json type 'Loose' is in the unnamed package",
                                                   "Declare it in a named package");
        }

        // Annotation processing never sees a local type; the annotation elsewhere in the class (here @Override) is what
        // makes javac initialise the processor.
        @Test
        void localRecord_isOneCleanError() {
            var compilation = compileWith(JavaFileObjects.forSourceString("com.example.Holder", """
                package com.example;

                import org.pragmatica.serialization.json.Json;

                public class Holder {
                    public Object make() {
                        @Json
                        record Point(int x) {}

                        return new Point(1);
                    }

                    @Override
                    public String toString() {
                        return "holder";
                    }
                }
                """));

            JsonNameClashes.assertSingleCleanError(compilation,
                                                   "@Json cannot be applied to 'Point'",
                                                   "Declare it as a top-level or member type");
        }

        // A member @Json type beside it still gets its codec; only the local one is refused.
        @Test
        void localEnum_isOneCleanError_besideAMemberJsonType() {
            var compilation = compileWith(JavaFileObjects.forSourceString("com.example.Holder", """
                package com.example;

                import org.pragmatica.serialization.json.Json;

                public class Holder {
                    @Json
                    public record Member(int a) {}

                    public Object make() {
                        @Json
                        enum Colour { RED, GREEN }

                        return Colour.RED;
                    }
                }
                """));

            JsonNameClashes.assertSingleCleanError(compilation,
                                                   "@Json cannot be applied to 'Colour'",
                                                   "Declare it as a top-level or member type");
            assertThat(compilation).hadNoteContaining("Generated JSON codec: Holder_MemberJson");
        }

        // A member type is unseen too when a type enclosing it is local: the check walks the enclosing chain.
        @Test
        void memberOfALocalClass_isOneCleanError() {
            var compilation = compileWith(JavaFileObjects.forSourceString("com.example.Holder", """
                package com.example;

                import org.pragmatica.serialization.json.Json;

                public class Holder {
                    public Object make() {
                        class Box {
                            @Json
                            record Point(int x) {}
                        }

                        return new Box.Point(1);
                    }

                    @Override
                    public String toString() {
                        return "holder";
                    }
                }
                """));

            JsonNameClashes.assertSingleCleanError(compilation,
                                                   "@Json cannot be applied to 'Point'",
                                                   "Declare it as a top-level or member type");
        }

        // The same for a member of an anonymous class, which is no local class: anonymous nesting is its own case.
        @Test
        void memberOfAnAnonymousClass_isOneCleanError() {
            var compilation = compileWith(JavaFileObjects.forSourceString("com.example.Holder", """
                package com.example;

                import org.pragmatica.serialization.json.Json;

                public class Holder {
                    public Object make() {
                        return new Object() {
                            @Json
                            record Point(int x) {}

                            @Override
                            public String toString() {
                                return new Point(1).toString();
                            }
                        };
                    }

                    @Override
                    public String toString() {
                        return "holder";
                    }
                }
                """));

            JsonNameClashes.assertSingleCleanError(compilation,
                                                   "@Json cannot be applied to 'Point'",
                                                   "Declare it as a top-level or member type");
        }
    }

    /// Generated readers track fields as bits of one long (field i is `1L << i`): 64 components fit, a 65th
    /// would wrap onto bit 0 and a missing first field would go unnoticed.
    @Nested
    class ComponentLimit {
        @Test
        void json_generatesCodec_forSixtyFourComponents() {
            var compilation = compileWith(wideRecord("Wide64", 64));

            assertThat(compilation).succeeded();
            assertThat(compilation).generatedSourceFile("com.example.Wide64Json")
                                   .contentsAsUtf8String()
                                   .contains("long REQUIRED = -1L;");
        }

        @Test
        void json_failsCompilation_forSixtyFiveComponents() {
            var compilation = compileWith(wideRecord("Wide65", 65));

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("@Json type 'com.example.Wide65' cannot get a generated codec: 65 components; generated codecs support at most 64");
        }

        private static JavaFileObject wideRecord(String name, int components) {
            var fields = IntStream.range(0, components)
                                                   .mapToObj(i -> "String f" + i)
                                                   .collect(Collectors.joining(", "));

            return JavaFileObjects.forSourceString("com.example." + name,
                                                   "package com.example;\n\n@org.pragmatica.serialization.json.Json\npublic record "
                                                   + name + "(" + fields + ") {}\n");
        }
    }

    private static Compilation compileWith(javax.tools.JavaFileObject... sources) {
        return javac().withProcessors(new CodecProcessor())
                      .compile(sources);
    }
}
