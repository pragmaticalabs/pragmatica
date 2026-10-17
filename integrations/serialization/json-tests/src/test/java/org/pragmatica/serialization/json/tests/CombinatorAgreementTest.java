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
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.json.JsonError;
import org.pragmatica.serialization.json.JsonTypeCodec;
import org.pragmatica.serialization.json.spi.JsonReader;
import org.pragmatica.serialization.json.tests.Containers.Code;
import org.pragmatica.serialization.json.tests.Containers.Item;
import org.pragmatica.serialization.json.tests.Containers.Level;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The combinators against the generator's own container code: for every container value the combinator writes the
/// bytes the generated holder writes for its component, and for every input the combinator accepts exactly what the
/// generated holder accepts (with the same value) and refuses what it refuses. Inputs cover nulls, duplicates,
/// refused elements, malformed and truncated documents.
class CombinatorAgreementTest {
    private static final Item A = new Item("a", Option.some(1L));
    private static final Item B = new Item("b \"q\" é", Option.none());

    // ---- encode --------------------------------------------------------------------------------
    @Test
    void listOf_writesTheGeneratedListBytes() {
        for (var value : List.of(List.<Item>of(), List.of(A), List.of(A, B, A))) {
            assertSameBytes(Containers_ItemsJson.encode(new Containers.Items(value)),
                            JsonTypeCodec.listOf(Containers_ItemJson.CODEC).encode(value));
        }
    }

    @Test
    void setOf_writesTheGeneratedSetBytes() {
        for (var value : List.<Set<Item>>of(Set.of(), new LinkedHashSet<>(List.of(B, A)))) {
            assertSameBytes(Containers_ItemSetJson.encode(new Containers.ItemSet(value)),
                            JsonTypeCodec.setOf(Containers_ItemJson.CODEC).encode(value));
        }
    }

    @Test
    void optionOf_writesTheGeneratedOptionBytes() {
        for (var value : List.of(Option.<Item>none(), Option.some(B))) {
            assertSameBytes(Containers_MaybeItemJson.encode(new Containers.MaybeItem(value)),
                            JsonTypeCodec.optionOf(Containers_ItemJson.CODEC).encode(value));
        }
    }

    @Test
    void mapOf_writesTheGeneratedMapBytes() {
        var map = new LinkedHashMap<String, Item>();

        map.put("first", A);
        map.put("sécond \"key\"", B);
        for (var value : List.<Map<String, Item>>of(Map.of(), map)) {
            assertSameBytes(Containers_ItemsByNameJson.encode(new Containers.ItemsByName(value)),
                            JsonTypeCodec.mapOf(Containers_ItemJson.CODEC).encode(value));
        }
    }

    @Test
    void listOfValueObjects_andSetOfEnums_writeTheGeneratedBytes() {
        var codes = List.of(new Code("x"), new Code("y"));
        var levels = new LinkedHashSet<>(List.of(Level.HIGH, Level.LOW));

        assertSameBytes(Containers_CodesJson.encode(new Containers.Codes(codes)),
                        JsonTypeCodec.listOf(Containers_CodeJson.CODEC).encode(codes));
        assertSameBytes(Containers_LevelsJson.encode(new Containers.Levels(levels)),
                        JsonTypeCodec.setOf(Containers_LevelJson.CODEC).encode(levels));
    }

    // ---- decode --------------------------------------------------------------------------------
    @Test
    void listOf_acceptsAndRefusesWhatTheGeneratedListDoes() {
        assertAgreement(JsonTypeCodec.listOf(Containers_ItemJson.CODEC), Containers_ItemsJson::decode, Containers.Items::v,
                        List.of("[]", " [ ] ", "[{\"name\":\"a\"}]", "[{\"name\":\"a\",\"qty\":1},{\"name\":\"b\",\"qty\":null}]",
                                "[{\"name\":\"a\"},{\"name\":\"a\"}]", "null", "[null]", "[{}]", "[{\"name\":null}]", "[1]",
                                "[{\"name\":\"a\"},]", "[{\"name\":\"a\"}", "{\"name\":\"a\"}", "[{\"name\":\"a\",\"name\":\"b\"}]",
                                "[{\"name\":\"\\u0061\",\"extra\":[1,{}]}]", "[{\"name\":\"a\"}] x", ""));
    }

    @Test
    void setOf_keepsOneOfEachElement_asTheGeneratedSetDoes() {
        assertAgreement(JsonTypeCodec.setOf(Containers_ItemJson.CODEC), Containers_ItemSetJson::decode, Containers.ItemSet::v,
                        List.of("[]", "[{\"name\":\"a\"},{\"name\":\"a\"},{\"name\":\"b\"}]", "[null]", "[{}]", "null", "[{\"name\":\"a\"}"));
        assertEquals(1, JsonTypeCodec.setOf(Containers_ItemJson.CODEC).decode("[{\"name\":\"a\"},{\"name\":\"a\"}]").unwrap().size());
    }

    // Set equality ignores order, so the order is pinned on the iteration itself: document order, a repeat kept
    // where it first appeared, for the combinator and the generated Set component alike.
    @Test
    void setOf_iteratesInDocumentOrder_asTheGeneratedSetDoes() {
        var json = "[{\"name\":\"c\"},{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"a\"},{\"name\":\"d\"}]";
        var expected = List.of("c", "a", "b", "d");

        assertEquals(expected, names(JsonTypeCodec.setOf(Containers_ItemJson.CODEC).decode(json).unwrap()));
        assertEquals(expected, names(Containers_ItemSetJson.decode("{\"v\":" + json + "}").unwrap().v()));
    }

    private static List<String> names(Set<Containers.Item> items) {
        return items.stream()
                    .map(Containers.Item::name)
                    .toList();
    }

    @Test
    void optionOf_mapsNullToEmpty_asTheGeneratedOptionDoes() {
        assertAgreement(JsonTypeCodec.optionOf(Containers_ItemJson.CODEC), Containers_MaybeItemJson::decode,
                        Containers.MaybeItem::v,
                        List.of("null", " null ", "{\"name\":\"a\"}", "{}", "nul", "[]", "{\"name\":null}"));
        assertEquals(Option.none(), JsonTypeCodec.optionOf(Containers_ItemJson.CODEC).decode("null").unwrap());
    }

    @Test
    void mapOf_keepsTheLastOfARepeatedKey_asTheGeneratedMapDoes() {
        assertAgreement(JsonTypeCodec.mapOf(Containers_ItemJson.CODEC), Containers_ItemsByNameJson::decode,
                        Containers.ItemsByName::v,
                        List.of("{}", "{\"x\":{\"name\":\"a\"}}", "{\"x\":{\"name\":\"a\"},\"x\":{\"name\":\"b\"}}", "{\"x\":null}",
                                "{1:{\"name\":\"a\"}}", "{\"x\":{\"name\":\"a\"}", "{\"x\" {\"name\":\"a\"}}", "null", "[]",
                                "{\"\\u00e9\":{\"name\":\"a\"}}"));
        assertEquals("b", JsonTypeCodec.mapOf(Containers_ItemJson.CODEC)
                                       .decode("{\"x\":{\"name\":\"a\"},\"x\":{\"name\":\"b\"}}")
                                       .unwrap()
                                       .get("x")
                                       .name());
    }

    @Test
    void listOfValueObjects_runsTheFactory_asTheGeneratedListDoes() {
        assertAgreement(JsonTypeCodec.listOf(Containers_CodeJson.CODEC), Containers_CodesJson::decode, Containers.Codes::v,
                        List.of("[\"ok\"]", "[\"\"]", "[\"ok\",\" \"]", "[null]", "[1]"));
        assertTrue(JsonTypeCodec.listOf(Containers_CodeJson.CODEC)
                                .decode("[\"\"]")
                                .fold(Cause::message, _ -> "")
                                .contains("Invalid value for field Code: Invalid Code: blank"));
    }

    @Test
    void setOfEnums_fallsBackToUnknown_asTheGeneratedSetDoes() {
        assertAgreement(JsonTypeCodec.setOf(Containers_LevelJson.CODEC), Containers_LevelsJson::decode, Containers.Levels::v,
                        List.of("[\"LOW\",\"HIGH\",\"LOW\"]", "[\"WEIRD\"]", "[null]", "[\"LOW\""));
        assertEquals(Set.of(Level.UNKNOWN), JsonTypeCodec.setOf(Containers_LevelJson.CODEC).decode("[\"WEIRD\"]").unwrap());
    }

    // The combinators open containers through the same reader calls as generated code, so the same per-container
    // limit holds: a document exactly MAX_DEPTH deep decodes, one level deeper fails as NestingTooDeep.
    @Test
    void combinators_enforceTheReadersNestingLimit() {
        var listOfLists = JsonTypeCodec.listOf(JsonTypeCodec.listOf(Containers_NodeJson.CODEC));
        var list = JsonTypeCodec.listOf(Containers_NodeJson.CODEC);
        var levels = (JsonReader.MAX_DEPTH - 2) / 2;

        assertTrue(listOfLists.decode("[[" + nodes(levels) + "]]").isSuccess(), "depth " + JsonReader.MAX_DEPTH);
        assertInstanceOf(JsonError.NestingTooDeep.class,
                         list.decode("[" + nodes(levels + 1) + "]")
                             .fold(cause -> cause, _ -> null),
                         "depth " + (JsonReader.MAX_DEPTH + 1));
    }

    private static String nodes(int depth) {
        return "{\"children\":[".repeat(depth) + "]}".repeat(depth);
    }

    private static <C, H> void assertAgreement(JsonTypeCodec<C> combinator,
                                               Fn1<Result<H>, String> generated,
                                               Fn1<C, H> component,
                                               List<String> fragments) {
        for (var fragment : fragments) {
            var ours = combinator.decode(fragment);
            var theirs = generated.apply("{\"v\":" + fragment + "}");

            assertEquals(theirs.isSuccess(), ours.isSuccess(), () -> "accept/refuse differs for " + fragment + ": generated "
                                                                     + theirs + ", combinator " + ours);
            theirs.onSuccess(holder -> assertEquals(component.apply(holder), ours.unwrap(), fragment));
        }
    }

    private static void assertSameBytes(byte[] holder, byte[] container) {
        assertEquals(new String(holder, StandardCharsets.UTF_8),
                     "{\"v\":" + new String(container, StandardCharsets.UTF_8) + "}");
    }
}
