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

package org.pragmatica.xml;

import org.pragmatica.lang.Result;

import java.util.function.Consumer;

import tools.jackson.dataformat.xml.XmlMapper.Builder;

/// Functional wrapper around Jackson's XmlMapper providing Result-based API.
/// All operations return Result<T> instead of throwing exceptions, enabling
/// seamless composition with other functional constructs.
///
/// Usage:
/// ```java
/// var mapper = XmlMapper.defaultXmlMapper();
///
/// mapper.writeAsString(user)
///     .onSuccess(xml -> logger.info("Serialized: {}", xml))
///     .onFailure(cause -> logger.error("Failed: {}", cause));
///
/// mapper.readString(xml, User.class)
///     .map(user -> user.withUpdatedTimestamp())
///     .flatMap(userRepo::save);
/// ```
public interface XmlMapper {
    /// Serialize value to XML string.
    ///
    /// @param value The value to serialize
    /// @param <T>   Value type
    ///
    /// @return Result containing XML string or error
    <T> Result<String> writeAsString(T value);

    /// Serialize value to XML bytes.
    ///
    /// @param value The value to serialize
    /// @param <T>   Value type
    ///
    /// @return Result containing XML bytes or error
    <T> Result<byte[]> writeAsBytes(T value);

    /// Deserialize from XML string.
    ///
    /// @param xml  XML string
    /// @param type Target class
    /// @param <T>  Target type
    ///
    /// @return Result containing deserialized value or error
    <T> Result<T> readString(String xml, Class<T> type);

    /// Deserialize from XML bytes.
    ///
    /// @param xml  XML bytes
    /// @param type Target class
    /// @param <T>  Target type
    ///
    /// @return Result containing deserialized value or error
    <T> Result<T> readBytes(byte[] xml, Class<T> type);

    /// Creates a new XmlMapper builder.
    ///
    /// @return Builder instance
    static XmlMapperBuilder xmlMapper() {
        return new XmlMapperImpl.BuilderImpl();
    }

    /// Creates an XmlMapper with default configuration.
    ///
    /// @return XmlMapper instance
    static XmlMapper defaultXmlMapper() {
        return xmlMapper().build();
    }

    /// Builder interface for configuring XmlMapper.
    interface XmlMapperBuilder {
        /// Configures underlying Jackson XmlMapper via builder.
        ///
        /// @param configurator Configuration function
        ///
        /// @return This builder
        XmlMapperBuilder configure(Consumer<Builder> configurator);

        /// Builds the XmlMapper instance.
        ///
        /// @return XmlMapper instance
        XmlMapper build();
    }

    /// Implementation of XmlMapper interface wrapping Jackson XmlMapper.
    record XmlMapperImpl(tools.jackson.dataformat.xml.XmlMapper mapper) implements XmlMapper {
        @Override
        public <T> Result<String> writeAsString(T value) {
            return Result.lift(XmlError::fromException, () -> mapper.writeValueAsString(value));
        }

        @Override
        public <T> Result<byte[]> writeAsBytes(T value) {
            return Result.lift(XmlError::fromException, () -> mapper.writeValueAsBytes(value));
        }

        @Override
        public <T> Result<T> readString(String xml, Class<T> type) {
            return Result.lift(XmlError::fromException, () -> mapper.readValue(xml, type));
        }

        @Override
        public <T> Result<T> readBytes(byte[] xml, Class<T> type) {
            return Result.lift(XmlError::fromException, () -> mapper.readValue(xml, type));
        }

        static final class BuilderImpl implements XmlMapperBuilder {
            private final java.util.List<Consumer<Builder>> configurators = new java.util.ArrayList<>();

            @Override
            public XmlMapperBuilder configure(Consumer<Builder> configurator) {
                configurators.add(configurator);
                return this;
            }

            @Override
            public XmlMapper build() {
                var builder = tools.jackson.dataformat.xml.XmlMapper.builder();
                configurators.forEach(c -> c.accept(builder));
                return new XmlMapperImpl(builder.build());
            }
        }
    }
}
