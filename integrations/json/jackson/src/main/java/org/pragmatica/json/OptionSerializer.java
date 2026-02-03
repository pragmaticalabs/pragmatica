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
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.jsontype.TypeSerializer;

/// Jackson serializer for Option<T> types.
/// Serializes Option as null-like: null for None, or the wrapped value for Some<T>
public class OptionSerializer extends ValueSerializer<Option<?>> {
    private final Option<JavaType> valueType;
    private final Option<ValueSerializer<Object>> valueSerializer;

    public OptionSerializer() {
        this(Option.none(), Option.none());
    }

    private OptionSerializer(Option<JavaType> valueType, Option<ValueSerializer<Object>> valueSerializer) {
        this.valueType = valueType;
        this.valueSerializer = valueSerializer;
    }

    @Override
    public void serialize(Option<?> value, JsonGenerator gen, SerializationContext provider) throws JacksonException {
        switch (value) {
            case Option.Some<?> some -> valueSerializer.onPresent(ser -> ser.serialize(some.value(),
                                                                                       gen,
                                                                                       provider))
                                                       .onEmpty(() -> gen.writePOJO(some.value()));
            case Option.None<?> ignored -> gen.writeNull();
        }
    }

    @Override
    public ValueSerializer<?> createContextual(SerializationContext prov, BeanProperty property) {
        return Option.option(property)
                     .map(BeanProperty::getType)
                     .filter(JavaType::hasContentType)
                     .map(type -> createContextualSerializer(prov, type))
                     .or(this);
    }

    private OptionSerializer createContextualSerializer(SerializationContext prov, JavaType type) {
        var contentType = type.getContentType();
        var ser = prov.findValueSerializer(contentType);
        return new OptionSerializer(Option.option(contentType), Option.option(ser));
    }

    @Override
    public void serializeWithType(Option<?> value,
                                  JsonGenerator gen,
                                  SerializationContext provider,
                                  TypeSerializer typeSer) throws JacksonException {
        serialize(value, gen, provider);
    }

    @Override
    public boolean isEmpty(SerializationContext provider, Option<?> value) {
        return value instanceof Option.None;
    }
}
