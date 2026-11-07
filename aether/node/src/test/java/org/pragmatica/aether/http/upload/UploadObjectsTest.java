// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.ObjectHandle;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.ObjectId;
import org.pragmatica.storage.ObjectRef;
import org.pragmatica.storage.Sha256;

import static org.assertj.core.api.Assertions.assertThat;

/// A storage reference crosses the wire as text and comes back as the same reference; a handle whose text storage
/// would not have printed is refused.
class UploadObjectsTest {
    private static final byte[] CONTENT = "upload objects".getBytes(StandardCharsets.UTF_8);

    /// Kills a component dropped or swapped in either direction.
    @Test
    void objectRef_ofObjectHandle_isTheSameReference() {
        var ref = reference();
        var handle = UploadObjects.objectHandle(ref);

        assertThat(handle.namespace()).isEqualTo("slice-ns");
        assertThat(handle.id()).isEqualTo(ref.id().asString());
        assertThat(handle.manifest()).isEqualTo(ref.manifest().hexString());
        assertThat(handle.size()).isEqualTo(42L);
        assertThat(handle.digest()).isEqualTo(ref.digest().hexString());
        assertThat(UploadObjects.objectRef(handle).unwrap()).isEqualTo(ref);
    }

    /// Kills an id read without the canonical-spelling check, and a hex component read without parsing.
    @Test
    void objectRef_malformedComponents_areRefused() {
        var good = UploadObjects.objectHandle(reference());

        assertThat(UploadObjects.objectRef(withId(good, good.id().toUpperCase(Locale.ROOT))).isFailure()).as("an id in another case").isTrue();
        assertThat(UploadObjects.objectRef(withId(good, good.id().substring(1))).isFailure()).as("a short id").isTrue();
        assertThat(UploadObjects.objectRef(withId(good, "not-an-object-id-at-all!!!")).isFailure()).as("an id of foreign characters").isTrue();
        assertThat(UploadObjects.objectRef(withManifest(good, "zz" + good.manifest().substring(2))).isFailure()).as("a manifest that is not hex").isTrue();
        assertThat(UploadObjects.objectRef(withDigest(good, good.digest().substring(2))).isFailure()).as("a digest of 31 bytes").isTrue();
        assertThat(UploadObjects.objectRef(withDigest(good, "g" + good.digest().substring(1))).isFailure()).as("a digest that is not hex").isTrue();
    }

    /// TRIPWIRE, asserting today's behaviour: the type's documentation says a component is refused when it is not the
    /// canonical form storage prints, but `BlockId.fromHex` checks no length and `HexFormat` reads either case, so a
    /// manifest of the wrong length and an upper-case manifest or digest are accepted. When this goes red, enable
    /// [#objectRef_nonCanonicalHex_isRefused] and delete this one.
    @Test
    void objectRef_nonCanonicalHex_isRefused() {
        var good = UploadObjects.objectHandle(reference());

        assertThat(UploadObjects.objectRef(withManifest(good, "abcd")).isFailure()).as("a 2-byte manifest").isTrue();
        assertThat(UploadObjects.objectRef(withManifest(good, good.manifest().toUpperCase(Locale.ROOT))).isFailure()).as("an upper-case manifest").isTrue();
        assertThat(UploadObjects.objectRef(withDigest(good, good.digest().toUpperCase(Locale.ROOT))).isFailure()).as("an upper-case digest").isTrue();
    }

    private static ObjectRef reference() {
        return ObjectRef.objectRef("slice-ns", ObjectId.objectId(), BlockId.blockId(CONTENT).unwrap(), 42L, Sha256.sha256(CONTENT).unwrap());
    }

    private static ObjectHandle withId(ObjectHandle handle, String id) {
        return ObjectHandle.objectHandle(handle.namespace(), id, handle.manifest(), handle.size(), handle.digest());
    }

    private static ObjectHandle withManifest(ObjectHandle handle, String manifest) {
        return ObjectHandle.objectHandle(handle.namespace(), handle.id(), manifest, handle.size(), handle.digest());
    }

    private static ObjectHandle withDigest(ObjectHandle handle, String digest) {
        return ObjectHandle.objectHandle(handle.namespace(), handle.id(), handle.manifest(), handle.size(), digest);
    }
}
