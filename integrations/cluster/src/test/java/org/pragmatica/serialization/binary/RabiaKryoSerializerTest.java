package org.pragmatica.serialization.binary;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.rabia.Batch;
import org.pragmatica.consensus.rabia.Phase;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage;
import org.pragmatica.consensus.rabia.infrastructure.TestCluster.StringKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.node.rabia.CustomClasses;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.serialization.kryo.KryoDeserializer;
import org.pragmatica.serialization.kryo.KryoSerializer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.rabia.infrastructure.TestCluster.StringKey.key;

class RabiaKryoSerializerTest {

    @Test
    void roundTrip_succeeds_forProtocolMessage() {
        var serializer = KryoSerializer.kryoSerializer(CustomClasses::configure, StringKey::register);
        var deserializer = KryoDeserializer.kryoDeserializer(CustomClasses::configure, StringKey::register);

        var commands = List.of(new KVCommand.Put<>(key("k1"), "v1"),
                               new KVCommand.Get<>(key("k2")),
                               new KVCommand.Remove<>(key("k3")));
        var message = new RabiaProtocolMessage.Synchronous.Propose<>(NodeId.randomNodeId(),
                                                                     new Phase(123L),
                                                                     Batch.batch(commands));

        var serialized = serializer.encode(message);
        var deserialized = deserializer.decode(serialized);

        assertThat(deserialized).isEqualTo(message);
    }
}