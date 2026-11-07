// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.ContentStoreError;
import org.pragmatica.storage.StorageError;
import org.pragmatica.storage.TierLevel;

import static org.assertj.core.api.Assertions.assertThat;

/// Spec §B6 status mapping of a storage failure during ingest: a full tier is 507 (also when the chunk store wraps it),
/// anything else 503 `storage-unavailable`; the decoder's own refusals keep their status.
class UploadIngestErrorTest {
    private static final StorageError.TierFull FULL = new StorageError.TierFull(TierLevel.MEMORY, 10, 10);

    @Test
    void ingestError_tierFull_is507() {
        assertThat(UploadIngestError.ingestError(FULL).status()).isEqualTo(HttpStatus.INSUFFICIENT_STORAGE);
    }

    @Test
    void ingestError_tierFullWrappedByTheChunkStore_is507() {
        assertThat(UploadIngestError.ingestError(new ContentStoreError.ChunkStoreFailed(3, FULL)).status())
            .isEqualTo(HttpStatus.INSUFFICIENT_STORAGE);
    }

    @Test
    void ingestError_otherStorageFailure_is503StorageUnavailable() {
        var refusal = UploadIngestError.ingestError(Causes.cause("tier unavailable"));

        assertThat(refusal.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refusal.message()).isEqualTo("storage-unavailable");
    }

    @Test
    void ingestError_ownRefusal_keepsItsStatus() {
        assertThat(UploadIngestError.ingestError(UploadIngestError.Malformed.NOT_CLOSED).status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(UploadIngestError.ingestError(UploadIngestError.LimitExceeded.limitExceeded("max_files", 2)).status())
            .isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
    }
}
