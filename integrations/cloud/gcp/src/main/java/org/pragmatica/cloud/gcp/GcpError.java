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

package org.pragmatica.cloud.gcp;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/// Typed error causes for GCP Cloud API operations.
public sealed interface GcpError extends Cause {
    /// GCP API returned an error response.
    record ApiError(int statusCode, String code, String errorMessage) implements GcpError {
        @Override
        public String message() {
            return "GCP API error " + statusCode + " (" + code + "): " + errorMessage;
        }
    }

    /// Authentication/token error.
    record AuthError(String details, Option<Throwable> cause) implements GcpError {
        @Override
        public String message() {
            return "GCP auth error: " + details + cause.map(t -> " - " + Causes.fromThrowable(t).message()).or("");
        }
    }

    /// Failed to parse API response.
    record ParseError(String context, Option<Throwable> cause) implements GcpError {
        @Override
        public String message() {
            return "Failed to parse GCP response: " + context + cause.map(t -> " - " + Causes.fromThrowable(t).message()).or("");
        }
    }

    /// GCP error response JSON structure.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorResponse(@JsonProperty("error") ErrorBody error) {
        /// Error body within the response.
        @JsonIgnoreProperties(ignoreUnknown = true)
        record ErrorBody(int code, String message, String status) {}
    }

    /// Maps an HTTP status code and response body to a GcpError.
    static GcpError fromResponse(int statusCode, String body, JsonMapper mapper) {
        return mapper.readString(body, ErrorResponse.class)
                     .map(resp -> extractApiError(statusCode, resp))
                     .or(new ApiError(statusCode, "unknown", body));
    }

    private static GcpError extractApiError(int statusCode, ErrorResponse resp) {
        return new ApiError(statusCode,
                            Option.option(resp.error())
                                  .map(ErrorResponse.ErrorBody::status)
                                  .or("unknown"),
                            Option.option(resp.error())
                                  .map(ErrorResponse.ErrorBody::message)
                                  .or("No details"));
    }
}
