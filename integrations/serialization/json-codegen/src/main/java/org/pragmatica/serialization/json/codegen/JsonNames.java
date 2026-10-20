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

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import java.util.List;
import java.util.stream.Stream;


/// The one naming function behind every generated name derived from a type: the flat entry points `<Flat>Json` (also
/// each `@Json` type's `ServiceLoader` provider name, which `JsonCodecs` derives from a `Class` the same way) and the
/// nested codec interfaces of entry points and route holders.
///
/// A type's flat name is its simple name and those of its enclosing types, outermost first, each escaped and joined
/// with a single `_`. Escaping writes `_` as `_0` and `$` as `_1`. The mapping is injective by construction: a
/// simple name never starts with a digit, so in a flat name an `_` followed by a digit is an escape and any other
/// `_` joins two levels, which decodes every flat name back to exactly one list of simple names. A nested `A.B` is
/// `A_B`, a top-level `A_B` is `A_0B`: no two types of one package share a flat name, whichever artifact compiles
/// them, so no entry point name has to be protected by a clash check. A flat name never contains `$`, so a generated
/// top-level class cannot collide with the binary name of a nested class either. Types of different packages may
/// share a flat name; where they meet inside one generated class, the emitter numbers the nested names.
public sealed interface JsonNames {
    String SEPARATOR = "_";

    /// The flat name of `type`.
    static String flatName(TypeElement type) {
        return flatName(simpleNames(type));
    }

    /// The flat name of a type whose simple name and enclosing simple names, outermost first, are `simpleNames`.
    static String flatName(List<String> simpleNames) {
        return String.join(SEPARATOR,
                           simpleNames.stream().map(JsonNames::escape).toList());
    }

    /// The `@Json` entry point of `type`: its flat name followed by `Json`.
    static String entryPointName(TypeElement type) {
        return flatName(type) + "Json";
    }

    private static String escape(String simpleName) {
        return simpleName.replace("_", "_0")
                         .replace("$", "_1");
    }

    // The type and its enclosing types, innermost first while walking out, returned outermost first.
    private static List<String> simpleNames(TypeElement type) {
        return Stream.<Element> iterate(type, TypeElement.class::isInstance, Element::getEnclosingElement)
                     .map(level -> level.getSimpleName()
                                        .toString())
                     .toList()
                     .reversed();
    }

    record unused() implements JsonNames {}
}
