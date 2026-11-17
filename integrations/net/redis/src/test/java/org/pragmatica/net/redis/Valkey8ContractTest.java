package org.pragmatica.net.redis;

/// The client contract against a real Valkey 8 server (Testcontainers).
class Valkey8ContractTest extends AbstractRedisContract {
    @Override
    String image() {
        return "valkey/valkey:8";
    }
}
