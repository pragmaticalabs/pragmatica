// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// [AppHttpConfig] carries its `[app-http.upload]` bounds through every copy: the loader sets the upload bounds before
/// the operator's TLS identity is attached, so a copy that drops them would silently reset them to the defaults.
class AppHttpConfigUploadTest {
    private static final UploadConfig CUSTOM = UploadConfig.DEFAULT.withMaxConcurrent(3).withMaxSizeCeiling(1_000_000);

    private static AppHttpConfig custom() {
        return AppHttpConfig.appHttpConfig(8080).withUpload(CUSTOM);
    }

    @Test
    void aFreshConfig_takesTheDefaultUploadBounds() {
        assertThat(AppHttpConfig.appHttpConfig(8080).upload()).isEqualTo(UploadConfig.DEFAULT);
    }

    @Test
    void everyCopy_keepsTheUploadBounds() {
        assertThat(custom().withTls(new AppHttpConfig.AppTls("cert.pem", "key.pem")).upload()).as("withTls").isEqualTo(CUSTOM);
        assertThat(custom().withReadTimeout(TimeSpan.timeSpan(5).seconds()).upload()).as("withReadTimeout").isEqualTo(CUSTOM);
        assertThat(custom().withMinBodyRate(10).upload()).as("withMinBodyRate").isEqualTo(CUSTOM);
        assertThat(custom().withManagementSecurityMode(Option.some(SecurityMode.NONE)).upload()).as("withManagementSecurityMode")
                                                                                                    .isEqualTo(CUSTOM);
    }
}
