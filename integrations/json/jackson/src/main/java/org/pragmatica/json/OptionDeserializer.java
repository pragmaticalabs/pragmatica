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

import org.pragmatica.lang.Option;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ValueDeserializer;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;


/// Jackson deserializer for Option<T> types.
/// Deserializes null as None, any other value as Some<T>
public class OptionDeserializer extends ValueDeserializer<Option<?>> {
    private final JavaType valueType;
    private final ValueDeserializer<Object> valueDeserializer;

    public OptionDeserializer() {
        this(null, null);
    }

    private OptionDeserializer(JavaType valueType, ValueDeserializer<Object> valueDeserializer) {
        this.valueType = valueType;
        this.valueDeserializer = valueDeserializer;
    }

    @Override
    public Option<?> deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        if (p.currentToken() == JsonToken.VALUE_NULL) {
            return none();
        }

        return option(valueDeserializer).map(deser -> deser.deserialize(p, ctxt))
                     .orElse(() -> option(valueType).map(type -> ctxt.readValue(p, type)))
                     .orElse(() -> option(p.readValueAs(Object.class)));
    }

    @Override
    public ValueDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
        return option(property).map(BeanProperty::getType)
                     .flatMap(OptionDeserializer::elementType)
                     .<ValueDeserializer<?>> map(type -> createContextualDeserializer(ctxt, property, type))
                     .or(this);
    }

    /// Option is registered on the plain class, so a declared `Option<T>` arrives as a SimpleType:
    /// `hasContentType()` is false and `T` lives in the generic binding instead (#696). Without this
    /// resolution the element deserializes as raw Object (Integer for Option<Long>, LinkedHashMap
    /// for record elements) and the declared type is violated at first use.
    private static Option<JavaType> elementType(JavaType type) {
        return type.hasContentType()
               ? option(type.getContentType())
               : type.containedTypeCount() == 1
                 ? option(type.containedType(0))
                 : none();
    }

    private OptionDeserializer createContextualDeserializer(DeserializationContext ctxt,
                                                            BeanProperty property,
                                                            JavaType elementType) {
        var deser = ctxt.findContextualValueDeserializer(elementType, property);

        return new OptionDeserializer(elementType, deser);
    }

    @Override
    public Option<?> getNullValue(DeserializationContext ctxt) {
        return none();
    }
}
