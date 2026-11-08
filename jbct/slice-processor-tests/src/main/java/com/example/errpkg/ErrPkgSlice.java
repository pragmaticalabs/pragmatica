// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.errpkg;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;

import com.example.errpkg.other.OtherError;
import com.example.errpkg.shared.SharedError;
import com.example.errpkg.unlisted.UnlistedError;


/// Every method fails with a Cause declared outside this package (#2883).
@Slice
public interface ErrPkgSlice {
    record Probe() {}

    Promise<String> quota(Probe request);
    Promise<String> sharedDenied(Probe request);
    Promise<String> otherDenied(Probe request);
    Promise<String> unlisted(Probe request);

    static ErrPkgSlice errPkgSlice() {
        return new ErrPkgSlice() {
            @Override
            public Promise<String> quota(Probe request) {
                return new SharedError.QuotaExceeded("acme").promise();
            }

            @Override
            public Promise<String> sharedDenied(Probe request) {
                return new SharedError.NotAllowed("read").promise();
            }

            @Override
            public Promise<String> otherDenied(Probe request) {
                return new OtherError.NotAllowed("write").promise();
            }

            @Override
            public Promise<String> unlisted(Probe request) {
                return new UnlistedError.Gone("page").promise();
            }
        };
    }
}
