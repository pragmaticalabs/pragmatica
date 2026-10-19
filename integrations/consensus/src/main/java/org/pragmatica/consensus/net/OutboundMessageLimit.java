package org.pragmatica.consensus.net;

import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Serializer;


/// Whole-message state transfer currently shares the transport's bounded frame. Reserve space
/// beyond the exact encoded payload for framing and conservative authority-envelope growth.
public interface OutboundMessageLimit {
    int MAX_FRAME_BYTES = 32 * 1024 * 1024;
    int MAX_TRANSFER_BYTES = MAX_FRAME_BYTES - 64 * 1024;

    /// The message encodes to `frameBytes`, over [#MAX_TRANSFER_BYTES]. The size is data, so a caller can report
    /// the frame that was refused rather than some part of it.
    record TooLarge(int frameBytes, String message) implements Cause {
        public static final Fn1<TooLarge, Integer> FACTORY = Causes.forOneValue("Message encodes to %d bytes, over the bounded transport frame",
                                                                                TooLarge::new);
    }

    static Result<Unit> validate(Serializer serializer, ProtocolMessage message) {
        return Result.lift(Causes::fromThrowable,
                           () -> serializer.encode(message))
                     .flatMap(OutboundMessageLimit::withinFrame);
    }

    private static Result<Unit> withinFrame(byte[] encoded) {
        return encoded.length <= MAX_TRANSFER_BYTES
               ? Result.unitResult()
               : TooLarge.FACTORY.apply(encoded.length).result();
    }
}
