// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.jsonoptions;

import java.util.Map;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;


/// A routed slice whose routes.toml declares `[json] naming = "snake_case"` (#2378): its generated route codecs and
/// their Jackson fallback name fields in snake case, and its generated factory provisions its JSON codec settings.
@Slice
public interface JsonOptionsSlice {
    // Generated codecs both ways.
    Promise<Profile> echo(ProfileRequest request);
    // A Map response has no generated codec: it is written by the Jackson fallback.
    Promise<Map<String, Profile>> byName(ProfileRequest request);

    static JsonOptionsSlice jsonOptionsSlice() {
        return new JsonOptionsSlice() {
            @Override
            public Promise<Profile> echo(ProfileRequest request) {
                return Promise.success(new Profile(request.displayName(), request.nickName()));
            }

            @Override
            public Promise<Map<String, Profile>> byName(ProfileRequest request) {
                return Promise.success(Map.of(request.displayName(),
                                              new Profile(request.displayName(), request.nickName())));
            }
        };
    }
}
