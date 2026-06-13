// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceCleanupHandleTest {

    @Test
    void factory_buildsHandle_withProviderRegionAndEnvVars() {
        var envVars = Map.of("api_token", "HCLOUD_TOKEN_PROD");

        var handle = SourceCleanupHandle.sourceCleanupHandle("hetzner", Option.some("eu-central"), envVars);

        assertEquals("hetzner", handle.provider());
        assertEquals("eu-central", handle.region().or(""));
        assertEquals("HCLOUD_TOKEN_PROD", handle.credentialEnvVars().get("api_token"));
    }

    @Test
    void factory_acceptsAbsentRegion() {
        var handle = SourceCleanupHandle.sourceCleanupHandle("docker", Option.none(), Map.of());

        assertTrue(handle.region().isEmpty(),
                   "Region must remain absent when caller passes Option.none()");
    }

    @Test
    void factory_copiesCredentialEnvVarsMap_externalMutationDoesNotLeakIn() {
        // SAFETY CONTRACT: handle must own its env-var map. Mutating the source map
        // after construction must not change the handle's view.
        var mutable = new HashMap<String, String>();
        mutable.put("api_token", "HCLOUD_TOKEN_PROD");

        var handle = SourceCleanupHandle.sourceCleanupHandle("hetzner", Option.none(), mutable);

        mutable.put("api_token", "HCLOUD_TOKEN_DIFFERENT");
        mutable.put("extra", "EVIL");

        assertEquals("HCLOUD_TOKEN_PROD", handle.credentialEnvVars().get("api_token"),
                     "Handle must keep the original env-var name despite external mutation");
        assertEquals(1, handle.credentialEnvVars().size(),
                     "Handle must not pick up entries added to the original map");
    }

    @Test
    void credentialEnvVars_isImmutable_externalMutationOfReturnedMapRejected() {
        // The handle's exposed Map must be immutable so callers cannot poke at it.
        var handle = SourceCleanupHandle.sourceCleanupHandle("hetzner",
                                                              Option.some("eu-central"),
                                                              Map.of("api_token", "HCLOUD_TOKEN_PROD"));

        assertThrows(UnsupportedOperationException.class,
                     () -> handle.credentialEnvVars().put("evil", "BAD"),
                     "Returned credentialEnvVars Map must be unmodifiable to enforce the safety contract");
    }

    @Test
    void factory_storesAllEnvVarAliases_forDownstreamFactoryLookup() {
        // The handle must surface every alias the operator supplied so per-provider
        // factories can read whichever key they expect (Hetzner: api_token, AWS:
        // access_key, GCP/Azure: credentials_file).
        var envVars = new LinkedHashMap<String, String>();
        envVars.put("api_token", "MY_TOKEN");
        envVars.put("access_key", "MY_TOKEN");
        envVars.put("credentials_file", "MY_TOKEN");

        var handle = SourceCleanupHandle.sourceCleanupHandle("hetzner", Option.some("eu-1"), envVars);

        assertEquals("MY_TOKEN", handle.credentialEnvVars().get("api_token"));
        assertEquals("MY_TOKEN", handle.credentialEnvVars().get("access_key"));
        assertEquals("MY_TOKEN", handle.credentialEnvVars().get("credentials_file"));
        assertEquals(3, handle.credentialEnvVars().size());
    }

    @Test
    void contract_envVarsMapValuesLookLikeEnvVarNames_notSecrets() {
        // Documentation contract: values must be env-var NAMES, not the underlying
        // secret values. This is a usage discipline; this assertion just records the
        // expected shape (uppercase identifiers) so the test fails loudly if a
        // test fixture accidentally seeds the map with a secret.
        var handle = SourceCleanupHandle.sourceCleanupHandle("hetzner",
                                                              Option.none(),
                                                              Map.of("api_token", "HCLOUD_TOKEN_PROD"));

        for (var value : handle.credentialEnvVars().values()) {
            assertTrue(value.matches("^[A-Z_][A-Z0-9_]*$"),
                       "credentialEnvVars values must be env-var NAMES (uppercase identifiers), got: " + value);
        }
    }
}
