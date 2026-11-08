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

/// The typed refusals of a streamed upload: status and message of each, and how a storage cause maps to 507 or 503.
class UploadIngestErrorTest {
    @Test
    void limitExceeded_answers413_namingTheLimitAndThenItsBound() {
        var error = UploadIngestError.LimitExceeded.limitExceeded("max_files", 3);

        assertThat(error.status()).isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        assertThat(error.message()).isEqualTo("The multipart body exceeds max_files (3)");
    }

    @Test
    void storageFailed_tierFull_answers507StorageFull() {
        var error = UploadIngestError.StorageFailed.storageFailed(new StorageError.TierFull(TierLevel.MEMORY, 2, 1));

        assertThat(error.status()).isEqualTo(HttpStatus.INSUFFICIENT_STORAGE);
        assertThat(error.message()).isEqualTo("storage-full");
    }

    @Test
    void storageFailed_tierFullWrappedByTheChunkStore_answers507StorageFull() {
        var wrapped = new ContentStoreError.ChunkStoreFailed(3, new StorageError.TierFull(TierLevel.MEMORY, 2, 1));
        var error = UploadIngestError.StorageFailed.storageFailed(wrapped);

        assertThat(error.status()).isEqualTo(HttpStatus.INSUFFICIENT_STORAGE);
        assertThat(error.message()).isEqualTo("storage-full");
    }

    @Test
    void storageFailed_anyOtherCause_answers503StorageUnavailable() {
        var other = UploadIngestError.StorageFailed.storageFailed(Causes.cause("disk on fire"));
        var wrapped = UploadIngestError.StorageFailed.storageFailed(new ContentStoreError.ChunkStoreFailed(3, Causes.cause("disk on fire")));

        assertThat(other.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(other.message()).isEqualTo("storage-unavailable");
        assertThat(wrapped.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(wrapped.message()).isEqualTo("storage-unavailable");
    }
}
