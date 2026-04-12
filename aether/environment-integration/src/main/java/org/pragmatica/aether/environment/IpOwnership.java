package org.pragmatica.aether.environment;


/// Floating IP ownership status. §11.1a
public record IpOwnership(boolean ownedByAccount, String currentAttachment) {
    public static IpOwnership ipOwnership(boolean ownedByAccount, String currentAttachment) {
        return new IpOwnership(ownedByAccount, currentAttachment);
    }
}
