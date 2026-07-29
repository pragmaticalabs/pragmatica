// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.cli.cluster.ClusterRotateKeyCommand.resolveKeyToRetire;

/// Regression coverage for #528: `rotate-key` chose the key to retire by asking whether the token
/// `"ACTIVE"` appeared anywhere in the listing and then taking whichever `keyId` came first in the
/// payload. The two checks were uncorrelated, so a listing led by a revoked or expired key retired
/// *that* key — revoking a credential possibly still in use, leaving the intended one valid, and
/// reporting success either way.
///
/// The listing is `GET /api/cluster/keys`, a bare JSON array of `KeyInfo` records whose per-record
/// state field is `status`.
class ClusterRotateKeyActiveKeySelectionTest {
    private static final String REVOKED_FIRST = """
            [\
            {"keyId":"ak_revoked1","status":"REVOKED","createdAt":1,"expiresAt":-1,"revokedAt":2,\
            "gracePeriodMs":300000,"authorizationRole":"ADMIN"},\
            {"keyId":"ak_active01","status":"ACTIVE","createdAt":3,"expiresAt":-1,"revokedAt":-1,\
            "gracePeriodMs":300000,"authorizationRole":"ADMIN"}\
            ]""";

    private static final String EXPIRED_FIRST = """
            [\
            {"keyId":"ak_expired1","status":"EXPIRED","createdAt":1,"expiresAt":2,"revokedAt":-1,\
            "gracePeriodMs":300000,"authorizationRole":"VIEWER"},\
            {"keyId":"ak_active01","status":"ACTIVE","createdAt":3,"expiresAt":-1,"revokedAt":-1,\
            "gracePeriodMs":300000,"authorizationRole":"ADMIN"}\
            ]""";

    private static final String TWO_ACTIVE = """
            [\
            {"keyId":"ak_active01","status":"ACTIVE","createdAt":1,"expiresAt":-1,"revokedAt":-1,\
            "gracePeriodMs":300000,"authorizationRole":"ADMIN"},\
            {"keyId":"ak_active02","status":"ACTIVE","createdAt":2,"expiresAt":-1,"revokedAt":-1,\
            "gracePeriodMs":300000,"authorizationRole":"OPERATOR"}\
            ]""";

    private static final String NONE_ACTIVE = """
            [\
            {"keyId":"ak_revoked1","status":"REVOKED","createdAt":1,"expiresAt":-1,"revokedAt":2,\
            "gracePeriodMs":300000,"authorizationRole":"ADMIN"},\
            {"keyId":"ak_expired1","status":"EXPIRED","createdAt":3,"expiresAt":4,"revokedAt":-1,\
            "gracePeriodMs":300000,"authorizationRole":"VIEWER"}\
            ]""";

    @Test
    void resolveKeyToRetire_revokedKeyListedFirst_retiresTheActiveKey() {
        // The exact ordering the positional scan mishandled: without --key-id the sole ACTIVE key
        // must be chosen no matter where it sits in the listing.
        resolveKeyToRetire(null, REVOKED_FIRST)
                .onFailure(cause -> fail(cause.message()))
                .onSuccess(keyId -> assertEquals("ak_active01",
                                                 keyId,
                                                 "a revoked key preceding the active one must never be retired"));
    }

    @Test
    void resolveKeyToRetire_expiredKeyListedFirst_retiresTheActiveKey() {
        resolveKeyToRetire(null, EXPIRED_FIRST)
                .onFailure(cause -> fail(cause.message()))
                .onSuccess(keyId -> assertEquals("ak_active01", keyId));
    }

    @Test
    void resolveKeyToRetire_soleActiveKeyListedFirst_retiresIt() {
        var activeFirst = """
                [\
                {"keyId":"ak_active01","status":"ACTIVE","createdAt":1,"expiresAt":-1,"revokedAt":-1,\
                "gracePeriodMs":300000,"authorizationRole":"ADMIN"},\
                {"keyId":"ak_revoked1","status":"REVOKED","createdAt":2,"expiresAt":-1,"revokedAt":3,\
                "gracePeriodMs":300000,"authorizationRole":"ADMIN"}\
                ]""";

        resolveKeyToRetire(null, activeFirst)
                .onFailure(cause -> fail(cause.message()))
                .onSuccess(keyId -> assertEquals("ak_active01", keyId));
    }

    @Test
    void resolveKeyToRetire_statusTokenOnlyInAnotherField_selectsNoKey() {
        // A revoked key whose role text carries the token the old guard matched on.
        var tokenElsewhere = """
                [\
                {"keyId":"ak_revoked1","status":"REVOKED","createdAt":1,"expiresAt":-1,"revokedAt":2,\
                "gracePeriodMs":300000,"authorizationRole":"ACTIVE"}\
                ]""";

        resolveKeyToRetire(null, tokenElsewhere)
                .onSuccess(keyId -> fail("retired " + keyId + " on a listing with no ACTIVE key"))
                .onFailure(cause -> assertTrue(cause.message().contains("No active API key")));
    }

    @Test
    void resolveKeyToRetire_multipleActiveKeysWithoutKeyId_refusesAndNamesThem() {
        resolveKeyToRetire(null, TWO_ACTIVE)
                .onSuccess(keyId -> fail("silently picked " + keyId + " out of two ACTIVE keys"))
                .onFailure(cause -> assertTrue(cause.message().contains("--key-id")
                                               && cause.message().contains("ak_active01")
                                               && cause.message().contains("ak_active02"),
                                               "refusal must name the candidates: " + cause.message()));
    }

    @Test
    void resolveKeyToRetire_multipleActiveKeysWithKeyId_retiresTheNamedKey() {
        resolveKeyToRetire("ak_active02", TWO_ACTIVE)
                .onFailure(cause -> fail(cause.message()))
                .onSuccess(keyId -> assertEquals("ak_active02", keyId));
    }

    @Test
    void resolveKeyToRetire_keyIdNamingRevokedKey_refuses() {
        resolveKeyToRetire("ak_revoked1", REVOKED_FIRST)
                .onSuccess(keyId -> fail("retired non-ACTIVE key " + keyId))
                .onFailure(cause -> assertTrue(cause.message().contains("ak_revoked1")));
    }

    @Test
    void resolveKeyToRetire_keyIdAbsentFromListing_refuses() {
        resolveKeyToRetire("ak_unknown1", REVOKED_FIRST)
                .onSuccess(keyId -> fail("retired unlisted key " + keyId))
                .onFailure(cause -> assertTrue(cause.message().contains("ak_unknown1")));
    }

    @Test
    void resolveKeyToRetire_noActiveKey_failsCleanly() {
        resolveKeyToRetire(null, NONE_ACTIVE)
                .onSuccess(keyId -> fail("retired " + keyId + " with no ACTIVE key present"))
                .onFailure(cause -> assertTrue(cause.message().contains("No active API key")));
    }

    @Test
    void resolveKeyToRetire_emptyListing_failsCleanly() {
        resolveKeyToRetire(null, "[]")
                .onSuccess(keyId -> fail("retired " + keyId + " from an empty listing"))
                .onFailure(cause -> assertTrue(cause.message().contains("No active API key")));
    }

    @Test
    void resolveKeyToRetire_unparseableBody_selectsNoKey() {
        resolveKeyToRetire(null, "not json at all")
                .onSuccess(keyId -> fail("retired " + keyId + " from an unparseable body"));
    }

    @Test
    void resolveKeyToRetire_errorEnvelopeNamingAKey_selectsNoKey() {
        // The old reading resolved this to "ak_active01": the envelope mentions ACTIVE and carries a
        // keyId, so the guard passed and the positional scan produced a key from an error response.
        resolveKeyToRetire(null, "{\"error\":\"no ACTIVE key for keyId ak_active01\"}")
                .onSuccess(keyId -> fail("retired " + keyId + " from an error envelope"))
                .onFailure(cause -> assertTrue(cause.message().contains("not a JSON array")));
    }

    @Test
    void resolveKeyToRetire_recordMissingStatus_selectsNoKey() {
        var missingStatus = """
                [\
                {"keyId":"ak_active01","createdAt":1,"expiresAt":-1,"revokedAt":-1,\
                "gracePeriodMs":300000,"authorizationRole":"ADMIN"}\
                ]""";

        resolveKeyToRetire(null, missingStatus)
                .onSuccess(keyId -> fail("retired " + keyId + " from a record with no status"))
                .onFailure(cause -> assertTrue(cause.message().contains("no keyId/status pair")));
    }

    @Test
    void resolveKeyToRetire_activeRecordMissingKeyId_selectsNoKey() {
        var missingKeyId = """
                [\
                {"status":"REVOKED","createdAt":1,"expiresAt":-1,"revokedAt":2,\
                "gracePeriodMs":300000,"authorizationRole":"ADMIN"},\
                {"keyId":"ak_active01","status":"ACTIVE","createdAt":3,"expiresAt":-1,"revokedAt":-1,\
                "gracePeriodMs":300000,"authorizationRole":"ADMIN"}\
                ]""";

        resolveKeyToRetire(null, missingKeyId)
                .onSuccess(keyId -> fail("retired " + keyId + " from a listing with a malformed record"))
                .onFailure(cause -> assertTrue(cause.message().contains("no keyId/status pair")));
    }

    @Test
    void resolveKeyToRetire_blankKeyId_treatedAsUnspecified() {
        // picocli leaves --key-id null when absent; a blank value must not be taken as a key name.
        resolveKeyToRetire("   ", REVOKED_FIRST)
                .onFailure(cause -> fail(cause.message()))
                .onSuccess(keyId -> assertEquals("ak_active01", keyId));
    }
}
