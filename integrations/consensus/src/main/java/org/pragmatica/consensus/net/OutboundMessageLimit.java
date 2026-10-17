package org.pragmatica.consensus.net;

import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Serializer;


/// Whole-message state transfer currently shares the transport's bounded frame. Reserve space
/// beyond the exact encoded payload for framing and conservative authority-envelope growth.
public interface OutboundMessageLimit {
    int MAX_FRAME_BYTES = 32 * 1024 * 1024;
    int MAX_TRANSFER_BYTES = MAX_FRAME_BYTES - 64 * 1024;

    enum Error implements Cause {
        TOO_LARGE;
        @Override
        public String message() {
            return "State transfer exceeds the bounded transport frame";
        }
    }

    static Result<Unit> validate(Serializer serializer, ProtocolMessage message) {
        return Result.lift(Causes::fromThrowable,
                           () -> serializer.encode(message))
                     .filter(Error.TOO_LARGE, bytes -> bytes.length <= MAX_TRANSFER_BYTES)
                     .mapToUnit();
    }
}
