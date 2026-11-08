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
import org.pragmatica.lang.Patch;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ValueDeserializer;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;


/// Jackson deserializer for [Patch] record components: a missing member is [Patch#absent()] (Jackson asks
/// [#getAbsentValue] for a creator property the document leaves out), `null` is [Patch#nullValue()], and any other
/// value is [Patch#value] of the component's element type.
///
/// Every other position (the whole document, a collection element, a map value, the element of an `Option` or a
/// `Patch`) has no missing state, so it is refused when the deserializer is contextualized, with [#POSITION] as the
/// message. The generated codecs refuse the same positions (json-codegen `JsonTypeAnalyzer`), so a route type the
/// generator leaves to this mapper fails here instead of reading `Absent` as `null`.
public class PatchDeserializer extends ValueDeserializer<Patch<?>> {
    /// Why a `Patch` type in an unsupported position is refused; the generated codecs give the same reason.
    static final String POSITION = "a Patch is supported only as a record component, over a type that is neither an Option nor a Patch";

    private final Option<ValueDeserializer<Object>> valueDeserializer;

    private PatchDeserializer(Option<ValueDeserializer<Object>> valueDeserializer) {
        this.valueDeserializer = valueDeserializer;
    }

    /// The not yet contextualized deserializer for a `Patch<T>` type; only [#createContextual] for a `Patch` record
    /// component makes it usable.
    static PatchDeserializer patchDeserializer() {
        return new PatchDeserializer(none());
    }

    @Override
    public Patch<?> deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        if (p.currentToken() == JsonToken.VALUE_NULL) {
            return Patch.nullValue();
        }

        return Patch.value(valueDeserializer.map(deser -> deser.deserialize(p, ctxt)).or(() -> refused(ctxt)));
    }

    @Override
    public ValueDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
        return option(property).map(BeanProperty::getType)
                     .filter(type -> type.getRawClass() == Patch.class)
                     .flatMap(PatchDeserializer::elementType)
                     .filter(PatchDeserializer::isSupportedElement)
                     .<ValueDeserializer<?>> map(type -> contextualized(ctxt, property, type))
                     .or(() -> refused(ctxt));
    }

    // Jackson's failure channel is an exception (JsonMapper lifts it into a Result). Thrown as a DatabindException,
    // not reported through reportBadDefinition: the mapper turns an InvalidDefinitionException into a bare
    // "invalid definition" and would drop the reason.
    private static <T> T refused(DeserializationContext ctxt) {
        throw DatabindException.from(ctxt, POSITION);
    }

    // Patch is resolved per concrete type like Option, so `T` sits in the generic binding of a SimpleType (#696).
    private static Option<JavaType> elementType(JavaType type) {
        return type.containedTypeCount() == 1
               ? option(type.containedType(0))
               : option(type.getContentType());
    }

    private static boolean isSupportedElement(JavaType element) {
        return element.getRawClass() != Option.class && element.getRawClass() != Patch.class;
    }

    // A container element is resolved without the property, unlike OptionDeserializer's: the container would hand
    // this property to its own elements, and a Patch element would then pass for a record component. Resolved here,
    // not at the first value, so a Patch among the elements is refused even when the member is null or missing, as
    // the generated codecs refuse the type itself.
    private static PatchDeserializer contextualized(DeserializationContext ctxt,
                                                    BeanProperty property,
                                                    JavaType element) {
        return new PatchDeserializer(option(element.isContainerType()
                                            ? ctxt.findRootValueDeserializer(element)
                                            : ctxt.findContextualValueDeserializer(element, property)));
    }

    @Override
    public Patch<?> getNullValue(DeserializationContext ctxt) {
        return Patch.nullValue();
    }

    @Override
    public Patch<?> getAbsentValue(DeserializationContext ctxt) {
        return Patch.absent();
    }
}
