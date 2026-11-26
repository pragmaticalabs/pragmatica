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
package org.pragmatica.http.routing;

import org.pragmatica.http.CodecError;
import org.pragmatica.http.JsonCodec;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import com.fasterxml.jackson.annotation.JsonInclude;


/// Adapter that wraps JsonMapper to implement JsonCodec interface.
/// Bridges between Jackson-based JSON serialization and the `byte[]` codec seam.
public record JsonCodecAdapter(JsonMapper mapper) implements JsonCodec {
    private static final String SERIALIZATION_FAILED = "Serialization failed";

    /// Every HTTP response body is written through an adapter, so this is where a failed `Result` or a `Cause` inside a
    /// body is held to its client text (#3723): the wrapped mapper is [JsonMapper#clientFacing()] however the adapter was
    /// made. The general mapper the caller passed (the CLI, cloud clients and state files use such mappers) is unchanged.
    public JsonCodecAdapter {
        mapper = mapper.clientFacing();
    }

    /// Creates a JsonCodec adapter for the given JsonMapper.
    ///
    /// @param mapper The JsonMapper instance to wrap
    /// @return JsonCodec implementation
    public static JsonCodec forMapper(JsonMapper mapper) {
        return new JsonCodecAdapter(mapper);
    }

    /// Creates a JsonCodec using the default JsonMapper configuration.
    ///
    /// @return JsonCodec implementation with default settings
    public static JsonCodec defaultCodec() {
        return forMapper(JsonMapper.jsonMapper()
                                   .withPragmaticaTypes()
                                   .configure(builder -> builder.changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_EMPTY)
                                                                                                            .withContentInclusion(JsonInclude.Include.NON_EMPTY)))
                                   .build());
    }

    /// The text of a failed serialization is fixed (#3723): the mapper's failure message quotes the value and the
    /// serializer's exception (class names, a reference chain), so the codec error never copies it into its own text. The
    /// mapper's failure stays as the error's source, for the server-side cause chain.
    @Override
    public Result<byte[]> serialize(Object value) {
        return mapper.writeAsBytes(value)
                     .mapError(cause -> CodecError.serializationFailed(SERIALIZATION_FAILED, cause));
    }

    @Override
    public <T> Result<T> deserialize(byte[] bytes, TypeToken<T> token) {
        return mapper.readBytes(bytes, token)
                     .mapError(cause -> CodecError.deserializationFailed(cause.message(),
                                                                         cause));
    }
}
