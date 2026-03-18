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

package org.pragmatica.cloud.aws;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

/// Typed error causes for AWS Cloud API operations.
public sealed interface AwsError extends Cause {
    /// AWS API returned an error response.
    record ApiError(int statusCode, String code, String errorMessage) implements AwsError {
        @Override
        public String message() {
            return "AWS API error " + statusCode + " (" + code + "): " + errorMessage;
        }
    }

    /// Failed to compute SigV4 signature.
    record SigningError(String details, Option<Throwable> cause) implements AwsError {
        @Override
        public String message() {
            return "AWS signing error: " + details;
        }
    }

    /// Failed to parse API response.
    record ParseError(String context, Option<Throwable> cause) implements AwsError {
        @Override
        public String message() {
            return "Failed to parse AWS response: " + context
                   + cause.map(t -> " - " + Causes.fromThrowable(t).message()).or("");
        }
    }

    /// Maps an HTTP status code and response body to an AwsError.
    /// Handles both XML error responses (EC2) and JSON error responses (ELBv2/SecretsManager).
    static AwsError fromResponse(int statusCode, String body) {
        return Option.option(body)
                     .filter(b -> b.contains("<Error>"))
                     .map(b -> extractXmlError(statusCode, b))
                     .or(() -> extractJsonError(statusCode, body));
    }

    private static AwsError extractXmlError(int statusCode, String body) {
        var code = extractXmlElement(body, "Code").or("UnknownError");
        var errorMessage = extractXmlElement(body, "Message").or("No details");
        return new ApiError(statusCode, code, errorMessage);
    }

    private static AwsError extractJsonError(int statusCode, String body) {
        var code = extractJsonField(body, "__type")
                       .or(extractJsonField(body, "code").or("UnknownError"));
        var errorMessage = extractJsonField(body, "message")
                               .or(extractJsonField(body, "Message").or(body));
        return new ApiError(statusCode, code, errorMessage);
    }

    private static Option<String> extractXmlElement(String xml, String elementName) {
        var openTag = "<" + elementName + ">";
        var closeTag = "</" + elementName + ">";
        var startIdx = xml.indexOf(openTag);
        return Option.option(startIdx)
                     .filter(idx -> idx >= 0)
                     .map(idx -> idx + openTag.length())
                     .flatMap(start -> extractUntilClose(xml, start, closeTag));
    }

    private static Option<String> extractUntilClose(String xml, int start, String closeTag) {
        var endIdx = xml.indexOf(closeTag, start);
        return Option.option(endIdx)
                     .filter(idx -> idx > start)
                     .map(idx -> xml.substring(start, idx));
    }

    private static Option<String> extractJsonField(String body, String fieldName) {
        var pattern = "\"" + fieldName + "\"";
        var keyIdx = body.indexOf(pattern);
        return Option.option(keyIdx)
                     .filter(idx -> idx >= 0)
                     .map(idx -> body.indexOf(':', idx + pattern.length()))
                     .filter(colonIdx -> colonIdx >= 0)
                     .flatMap(colonIdx -> extractJsonStringValue(body, colonIdx + 1));
    }

    private static Option<String> extractJsonStringValue(String body, int startIdx) {
        var trimmed = body.substring(startIdx).trim();
        return Option.option(trimmed)
                     .filter(s -> s.startsWith("\""))
                     .map(s -> s.substring(1))
                     .flatMap(AwsError::extractUntilQuote);
    }

    private static Option<String> extractUntilQuote(String value) {
        var endIdx = value.indexOf('"');
        return Option.option(endIdx)
                     .filter(idx -> idx >= 0)
                     .map(idx -> value.substring(0, idx));
    }

    /// Extracts SecretString field from a Secrets Manager JSON response.
    static Result<String> extractSecretStringField(String body) {
        return extractJsonField(body, "SecretString")
            .toResult(new ParseError("Missing SecretString in response", Option.none()));
    }
}
