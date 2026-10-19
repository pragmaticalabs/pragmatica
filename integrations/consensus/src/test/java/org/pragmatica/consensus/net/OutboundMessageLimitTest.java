package org.pragmatica.consensus.net;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The frame check at its edge: a message encoding to exactly [OutboundMessageLimit#MAX_TRANSFER_BYTES] is sent, one
/// byte more is refused, and the refusal carries the size it refused.
class OutboundMessageLimitTest {
    private static final ProtocolMessage MESSAGE = new Probe(NodeId.nodeId("limit-probe").unwrap());

    @Test
    void messageOfExactlyTheLimit_passes() {
        assertThat(OutboundMessageLimit.validate(new SizedSerializer(OutboundMessageLimit.MAX_TRANSFER_BYTES), MESSAGE)
                                       .isSuccess()).isTrue();
    }

    @Test
    void messageOneByteOverTheLimit_isRefused_withItsSize() {
        var oneOver = OutboundMessageLimit.MAX_TRANSFER_BYTES + 1;
        var result = OutboundMessageLimit.validate(new SizedSerializer(oneOver), MESSAGE);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isEqualTo(OutboundMessageLimit.TooLarge.FACTORY.apply(oneOver)));
    }

    private record Probe(NodeId sender) implements ProtocolMessage {}

    private record SizedSerializer(int size) implements Serializer {
        @Override public <T> void write(ByteBuf byteBuf, T object) {}
        @Override public <T> byte[] encode(T value) {return new byte[size];}
    }
}
