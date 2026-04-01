package org.pragmatica.aether.example.notification;

import org.pragmatica.aether.slice.annotation.PartitionKey;
import org.pragmatica.serialization.Codec;

@Codec public record NotificationEvent( @PartitionKey String senderId, String message, String channel, long timestamp){}
