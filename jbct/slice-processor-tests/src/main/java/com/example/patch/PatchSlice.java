// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.patch;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// Test slice for #2884: a PUT-style partial update whose body tells a left-out field from an explicit `null`, on the
/// generated codec path and on the Jackson fallback, and a `Patch` written back in a response.
@Slice
public interface PatchSlice {
    Profile CURRENT = new Profile("ann", Option.some("annie"), Option.some(30));

    Promise<Profile> update(ProfileUpdate request);
    Promise<Profile> updateViaJackson(JacksonProfileUpdate request);
    Promise<ProfileUpdate> echo(ProfileUpdate request);
    Promise<Profile> tags(TagsUpdate request);

    static PatchSlice patchSlice() {
        return new PatchSlice() {
            @Override
            public Promise<Profile> update(ProfileUpdate request) {
                return Promise.success(patched(request));
            }

            @Override
            public Promise<Profile> updateViaJackson(JacksonProfileUpdate request) {
                return Promise.success(patched(new ProfileUpdate(request.nickname(), request.age())));
            }

            @Override
            public Promise<ProfileUpdate> echo(ProfileUpdate request) {
                return Promise.success(request);
            }

            @Override
            public Promise<Profile> tags(TagsUpdate request) {
                return Promise.success(CURRENT);
            }
        };
    }

    private static Profile patched(ProfileUpdate update) {
        return new Profile(CURRENT.name(),
                           update.nickname()
                                 .applyTo(CURRENT.nickname()),
                           update.age()
                                 .applyTo(CURRENT.age()));
    }
}
