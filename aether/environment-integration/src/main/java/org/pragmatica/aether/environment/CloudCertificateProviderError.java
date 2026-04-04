package org.pragmatica.aether.environment;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;


/// Error types for cloud-based certificate provider operations.
public sealed interface CloudCertificateProviderError extends Cause {
    static CertificateFetchFailed certificateFetchFailed(String secretPath, Throwable cause) {
        return new CertificateFetchFailed(secretPath, cause);
    }

    static InvalidCertificateMaterial invalidCertificateMaterial(String detail) {
        return new InvalidCertificateMaterial(detail);
    }

    static CertificateIssueFailed certificateIssueFailed(String nodeId, Throwable cause) {
        return new CertificateIssueFailed(nodeId, cause);
    }

    static GossipKeyFailed gossipKeyFailed(String detail) {
        return new GossipKeyFailed(detail);
    }

    record CertificateFetchFailed(String secretPath, Throwable cause) implements CloudCertificateProviderError {
        @Override public String message() {
            return "Certificate fetch failed for '" + secretPath + "': " + Causes.fromThrowable(cause).message();
        }
    }

    record InvalidCertificateMaterial(String detail) implements CloudCertificateProviderError {
        @Override public String message() {
            return "Invalid certificate material: " + detail;
        }
    }

    record CertificateIssueFailed(String nodeId, Throwable cause) implements CloudCertificateProviderError {
        @Override public String message() {
            return "Certificate issue failed for node " + nodeId + ": " + Causes.fromThrowable(cause).message();
        }
    }

    record GossipKeyFailed(String detail) implements CloudCertificateProviderError {
        @Override public String message() {
            return "Gossip key failed: " + detail;
        }
    }
}
