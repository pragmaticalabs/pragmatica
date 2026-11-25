// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.slice.ReplicationFactors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.slice.blueprint.StreamConfigParser.parseResources;

/// Tripwire for the in-memory streams work: asserts CURRENT behaviour, so it is enabled and green until the
/// `storage` key is added to the stream section keys. When it goes red, enable `StreamConfigParserStorageTest`
/// and delete this class.
class StreamStorageKeyTripwireTest {

    private static final String TRIPWIRE_MESSAGE = "storage key landed: enable StreamConfigParserStorageTest and delete this tripwire";

    @Test
    void storageKey_isStillAnUnknownStreamKey() {
        var toml = """
                [streams.x]
                version = "1.0.0"
                storage = "memory"
                """;

        parseResources(toml, ReplicationFactors.BUILT_IN).onSuccess(_ -> fail(TRIPWIRE_MESSAGE))
                            .onFailure(cause -> assertThat(cause).as(TRIPWIRE_MESSAGE)
                                                                 .isInstanceOf(StreamDeclarationError.UnknownStreamKeys.class))
                            .onFailure(cause -> assertThat(((StreamDeclarationError.UnknownStreamKeys) cause).keys())
                                                    .as(TRIPWIRE_MESSAGE)
                                                    .contains("storage"));
    }
}
