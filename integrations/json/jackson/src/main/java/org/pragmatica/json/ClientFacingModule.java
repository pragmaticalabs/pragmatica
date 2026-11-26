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

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.Version;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;


/// Jackson module for a mapper that writes an HTTP client body (#3723): a failed `Result` and a `Cause` are written with
/// their `clientMessage()`, never their `message()`, which may carry a caught throwable's stack trace. Registered after
/// [PragmaticaModule] by `JsonMapper.clientFacing()`, so its `Result` serializer replaces the general one; the general
/// mapper (CLI, cloud clients, state files) keeps writing `message()`.
///
/// A `Cause` is written as the object a failed `Result` carries: `{"message": "...", "type": "..."}`.
public class ClientFacingModule extends SimpleModule {
    public ClientFacingModule() {
        super("pragmatica-client-facing", Version.unknownVersion());
        addSerializer((Class) Result.class, ResultSerializer.clientFacing());
        addSerializer(Cause.class, new CauseSerializer());
    }

    private static final class CauseSerializer extends ValueSerializer<Cause> {
        @Override
        public void serialize(Cause value, JsonGenerator gen, SerializationContext provider) throws JacksonException {
            gen.writeStartObject();
            gen.writeStringProperty("message",
                                    value.clientMessage());
            gen.writeStringProperty("type",
                                    value.getClass()
                                         .getSimpleName());
            gen.writeEndObject();
        }
    }
}
