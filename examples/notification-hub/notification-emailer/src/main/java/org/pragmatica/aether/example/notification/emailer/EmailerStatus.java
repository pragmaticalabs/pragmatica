package org.pragmatica.aether.example.notification.emailer;

import org.pragmatica.serialization.Codec;


@Codec
public record EmailerStatus(long sent, long failed) {
    public static EmailerStatus emailerStatus(long sent, long failed) {
        return new EmailerStatus(sent, failed);
    }
}
