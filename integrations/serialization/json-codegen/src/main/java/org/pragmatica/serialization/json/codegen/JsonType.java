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
package org.pragmatica.serialization.json.codegen;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import java.util.List;

import org.pragmatica.lang.Option;


/// What a JSON codec has to do for one Java type. Records and enums are references: their definitions live
/// in [JsonTypeAnalyzer], so recursive types resolve without cycles in this model.
sealed interface JsonType {
    enum ScalarKind {
        INT,
        LONG,
        DOUBLE,
        BOOLEAN,
        STRING,
        DECIMAL,
        INSTANT,
        UUID,
        LOCAL_DATE,
        CURRENCY
    }

    record Scalar(ScalarKind kind) implements JsonType {}

    record EnumRef(TypeElement element) implements JsonType {}

    record RecordRef(TypeElement element) implements JsonType {}

    record OptionalOf(JsonType element) implements JsonType {}

    record ListOf(JsonType element, boolean set) implements JsonType {}

    /// Keys are a string, an enum, or a value object over a string.
    record MapOf(JsonType key, JsonType value) implements JsonType {}

    /// `name` is the Java component name (accessor and generated locals); `wireName` is its JSON name, after
    /// `@JsonName` or the build's naming strategy.
    record Component(String name, String wireName, JsonType type) {}

    /// `factory` is the JBCT construction path (`static Result<T> t(...)` over the components, in order), when
    /// the record has one. A value object is a single-component record over a scalar that has
    /// one; it travels unwrapped, as its bare component (owner ruling 2026-10-17).
    ///
    /// The value-object rule has exactly one counterpart, on the Jackson side:
    /// `org.pragmatica.json.ValueObjectModule#valueObjectFactory`. The two are pinned together by
    /// `ValueObjectRuleAgreementTest` (json-tests); change both or neither.
    record RecordDefinition(TypeElement element, List<Component> components, Option<ExecutableElement> factory) {
        boolean isValueObject() {
            return factory.isPresent()
                   && components.size() == 1
                   && components.getFirst()
                                .type() instanceof Scalar;
        }

        /// The factory's name; asked only where the record has one (value objects, the factory branch of the
        /// record reader).
        String factoryName() {
            return factory.map(method -> method.getSimpleName()
                                               .toString())
                          .or("");
        }
    }

    /// `constants` are the JSON names, in declaration order (a constant's `@JsonName`, otherwise its Java name);
    /// `unknownIndex` is the position of the constant declared as `UNKNOWN`, or -1.
    record EnumDefinition(TypeElement element, List<String> constants, int unknownIndex) {}
}
