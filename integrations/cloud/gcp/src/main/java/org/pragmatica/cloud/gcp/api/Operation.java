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
package org.pragmatica.cloud.gcp.api;

import java.util.List;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import static org.pragmatica.lang.Option.option;


/// GCP async operation tracking model. `error` is the operation's own failure report: GCP answers a
/// rejected request with HTTP 200 and a body whose `error` member is set (#1535), so a success status on the
/// call alone does not mean the request was accepted. It is null (absent from the JSON) when none is reported.
@JsonIgnoreProperties(ignoreUnknown = true)
public record Operation(String name, String status, String targetLink, String operationType, OperationError error) {
    /// An operation that reports no error.
    public Operation(String name, String status, String targetLink, String operationType) {
        this(name, status, targetLink, operationType, null);
    }

    /// The operation's own error text, empty when it reports none.
    public Option<String> failure() {
        return option(error).map(OperationError::describe);
    }

    /// GCP operation error body: a list of `{code, message}` entries.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OperationError(List<Detail> errors) {
        String describe() {
            return option(errors).map(OperationError::join)
                         .filter(text -> !text.isBlank())
                         .or("operation reported an error without details");
        }

        private static String join(List<Detail> details) {
            return details.stream()
                          .map(Detail::describe)
                          .collect(Collectors.joining("; "));
        }
    }

    /// One entry of an operation error.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Detail(String code, String message) {
        String describe() {
            return code + ": " + message;
        }
    }
}
