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

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.exc.StreamWriteException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.InvalidDefinitionException;
import tools.jackson.databind.exc.MismatchedInputException;

/// Typed error causes for XML serialization/deserialization operations.
/// Maps common Jackson exceptions to domain-friendly error types with context.
public sealed interface XmlError extends Cause {
    /// Serialization failed (converting object to XML).
    record SerializationFailed(String details, Option<Throwable> cause) implements XmlError {
        public static SerializationFailed serializationFailed(String details) {
            return new SerializationFailed(details, Option.none());
        }

        public static SerializationFailed serializationFailed(String details, Throwable cause) {
            return new SerializationFailed(details, Option.option(cause));
        }

        @Override
        public String message() {
            return "Serialization failed: " + details;
        }
    }

    /// Deserialization failed (converting XML to object).
    record DeserializationFailed(String details, Option<Throwable> cause) implements XmlError {
        public static DeserializationFailed deserializationFailed(String details) {
            return new DeserializationFailed(details, Option.none());
        }

        public static DeserializationFailed deserializationFailed(String details, Throwable cause) {
            return new DeserializationFailed(details, Option.option(cause));
        }

        @Override
        public String message() {
            return "Deserialization failed: " + details;
        }
    }

    /// Invalid XML syntax (parse error).
    record InvalidXml(String details, Option<String> locationInfo) implements XmlError {
        public static InvalidXml invalidXml(String details) {
            return new InvalidXml(details, Option.none());
        }

        public static InvalidXml invalidXml(String details, Option<String> locationInfo) {
            return new InvalidXml(details, locationInfo);
        }

        @Override
        public String message() {
            return locationInfo.map(loc -> "Invalid XML at " + loc + ": " + details)
                               .or("Invalid XML: " + details);
        }
    }

    /// Type mismatch during deserialization.
    record TypeMismatch(String expectedType, String actualValue, Option<String> path) implements XmlError {
        public static TypeMismatch typeMismatch(String expectedType, String actualValue) {
            return new TypeMismatch(expectedType, actualValue, Option.none());
        }

        public static TypeMismatch typeMismatch(String expectedType, String actualValue, String path) {
            return new TypeMismatch(expectedType, actualValue, Option.option(path));
        }

        @Override
        public String message() {
            var base = "Type mismatch: expected " + expectedType + ", got " + actualValue;
            return path.map(p -> base + " at " + p)
                       .or(base);
        }
    }

    /// Maps Jackson exceptions to typed XmlError causes.
    ///
    /// @param throwable Exception to map
    ///
    /// @return Corresponding Cause (XmlError for known types, generic Cause otherwise)
    static Cause fromException(Throwable throwable) {
        return switch (throwable) {
            case StreamWriteException e ->
            SerializationFailed.serializationFailed(e.getMessage(), e);
            case MismatchedInputException e ->
            TypeMismatch.typeMismatch(Option.option(e.getTargetType())
                                            .map(Class::getSimpleName)
                                            .or("unknown"),
                                      extractValue(e),
                                      e.getPathReference());
            case InvalidDefinitionException e ->
            TypeMismatch.typeMismatch(Option.option(e.getType())
                                            .map(t -> t.getTypeName())
                                            .or("unknown"),
                                      "invalid definition",
                                      e.getPathReference());
            case StreamReadException e ->
            InvalidXml.invalidXml(e.getMessage(), extractLocation(e));
            case DatabindException e ->
            DeserializationFailed.deserializationFailed(e.getMessage(), e);
            case JacksonException e ->
            DeserializationFailed.deserializationFailed(e.getMessage(), e);
            default -> Causes.fromThrowable(throwable);
        };
    }

    private static String extractValue(MismatchedInputException e) {
        return Option.option(e.getMessage())
                     .filter(msg -> msg.contains("Cannot deserialize value of type"))
                     .flatMap(XmlError::extractValueFromMessage)
                     .or("unknown");
    }

    private static Option<String> extractValueFromMessage(String msg) {
        return Option.option(msg.indexOf("from "))
                     .filter(fromIdx -> fromIdx > 0)
                     .flatMap(fromIdx -> extractSubstring(msg, fromIdx + 5));
    }

    private static Option<String> extractSubstring(String msg, int startIdx) {
        return Option.option(msg.indexOf(" ", startIdx))
                     .filter(endIdx -> endIdx > startIdx)
                     .map(endIdx -> msg.substring(startIdx, endIdx));
    }

    private static Option<String> extractLocation(StreamReadException e) {
        return Option.option(e.getLocation())
                     .map(loc -> "line " + loc.getLineNr() + ", column " + loc.getColumnNr());
    }
}
