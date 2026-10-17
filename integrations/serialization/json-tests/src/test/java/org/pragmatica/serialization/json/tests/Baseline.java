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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.pragmatica.json.JsonMapper;

import static org.pragmatica.serialization.json.tests.Ecommerce.CustomerId;
import static org.pragmatica.serialization.json.tests.Ecommerce.OrderId;
import static org.pragmatica.serialization.json.tests.Ecommerce.ProductId;
import static org.pragmatica.serialization.json.tests.Ecommerce.Quantity;

/// The Jackson contestant: the production builder (`JsonMapper.jsonMapper().withPragmaticaTypes()`)
/// plus mixins that make it emit the same unwrapped value-object shape as the generated codecs.
/// This is what production Jackson would need for that shape, so the comparison stays like for like.
public interface Baseline {
    JsonMapper MAPPER = JsonMapper.jsonMapper()
                                  .withPragmaticaTypes()
                                  .configure(builder -> builder.addMixIn(ProductId.class, StringValueMixin.class)
                                                               .addMixIn(CustomerId.class, StringValueMixin.class)
                                                               .addMixIn(OrderId.class, StringValueMixin.class)
                                                               .addMixIn(Quantity.class, IntValueMixin.class))
                                  .build();

    static tools.jackson.databind.ObjectMapper objectMapper() {
        return ((JsonMapper.JsonMapperImpl) MAPPER).mapper();
    }

    abstract class StringValueMixin {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        StringValueMixin(String value) {}

        @JsonValue
        abstract String value();
    }

    abstract class IntValueMixin {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        IntValueMixin(int value) {}

        @JsonValue
        abstract int value();
    }
}
