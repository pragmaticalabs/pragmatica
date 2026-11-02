package org.pragmatica.storage;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;


/// Failures of the S3-compatible [RemoteTier], classified for the caller.
///
/// The distinction that matters is unavailable versus rejected. A block whose PUT returned 200 is the
/// provider's to keep; a GET that cannot reach the provider, times out, or is answered 5xx/429/408 says
/// nothing about whether the block exists, so it is [Unavailable] -- retriable, never "absent", never
/// data loss. A request the provider refuses for what it is (credentials, a missing bucket, a malformed
/// request) will not succeed on retry, so it is [Rejected], a [Cause.Terminal].
///
/// Each wrapper carries the S3 or transport failure as `origin`, read through `source()` where the chain
/// is logged; the template does not print it, so a message shown to a client carries no provider internals.
public sealed interface RemoteTierError extends Cause {
    /// The provider could not be reached or did not answer usably. Retriable; the block's fate is unknown, not lost.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Unavailable(String operation, String key, Cause origin, String message) implements RemoteTierError, Cause.Wrapped {
        static final Fn3<Unavailable, String, String, Cause> FACTORY = Causes.forThreeValues("S3 %s of %s failed: the object store is unavailable (retriable; nothing acknowledged was lost)",
                                                                                             Unavailable::new);
    }

    /// The provider refused the request for what it is (credentials, bucket, request); retrying will not help.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Rejected(String operation, String key, Cause origin, String message) implements RemoteTierError, Cause.Wrapped, Cause.Terminal {
        static final Fn3<Rejected, String, String, Cause> FACTORY = Causes.forThreeValues("S3 %s of %s was rejected by the object store (check credentials, bucket and permissions)",
                                                                                          Rejected::new);

        /// Terminal wins over the origin's classification: a verdict is never also "worth retrying".
        @Override
        public boolean isTerminal() {
            return true;
        }

        @Override
        public boolean isTransient() {
            return false;
        }
    }

    /// The boot probe read back something other than what it wrote: the endpoint does not provide
    /// read-after-write for a new key, which the tier requires.
    record ProbeMismatch(String key, String observed, String message) implements RemoteTierError, Cause.Terminal {
        static final Fn2<ProbeMismatch, String, String> FACTORY = Causes.forTwoValues("S3 boot probe read back %s %s instead of the bytes it wrote: "
                                                                                     + "the endpoint does not provide read-after-write for new keys",
                                                                                      ProbeMismatch::new);
    }
}
