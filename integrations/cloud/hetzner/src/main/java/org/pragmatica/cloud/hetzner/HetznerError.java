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

package org.pragmatica.cloud.hetzner;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/// Typed error causes for Hetzner Cloud API operations.
public sealed interface HetznerError extends Cause {
    /// Hetzner API returned an error response.
    record ApiError(int statusCode, String code, String errorMessage) implements HetznerError {
        @Override
        public String message() {
            return "Hetzner API error " + statusCode + " (" + code + "): " + errorMessage;
        }
    }

    /// Rate limit exceeded (HTTP 429).
    record RateLimited(long retryAfterSeconds) implements HetznerError {
        @Override
        public String message() {
            return "Hetzner API rate limited, retry after " + retryAfterSeconds + " seconds";
        }
    }

    /// Failed to parse API response.
    record ParseError(String context, Throwable cause) implements HetznerError {
        @Override
        public String message() {
            return "Failed to parse Hetzner response: " + context + " - " + Causes.fromThrowable(cause)
                                                                                 .message();
        }
    }

    /// Hetzner error response JSON structure.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorResponse(@JsonProperty("error") ErrorBody error) {
        /// Error body within the response.
        @JsonIgnoreProperties(ignoreUnknown = true)
        record ErrorBody(String code, String message) {}
    }

    /// Maps an HTTP status code and response body to a HetznerError.
    static HetznerError fromResponse(int statusCode, String body, org.pragmatica.json.JsonMapper mapper) {
        if (statusCode == 429) {
            return new RateLimited(extractRetryAfter(body));
        }
        return mapper.readString(body, ErrorResponse.class)
                     .map(resp -> extractApiError(statusCode, resp))
                     .or(new ApiError(statusCode, "unknown", body));
    }

    private static long extractRetryAfter(String body) {
        return Option.option(body)
                     .filter(b -> b.contains("retry_after"))
                     .flatMap(HetznerError::parseRetryAfterFromJson)
                     .or(60L);
    }

    private static Option<Long> parseRetryAfterFromJson(String body) {
        var colonIdx = body.indexOf(':', body.indexOf("retry_after"));
        return findEndIndex(body, colonIdx).filter(endIdx -> endIdx > colonIdx)
                           .flatMap(endIdx -> parseLongSafe(body.substring(colonIdx + 1, endIdx)
                                                                .trim()));
    }

    private static Option<Integer> findEndIndex(String body, int colonIdx) {
        var commaIdx = body.indexOf(',', colonIdx);
        var braceIdx = body.indexOf('}', colonIdx);
        return Option.option(commaIdx > 0 && (braceIdx < 0 || commaIdx < braceIdx)
                             ? commaIdx
                             : braceIdx)
                     .filter(idx -> idx > 0);
    }

    private static Option<Long> parseLongSafe(String value) {
        return Result.lift(() -> Long.parseLong(value))
                     .option();
    }

    private static HetznerError extractApiError(int statusCode, ErrorResponse resp) {
        return new ApiError(statusCode,
                            Option.option(resp.error())
                                  .map(ErrorResponse.ErrorBody::code)
                                  .or("unknown"),
                            Option.option(resp.error())
                                  .map(ErrorResponse.ErrorBody::message)
                                  .or("No details"));
    }
}
