// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.handler.security;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import java.util.regex.Pattern;

import static org.pragmatica.lang.Option.option;


public record ApiKey(String value) {
    private static final Pattern VALID_KEY = Pattern.compile("^[a-zA-Z0-9_-]{8,64}$");

    public sealed interface ApiKeyError extends Cause {
        enum General implements ApiKeyError {
            NULL_VALUE("API key value cannot be null");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override public String message() {
                return message;
            }
        }

        record InvalidFormat(String value) implements ApiKeyError {
            @SuppressWarnings("unused") public static Result<InvalidFormat> invalidFormat(Result<String> value) {
                return value.map(InvalidFormat::new);
            }

            @Override public String message() {
                return "Invalid API key format: must be 8-64 alphanumeric characters with _ or -";
            }
        }

        @SuppressWarnings("unused") record unused() implements ApiKeyError {
            @Override public String message() {
                return "";
            }
        }
    }

    public static Result<ApiKey> apiKey(String value) {
        return ensureNotNull(value).filter(ApiKey::invalidFormat, VALID_KEY.asMatchPredicate()).map(ApiKey::new);
    }

    public static boolean isValidFormat(String value) {
        return option(value).map(VALID_KEY.asMatchPredicate()::test).or(false);
    }

    private static Result<String> ensureNotNull(String value) {
        return Verify.ensure(value, Verify.Is::notNull, ApiKeyError.General.NULL_VALUE);
    }

    private static Cause invalidFormat(String val) {
        return new ApiKeyError.InvalidFormat(val);
    }
}
