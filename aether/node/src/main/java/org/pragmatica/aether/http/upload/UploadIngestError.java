// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.storage.ContentStoreError;
import org.pragmatica.storage.StorageError;

/// Why a streamed upload was refused, each with the status the client is answered (U2, spec §B6). The message is the
/// client-facing description: it names the framing rule or the limit, never decoder internals.
public sealed interface UploadIngestError extends Cause {
    /// The status the client is answered.
    HttpStatus status();

    /// The multipart framing could not be read: 400.
    enum Malformed implements UploadIngestError {
        NO_BOUNDARY("The multipart Content-Type has no usable boundary parameter"),
        BAD_DELIMITER("A multipart boundary delimiter is followed by neither a part nor the end of the body"),
        BAD_HEADERS("A multipart part header block cannot be read"),
        HEADERS_TOO_LARGE("A multipart part header block is too large"),
        NO_DISPOSITION("A multipart part has no form-data Content-Disposition with a name"),
        NESTED_MULTIPART("A multipart part is itself multipart, which this route does not accept"),
        NOT_CLOSED("The multipart body ended before its closing boundary delimiter");

        private final String message;

        Malformed(String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return message;
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.BAD_REQUEST;
        }
    }

    /// A multipart part limit the body crossed: 413.
    ///
    /// @param limit the route key (or built-in bound) that was crossed
    /// @param bound its value
    record LimitExceeded(String limit, long bound) implements UploadIngestError {
        public static LimitExceeded limitExceeded(String limit, long bound) {
            return new LimitExceeded(limit, bound);
        }

        @Override
        public String message() {
            return "The multipart body exceeds " + limit + " (" + bound + ")";
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.REQUEST_ENTITY_TOO_LARGE;
        }
    }

    /// Content storage could not take the upload: 507 when a tier is full, else 503 `storage-unavailable`.
    ///
    /// @param cause what storage answered
    record StorageFailed(Cause cause) implements UploadIngestError {
        public static StorageFailed storageFailed(Cause cause) {
            return new StorageFailed(cause);
        }

        @Override
        public String message() {
            return status() == HttpStatus.INSUFFICIENT_STORAGE
                   ? "storage-full"
                   : "storage-unavailable";
        }

        @Override
        public HttpStatus status() {
            return isTierFull(cause)
                   ? HttpStatus.INSUFFICIENT_STORAGE
                   : HttpStatus.SERVICE_UNAVAILABLE;
        }

        /// `QuotaExceeded` does not exist yet (A2.3); a full tier is the one 507 cause storage reports today, possibly
        /// wrapped by the chunk store that met it.
        private static boolean isTierFull(Cause cause) {
            return switch (cause) {
                case StorageError.TierFull _ -> true;
                case ContentStoreError.ChunkStoreFailed failed -> isTierFull(failed.cause());
                default -> false;
            };
        }
    }

    /// `cause` as an ingest refusal: one of this type's own as it is, anything else as a storage failure.
    static UploadIngestError ingestError(Cause cause) {
        return cause instanceof UploadIngestError ingest
               ? ingest
               : StorageFailed.storageFailed(cause);
    }
}
