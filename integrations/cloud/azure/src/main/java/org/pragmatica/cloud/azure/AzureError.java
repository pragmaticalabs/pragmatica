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

package org.pragmatica.cloud.azure;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/// Typed error causes for Azure Cloud API operations.
public sealed interface AzureError extends Cause {
    /// Azure API returned an error response.
    record ApiError(int statusCode, String code, String errorMessage) implements AzureError {
        @Override
        public String message() {
            return "Azure API error " + statusCode + " (" + code + "): " + errorMessage;
        }
    }

    /// Authentication or token acquisition failed.
    record AuthError(String details, Option<Throwable> cause) implements AzureError {
        @Override
        public String message() {
            return "Azure authentication error: " + details
                   + cause.map(t -> " - " + Causes.fromThrowable(t).message()).or("");
        }
    }

    /// Failed to parse API response.
    record ParseError(String context, Option<Throwable> cause) implements AzureError {
        @Override
        public String message() {
            return "Failed to parse Azure response: " + context
                   + cause.map(t -> " - " + Causes.fromThrowable(t).message()).or("");
        }
    }

    /// Azure error response JSON structure.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorResponse(@JsonProperty("error") ErrorBody error) {
        /// Error body within the response.
        @JsonIgnoreProperties(ignoreUnknown = true)
        record ErrorBody(String code, String message) {}
    }

    /// Maps an HTTP status code and response body to an AzureError.
    static AzureError fromResponse(int statusCode, String body, org.pragmatica.json.JsonMapper mapper) {
        return mapper.readString(body, ErrorResponse.class)
                     .map(resp -> extractApiError(statusCode, resp))
                     .or(new ApiError(statusCode, "unknown", body));
    }

    private static AzureError extractApiError(int statusCode, ErrorResponse resp) {
        return new ApiError(statusCode,
                            Option.option(resp.error())
                                  .map(ErrorResponse.ErrorBody::code)
                                  .or("unknown"),
                            Option.option(resp.error())
                                  .map(ErrorResponse.ErrorBody::message)
                                  .or("No details"));
    }
}
