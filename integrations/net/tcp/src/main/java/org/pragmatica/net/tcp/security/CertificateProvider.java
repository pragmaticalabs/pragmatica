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

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

/// Service Provider Interface for certificate issuance and management.
///
/// Implementations provide certificate material for inter-node mTLS
/// and gossip encryption keys for authenticated cluster communication.
public interface CertificateProvider {

    /// Issue a node certificate signed by the cluster CA.
    ///
    /// @param nodeId   identity of the node requesting the certificate
    /// @param hostname hostname for the Subject Alternative Name extension
    /// @return certificate bundle or error
    Result<CertificateBundle> issueCertificate(String nodeId, String hostname);

    /// Retrieve the cluster CA certificate.
    ///
    /// @return CA certificate bundle or error
    Result<CertificateBundle> caCertificate();

    /// Retrieve the current gossip encryption key.
    ///
    /// @return current gossip key or error
    Result<GossipKey> currentGossipKey();

    /// Retrieve the previous gossip encryption key for key rotation overlap.
    ///
    /// @return previous gossip key, or none if no rotation has occurred
    Option<GossipKey> previousGossipKey();
}
