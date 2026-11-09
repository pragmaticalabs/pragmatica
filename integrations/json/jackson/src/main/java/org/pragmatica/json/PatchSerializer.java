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

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Patch;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.exc.InvalidDefinitionException;
import tools.jackson.databind.jsontype.TypeSerializer;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;

import static org.pragmatica.lang.Option.option;


/// Jackson serializer for [Patch] record components: [Patch.Value] writes its value, [Patch.Null] writes `null`, and
/// [Patch.Absent] is empty ([#isEmpty]), which [PragmaticaModule]'s as-property inclusion for `Patch` turns into a
/// left-out member, whatever the mapper's null inclusion.
///
/// The value is written by its runtime type, in the stream context of the member, so a value object stays bare as in
/// any other member. Every position other than a record component, at any container depth, is refused when the serializer is
/// contextualized, as [PatchDeserializer] refuses it, and so is an `Absent` that reaches [#serialize] (a property
/// whose own inclusion overrides the module's): writing it as `null` would turn "leave the field" into "clear it".
public class PatchSerializer extends ValueSerializer<Patch<?>> {
    private static final String ABSENT_WRITTEN = "an Absent Patch must be left out, but this member's inclusion writes it";

    @Contract
    @Override
    public void serialize(Patch<?> value, JsonGenerator gen, SerializationContext provider) {
        switch (value) {
            case Patch.Value<?> present -> provider.writeValue(gen, present.value());
            case Patch.Null<?>_ -> gen.writeNull();
            case Patch.Absent<?>_ -> refused(provider, ABSENT_WRITTEN);
        }
    }

    @Override
    public ValueSerializer<?> createContextual(SerializationContext prov, BeanProperty property) {
        return option(property).filter(PatchSerializer::isPatchRecordComponent)
                     .<ValueSerializer<?>> map(_ -> this)
                     .or(() -> refused(prov,
                                       PatchDeserializer.reason(option(property))));
    }

    // Jackson's failure channel is an exception, which JsonMapper lifts into a Result whose message keeps the reason.
    private static <T> T refused(SerializationContext provider, String reason) {
        return provider.reportBadDefinition(Patch.class, reason);
    }

    // The property must be the Patch component itself: its declared type is a Patch over a type that is neither an
    // Option nor a Patch. A container, wrapper or Object-typed property hands itself on to the Patch it holds, at any
    // depth (List<List<Patch>>, Map<K, List<Patch>>, an Object holding a Patch), and its type is not a Patch, so it is
    // refused here, as PatchDeserializer refuses the type outright. Checked here because the value is written by its
    // runtime type: a Null or Absent inner Patch would otherwise never reach a serializer that could refuse it.
    private static boolean isPatchRecordComponent(BeanProperty property) {
        return hasSupportedElement(property.getType()) && isComponentOfItsRecord(property);
    }

    // A field or a getter of a plain class, or an extra getter of a record, is no component: refused as on read.
    private static boolean isComponentOfItsRecord(BeanProperty property) {
        return option(property.getMember()).filter(_ -> PatchDeserializer.isRecordMember(property))
                     .filter(member -> isComponentName(member.getDeclaringClass(),
                                                       member.getName()))
                     .isPresent();
    }

    private static boolean isComponentName(Class<?> owner, String name) {
        return Stream.of(owner.getRecordComponents()).anyMatch(component -> component.getName()
                                                                                     .equals(name));
    }

    private static boolean hasSupportedElement(JavaType type) {
        return type.getRawClass() == Patch.class && hasPlainElement(type);
    }

    private static boolean hasPlainElement(JavaType type) {
        return option(type.containedTypeCount() == 1
                      ? type.containedType(0)
                      : type.getContentType()).map(JavaType::getRawClass)
                     .filter(raw -> raw == Option.class || raw == Patch.class)
                     .isEmpty();
    }

    /// Refuses, when a bean serializer is built for a type (before any value is written), a property that holds a
    /// `Patch` anywhere in its declared type but is not a `Patch` record component: the runtime-typed
    /// [#createContextual] check alone would see it only once a non-null value is written. `@Contract`: Jackson's
    /// failure channel for a bean serializer under construction is an exception.
    @Contract
    static final class Guard extends ValueSerializerModifier {
        @Override
        public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                         BeanDescription.Supplier description,
                                                         List<BeanPropertyWriter> properties) {
            return properties.stream()
                             .filter(Guard::isMisplaced)
                             .findFirst()
                             .map(writer -> Guard.<List<BeanPropertyWriter>> misplaced(description, writer))
                             .orElse(properties);
        }

        private static boolean isMisplaced(BeanPropertyWriter writer) {
            return PatchDeserializer.containsPatch(writer.getType()) && !isPatchRecordComponent(writer);
        }

        // Jackson's failure channel is an exception, which JsonMapper lifts into a Result whose message keeps the reason.
        private static <T> T misplaced(BeanDescription.Supplier description, BeanPropertyWriter writer) {
            throw InvalidDefinitionException.from((JsonGenerator) null,
                                                  PatchDeserializer.reason(option(writer)),
                                                  description.getType());
        }
    }

    @Contract
    @Override
    public void serializeWithType(Patch<?> value,
                                  JsonGenerator gen,
                                  SerializationContext provider,
                                  TypeSerializer typeSer) {
        serialize(value, gen, provider);
    }

    @Override
    public boolean isEmpty(SerializationContext provider, Patch<?> value) {
        return value instanceof Patch.Absent;
    }
}
