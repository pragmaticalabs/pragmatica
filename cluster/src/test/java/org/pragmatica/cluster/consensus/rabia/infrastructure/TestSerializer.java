package org.pragmatica.cluster.consensus.rabia.infrastructure;

import org.pragmatica.cluster.net.netty.Serializer;

import java.io.*;

/// A very simple Serializer that uses Java built-in object streams
/// to encode/decode snapshots of the KVStore.
public class TestSerializer implements Serializer {
    @Override
    public byte[] encode(Object msg) {
        try (var baos = new ByteArrayOutputStream();
             var oos = new ObjectOutputStream(baos)) {
            oos.writeObject(msg);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T decode(byte[] bytes, Class<T> clazz) {
        try (var bais = new ByteArrayInputStream(bytes);
             var ois = new ObjectInputStream(bais)) {
            return (T) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
