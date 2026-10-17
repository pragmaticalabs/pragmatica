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

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.json.JsonCodecs;
import org.pragmatica.serialization.json.spi.JsonCodecProvider;
import org.pragmatica.serialization.json.tests.other.Receipt;
import org.pragmatica.serialization.json.tests.other.ReceiptJson;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `JsonCodecs.codecFor` over the registries the processor generated for this module's two packages, found through
/// the one services file.
class JsonCodecsTest {
    @Test
    void servicesFile_namesTheRegistryOfEachPackage() {
        var providers = ServiceLoader.load(JsonCodecProvider.class)
                                     .stream()
                                     .map(provider -> provider.type().getName())
                                     .toList();

        assertTrue(providers.stream().anyMatch(name -> name.matches("org\\.pragmatica\\.serialization\\.json\\.tests\\.TestsJsonCodecs_[0-9a-f]{16}")),
                   providers.toString());
        assertTrue(providers.stream().anyMatch(name -> name.matches("org\\.pragmatica\\.serialization\\.json\\.tests\\.other\\.OtherJsonCodecs_[0-9a-f]{16}")),
                   providers.toString());
        assertEquals(providers.size(), providers.stream().distinct().count(), providers.toString());
    }

    @Test
    void codecFor_findsAnnotatedTypesOfBothPackages_asTheirEntryPointCodec() {
        assertSame(Ticketing_BuyRequestJson.CODEC, JsonCodecs.codecFor(Ticketing.BuyRequest.class).unwrap().unwrap());
        assertSame(ReceiptJson.CODEC, JsonCodecs.codecFor(Receipt.class).unwrap().unwrap());
        assertEquals(new Receipt("r-1", 250),
                     JsonCodecs.codecFor(Receipt.class).unwrap().unwrap().decode("{\"id\":\"r-1\",\"amountMinor\":250}").unwrap());
    }

    @Test
    void codecFor_isEmpty_forATypeWithoutGeneratedCodec() {
        assertEquals(Result.success(Option.none()), JsonCodecs.codecFor(String.class));
    }

    // Documented decision: only @Json types are listed. Inner and ProductId have codec code inside their roots'
    // entry points (AllRequired, OrderConfirmation) but are not annotated, so they are not lookup targets.
    @Test
    void codecFor_isEmpty_forATypeReachableOnlyAsANestedComponent() {
        assertTrue(StrictDecodingTest_AllRequiredJson.CODECS.containsKey(StrictDecodingTest.Inner.class));
        assertEquals(Result.success(Option.none()), JsonCodecs.codecFor(StrictDecodingTest.Inner.class));
        assertEquals(Result.success(Option.none()), JsonCodecs.codecFor(Ecommerce.ProductId.class));
    }
}
