/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.serialization;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.pragmatica.serialization.SliceCodec.*;

class SliceCodecTest {

    @Nested
    class VlqTests {

        @Test
        void writeCompact_zero_singleByte() {
            var buf = Unpooled.buffer();

            try {
                SliceCodec.writeCompact(buf, 0);

                assertEquals(1, buf.readableBytes());
                assertEquals(0, buf.readByte());
            } finally {
                buf.release();
            }
        }

        @Test
        void writeCompact_maxOneByte_singleByte() {
            var buf = Unpooled.buffer();

            try {
                SliceCodec.writeCompact(buf, 127);

                assertEquals(1, buf.readableBytes());
                assertEquals(127, SliceCodec.readCompact(Unpooled.wrappedBuffer(new byte[]{127})));
            } finally {
                buf.release();
            }
        }

        @Test
        void writeCompact_minTwoByte_twoBytes() {
            var buf = Unpooled.buffer();

            try {
                SliceCodec.writeCompact(buf, 128);

                assertEquals(2, buf.readableBytes());
                assertEquals(128, SliceCodec.readCompact(buf));
            } finally {
                buf.release();
            }
        }

        @Test
        void writeCompact_maxTwoByte_twoBytes() {
            var buf = Unpooled.buffer();

            try {
                SliceCodec.writeCompact(buf, 16383);

                assertEquals(2, buf.readableBytes());
                assertEquals(16383, SliceCodec.readCompact(buf));
            } finally {
                buf.release();
            }
        }

        @Test
        void writeCompact_minThreeByte_threeBytes() {
            var buf = Unpooled.buffer();

            try {
                SliceCodec.writeCompact(buf, 16384);

                assertEquals(3, buf.readableBytes());
                assertEquals(16384, SliceCodec.readCompact(buf));
            } finally {
                buf.release();
            }
        }

        @Test
        void writeCompact_largeValue_multipleBytes() {
            var buf = Unpooled.buffer();

            try {
                SliceCodec.writeCompact(buf, Integer.MAX_VALUE);

                assertTrue(buf.readableBytes() <= 5);
                assertEquals(Integer.MAX_VALUE, SliceCodec.readCompact(buf));
            } finally {
                buf.release();
            }
        }
    }

    @Nested
    class StringTests {

        @Test
        void writeString_readString_roundTrip() {
            var buf = Unpooled.buffer();

            try {
                SliceCodec.writeString(buf, "hello world");

                assertEquals("hello world", SliceCodec.readString(buf));
            } finally {
                buf.release();
            }
        }

        @Test
        void writeString_empty_roundTrip() {
            var buf = Unpooled.buffer();

            try {
                SliceCodec.writeString(buf, "");

                assertEquals("", SliceCodec.readString(buf));
            } finally {
                buf.release();
            }
        }

        @Test
        void writeString_unicode_roundTrip() {
            var buf = Unpooled.buffer();

            try {
                SliceCodec.writeString(buf, "\u043f\u0440\u0438\u0432\u0456\u0442 \ud83c\udf0d");

                assertEquals("\u043f\u0440\u0438\u0432\u0456\u0442 \ud83c\udf0d", SliceCodec.readString(buf));
            } finally {
                buf.release();
            }
        }
    }

    @Nested
    class TagTests {

        @Test
        void deterministicTag_sameInput_sameOutput() {
            var tag1 = SliceCodec.deterministicTag("com.example.MyClass");
            var tag2 = SliceCodec.deterministicTag("com.example.MyClass");

            assertEquals(tag1, tag2);
        }

        @Test
        void deterministicTag_differentInputs_inRange() {
            var names = List.of(
                "com.example.Alpha",
                "com.example.Beta",
                "com.example.Gamma",
                "org.other.Delta",
                "net.third.Epsilon"
            );

            for (var name : names) {
                var tag = SliceCodec.deterministicTag(name);

                assertTrue(tag >= 128, "Tag %d for %s below 128".formatted(tag, name));
                assertTrue(tag <= 16383, "Tag %d for %s above 16383".formatted(tag, name));
            }
        }
    }

    @Nested
    class CodecTests {

        private static final int TEST_TAG = 200;

        private static final TypeCodec<String> STRING_CODEC = new TypeCodec<>(
            String.class, TEST_TAG,
            (codec, buf, value) -> SliceCodec.writeString(buf, value),
            (codec, buf) -> SliceCodec.readString(buf)
        );

        @Test
        void sliceCodec_writeRead_roundTrip() {
            var codec = sliceCodec(List.of(STRING_CODEC));
            var buf = Unpooled.buffer();

            try {
                codec.write(buf, "test value");

                String result = codec.read(buf);
                assertEquals("test value", result);
            } finally {
                buf.release();
            }
        }

        @Test
        void sliceCodec_parentMerge_childOverrides() {
            var parentCodec = new TypeCodec<>(
                String.class, TEST_TAG,
                (codec, buf, value) -> SliceCodec.writeString(buf, "parent:" + value),
                (codec, buf) -> SliceCodec.readString(buf)
            );

            var childCodec = new TypeCodec<>(
                String.class, TEST_TAG,
                (codec, buf, value) -> SliceCodec.writeString(buf, "child:" + value),
                (codec, buf) -> SliceCodec.readString(buf)
            );

            var parent = sliceCodec(List.of(parentCodec));
            var child = sliceCodec(parent, List.of(childCodec));

            var buf = Unpooled.buffer();

            try {
                child.write(buf, "data");

                String result = child.read(buf);
                assertEquals("child:data", result);
            } finally {
                buf.release();
            }
        }

        @Test
        void sliceCodec_unknownClass_throwsException() {
            var codec = sliceCodec(List.of(STRING_CODEC));

            var buf = Unpooled.buffer();

            try {
                assertThrows(IllegalArgumentException.class, () -> codec.write(buf, 42));
            } finally {
                buf.release();
            }
        }

        @Test
        void sliceCodec_unknownTag_throwsException() {
            var codec = sliceCodec(List.of(STRING_CODEC));

            var buf = Unpooled.buffer();

            try {
                SliceCodec.writeCompact(buf, 9999);

                assertThrows(IllegalArgumentException.class, () -> codec.read(buf));
            } finally {
                buf.release();
            }
        }
    }

    @Nested
    class HeaderTests {

        @Test
        void header_writeVerify_roundTrip() {
            var buf = Unpooled.buffer();

            try {
                SliceCodec.writeHeader(buf);
                SliceCodec.verifyHeader(buf);

                assertEquals(0, buf.readableBytes());
            } finally {
                buf.release();
            }
        }
    }

    @Nested
    class RequiredTypesValidationTests {

        private static final int TEST_TAG = 200;

        private static final TypeCodec<String> STRING_CODEC = new TypeCodec<>(
            String.class, TEST_TAG,
            (codec, buf, value) -> SliceCodec.writeString(buf, value),
            (codec, buf) -> SliceCodec.readString(buf)
        );

        @Test
        void validateRequiredTypes_registeredType_noError() {
            var codec = sliceCodec(List.of(STRING_CODEC));

            assertDoesNotThrow(() -> SliceCodec.validateRequiredTypes(codec, Set.of(String.class)));
        }

        @Test
        void validateRequiredTypes_missingType_throwsException() {
            var codec = sliceCodec(List.of(STRING_CODEC));

            var ex = assertThrows(IllegalStateException.class,
                () -> SliceCodec.validateRequiredTypes(codec, Set.of(Integer.class)));

            assertTrue(ex.getMessage().contains("java.lang.Integer"));
            assertTrue(ex.getMessage().contains("record or an enum"),
                       "the message must say how to obtain a codec, not name an annotation the author may never have written");
        }

        @Test
        void validateRequiredTypes_emptySet_noError() {
            var codec = sliceCodec(List.of(STRING_CODEC));

            assertDoesNotThrow(() -> SliceCodec.validateRequiredTypes(codec, Set.of()));
        }

        @Test
        void sliceCodec_withRequiredTypes_validatesOnCreation() {
            assertThrows(IllegalStateException.class,
                () -> sliceCodec(null, List.of(STRING_CODEC), Set.of(Integer.class)));
        }

        @Test
        void sliceCodec_withRequiredTypes_succeedsWhenAllRegistered() {
            assertDoesNotThrow(
                () -> sliceCodec(null, List.of(STRING_CODEC), Set.of(String.class)));
        }
    }

    @Nested
    class SupertypeResolutionTests {

        interface Base {}

        interface Middle extends Base {}

        interface Alpha {}

        interface Beta {}

        interface Marker {}

        record Leaf(String value) implements Middle {}

        record TwoParents(String value) implements Alpha, Beta {}

        static class Root {}

        static class MarkedRoot extends Root implements Marker {}

        /// Test double whose encoded body is the NAME OF THE REGISTERED TYPE, so a round-trip reveals
        /// which codec the resolver actually selected. #529 is a silently WRONG encoding, not a
        /// missing one, so asserting on the bytes is the only assertion that can see the defect.
        @SuppressWarnings({"unchecked", "rawtypes"})
        private static TypeCodec<?> namingCodec(Class<?> type, int tag) {
            return new TypeCodec((Class) type,
                tag,
                (codec, buf, value) -> SliceCodec.writeString(buf, type.getName()),
                (codec, buf) -> SliceCodec.readString(buf));
        }

        private static String encodedBy(SliceCodec codec, Object value) {
            var buf = Unpooled.buffer();

            try {
                codec.write(buf, value);

                return codec.read(buf);
            } finally {
                buf.release();
            }
        }

        @Test
        void lookupByClass_picksMostSpecificSupertype_whenSeveralAreAssignable() {
            var codec = sliceCodec(List.of(namingCodec(Base.class, 300), namingCodec(Middle.class, 301)));

            assertEquals(Middle.class.getName(), encodedBy(codec, new Leaf("x")));
        }

        @Test
        void lookupByClass_picksSameSupertype_whenRegistrationOrderIsReversed() {
            var forward = sliceCodec(List.of(namingCodec(Base.class, 300), namingCodec(Middle.class, 301)));
            var reversed = sliceCodec(List.of(namingCodec(Middle.class, 301), namingCodec(Base.class, 300)));

            assertEquals(Middle.class.getName(), encodedBy(forward, new Leaf("x")));
            assertEquals(Middle.class.getName(), encodedBy(reversed, new Leaf("x")));
        }

        @Test
        void lookupByClass_picksSameSupertype_acrossShuffledRegistries() {
            var entries = new ArrayList<>(List.of(namingCodec(Base.class, 300),
                                                  namingCodec(Middle.class, 301),
                                                  namingCodec(Alpha.class, 302),
                                                  namingCodec(Marker.class, 305)));
            var resolved = new HashSet<String>();

            for (int i = 0; i < 32; i++) {
                Collections.shuffle(entries);
                resolved.add(encodedBy(sliceCodec(List.copyOf(entries)), new Leaf("x")));
            }

            assertEquals(Set.of(Middle.class.getName()), resolved);
        }

        @Test
        void lookupByClass_failsLoud_whenCandidatesAreUnrelated() {
            var codec = sliceCodec(List.of(namingCodec(Alpha.class, 302), namingCodec(Beta.class, 303)));

            var ex = assertThrows(IllegalArgumentException.class, () -> encodedBy(codec, new TwoParents("x")));

            assertTrue(ex.getMessage().contains(TwoParents.class.getName()));
            assertTrue(ex.getMessage().contains(Alpha.class.getName()));
            assertTrue(ex.getMessage().contains(Beta.class.getName()));
        }

        @Test
        void lookupByClass_prefersClass_whenClassAndInterfaceAreEquallySpecific() {
            var codec = sliceCodec(List.of(namingCodec(Root.class, 304), namingCodec(Marker.class, 305)));

            assertEquals(Root.class.getName(), encodedBy(codec, new MarkedRoot()));
        }

        @Test
        void lookupByClass_failsLoud_whenNoSupertypeIsRegistered() {
            var codec = sliceCodec(List.of(namingCodec(Alpha.class, 302)));

            var ex = assertThrows(IllegalArgumentException.class, () -> encodedBy(codec, new Leaf("x")));

            assertTrue(ex.getMessage().contains("No codec registered for class"));
            assertTrue(ex.getMessage().contains(Leaf.class.getName()));
        }

        @Test
        void frameworkCodecs_resolveImmutableList_viaListSupertype() {
            var codec = FrameworkCodecs.frameworkCodecs();
            var buf = Unpooled.buffer();

            try {
                codec.write(buf, List.of("a", "b"));

                List<String> result = codec.read(buf);

                assertEquals(List.of("a", "b"), result);
            } finally {
                buf.release();
            }
        }

        @Test
        void frameworkCodecs_resolveImmutableSet_viaSetSupertype() {
            var codec = FrameworkCodecs.frameworkCodecs();
            var buf = Unpooled.buffer();

            try {
                codec.write(buf, Set.of("a"));

                Set<String> result = codec.read(buf);

                assertEquals(Set.of("a"), result);
            } finally {
                buf.release();
            }
        }

        @Test
        void frameworkCodecs_resolveImmutableMap_viaMapSupertype() {
            var codec = FrameworkCodecs.frameworkCodecs();
            var buf = Unpooled.buffer();

            try {
                codec.write(buf, Map.of("k", "v"));

                Map<String, String> result = codec.read(buf);

                assertEquals(Map.of("k", "v"), result);
            } finally {
                buf.release();
            }
        }
    }
}
