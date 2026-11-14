package org.pragmatica.net.redis;

/// The client contract against a real Redis 7 server (Testcontainers).
class Redis7ContractTest extends AbstractRedisContract {
    @Override
    String image() {
        return "redis:7";
    }
}
