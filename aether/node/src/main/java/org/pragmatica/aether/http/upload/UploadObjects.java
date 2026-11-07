// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import org.pragmatica.aether.http.handler.ObjectHandle;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.ObjectId;
import org.pragmatica.storage.ObjectRef;
import org.pragmatica.storage.Sha256;

/// The two directions between a storage [ObjectRef] and the wire's [ObjectHandle], which carries each component in the
/// text form storage prints (U2). Writing never fails; reading parses each text back and is refused when one is not the
/// canonical form storage would have printed, so a handle that did not come from storage opens nothing.
public sealed interface UploadObjects {
    /// `ref` as it crosses the wire.
    static ObjectHandle objectHandle(ObjectRef ref) {
        return ObjectHandle.objectHandle(ref.namespace(),
                                         ref.id()
                                            .asString(),
                                         ref.manifest()
                                            .hexString(),
                                         ref.size(),
                                         ref.digest()
                                            .hexString());
    }

    /// The reference `handle` names, to open with a `ContentStore`.
    static Result<ObjectRef> objectRef(ObjectHandle handle) {
        return Result.all(ObjectId.objectId(handle.id()),
                          BlockId.fromHex(handle.manifest()),
                          Sha256.fromHex(handle.digest()))
                     .map((id, manifest, digest) -> ObjectRef.objectRef(handle.namespace(),
                                                                         id,
                                                                         manifest,
                                                                         handle.size(),
                                                                         digest));
    }

    record unused() implements UploadObjects {}
}
