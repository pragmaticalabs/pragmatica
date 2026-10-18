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
package org.pragmatica.serialization.json.tests;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.serialization.json.JsonError;
import org.pragmatica.serialization.json.JsonTypeCodec;
import org.pragmatica.serialization.json.spi.JsonCodecProvider;
import org.pragmatica.serialization.json.spi.JsonReader;
import org.pragmatica.serialization.json.spi.JsonWriter;
import org.pragmatica.serialization.json.tests.Ticketing.BuyRequest;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The user surface of a generated codec: the flat `<Type>Json` entry point and every `JsonTypeCodec` decode and
/// encode overload, including the in-place region decode and the reuse overloads.
class JsonTypeCodecApiTest {
    private static final BuyRequest REQUEST = new BuyRequest("cust-é", "evt \"1\"", "A-1", "VIP 🎟");
    private static final String JSON = "{\"customer\":\"cust-é\",\"event\":\"evt \\\"1\\\"\",\"seat\":\"A-1\",\"tier\":\"VIP 🎟\"}";

    @Test
    void entryPoint_shortcuts_areTheCodec() {
        assertEquals(JSON, new String(Ticketing_BuyRequestJson.encode(REQUEST), StandardCharsets.UTF_8));
        assertArrayEquals(Ticketing_BuyRequestJson.CODEC.encode(REQUEST), Ticketing_BuyRequestJson.encode(REQUEST));
        assertEquals(REQUEST, Ticketing_BuyRequestJson.decode(JSON).unwrap());
        assertEquals(REQUEST, Ticketing_BuyRequestJson.decode(JSON.getBytes(StandardCharsets.UTF_8)).unwrap());
    }

    // Every decode and encode overload reads and writes the whole document, so a value object is its object form
    // through each of them; its bare form is the nested one, which only the combinators and generated code use.
    @Test
    void valueObjectCodec_everyOverloadUsesTheDocumentForm() {
        var code = new Containers.Code("x");
        var document = "{\"value\":\"x\"}";
        var bytes = document.getBytes(StandardCharsets.UTF_8);
        var padded = ("  " + document + "}}").getBytes(StandardCharsets.UTF_8);
        var codec = Containers_CodeJson.CODEC;

        assertEquals(document, new String(codec.encode(code), StandardCharsets.UTF_8));
        assertEquals(document, new String(codec.encode(JsonWriter.jsonWriter(4), code), StandardCharsets.UTF_8));
        assertEquals(code, codec.decode(document).unwrap());
        assertEquals(code, codec.decode(bytes).unwrap());
        assertEquals(code, codec.decode(JsonReader.jsonReader(), bytes).unwrap());
        assertEquals(code, codec.decode(padded, 2, bytes.length).unwrap());
        assertTrue(codec.decode("\"x\"").isFailure());
    }

    @Test
    void twoArgumentFactory_hasOneFormForDocumentAndNested() {
        var codec = JsonTypeCodec.jsonTypeCodec(Containers_ItemJson.CODEC.writer(), Containers_ItemJson.CODEC.reader());

        assertEquals(codec.writer(), codec.nestedWriter());
        assertEquals(codec.reader(), codec.nestedReader());
    }

    @Test
    void entryPoint_codecs_listTheRootAndEveryTypeItReaches() {
        assertEquals(Ecommerce_OrderConfirmationJson.CODEC, Ecommerce_OrderConfirmationJson.CODECS.get(Ecommerce.OrderConfirmation.class));
        assertTrue(Ecommerce_OrderConfirmationJson.CODECS.containsKey(Ecommerce.ProductId.class));
    }

    @Test
    void decodeString_readsTheTextAsUtf8() {
        assertEquals(REQUEST, Ticketing_BuyRequestJson.CODEC.decode(JSON).unwrap());
        assertInstanceOf(JsonError.MissingField.class, failureOf(Ticketing_BuyRequestJson.CODEC.decode("{\"customer\":\"x\"}")));
    }

    // Bytes outside the region are garbage that would fail any decode that looked at them.
    @Test
    void decodeRegion_readsOnlyTheRegion_inPlace() {
        var json = JSON.getBytes(StandardCharsets.UTF_8);
        var padded = new byte[json.length + 7];

        Arrays.fill(padded, (byte) '}');
        System.arraycopy(json, 0, padded, 3, json.length);
        assertEquals(REQUEST, Ticketing_BuyRequestJson.CODEC.decode(padded, 3, json.length).unwrap());
        assertEquals(REQUEST, Ticketing_BuyRequestJson.CODEC.decode(json, 0, json.length).unwrap());
        assertTrue(Ticketing_BuyRequestJson.CODEC.decode(padded, 3, json.length + 1).isFailure());
        assertTrue(Ticketing_BuyRequestJson.CODEC.decode(padded, 2, json.length).isFailure());
    }

    // Documented decision: a region is read where it lies, so an error position is an offset into the whole buffer,
    // not into the region. A copying decode would report the position relative to the region instead.
    @Test
    void decodeRegion_reportsErrorPositions_asOffsetsIntoTheWholeBuffer() {
        var broken = "{\"customer\":x}".getBytes(StandardCharsets.UTF_8);
        var padded = new byte[broken.length + 9];

        Arrays.fill(padded, (byte) ' ');
        System.arraycopy(broken, 0, padded, 5, broken.length);
        var alone = assertInstanceOf(JsonError.Malformed.class, failureOf(Ticketing_BuyRequestJson.CODEC.decode(broken)));
        var inRegion = assertInstanceOf(JsonError.Malformed.class,
                                        failureOf(Ticketing_BuyRequestJson.CODEC.decode(padded, 5, broken.length)));

        assertEquals(12, alone.position());
        assertEquals(5 + 12, inRegion.position());
    }

    // The entry point's public surface is exactly the documented API, plus what being its type's ServiceLoader provider
    // takes (a public no-argument constructor, type() and codec()); everything else is package-private machinery.
    @Test
    void entryPoint_publicMembers_areExactlyTheDocumentedApi() {
        var type = Ticketing_BuyRequestJson.class;
        var fields = Arrays.stream(type.getDeclaredFields())
                           .filter(field -> Modifier.isPublic(field.getModifiers()))
                           .map(Field::getName)
                           .collect(Collectors.toSet());
        var methods = Arrays.stream(type.getDeclaredMethods())
                            .filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
                            .map(method -> method.getName() + Arrays.toString(method.getParameterTypes()))
                            .collect(Collectors.toSet());

        assertEquals(Set.of("CODEC", "CODECS"), fields);
        assertEquals(Set.of("decode[class [B]", "decode[class java.lang.String]", "encode[class " + BuyRequest.class.getName() + "]",
                            "type[]", "codec[]"),
                     methods);
        assertEquals(List.of(0),
                     Arrays.stream(type.getDeclaredConstructors())
                           .filter(ctor -> Modifier.isPublic(ctor.getModifiers()))
                           .map(Constructor::getParameterCount)
                           .toList());
        assertTrue(JsonCodecProvider.class.isAssignableFrom(type));
        assertEquals(0, Arrays.stream(type.getDeclaredClasses()).filter(nested -> Modifier.isPublic(nested.getModifiers())).count());
        assertTrue(Modifier.isPublic(type.getModifiers()) && Modifier.isFinal(type.getModifiers()));
    }

    @Test
    void decodeRegion_refusesARegionOutsideTheInput_beforeReading() {
        var json = JSON.getBytes(StandardCharsets.UTF_8);
        var size = json.length;

        assertRange(Ticketing_BuyRequestJson.CODEC.decode(json, -1, 2), -1, 2, size);
        assertRange(Ticketing_BuyRequestJson.CODEC.decode(json, 0, -1), 0, -1, size);
        assertRange(Ticketing_BuyRequestJson.CODEC.decode(json, 1, size), 1, size, size);
        assertRange(Ticketing_BuyRequestJson.CODEC.decode(json, 1, Integer.MAX_VALUE), 1, Integer.MAX_VALUE, size);
        assertRange(Ticketing_BuyRequestJson.CODEC.decode(json, size + 1, 0), size + 1, 0, size);
        assertTrue(Ticketing_BuyRequestJson.CODEC.decode(json, size, 0).isFailure());
    }

    @Test
    void decodeWithReusedReader_returnsAResult_andForgetsThePreviousFailure() {
        var reader = JsonReader.jsonReader();
        var json = JSON.getBytes(StandardCharsets.UTF_8);

        assertTrue(Ticketing_BuyRequestJson.CODEC.decode(reader, "{\"customer\":".getBytes(StandardCharsets.UTF_8)).isFailure());
        assertEquals(REQUEST, Ticketing_BuyRequestJson.CODEC.decode(reader, json).unwrap());
        assertEquals(REQUEST, Ticketing_BuyRequestJson.CODEC.decode(reader, json).unwrap());
    }

    @Test
    void encodeWithReusedWriter_startsEmptyEachTime() {
        var writer = JsonWriter.jsonWriter(4);
        var other = new BuyRequest("c", "e", "s", "t");

        assertEquals(JSON, new String(Ticketing_BuyRequestJson.CODEC.encode(writer, REQUEST), StandardCharsets.UTF_8));
        assertArrayEquals(Ticketing_BuyRequestJson.encode(other), Ticketing_BuyRequestJson.CODEC.encode(writer, other));
    }

    private static void assertRange(org.pragmatica.lang.Result<BuyRequest> result, int offset, int length, int size) {
        var range = assertInstanceOf(JsonError.InvalidRange.class, failureOf(result));

        assertEquals(offset, range.offset());
        assertEquals(length, range.length());
        assertEquals(size, range.size());
    }

    private static Cause failureOf(org.pragmatica.lang.Result<?> result) {
        return StrictDecodingTest.failureOf(result);
    }
}
