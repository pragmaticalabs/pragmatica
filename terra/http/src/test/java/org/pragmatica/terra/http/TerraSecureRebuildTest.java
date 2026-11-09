// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.http;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.SuccessSpec;
import org.pragmatica.http.routing.UploadSpec;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// #2965: the route Terra builds to secure a route keeps every component of the route it secures but the handler.
class TerraSecureRebuildTest {
    private static final UploadSpec UPLOAD = UploadSpec.uploadSpec(true, 1_000, 5_000);
    private static final SuccessSpec SUCCESS = SuccessSpec.successSpec(201, _ -> "/items/1");

    @Test
    void secure_keepsEveryComponentButTheHandler() {
        var declared = Route.route(HttpMethod.PUT,
                                   "/items/",
                                   _ -> Promise.success("ok"),
                                   CommonContentType.APPLICATION_JSON,
                                   List.of("detail"),
                                   "putItem",
                                   SecurityPolicy.publicRoute(),
                                   3,
                                   2,
                                   List.of(1),
                                   UPLOAD,
                                   SUCCESS);
        var secured = TerraHttpRoutes.secure(declared, SecurityPolicy.publicRoute(), HttpAuthenticator.denyUnlessPublicValidator());

        assertThat(secured.method()).isEqualTo(declared.method());
        assertThat(secured.path()).isEqualTo(declared.path());
        assertThat(secured.contentType()).isEqualTo(declared.contentType());
        assertThat(secured.spacers()).isEqualTo(declared.spacers());
        assertThat(secured.name()).isEqualTo(declared.name());
        assertThat(secured.security()).isEqualTo(declared.security());
        assertThat(secured.version()).isEqualTo(declared.version());
        assertThat(secured.pathParamCount()).isEqualTo(declared.pathParamCount());
        assertThat(secured.spacerSlots()).isEqualTo(declared.spacerSlots());
        assertThat(secured.uploadSpec()).isEqualTo(UPLOAD);
        assertThat(secured.successSpec()).isSameAs(SUCCESS);
    }
}
