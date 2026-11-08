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

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Patch;
import org.pragmatica.lang.Result;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.core.Version;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.Deserializers;
import tools.jackson.databind.module.SimpleModule;


/// Jackson module for Pragmatica-Lite types (Result, Option, Patch).
/// Registers custom serializers and deserializers for functional types.
///
/// Result serialization format:
/// - Success: {"success": true, "value": <T>}
/// - Failure: {"success": false, "error": {"message": "...", "type": "..."}}
///
/// Option serialization format:
/// - Some: <T> (the wrapped value)
/// - None: null
///
/// Patch serialization format (a record component only, see [Patch]):
/// - Value: <T> (the wrapped value)
/// - Null: null
/// - Absent: the member is left out, whatever the mapper's null inclusion
public class PragmaticaModule extends SimpleModule {
    public PragmaticaModule() {
        super("pragmatica", Version.unknownVersion());
        // Register Result serializers
        addSerializer((Class) Result.class, new ResultSerializer());
        addDeserializer((Class) Result.class, new ResultDeserializer());
        // Register Option and Patch serializers
        addSerializer((Class) Option.class, new OptionSerializer());
        addSerializer((Class) Patch.class, new PatchSerializer());
    }

    // Option and Patch deserializers are found per concrete type, not registered on the plain class: a class-keyed
    // registration hands every Option<T> the same untyped instance, and only a declared Option property can recover
    // T from its context. See OptionDeserializer#optionDeserializer and PatchDeserializer#patchDeserializer.
    @Contract
    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);
        context.addDeserializers(new OptionDeserializers());
        context.addSerializerModifier(new PatchSerializer.Guard());
        context.configOverride(Patch.class).setIncludeAsProperty(ABSENT_LEFT_OUT);
    }

    // A Patch member is left out exactly when it is Absent (PatchSerializer#isEmpty). As-property, like the Option
    // override JsonMapper applies for its null inclusion, which never touches Patch: a Null Patch is always written.
    private static final JsonInclude.Value ABSENT_LEFT_OUT = JsonInclude.Value.construct(JsonInclude.Include.NON_EMPTY,
                                                                                         JsonInclude.Include.USE_DEFAULTS);

    private static final class OptionDeserializers extends Deserializers.Base {
        @NullReturn
        @Override
        public ValueDeserializer<?> findBeanDeserializer(JavaType type,
                                                         DeserializationConfig config,
                                                         BeanDescription.Supplier description) {
            return type.getRawClass() == Option.class
                   ? OptionDeserializer.optionDeserializer(type)
                   : type.getRawClass() == Patch.class
                     ? PatchDeserializer.patchDeserializer()
                     : null;
        }

        @Override
        public boolean hasDeserializerFor(DeserializationConfig config, Class<?> type) {
            return type == Option.class || type == Patch.class;
        }
    }
}
