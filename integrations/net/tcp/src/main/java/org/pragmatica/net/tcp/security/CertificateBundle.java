/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.net.tcp.security;

import java.time.Instant;

/// Certificate bundle containing PEM-encoded certificate, private key, and CA certificate.
///
/// @param certificatePem   PEM-encoded node certificate
/// @param privateKeyPem    PEM-encoded private key
/// @param caCertificatePem PEM-encoded CA certificate
/// @param notAfter         expiration timestamp
public record CertificateBundle(byte[] certificatePem,
                                byte[] privateKeyPem,
                                byte[] caCertificatePem,
                                Instant notAfter) {

    /// Create a certificate bundle from PEM-encoded components.
    public static CertificateBundle certificateBundle(byte[] certificatePem,
                                                      byte[] privateKeyPem,
                                                      byte[] caCertificatePem,
                                                      Instant notAfter) {
        return new CertificateBundle(certificatePem, privateKeyPem, caCertificatePem, notAfter);
    }
}
