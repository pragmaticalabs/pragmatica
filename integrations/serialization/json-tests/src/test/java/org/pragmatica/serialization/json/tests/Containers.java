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

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.json.Json;

import java.util.List;
import java.util.Map;
import java.util.Set;

/// Element types for the container combinators, and one-component holders whose generated container code the
/// combinators must agree with: a holder `X(C v)` encodes as `{"v":<C>}`, so the bytes between `{"v":` and the
/// closing `}` are exactly what the generator emits for a component of type `C`.
public interface Containers {
    @Json
    record Item(String name, Option<Long> qty) {}

    @Json
    record Code(String value) {
        public static Result<Code> code(String value) {
            return value.isBlank()
                   ? Causes.cause("Invalid Code: blank").result()
                   : Result.success(new Code(value));
        }
    }

    @Json
    enum Level {
        LOW,
        HIGH,
        UNKNOWN
    }

    @Json
    record Node(List<Node> children) {}

    @Json
    record Items(List<Item> v) {}

    @Json
    record ItemSet(Set<Item> v) {}

    @Json
    record MaybeItem(Option<Item> v) {}

    @Json
    record ItemsByName(Map<String, Item> v) {}

    @Json
    record Codes(List<Code> v) {}

    @Json
    record Levels(Set<Level> v) {}
}
