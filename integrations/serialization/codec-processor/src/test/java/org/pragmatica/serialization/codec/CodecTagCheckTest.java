package org.pragmatica.serialization.codec;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.TagMismatchException;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// #1459: a record codec writes a type discriminator before every field and must CHECK it on read. The generated
/// reader used to consume the byte (or, for a codec-typed field, the varint tag) and run the compile-time reader
/// anyway, so a frame written with a different field shape decoded as garbage instead of failing. The mismatch is now a
/// typed [TagMismatchException] naming the field, the tag it expected and the tag it found. The wire format is
/// unchanged: the tags were already written.
class CodecTagCheckTest {
    private static final javax.tools.JavaFileObject INNER = JavaFileObjects.forSourceString("com.example.Inner",
        """
        package com.example;

        import org.pragmatica.serialization.Codec;

        @Codec(tag = 200)
        public record Inner(int n) {}
        """);

    private static final javax.tools.JavaFileObject PROBE = JavaFileObjects.forSourceString("com.example.Probe",
        """
        package com.example;

        import org.pragmatica.serialization.Codec;

        @Codec(tag = 201)
        public record Probe(int a, String s, long l, Inner inner) {}
        """);

    private static final javax.tools.JavaFileObject ALL_KINDS = JavaFileObjects.forSourceString("com.example.AllKinds",
        """
        package com.example;

        import org.pragmatica.serialization.Codec;

        @Codec(tag = 202)
        public record AllKinds(byte b, short sh, char c, int i, long l, float f, double d, boolean z, String s) {}
        """);

    @Test
    void generatedReader_checksEveryFieldTag() {
        var compilation = compileWith(ALL_KINDS);

        assertThat(compilation).succeeded();

        var source = generatedSource(compilation, "com.example.AllKindsCodec");

        for (var tag : List.of("TAG_BYTE", "TAG_SHORT", "TAG_CHAR", "TAG_INT", "TAG_LONG", "TAG_FLOAT", "TAG_DOUBLE", "TAG_STRING")) {
            assertTrue(source.contains("SliceCodec.expectTag(buf, SliceCodec." + tag + ","), "reader must check " + tag + " in:\n" + source);
        }
        assertTrue(!source.contains("        buf.readByte();\n"), "no discarded tag byte may remain in:\n" + source);
    }

    @Test
    void generatedReader_checksTheCodecTagOfACodecTypedField() {
        var source = generatedSource(compileWith(INNER, PROBE), "com.example.ProbeCodec");

        assertTrue(source.contains("SliceCodec.expectCompactTag(buf, InnerCodec.TAG,"), "reader must check the codec tag in:\n" + source);
        assertTrue(!source.contains("        SliceCodec.readCompact(buf);\n"), "no discarded codec tag may remain in:\n" + source);
    }

    @Test
    void roundTrip_stillWorks_forEveryFieldKind() throws Exception {
        var loader = loaderOf(compileWith(ALL_KINDS));
        var type = loader.loadClass("com.example.AllKinds");
        var value = type.getConstructors()[0].newInstance((byte) 1, (short) 2, 'c', 3, 4L, 5.5f, 6.5d, true, "s");
        var codec = codecOf(loader);
        var buf = Unpooled.buffer();

        codec.write(buf, value);
        Object decoded = codec.read(buf);

        assertEquals(value, decoded);
    }

    @Test
    void read_failsWithTagMismatch_whenAPrimitiveTagIsCorrupted() throws Exception {
        var loader = loaderOf(compileWith(INNER, PROBE));
        var buf = encodedProbe(loader);

        buf.setByte(firstFieldOffset(buf), SliceCodec.TAG_LONG);

        var failure = assertThrows(TagMismatchException.class, () -> codecOf(loader).read(buf));

        assertEquals(SliceCodec.TAG_INT, failure.expected());
        assertEquals(SliceCodec.TAG_LONG, failure.actual());
        assertTrue(failure.getMessage().contains("Probe.a"), failure.getMessage());
    }

    @Test
    void read_failsWithTagMismatch_whenAStringTagIsCorrupted() throws Exception {
        var loader = loaderOf(compileWith(INNER, PROBE));
        var buf = encodedProbe(loader);

        buf.setByte(firstFieldOffset(buf) + 1 + Integer.BYTES, SliceCodec.TAG_INT);

        var failure = assertThrows(TagMismatchException.class, () -> codecOf(loader).read(buf));

        assertEquals(SliceCodec.TAG_STRING, failure.expected());
        assertTrue(failure.getMessage().contains("Probe.s"), failure.getMessage());
    }

    @Test
    void read_failsWithTagMismatch_whenACodecTypedFieldCarriesAnotherCodecsTag() throws Exception {
        var loader = loaderOf(compileWith(INNER, PROBE));
        var buf = encodedProbe(loader);
        // Inner is the last field: a 2-byte varint codec tag (200) followed by its body, one int tag byte and four int bytes.
        var tagOffset = buf.writerIndex() - (1 + Integer.BYTES) - 2;

        assertEquals(200, readCompactAt(buf, tagOffset));
        buf.setByte(tagOffset, buf.getByte(tagOffset) ^ 1);

        var failure = assertThrows(TagMismatchException.class, () -> codecOf(loader).read(buf));

        assertEquals(200, failure.expected());
        assertEquals(201, failure.actual());
        assertTrue(failure.getMessage().contains("Probe.inner"), failure.getMessage());
    }

    private static ByteBuf encodedProbe(ClassLoader loader) throws Exception {
        var inner = loader.loadClass("com.example.Inner").getConstructor(int.class).newInstance(9);
        var probe = loader.loadClass("com.example.Probe")
                          .getConstructor(int.class, String.class, long.class, loader.loadClass("com.example.Inner"))
                          .newInstance(7, "x", 8L, inner);
        var buf = Unpooled.buffer();

        codecOf(loader).write(buf, probe);

        return buf;
    }

    /// Offset of the first field's tag: right after the record's own varint type tag.
    private static int firstFieldOffset(ByteBuf buf) {
        var probe = buf.duplicate();

        SliceCodec.readCompact(probe);

        return probe.readerIndex();
    }

    private static int readCompactAt(ByteBuf buf, int offset) {
        var probe = buf.duplicate().readerIndex(offset);

        return SliceCodec.readCompact(probe);
    }

    @SuppressWarnings("unchecked")
    private static SliceCodec codecOf(ClassLoader loader) throws Exception {
        var codecs = (List<SliceCodec.TypeCodec<?>>) loader.loadClass("com.example.ExampleCodecs")
                                                           .getField("CODECS")
                                                           .get(null);

        return SliceCodec.sliceCodec(codecs);
    }

    private static String generatedSource(Compilation compilation, String className) {
        try {
            return compilation.generatedSourceFile(className).orElseThrow().getCharContent(true).toString();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Compilation compileWith(javax.tools.JavaFileObject... sources) {
        return javac().withProcessors(new CodecProcessor())
                      .compile(sources);
    }

    private static ClassLoader loaderOf(Compilation compilation) throws IOException {
        assertThat(compilation).succeeded();

        var classes = new HashMap<String, byte[]>();

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

        return new ClassLoader(CodecTagCheckTest.class.getClassLoader()) {
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
}
