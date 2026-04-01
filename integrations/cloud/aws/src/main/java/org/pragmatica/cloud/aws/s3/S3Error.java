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

package org.pragmatica.cloud.aws.s3;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

/// Typed error causes for S3-compatible object storage operations.
public sealed interface S3Error extends Cause {

    /// Object not found (404).
    record NotFound(String key) implements S3Error {
        @Override
        public String message() {
            return "S3 object not found: " + key;
        }
    }

    /// Access denied (403).
    record AccessDenied(String details) implements S3Error {
        @Override
        public String message() {
            return "S3 access denied: " + details;
        }
    }

    /// Bucket does not exist (404 with NoSuchBucket code).
    record BucketNotFound(String bucket) implements S3Error {
        @Override
        public String message() {
            return "S3 bucket not found: " + bucket;
        }
    }

    /// Network or I/O error during S3 operation.
    record NetworkError(String context, Option<Throwable> cause) implements S3Error {
        @Override
        public String message() {
            return "S3 network error: " + context
                   + cause.map(t -> " - " + Causes.fromThrowable(t).message()).or("");
        }
    }

    /// Failed to parse S3 XML response.
    record ParseError(String context) implements S3Error {
        @Override
        public String message() {
            return "S3 parse error: " + context;
        }
    }

    /// General S3 API error with status code.
    record ApiError(int statusCode, String code, String errorMessage) implements S3Error {
        @Override
        public String message() {
            return "S3 API error " + statusCode + " (" + code + "): " + errorMessage;
        }
    }

    /// Maps an HTTP status code and XML response body to an S3Error.
    static S3Error fromResponse(int statusCode, String body, String key) {
        if (statusCode == 404) {
            return extractXmlCode(body)
                .filter("NoSuchBucket"::equals)
                .map(_ -> (S3Error) new BucketNotFound(key))
                .or(new NotFound(key));
        }
        if (statusCode == 403) {
            return new AccessDenied(extractXmlMessage(body).or("Forbidden"));
        }
        var code = extractXmlCode(body).or("UnknownError");
        var errorMessage = extractXmlMessage(body).or(body);
        return new ApiError(statusCode, code, errorMessage);
    }

    private static Option<String> extractXmlCode(String xml) {
        return extractXmlElement(xml, "Code");
    }

    private static Option<String> extractXmlMessage(String xml) {
        return extractXmlElement(xml, "Message");
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
}
