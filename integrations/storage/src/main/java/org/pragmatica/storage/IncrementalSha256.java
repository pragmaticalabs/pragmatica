package org.pragmatica.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Unit.unit;


/// The whole-object hash a writer keeps while bytes stream through it. A seam, not a feature: the JDK digest cannot
/// export its state, so a writer that must survive a restart needs another implementation, and an object writer holds
/// this interface rather than the digest.
interface IncrementalSha256 {
    Unit update(byte[] bytes, int offset, int length);
    /// The digest of everything fed so far; the hasher is finished and must not be fed again.
    Sha256 digest();

    static IncrementalSha256 incrementalSha256() {
        return new JdkIncrementalSha256(jdkDigest());
    }

    @SuppressWarnings("JBCT-EX-01")  // SHA-256 is a JVM-mandated algorithm; its absence is a platform defect, not a business failure
    private static MessageDigest jdkDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
    }

    record JdkIncrementalSha256(MessageDigest delegate) implements IncrementalSha256 {
        @Override
        public Unit update(byte[] bytes, int offset, int length) {
            delegate.update(bytes, offset, length);

            return unit();
        }

        @Override
        public Sha256 digest() {
            return new Sha256(delegate.digest());
        }
    }
}
