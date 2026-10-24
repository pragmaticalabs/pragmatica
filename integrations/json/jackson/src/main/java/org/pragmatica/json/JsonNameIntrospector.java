/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
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
 *
 */
package org.pragmatica.json;

import java.lang.reflect.Field;
import java.util.Arrays;

import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.json.JsonName;

import tools.jackson.databind.PropertyName;
import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.introspect.Annotated;
import tools.jackson.databind.introspect.AnnotatedClass;
import tools.jackson.databind.introspect.NopAnnotationIntrospector;

import static org.pragmatica.lang.Option.option;


/// Reads `@JsonName` (the generated JSON codecs' rename annotation, not a Jackson one) as an explicit property name,
/// and as an enum constant's name, so Jackson writes and reads the names the generated codecs compile in. An explicit
/// name is not touched by the naming strategy, which is the precedence the generator gives `@JsonName` too.
///
/// The compiler carries a record component's annotation to its field, accessor and canonical constructor parameter
/// (`@JsonName` targets all three), so every member Jackson collects for the property agrees.
final class JsonNameIntrospector extends NopAnnotationIntrospector {
    @NullReturn
    @Override
    public PropertyName findNameForSerialization(MapperConfig<?> config, Annotated annotated) {
        return explicitName(annotated);
    }

    @NullReturn
    @Override
    public PropertyName findNameForDeserialization(MapperConfig<?> config, Annotated annotated) {
        return explicitName(annotated);
    }

    // Jackson's contract: `names[i]` is the explicit name found so far for `constants[i]` (or null for none), and the
    // array is filled in place and returned.
    @Override
    public String[] findEnumValues(MapperConfig<?> config,
                                   AnnotatedClass enumType,
                                   Enum<?>[] constants,
                                   String[] names) {
        for (int i = 0; i < constants.length; i++) {
            var index = i;

            constantName(enumType.getRawType(), constants[i]).onPresent(name -> names[index] = name);
        }

        return names;
    }

    // Jackson's contract: null when this introspector has no name for the member, so the next one is asked.
    @NullReturn
    private static PropertyName explicitName(Annotated annotated) {
        return option(annotated.getAnnotation(JsonName.class)).map(JsonName::value)
                     .map(PropertyName::construct)
                     .toOptional()
                     .orElse(null);
    }

    // The constant's field (public, like every enum constant), and its `@JsonName` if it has one.
    private static Option<String> constantName(Class<?> enumType, Enum<?> constant) {
        return Option.from(Arrays.stream(enumType.getFields())
                                 .filter(Field::isEnumConstant)
                                 .filter(field -> field.getName()
                                                       .equals(constant.name()))
                                 .findFirst())
                     .flatMap(field -> option(field.getAnnotation(JsonName.class)))
                     .map(JsonName::value);
    }
}
