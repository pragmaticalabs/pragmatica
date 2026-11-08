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

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;

/// Input variants a producer we do not control may send, derived from Jackson's compact output.
/// Shared by the conformance tests and the benchmarks.
public interface Inputs {
    JsonMapper TREE = JsonMapper.builder()
                                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                                .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
                                .build();

    /// `compact` (Jackson's bytes), `pretty` (Jackson's default pretty printer), `reordered` (every
    /// object's keys reversed), `unknown` (unknown keys before and after each object's fields),
    /// `sparse` (null-valued fields omitted, as NON_NULL producers do).
    static byte[] variant(String name, Object value) {
        var compact = Baseline.objectMapper()
                              .writeValueAsBytes(value);
        return switch (name) {
            case "compact" -> compact;
            case "pretty" -> Baseline.objectMapper()
                                     .writerWithDefaultPrettyPrinter()
                                     .writeValueAsBytes(value);
            case "reordered" -> TREE.writeValueAsBytes(reversed(TREE.readTree(compact)));
            case "unknown" -> TREE.writeValueAsBytes(withNoise(TREE.readTree(compact)));
            case "sparse" -> TREE.writeValueAsBytes(withoutNulls(TREE.readTree(compact)));
            default -> throw new IllegalArgumentException(name);
        };
    }

    static JsonNode reversed(JsonNode node) {
        if (node.isObject()) {
            var names = new ArrayList<>(node.propertyNames());
            var result = JsonNodeFactory.instance.objectNode();
            for (int i = names.size() - 1; i >= 0; i--) {
                result.set(names.get(i), reversed(node.get(names.get(i))));
            }
            return result;
        }
        if (node.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> result.add(reversed(element)));
            return result;
        }
        return node;
    }

    // Map objects (keyed by product id) get no noise: there every key is a legitimate entry.
    static JsonNode withNoise(JsonNode node) {
        if (node.isObject() && !isMap(node)) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            var noise = JsonNodeFactory.instance.objectNode();
            noise.putArray("a").add(1.5e-3).add("}],\"").add(true).addNull();
            result.set("zzUnknownFirst", noise);
            node.propertyNames()
                .forEach(name -> result.set(name, withNoise(node.get(name))));
            result.put("zzUnknownLast", -12);
            return result;
        }
        return mapChildren(node, Inputs::withNoise);
    }

    static JsonNode withoutNulls(JsonNode node) {
        if (node.isObject()) {
            var result = JsonNodeFactory.instance.objectNode();
            node.propertyNames()
                .stream()
                .filter(name -> !node.get(name).isNull())
                .forEach(name -> result.set(name, withoutNulls(node.get(name))));
            return result;
        }
        return mapChildren(node, Inputs::withoutNulls);
    }

    private static JsonNode mapChildren(JsonNode node, java.util.function.UnaryOperator<JsonNode> transform) {
        if (node.isObject()) {
            var result = JsonNodeFactory.instance.objectNode();
            node.propertyNames()
                .forEach(name -> result.set(name, transform.apply(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> result.add(transform.apply(element)));
            return result;
        }
        return node;
    }

    private static boolean isMap(JsonNode node) {
        return node.propertyNames()
                   .stream()
                   .anyMatch(name -> name.startsWith("PROD-"));
    }
}
