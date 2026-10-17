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
import org.pragmatica.serialization.json.spi.JsonReader;
import org.pragmatica.serialization.json.spi.JsonWriter;
import org.pragmatica.serialization.json.tests.Ticketing.BuyRequest;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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
