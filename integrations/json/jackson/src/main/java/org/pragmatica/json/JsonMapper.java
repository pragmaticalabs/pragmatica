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

import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import java.util.function.Consumer;

import tools.jackson.databind.json.JsonMapper.Builder;

/// Functional wrapper around Jackson's JsonMapper providing Result-based API.
/// All operations return Result<T> instead of throwing exceptions, enabling
/// seamless composition with other functional constructs.
///
/// Usage:
/// ```java
/// var mapper = JsonMapper.create();
///
/// mapper.writeAsString(user)
///     .onSuccess(json -> logger.info("Serialized: {}", json))
///     .onFailure(cause -> logger.error("Failed: {}", cause));
///
/// mapper.readString(json, User.class)
///     .map(user -> user.withUpdatedTimestamp())
///     .flatMap(userRepo::save);
/// ```
public interface JsonMapper {
    /// Serialize value to JSON string.
    ///
    /// @param value The value to serialize
    /// @param <T>   Value type
    ///
    /// @return Result containing JSON string or error
    <T> Result<String> writeAsString(T value);

    /// Serialize value to JSON bytes.
    ///
    /// @param value The value to serialize
    /// @param <T>   Value type
    ///
    /// @return Result containing JSON bytes or error
    <T> Result<byte[]> writeAsBytes(T value);

    /// Deserialize from JSON string.
    ///
    /// @param json JSON string
    /// @param type Target class
    /// @param <T>  Target type
    ///
    /// @return Result containing deserialized value or error
    <T> Result<T> readString(String json, Class<T> type);

    /// Deserialize from JSON bytes.
    ///
    /// @param json JSON bytes
    /// @param type Target class
    /// @param <T>  Target type
    ///
    /// @return Result containing deserialized value or error
    <T> Result<T> readBytes(byte[] json, Class<T> type);

    /// Deserialize from JSON string using TypeToken.
    ///
    /// @param json      JSON string
    /// @param typeToken Type token for generic types
    /// @param <T>       Target type
    ///
    /// @return Result containing deserialized value or error
    <T> Result<T> readString(String json, TypeToken<T> typeToken);

    /// Deserialize from JSON bytes using TypeToken.
    ///
    /// @param json      JSON bytes
    /// @param typeToken Type token for generic types
    /// @param <T>       Target type
    ///
    /// @return Result containing deserialized value or error
    <T> Result<T> readBytes(byte[] json, TypeToken<T> typeToken);

    /// Creates a new JsonMapper builder.
    ///
    /// @return Builder instance
    static JsonMapperBuilder jsonMapper() {
        return new JsonMapperImpl.BuilderImpl();
    }

    /// Creates a JsonMapper with Pragmatica types support enabled.
    ///
    /// @return JsonMapper instance
    static JsonMapper defaultJsonMapper() {
        return jsonMapper().withPragmaticaTypes()
                         .build();
    }

    /// Builder interface for configuring JsonMapper.
    interface JsonMapperBuilder {
        /// Registers PragmaticaModule for Result/Option serialization.
        ///
        /// @return This builder
        JsonMapperBuilder withPragmaticaTypes();

        /// Configures underlying Jackson JsonMapper via builder.
        ///
        /// @param configurator Configuration function
        ///
        /// @return This builder
        JsonMapperBuilder configure(Consumer<Builder> configurator);

        /// Builds the JsonMapper instance.
        ///
        /// @return JsonMapper instance
        JsonMapper build();
    }

    /// Implementation of JsonMapper interface wrapping Jackson 3.0 JsonMapper.
    record JsonMapperImpl(tools.jackson.databind.json.JsonMapper mapper) implements JsonMapper {
        @Override
        public <T> Result<String> writeAsString(T value) {
            return Result.lift(JsonError::fromException, () -> mapper.writeValueAsString(value));
        }

        @Override
        public <T> Result<byte[]> writeAsBytes(T value) {
            return Result.lift(JsonError::fromException, () -> mapper.writeValueAsBytes(value));
        }

        @Override
        public <T> Result<T> readString(String json, Class<T> type) {
            return Result.lift(JsonError::fromException, () -> mapper.readValue(json, type));
        }

        @Override
        public <T> Result<T> readBytes(byte[] json, Class<T> type) {
            return Result.lift(JsonError::fromException, () -> mapper.readValue(json, type));
        }

        @Override
        public <T> Result<T> readString(String json, TypeToken<T> typeToken) {
            return Result.lift(JsonError::fromException, () -> mapper.readValue(json, toTypeReference(typeToken)));
        }

        @Override
        public <T> Result<T> readBytes(byte[] json, TypeToken<T> typeToken) {
            return Result.lift(JsonError::fromException, () -> mapper.readValue(json, toTypeReference(typeToken)));
        }

        /// Converts TypeToken to Jackson TypeReference.
        private static <T> tools.jackson.core.type.TypeReference<T> toTypeReference(TypeToken<T> typeToken) {
            return new tools.jackson.core.type.TypeReference<>() {
                @Override
                public java.lang.reflect.Type getType() {
                    return typeToken.token();
                }
            };
        }

        static final class BuilderImpl implements JsonMapperBuilder {
            private final java.util.List<Consumer<Builder>> configurators = new java.util.ArrayList<>();

            @Override
            public JsonMapperBuilder withPragmaticaTypes() {
                return configure(builder -> builder.addModule(new PragmaticaModule()));
            }

            @Override
            public JsonMapperBuilder configure(Consumer<Builder> configurator) {
                configurators.add(configurator);
                return this;
            }

            @Override
            public JsonMapper build() {
                var builder = tools.jackson.databind.json.JsonMapper.builder();
                configurators.forEach(c -> c.accept(builder));
                return new JsonMapperImpl(builder.build());
            }
        }
    }
}
