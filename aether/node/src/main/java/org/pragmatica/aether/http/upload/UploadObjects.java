// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import org.pragmatica.aether.http.handler.ObjectHandle;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.ObjectId;
import org.pragmatica.storage.ObjectRef;
import org.pragmatica.storage.Sha256;


/// The two directions between a storage [ObjectRef] and the wire's [ObjectHandle], which carries each component in the
/// text form storage prints (U2). Writing never fails; reading parses each text back and is refused when one is not the
/// canonical form storage would have printed (lower-case hex of exactly 32 bytes for the manifest and the digest;
/// `Sha256.fromHex` checks the digest's length itself), so a handle that did not come from storage opens nothing.
public sealed interface UploadObjects {
    Cause NOT_CANONICAL = Causes.cause("An object handle component is not in the form storage prints");

    /// `ref` as it crosses the wire.
    static ObjectHandle objectHandle(ObjectRef ref) {
        return ObjectHandle.objectHandle(ref.namespace(),
                                         ref.id().asString(),
                                         ref.manifest().hexString(),
                                         ref.size(),
                                         ref.digest().hexString());
    }

    /// The reference `handle` names, to open with a `ContentStore`.
    static Result<ObjectRef> objectRef(ObjectHandle handle) {
        return Result.all(ObjectId.objectId(handle.id()),
                          BlockId.fromHex(handle.manifest()).filter(NOT_CANONICAL,
                                                                    manifest -> manifest.hash().length == Sha256.LENGTH && manifest.hexString()
                                                                                                                                   .equals(handle.manifest())),
                          Sha256.fromHex(handle.digest()).filter(NOT_CANONICAL,
                                                                 digest -> digest.hexString()
                                                                                 .equals(handle.digest())))
                     .map((id, manifest, digest) -> ObjectRef.objectRef(handle.namespace(),
                                                                        id,
                                                                        manifest,
                                                                        handle.size(),
                                                                        digest));
    }

    record unused() implements UploadObjects {}
}
