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
package org.pragmatica.cloud.hetzner.api;

import java.util.List;

import org.pragmatica.lang.Option;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import static org.pragmatica.lang.Option.option;


/// Hetzner API pagination metadata. `previous_page` and `next_page` are JSON `null` on the first and last
/// page respectively, hence the boxed types.
@JsonIgnoreProperties(ignoreUnknown = true)
public record Pagination(int page,
                         @JsonProperty("per_page") int perPage,
                         @JsonProperty("previous_page") Integer previousPage,
                         @JsonProperty("next_page") Integer nextPage,
                         @JsonProperty("last_page") int lastPage,
                         @JsonProperty("total_entries") int totalEntries) {
    /// The `meta` object every list response carries.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(Pagination pagination) {}

    /// A list response that knows which page follows it. `meta` is absent from a response with no
    /// pagination information, which reads as "no further page".
    public interface Paged<T> {
        List<T> items();
        Meta meta();

        default Option<Integer> nextPage() {
            return option(meta()).flatMap(meta -> option(meta.pagination()))
                         .flatMap(pagination -> option(pagination.nextPage()));
        }
    }
}
