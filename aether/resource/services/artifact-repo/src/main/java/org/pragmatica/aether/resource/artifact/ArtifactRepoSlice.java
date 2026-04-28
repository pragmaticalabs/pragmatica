// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.artifact;

import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;

import java.util.List;


public record ArtifactRepoSlice(MavenProtocolHandler mavenHandler) implements Slice {
    public static ArtifactRepoSlice artifactRepoSlice(ArtifactStore store) {
        return new ArtifactRepoSlice(MavenProtocolHandler.mavenProtocolHandler(store));
    }

    @Override public List<SliceMethod<?, ?>> methods() {
        return List.of(new SliceMethod<>(MethodName.methodName("get").unwrap(),
                                         this::handleGet,
                                         new TypeToken<MavenProtocolHandler.MavenResponse>() {},
                                         new TypeToken<RepositoryRequest>() {}),
                       new SliceMethod<>(MethodName.methodName("put").unwrap(),
                                         this::handlePut,
                                         new TypeToken<MavenProtocolHandler.MavenResponse>() {},
                                         new TypeToken<RepositoryRequest>() {}));
    }

    private Promise<MavenProtocolHandler.MavenResponse> handleGet(RepositoryRequest request) {
        return mavenHandler.handleGet("/repository/" + request.path());
    }

    private Promise<MavenProtocolHandler.MavenResponse> handlePut(RepositoryRequest request) {
        return mavenHandler.handlePut("/repository/" + request.path(), request.content());
    }

    public record RepositoryRequest(String path, byte[] content){}
}
