// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.routing;

import org.pragmatica.lang.Option;


/// Strict (decision D3) compile-time validator for `consumes`/`produces` media types against
/// the Java types of a slice method.
///
/// Pure and side-effect free so it can be unit-tested without a compile-fail harness: it takes a
/// declared [org.pragmatica.http.ContentCategory] name plus a normalized Java type string and
/// returns [Option#none()] when the pairing is valid, or [Option#some] with an error message when
/// it is not.
///
/// Rules:
///
///   - produces TEXT/HTML/XML → response must be `String` (`byte[]` also allowed)
///   - produces BINARY → response must be `byte[]` (`String` also allowed)
///   - consumes TEXT/HTML/XML → parameter must be `String`
///   - consumes BINARY → parameter must be `byte[]`
///   - consumes MULTIPART → parameter must be `MultipartRequest`
///   - JSON (in or out) → any type is accepted
///   - FORM_URLENCODED is rejected in both directions: as a `produces` it is input-only, and as a
///     `consumes` it is rejected until form-body binding is implemented (#414)
///
public sealed interface MediaTypeTypeChecker {
    /// Validate a `produces` media category against the method's response type.
    ///
    /// @param category     the ContentCategory enum constant name (e.g. "TEXT", "BINARY", "JSON")
    /// @param responseType the normalized Java response type string (e.g. "java.lang.String", "byte[]")
    /// @param methodName   the slice method name, for the error message
    /// @return none when valid, some(errorMessage) when the response type is incompatible
    static Option<String> checkProduces(String category, String responseType, String methodName) {
        return switch (category) {
            case "JSON" -> Option.none();
            case "TEXT", "HTML", "XML" -> isString(responseType) || isByteArray(responseType)
                                          ? Option.none()
                                          : producesError(category, "String (or byte[])", responseType, methodName);
            case "BINARY" -> isByteArray(responseType) || isString(responseType)
                             ? Option.none()
                             : producesError(category, "byte[] (or String)", responseType, methodName);
            case "FORM_URLENCODED", "MULTIPART" -> Option.some("Slice method '" + methodName + "': produces media type with category " + category + " is input-only and cannot be used for responses");
            default -> Option.some("Slice method '" + methodName + "': unknown produces content category " + category);
        };
    }

    /// Validate a `consumes` media category against the method's parameter type.
    ///
    /// @param category      the ContentCategory enum constant name (e.g. "TEXT", "BINARY", "MULTIPART")
    /// @param parameterType the normalized Java parameter type string (e.g. "java.lang.String", "byte[]")
    /// @param methodName    the slice method name, for the error message
    /// @return none when valid, some(errorMessage) when the parameter type is incompatible
    static Option<String> checkConsumes(String category, String parameterType, String methodName) {
        return switch (category) {
            case "JSON" -> Option.none();
            case "TEXT", "HTML", "XML" -> isString(parameterType)
                                          ? Option.none()
                                          : consumesError(category, "String", parameterType, methodName);
            case "BINARY" -> isByteArray(parameterType)
                             ? Option.none()
                             : consumesError(category, "byte[]", parameterType, methodName);
            case "MULTIPART" -> isMultipart(parameterType)
                                ? Option.none()
                                : consumesError(category, "MultipartRequest", parameterType, methodName);
            case "FORM_URLENCODED" -> Option.some("Slice method '" + methodName + "': consumes media type with category " + category + " is not supported yet — form-body binding is unimplemented; use application/json" + " or a supported type");
            default -> Option.some("Slice method '" + methodName + "': unknown consumes content category " + category);
        };
    }

    private static Option<String> producesError(String category, String expected, String actual, String methodName) {
        return Option.some("Slice method '" + methodName
                          + "': produces category " + category
                          + " requires return type " + expected
                          + ", but method returns " + actual);
    }

    private static Option<String> consumesError(String category, String expected, String actual, String methodName) {
        return Option.some("Slice method '" + methodName
                          + "': consumes category " + category
                          + " requires parameter type " + expected
                          + ", but method parameter is " + actual);
    }

    private static boolean isString(String type) {
        return "java.lang.String".equals(type) || "String".equals(type);
    }

    private static boolean isByteArray(String type) {
        return "byte[]".equals(type);
    }

    private static boolean isMultipart(String type) {
        return "org.pragmatica.http.routing.MultipartRequest".equals(type) || "MultipartRequest".equals(type);
    }

    record unused() implements MediaTypeTypeChecker {}
}
