# Serialization Module

Binary serialization integrations for Pragmatica Lite.

## Overview

Provides pluggable binary serialization with core interfaces (`Serializer`, `Deserializer`, `ClassRegistrator`) and two implementations: Kryo (fast, compact binary format) and Apache Fury (extremely fast, JIT optimized). Both are thread-safe and use pooling for optimal performance.

| Aspect | Kryo | Fury |
|--------|------|------|
| Performance | Fast | Very fast (JIT optimized) |
| Compatibility | Wide ecosystem | Newer, less adoption |
| Memory | Low | Lower (native memory) |
| Cross-language | No | Yes (multiple languages) |

## Usage

```java
// Kryo
var serializer = KryoSerializer.kryoSerializer();
var deserializer = KryoDeserializer.kryoDeserializer();

// Fury
var serializer = FurySerializer.furySerializer();
var deserializer = FuryDeserializer.furyDeserializer();

// Serialize/deserialize
byte[] bytes = serializer.encode(myObject);
MyObject result = deserializer.decode(bytes);

// Class registration (recommended for performance)
ClassRegistrator registrator = consumer -> {
    consumer.accept(User.class);
    consumer.accept(Order.class);
};
var serializer = KryoSerializer.kryoSerializer(registrator);

// ByteBuf integration (Netty)
serializer.write(buffer, myObject);
MyObject result = deserializer.read(buffer);
```

## Dependencies

- Kryo or Apache Fury (depending on chosen implementation)
- `pragmatica-lite-core`
