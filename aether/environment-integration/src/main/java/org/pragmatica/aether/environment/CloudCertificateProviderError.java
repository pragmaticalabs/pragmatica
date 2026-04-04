package org.pragmatica.aether.environment;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;


/// Error types for cloud-based certificate provider operations.
public sealed interface CloudCertificateProviderError extends Cause {

    /// Failed to fetch certificate material from the cloud secrets backend.
    record CertificateFetchFailed(String secretPath, Throwable cause) implements CloudCertificateProviderError {
        @Override
        public String message() {
            return "Certificate fetch failed for '" + secretPath + "': " + Causes.fromThrowable(cause).message();
        }
    }

    /// Certificate material retrieved from cloud secrets was invalid or unparseable.
    record InvalidCertificateMaterial(String detail) implements CloudCertificateProviderError {
        @Override
        public String message() {
            return "Invalid certificate material: " + detail;
        }
    }

    /// Failed to issue a node certificate using cloud-stored CA material.
    record CertificateIssueFailed(String nodeId, Throwable cause) implements CloudCertificateProviderError {
        @Override
        public String message() {
            return "Certificate issue failed for node " + nodeId + ": " + Causes.fromThrowable(cause).message();
        }
    }

    /// Gossip key derivation or retrieval failed.
    record GossipKeyFailed(String detail) implements CloudCertificateProviderError {
        @Override
        public String message() {
            return "Gossip key failed: " + detail;
        }
    }
}
