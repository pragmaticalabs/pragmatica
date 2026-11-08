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

import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Patch;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.CreatorProperty;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;


/// Jackson deserializer for [Patch] record components: a missing member is [Patch#absent()] (Jackson asks
/// [#getAbsentValue] for a creator property the document leaves out), `null` is [Patch#nullValue()], and any other
/// value is [Patch#value] of the component's element type.
///
/// Every other position (the whole document, a collection element, a map value, the element of an `Option` or a
/// `Patch`, a field or a setter property) has no missing state, so it is refused when the deserializer is contextualized, with [#POSITION] as the
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

    /// Reached only for a present, non-null member: Jackson hands a record component's `null` to [#getNullValue]
    /// and its absence to [#getAbsentValue]. Only a contextualized instance is ever asked to read.
    @Override
    public Patch<?> deserialize(JsonParser p, DeserializationContext ctxt) {
        return Patch.value(valueDeserializer.map(deser -> deser.deserialize(p, ctxt)).or(() -> refused(ctxt, POSITION)));
    }

    /// `property` is the `Patch` record component, or none (the whole document, or an element resolved by type, as
    /// [#contextualized] resolves one): refused. A container or wrapper property hands itself on to this element, and
    /// then its "element" is this `Patch` type itself, which, resolved again without a property, refuses itself.
    @Override
    public ValueDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
        return option(property).filter(PatchDeserializer::isRecordCreatorProperty)
                     .map(BeanProperty::getType)
                     .flatMap(PatchDeserializer::elementType)
                     .filter(PatchDeserializer::isSupportedElement)
                     .<ValueDeserializer<?>> map(type -> contextualized(ctxt, type))
                     .or(() -> refused(ctxt,
                                       reason(property)));
    }

    // Jackson's failure channel is an exception, which JsonMapper lifts into a Result whose message keeps the reason.
    static <T> T refused(DeserializationContext ctxt, String reason) {
        return ctxt.reportBadDefinition(Patch.class, reason);
    }

    // Jackson asks for the absent value of a creator property only: a field or a setter property would read a missing
    // member as Java null, so it is refused; and only a record has a component to be absent from.
    private static boolean isRecordCreatorProperty(BeanProperty property) {
        return property instanceof CreatorProperty && isRecordMember(property);
    }

    /// Whether the property's member belongs to a record.
    static boolean isRecordMember(BeanProperty property) {
        return option(property.getMember()).map(member -> member.getDeclaringClass()
                                                                .isRecord())
                     .or(false);
    }

    /// The refusal message: [#POSITION], naming the property and its owning type when there is one (the whole
    /// document and a type-resolved element have none).
    static String reason(Option<BeanProperty> property) {
        return property.flatMap(PatchDeserializer::describe)
                       .map(described -> POSITION + ": " + described)
                       .or(POSITION);
    }

    private static Option<String> describe(BeanProperty property) {
        return option(property.getMember()).map(member -> "property '" + property.getName()
                                                         + "' of " + member.getDeclaringClass()
                                                                           .getName());
    }

    /// Whether a `Patch` sits anywhere in the type's generic tree (element, key or type argument).
    static boolean containsPatch(JavaType type) {
        return type.getRawClass() == Patch.class || Stream.concat(Stream.of(option(type.getKeyType()),
                                                                            option(type.getContentType()))
                                                                        .flatMap(Option::stream),
                                                                  IntStream.range(0,
                                                                                  type.containedTypeCount())
                                                                           .mapToObj(type::containedType))
                                                          .anyMatch(PatchDeserializer::containsPatch);
    }

    // Patch is resolved per concrete type like Option, so `T` sits in the generic binding of a SimpleType (#696).
    private static Option<JavaType> elementType(JavaType type) {
        return type.containedTypeCount() == 1
               ? option(type.containedType(0))
               : option(type.getContentType());
    }

    // An Option element would read `null` itself; a Patch element is refused by its own contextualization (below).
    private static boolean isSupportedElement(JavaType element) {
        return element.getRawClass() != Option.class;
    }

    // The element is resolved without the property, unlike OptionDeserializer's: a container element would hand this
    // property on to its own elements, and a Patch among them would then pass for a record component. Without it, a
    // Patch anywhere inside the element is refused here, at contextualization, even when the member is null or
    // missing, as the generated codecs refuse the type itself. The cost: an annotation on the component
    // (`@JsonFormat`) does not reach the value, on read as on write (PatchSerializer writes by runtime type).
    private static PatchDeserializer contextualized(DeserializationContext ctxt, JavaType element) {
        return new PatchDeserializer(option(ctxt.findRootValueDeserializer(element)));
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
