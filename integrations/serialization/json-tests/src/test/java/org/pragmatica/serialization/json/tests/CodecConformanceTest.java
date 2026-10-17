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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.serialization.json.JsonError;
import org.pragmatica.serialization.json.spi.JsonReader;
import org.pragmatica.serialization.json.JsonTypeCodec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the generated codecs to the production slice-route mapper (Jackson 3 + PragmaticaModule + ValueObjectModule, see Baseline):
/// identical bytes out, equal values in, and no exception escaping on any input.
class CodecConformanceTest {
    static final JsonMapper JACKSON = org.pragmatica.serialization.json.tests.Baseline.MAPPER;

    record Case<T>(String name, T value, JsonTypeCodec<T> codec, Class<T> type) {
        @Override
        public String toString() {
            return name;
        }

        byte[] jacksonBytes() {
            return JACKSON.writeAsBytes(value)
                          .unwrap();
        }
    }

    static Stream<Case<?>> cases() {
        return Stream.of(new Case<>("buyRequest", Samples.buyRequest(), Ticketing_BuyRequestJson.CODEC,
                                    org.pragmatica.serialization.json.tests.Ticketing.BuyRequest.class),
                         new Case<>("buyResponse", Samples.buyResponse(), Ticketing_BuyResponseJson.CODEC,
                                    org.pragmatica.serialization.json.tests.Ticketing.BuyResponse.class),
                         new Case<>("catalog", Samples.catalog(), Catalog_ItemListV2Json.CODEC,
                                    org.pragmatica.serialization.json.tests.Catalog.ItemListV2.class),
                         new Case<>("placeOrderRequest", Samples.placeOrderRequest(), Ecommerce_PlaceOrderRequestJson.CODEC,
                                    org.pragmatica.serialization.json.tests.Ecommerce.PlaceOrderRequest.class),
                         new Case<>("orderConfirmation", Samples.orderConfirmation(), Ecommerce_OrderConfirmationJson.CODEC,
                                    org.pragmatica.serialization.json.tests.Ecommerce.OrderConfirmation.class),
                         new Case<>("refundRequest", Samples.refundRequest(), Ecommerce_RefundRequestJson.CODEC,
                                    org.pragmatica.serialization.json.tests.Ecommerce.RefundRequest.class),
                         new Case<>("telemetry", Samples.telemetry(), Telemetry_SeriesJson.CODEC,
                                    org.pragmatica.serialization.json.tests.Telemetry.Series.class));
    }

    @ParameterizedTest
    @MethodSource("cases")
    <T> void encode_matchesJacksonBytes(Case<T> c) {
        var ours = c.codec().encode(c.value());
        assertEquals(new String(c.jacksonBytes(), StandardCharsets.UTF_8), new String(ours, StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @MethodSource("cases")
    <T> void decode_ofJacksonOutput_equalsOriginal(Case<T> c) {
        assertDecodes(c, c.jacksonBytes());
    }

    @ParameterizedTest
    @MethodSource("cases")
    <T> void jackson_decodesOurOutput_equalsOriginal(Case<T> c) {
        JACKSON.readBytes(c.codec().encode(c.value()), c.type())
               .onFailure(cause -> Assertions.fail(cause.message()))
               .onSuccess(decoded -> assertEquals(c.value(), decoded));
    }

    @ParameterizedTest
    @MethodSource("cases")
    <T> void decode_withWhitespaceEverywhere_equalsOriginal(Case<T> c) {
        assertDecodes(c, spaced(c.jacksonBytes()));
    }

    // Defeats the expected-order fast path on every key, exercising hash lookup and byte confirmation.
    @ParameterizedTest
    @MethodSource("cases")
    <T> void decode_withReversedFieldOrder_equalsOriginal(Case<T> c) {
        assertDecodes(c, Inputs.variant("reordered", c.value()));
    }

    @ParameterizedTest
    @MethodSource("cases")
    <T> void decode_withUnknownFields_skipsThem(Case<T> c) {
        assertDecodes(c, Inputs.variant("unknown", c.value()));
    }

    @ParameterizedTest
    @MethodSource("cases")
    <T> void decode_prettyPrinted_equalsOriginal(Case<T> c) {
        assertDecodes(c, Inputs.variant("pretty", c.value()));
    }

    @ParameterizedTest
    @MethodSource("cases")
    <T> void decode_withNullFieldsOmitted_equalsOriginal(Case<T> c) {
        assertDecodes(c, Inputs.variant("sparse", c.value()));
    }

    @ParameterizedTest
    @MethodSource("cases")
    <T> void decode_everyTruncatedPrefix_failsWithoutThrowing(Case<T> c) {
        var json = c.jacksonBytes();
        for (int len = 0; len < json.length; len++) {
            var prefix = len;
            c.codec()
             .decode(json, 0, len)
             .onSuccess(v -> Assertions.fail("prefix of length " + prefix + " decoded: " + v));
        }
    }

    // One reused reader: state left by a failed decode (element stack, error slot) must not leak.
    @ParameterizedTest
    @MethodSource("cases")
    <T> void reusedReader_afterFailedDecode_decodesCorrectly(Case<T> c) {
        var json = c.jacksonBytes();
        var reader = JsonReader.jsonReader();
        for (int len = 0; len < json.length; len += 7) {
            assertTrue(c.codec().decode(reader, Arrays.copyOf(json, len)).isFailure());
            assertEquals(c.value(), c.codec().decode(reader, json).unwrap());
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    <T> void decode_randomMutations_neverThrows(Case<T> c) {
        var json = c.jacksonBytes();
        var random = new Random(42);
        var alphabet = "{}[]:,\"\\ 0-9eE.ntfu".getBytes(StandardCharsets.ISO_8859_1);
        for (int round = 0; round < 20_000; round++) {
            var mutated = json.clone();
            for (int k = 1 + random.nextInt(3); k > 0; k--) {
                mutated[random.nextInt(mutated.length)] = random.nextBoolean()
                                                          ? alphabet[random.nextInt(alphabet.length)]
                                                          : (byte) random.nextInt(256);
            }
            c.codec()
             .decode(mutated);
        }
    }

    private static <T> void assertDecodes(Case<T> c, byte[] json) {
        c.codec()
         .decode(json)
         .onFailure(cause -> Assertions.fail(cause.message() + " in " + new String(json, StandardCharsets.UTF_8)))
         .onSuccess(decoded -> assertEquals(c.value(), decoded));
    }

    // Inserts whitespace around every structural byte ({}[]:,) outside strings.
    static byte[] spaced(byte[] json) {
        var out = new StringBuilder();
        var inString = false;
        for (int i = 0; i < json.length; i++) {
            var ch = (char) (json[i] & 0xFF);
            if (inString) {
                out.append(ch);
                if (ch == '\\') {
                    out.append((char) (json[++i] & 0xFF));
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                out.append(ch);
                continue;
            }
            if ("{}[]:,".indexOf(ch) >= 0) {
                out.append(" \n\t").append(ch).append("\r ");
            } else {
                out.append(ch);
            }
        }
        return out.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    // Value objects decode through their factories: a refused value fails the decode, naming the field and
    // carrying the factory's cause, while the rest of the input is still read. The production mapper runs the same
    // factory (ValueObjectModule) and refuses it too, with the factory's message.
    @org.junit.jupiter.api.Test
    void decode_refusedValueObject_failsWithFieldAndFactoryCause() {
        var json = new String(Ecommerce_OrderConfirmationJson.CODEC.encode(Samples.orderConfirmation()),
                              StandardCharsets.UTF_8).replace("\"orderId\":\"ord-2026-10-17-58f1c3\",\"customerId\"",
                                                              "\"orderId\":\"   \",\"customerId\"");
        assertTrue(json.contains("\"orderId\":\"   \""), json);
        Ecommerce_OrderConfirmationJson.CODEC.decode(json.getBytes(StandardCharsets.UTF_8))
                                                                                .onSuccess(value -> Assertions.fail("unexpected success: " + value))
                                                                                .onFailure(cause -> assertTrue(cause.stream()
                                                                                                                    .anyMatch(c -> c instanceof JsonError.InvalidField invalid
                                                                                                                                   && invalid.field().equals("orderId")
                                                                                                                                   && invalid.origin().message().contains("Invalid OrderId")),
                                                                                                               cause.message()));
        JACKSON.readBytes(json.getBytes(StandardCharsets.UTF_8), Ecommerce.OrderConfirmation.class)
               .onSuccess(value -> Assertions.fail("production mapper accepted: " + value))
               .onFailure(cause -> assertTrue(cause.message().contains("Invalid OrderId"), cause.message()));
    }

    // Owner ruling 2026-10-17: value objects travel as their bare component, map keys included.
    @org.junit.jupiter.api.Test
    void valueObjects_areUnwrapped_includingMapKeys() {
        var json = new String(Ecommerce_OrderConfirmationJson.CODEC.encode(Samples.orderConfirmation()), StandardCharsets.UTF_8);
        assertTrue(json.startsWith("{\"orderId\":\"ord-2026-10-17-58f1c3\",\"customerId\":\"cust-7f3a91\""), json);
        assertTrue(json.contains("\"quantity\":2}"), json);
        assertTrue(json.contains("\"linePrices\":{\"PROD-ABC123\":{\"productId\":\"PROD-ABC123\""), json);
        assertTrue(!json.contains("\"value\"") && !json.contains("ProductId["), json);
    }

    @org.junit.jupiter.api.Test
    void spaced_insertsWhitespace() {
        assertTrue(new String(spaced("{\"a\":1}".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8).contains(" \n\t{"));
        assertArrayEquals("\"x\"".getBytes(StandardCharsets.UTF_8), spaced("\"x\"".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals("1000".getBytes(StandardCharsets.UTF_8), spaced("1000".getBytes(StandardCharsets.UTF_8)));
    }
}
