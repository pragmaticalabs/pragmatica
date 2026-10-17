/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
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
 */
package org.pragmatica.serialization.json.tests;

import org.pragmatica.json.JsonMapper;

/// The Jackson contestant: the mapper slice HTTP routes use in production (`JsonMapper.valueObjectJsonMapper()`,
/// value objects unwrapped through `ValueObjectModule`), with no test-only configuration. Byte equality against
/// it is what "generated codecs and the production Jackson path write one wire form" means.
public interface Baseline {
    JsonMapper MAPPER = JsonMapper.valueObjectJsonMapper();

    static tools.jackson.databind.ObjectMapper objectMapper() {
        return ((JsonMapper.JsonMapperImpl) MAPPER).mapper();
    }
}
